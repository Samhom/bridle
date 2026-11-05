package io.github.samhom.bridle.couchbase;

import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.kv.*;
import io.github.samhom.bridle.core.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public final class ManagedDocuments {
  private static final com.couchbase.client.core.retry.RetryStrategy NO_REPLAY =
      (request, reason) ->
          CompletableFuture.completedFuture(com.couchbase.client.core.retry.RetryAction.noRetry());

  public record Document(String json, long cas) {}

  private final ManagedClient<Cluster, CouchbaseSettings> client;
  private final String bucket, scope, collection;
  private final Duration timeout;
  private final int maximum;

  public ManagedDocuments(
      ManagedClient<Cluster, CouchbaseSettings> client, CouchbaseSettings settings) {
    this.client = java.util.Objects.requireNonNull(client);
    bucket = settings.bucket();
    scope = settings.scope();
    collection = settings.collection();
    timeout = settings.operationTimeout();
    maximum = settings.maxDocumentBytes();
  }

  private static void key(String key) {
    if (key == null || key.isEmpty() || key.getBytes(StandardCharsets.UTF_8).length > 250)
      throw new IllegalArgumentException("Invalid document key size");
  }

  /**
   * Millisecond floor of the call deadline. A shorter Duration would still be sent, then time out
   * after dispatch.
   */
  private Duration nativeTimeout(CallContext context) {
    return Duration.ofMillis(context.remainingMillis(timeout));
  }

  public CompletableFuture<Document> getAsync(String key) {
    key(key);
    return client.executeAsync(
        Operation.read("get", timeout),
        (cluster, context) ->
            cluster
                .bucket(bucket)
                .scope(scope)
                .collection(collection)
                .async()
                .get(
                    key,
                    GetOptions.getOptions()
                        .transcoder(
                            new DocumentJson(
                                Math.min(
                                    maximum,
                                    context.settings(CouchbaseSettings.class).maxDocumentBytes())))
                        .retryStrategy(NO_REPLAY)
                        .timeout(nativeTimeout(context)))
                .thenApply(DocumentJson::document));
  }

  /** Explicit application codecs avoid polymorphic native deserialization. */
  public interface JsonCodec<T> {
    String encode(T value);

    T decode(String json);
  }

  public record TypedDocument<T>(T value, long cas) {}

  public <T> CompletableFuture<TypedDocument<T>> getAsync(String key, JsonCodec<T> codec) {
    java.util.Objects.requireNonNull(codec);
    return getAsync(key)
        .thenApply(
            document -> {
              Limits.bytes(document.json().getBytes(StandardCharsets.UTF_8), maximum);
              return new TypedDocument<>(codec.decode(document.json()), document.cas());
            });
  }

  public <T> CompletableFuture<Long> upsertAsync(
      String key,
      T value,
      JsonCodec<T> codec,
      com.couchbase.client.core.msg.kv.DurabilityLevel durability) {
    return upsertAsync(key, java.util.Objects.requireNonNull(codec).encode(value), durability);
  }

  public CompletableFuture<Long> upsertAsync(String key, String json) {
    return upsertAsync(key, json, com.couchbase.client.core.msg.kv.DurabilityLevel.NONE);
  }

  public CompletableFuture<Long> upsertAsync(
      String key, String json, com.couchbase.client.core.msg.kv.DurabilityLevel durability) {
    java.util.Objects.requireNonNull(durability);
    key(key);
    byte[] value = DocumentJson.encodeJson(json, maximum);
    return client.executeAsync(
        Operation.write("upsert", timeout),
        (cluster, context) -> {
          Limits.bytes(
              value,
              Math.min(maximum, context.settings(CouchbaseSettings.class).maxDocumentBytes()));
          return cluster
              .bucket(bucket)
              .scope(scope)
              .collection(collection)
              .async()
              .upsert(
                  key,
                  value,
                  UpsertOptions.upsertOptions()
                      .transcoder(
                          new DocumentJson(
                              Math.min(
                                  maximum,
                                  context.settings(CouchbaseSettings.class).maxDocumentBytes())))
                      .durability(durability)
                      .retryStrategy(NO_REPLAY)
                      .timeout(nativeTimeout(context)))
              .thenApply(MutationResult::cas);
        });
  }

  public CompletableFuture<Long> replaceAsync(String key, String json, long cas) {
    key(key);
    byte[] value = DocumentJson.encodeJson(json, maximum);
    return client.executeAsync(
        Operation.write("replace", timeout),
        (cluster, context) -> {
          Limits.bytes(
              value,
              Math.min(maximum, context.settings(CouchbaseSettings.class).maxDocumentBytes()));
          return cluster
              .bucket(bucket)
              .scope(scope)
              .collection(collection)
              .async()
              .replace(
                  key,
                  value,
                  ReplaceOptions.replaceOptions()
                      .transcoder(
                          new DocumentJson(
                              Math.min(
                                  maximum,
                                  context.settings(CouchbaseSettings.class).maxDocumentBytes())))
                      .retryStrategy(NO_REPLAY)
                      .cas(cas)
                      .timeout(nativeTimeout(context)))
              .thenApply(MutationResult::cas);
        });
  }

  private com.couchbase.client.java.AsyncCollection collection(Cluster cluster) {
    return cluster.bucket(bucket).scope(scope).collection(collection).async();
  }

  private static void path(String path) {
    if (path == null || path.isEmpty() || path.getBytes(StandardCharsets.UTF_8).length > 1024)
      throw new IllegalArgumentException("Subdocument path out of bounds");
  }

  public CompletableFuture<LookupInResult> lookupInAsync(String key, java.util.List<String> paths) {
    key(key);
    java.util.List<String> copy = java.util.List.copyOf(paths);
    Limits.count(copy.size(), 16);
    copy.forEach(ManagedDocuments::path);
    java.util.List<LookupInSpec> specs =
        copy.stream().map(LookupInSpec::get).map(spec -> (LookupInSpec) spec).toList();
    return client.executeAsync(
        Operation.read("lookupIn", timeout),
        (cluster, context) ->
            collection(cluster)
                .lookupIn(
                    key,
                    specs,
                    LookupInOptions.lookupInOptions()
                        .retryStrategy(NO_REPLAY)
                        .timeout(nativeTimeout(context))));
  }

  /** Bounded JSON subdocument upserts, with optional CAS and server-side durability. */
  public CompletableFuture<MutateInResult> mutateInAsync(
      String key,
      java.util.Map<String, String> jsonValues,
      long cas,
      com.couchbase.client.core.msg.kv.DurabilityLevel durability) {
    key(key);
    java.util.Map<String, String> copy = java.util.Map.copyOf(jsonValues);
    Limits.count(copy.size(), 16);
    java.util.Objects.requireNonNull(durability);
    long bytes = 0;
    java.util.List<MutateInSpec> specs = new java.util.ArrayList<>();
    for (var entry : copy.entrySet()) {
      path(entry.getKey());
      byte[] encoded = DocumentJson.encodeJson(entry.getValue(), maximum);
      bytes += encoded.length;
      if (bytes > maximum) throw new IllegalArgumentException("Subdocument byte budget exceeded");
      var parsed = com.couchbase.client.java.json.JsonArray.fromJson("[" + entry.getValue() + "]");
      if (parsed.size() != 1) throw new IllegalArgumentException("Expected one JSON value");
      Object value = parsed.get(0);
      specs.add(MutateInSpec.upsert(entry.getKey(), value));
    }
    final long size = bytes;
    return client.executeAsync(
        Operation.write("mutateIn", timeout),
        (cluster, context) -> {
          if (size > context.settings(CouchbaseSettings.class).maxDocumentBytes())
            throw new IllegalArgumentException("Subdocument byte budget exceeded");
          return collection(cluster)
              .mutateIn(
                  key,
                  specs,
                  MutateInOptions.mutateInOptions()
                      .cas(cas)
                      .durability(durability)
                      .retryStrategy(NO_REPLAY)
                      .timeout(nativeTimeout(context)));
        });
  }

  /**
   * Native transactions may replay this callback. It must contain only transactional database work;
   * external side effects require application idempotency. The SDK does not retry the whole run.
   */
  public com.couchbase.client.java.transactions.TransactionResult transaction(
      java.util.function.Consumer<com.couchbase.client.java.transactions.TransactionAttemptContext>
          work,
      com.couchbase.client.core.msg.kv.DurabilityLevel durability) {
    java.util.Objects.requireNonNull(work);
    java.util.Objects.requireNonNull(durability);
    if (durability == com.couchbase.client.core.msg.kv.DurabilityLevel.NONE)
      throw new IllegalArgumentException("Transactions require durability");
    return client.execute(
        Operation.write("transaction", timeout),
        (cluster, context) ->
            cluster
                .transactions()
                .run(
                    work,
                    com.couchbase.client.java.transactions.config.TransactionOptions
                        .transactionOptions()
                        .durabilityLevel(durability)
                        .timeout(nativeTimeout(context))));
  }

  public ManagedClient<Cluster, CouchbaseSettings> handle() {
    return client;
  }
}
