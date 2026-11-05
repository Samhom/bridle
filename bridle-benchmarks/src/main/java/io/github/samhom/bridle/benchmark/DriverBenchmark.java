package io.github.samhom.bridle.benchmark;

import io.github.samhom.bridle.core.*;
import io.github.samhom.bridle.core.Observer;
import io.github.samhom.bridle.micrometer.MicrometerObserver;
import io.github.samhom.bridle.otel.OpenTelemetryObserver;
import io.github.samhom.bridle.redis.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.lang.management.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Standalone measurement, deliberately excluded from ordinary builds. No machine-specific pass
 * gate.
 */
public final class DriverBenchmark {
  interface Probe extends AutoCloseable {
    Object read();

    default void close() {}
  }

  static volatile Object blackhole;
  static final Duration TIMEOUT = Duration.ofSeconds(3);
  static final ResourceId ID = new ResourceId("benchmark", "local", "fast");

  static Probe probe() {
    String host = System.getenv("DRIVERKIT_BENCH_REDIS_HOST");
    if (host == null) {
      AtomicLong value = new AtomicLong();
      return value::incrementAndGet;
    }
    int port = Integer.parseInt(System.getenv().getOrDefault("DRIVERKIT_BENCH_REDIS_PORT", "6379"));
    String tlsValue = System.getenv().getOrDefault("DRIVERKIT_BENCH_REDIS_TLS", "true");
    if (!tlsValue.equals("true") && !tlsValue.equals("false"))
      throw new IllegalArgumentException("TLS must be true or false");
    boolean tls = Boolean.parseBoolean(tlsValue);
    CredentialsProvider credentials =
        () ->
            new Credentials(
                System.getenv().getOrDefault("DRIVERKIT_BENCH_REDIS_USER", ""),
                System.getenv().getOrDefault("DRIVERKIT_BENCH_REDIS_PASSWORD", "").toCharArray());
    var adapter = new JedisAdapter();
    var settings = RedisSettings.defaults(new Endpoint(host, port, tls), credentials);
    var nativeClient = adapter.create(settings);
    try {
      adapter.warmup(nativeClient, TIMEOUT);
    } catch (Exception failure) {
      nativeClient.close();
      throw new IllegalStateException("Redis benchmark initialization failed");
    }
    return new Probe() {
      public Object read() {
        return nativeClient.get("bridle:benchmark:read-only");
      }

      public void close() {
        nativeClient.close();
      }
    };
  }

  static Map<Long, Long> allocated() {
    var base = ManagementFactory.getThreadMXBean();
    if (!(base instanceof com.sun.management.ThreadMXBean bean)
        || !bean.isThreadAllocatedMemorySupported()) return Map.of();
    if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
    Map<Long, Long> values = new HashMap<>();
    for (long id : bean.getAllThreadIds()) values.put(id, bean.getThreadAllocatedBytes(id));
    return values;
  }

  static long allocationDelta(Map<Long, Long> before, Map<Long, Long> after) {
    if (before.isEmpty()) return -1;
    long sum = 0;
    for (var value : after.entrySet())
      if (before.containsKey(value.getKey()) && value.getValue() >= 0)
        sum += Math.max(0, value.getValue() - before.get(value.getKey()));
    return sum;
  }

  static long[] sample(Callable<Object> call, int count, int concurrency, ExecutorService workers)
      throws Exception {
    long[] times = new long[count];
    AtomicInteger next = new AtomicInteger();
    CountDownLatch start = new CountDownLatch(1);
    List<Future<?>> tasks = new ArrayList<>();
    for (int t = 0; t < concurrency; t++)
      tasks.add(
          workers.submit(
              () -> {
                try {
                  start.await();
                  for (int i; (i = next.getAndIncrement()) < count; ) {
                    long began = System.nanoTime();
                    blackhole = call.call();
                    times[i] = System.nanoTime() - began;
                  }
                } catch (Exception e) {
                  throw new CompletionException(e);
                }
              }));
    start.countDown();
    for (Future<?> task : tasks) task.get(2, TimeUnit.MINUTES);
    return times;
  }

  static void measure(String mode, int count, int warmup, int concurrency) throws Exception {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    try (Probe nativeProbe = probe();
        OpenTelemetrySdk telemetry =
            OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder().build())
                .build();
        ClientRuntime runtime =
            new ClientRuntime(
                2,
                concurrency,
                1024,
                mode.equals("metrics")
                    ? new MicrometerObserver(meters)
                    : mode.equals("trace")
                        ? new CompositeObserver(
                            new MicrometerObserver(meters), new OpenTelemetryObserver(telemetry))
                        : Observer.NOOP)) {
      var client =
          runtime.register(
              new ConfigSnapshot<>(ID, 1, "fixture"),
              new FactoryAdapter<Probe, String>("probe", s -> nativeProbe, (c, t) -> {}, c -> {}),
              ClientPolicy.defaults());
      Callable<Object> call =
          mode.equals("native")
              ? nativeProbe::read
              : () -> client.execute(Operation.read("get", TIMEOUT), (p, ctx) -> p.read());
      ExecutorService workers = Executors.newFixedThreadPool(concurrency);
      try {
        sample(call, warmup, concurrency, workers);
        Map<Long, Long> before = allocated();
        long began = System.nanoTime();
        long[] latencies = sample(call, count, concurrency, workers);
        long elapsed = System.nanoTime() - began;
        long bytes = allocationDelta(before, allocated());
        Arrays.sort(latencies);
        System.out.printf(
            Locale.ROOT,
            "{\"mode\":\"%s\",\"workload\":\"%s\",\"operations\":%d,\"concurrency\":%d,\"ops_per_second\":%.2f,\"p99_nanos\":%d,\"allocated_bytes_lower_bound\":%d,\"live_threads\":%d}%n",
            mode,
            System.getenv("DRIVERKIT_BENCH_REDIS_HOST") == null ? "synthetic" : "redis-get",
            count,
            concurrency,
            count * 1_000_000_000.0 / elapsed,
            latencies[Math.min(count - 1, (int) Math.ceil(count * .99) - 1)],
            bytes,
            ManagementFactory.getThreadMXBean().getThreadCount());
      } finally {
        workers.shutdownNow();
        workers.awaitTermination(5, TimeUnit.SECONDS);
      }
    } finally {
      meters.close();
    }
  }

  static void isolation(boolean isolated) throws Exception {
    var policy =
        new ClientPolicy(
            8,
            TIMEOUT,
            TIMEOUT,
            isolated ? ExecutionPolicy.isolated(1, 8) : ExecutionPolicy.shared());
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    try (ClientRuntime runtime = new ClientRuntime(2, 1, 8, Observer.NOOP)) {
      var adapter = new FactoryAdapter<String, String>("probe", s -> s, (c, t) -> {}, c -> {});
      var slow =
          runtime.register(
              new ConfigSnapshot<>(new ResourceId("benchmark", "local", "slow"), 1, "slow"),
              adapter,
              policy);
      var fast = runtime.register(new ConfigSnapshot<>(ID, 1, "fast"), adapter, policy);
      try {
        var blocked =
            slow.executeAsync(
                Operation.read("hold", TIMEOUT),
                (c, ctx) -> {
                  entered.countDown();
                  release.await();
                  return CompletableFuture.completedFuture(c);
                });
        if (!entered.await(3, TimeUnit.SECONDS))
          throw new IllegalStateException("Slow lane did not start");
        long began = System.nanoTime();
        var result =
            fast.executeAsync(
                Operation.read("get", TIMEOUT), (c, ctx) -> CompletableFuture.completedFuture(c));
        boolean completed;
        try {
          result.get(100, TimeUnit.MILLISECONDS);
          completed = true;
        } catch (TimeoutException expected) {
          completed = false;
        }
        long elapsed = System.nanoTime() - began;
        release.countDown();
        blocked.get(3, TimeUnit.SECONDS);
        result.get(3, TimeUnit.SECONDS);
        System.out.printf(
            Locale.ROOT,
            "{\"scenario\":\"slow-resource\",\"isolated\":%s,\"fast_completed_within_100ms\":%s,\"observed_nanos\":%d}%n",
            isolated,
            completed,
            elapsed);
      } finally {
        release.countDown();
      }
    }
  }

  public static void main(String[] args) throws Exception {
    int count = args.length > 0 ? Integer.parseInt(args[0]) : 10000;
    int warmup = args.length > 1 ? Integer.parseInt(args[1]) : 2000;
    int concurrency = args.length > 2 ? Integer.parseInt(args[2]) : 4;
    Limits.count(count, 1000000);
    Limits.count(warmup, 1000000);
    Limits.count(concurrency, 64);
    for (String mode : List.of("native", "core", "metrics", "trace"))
      measure(mode, count, warmup, concurrency);
    isolation(false);
    isolation(true);
  }
}
