package io.github.samhom.driverkit.elasticsearch;

import static org.junit.jupiter.api.Assertions.*;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.json.*;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.*;
import co.elastic.clients.transport.rest5_client.Rest5ClientOptions;
import co.elastic.clients.transport.rest5_client.low_level.RequestOptions;
import io.github.samhom.driverkit.core.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/**
 * Real Java API Client over a controllable transport; it deliberately ignores deadlines to expose
 * late completions.
 */
class PitOwnershipTest {
  static final ResourceId ID = new ResourceId("pit", "test", "search");

  static ElasticsearchSettings settings(Duration read) {
    return new ElasticsearchSettings(
        new io.github.samhom.driverkit.core.Endpoint("localhost", 9200, false),
        () -> new Credentials("fixture", new char[0]),
        Duration.ofSeconds(1),
        read,
        4,
        10,
        1024);
  }

  static OpenPointInTimeResponse opened(String id) {
    return OpenPointInTimeResponse.of(
        r -> r.id(id).shards(s -> s.total(1).successful(1).failed(0)));
  }

  static SearchResponse<JsonData> page(String id) {
    return SearchResponse.of(
        r ->
            r.pitId(id)
                .took(1)
                .timedOut(false)
                .shards(s -> s.total(1).successful(1).failed(0))
                .hits(h -> h.hits(List.of())));
  }

  static SearchRequest request() {
    return SearchRequest.of(
        r -> r.size(10).sort(s -> s.field(f -> f.field("_shard_doc"))).searchAfter(1L));
  }

  static void eventually(BooleanSupplier condition) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(5);
    assertTrue(condition.getAsBoolean());
  }

  static final class TransportFixture implements ElasticsearchTransport {
    CompletableFuture<OpenPointInTimeResponse> open =
        CompletableFuture.completedFuture(opened("initial"));
    CompletableFuture<SearchResponse<JsonData>> search =
        CompletableFuture.completedFuture(page("next"));
    final Queue<String> closedIds = new ConcurrentLinkedQueue<>();
    final CountDownLatch openEntered = new CountDownLatch(1), pageEntered = new CountDownLatch(1);
    final AtomicInteger transportCloses = new AtomicInteger();
    volatile SearchRequest lastPage;

    public JsonpMapper jsonpMapper() {
      return new JacksonJsonpMapper();
    }

    public TransportOptions options() {
      return new Rest5ClientOptions(RequestOptions.DEFAULT, false);
    }

    public void close() {
      transportCloses.incrementAndGet();
    }

    public <Q, R, E> R performRequest(
        Q q, co.elastic.clients.transport.Endpoint<Q, R, E> e, TransportOptions o) {
      throw new UnsupportedOperationException();
    }

    @SuppressWarnings("unchecked")
    public <Q, R, E> CompletableFuture<R> performRequestAsync(
        Q q, co.elastic.clients.transport.Endpoint<Q, R, E> e, TransportOptions o) {
      if (q instanceof OpenPointInTimeRequest) {
        openEntered.countDown();
        return (CompletableFuture<R>) open;
      }
      if (q instanceof SearchRequest page) {
        lastPage = page;
        pageEntered.countDown();
        return (CompletableFuture<R>) search;
      }
      if (q instanceof ClosePointInTimeRequest close) {
        closedIds.add(close.id());
        return (CompletableFuture<R>)
            CompletableFuture.completedFuture(
                ClosePointInTimeResponse.of(r -> r.succeeded(true).numFreed(1)));
      }
      throw new AssertionError("Unexpected native request");
    }
  }

  static FactoryAdapter<ElasticsearchClient, ElasticsearchSettings> adapter(
      Queue<TransportFixture> fixtures) {
    return new FactoryAdapter<>(
        "fixture",
        s -> new ElasticsearchClient(fixtures.remove()),
        (c, t) -> {},
        c -> c._transport().close());
  }

  @Test
  void pagesUseOriginalTransportSettingsRevisionAndDoNotDuplicatePaginationArrays()
      throws Exception {
    TransportFixture old = new TransportFixture(), replacement = new TransportFixture();
    Queue<TransportFixture> fixtures = new ConcurrentLinkedQueue<>(List.of(old, replacement));
    Queue<Long> observed = new ConcurrentLinkedQueue<>();
    io.github.samhom.driverkit.core.Observer observer =
        (r, d, o, revision) -> (outcome, elapsed) -> observed.add(revision);
    try (ClientRuntime runtime = new ClientRuntime(1, 2, 8, observer)) {
      ElasticsearchSettings initial = settings(Duration.ofSeconds(1));
      var handle =
          runtime.register(
              new ConfigSnapshot<>(ID, 1, initial), adapter(fixtures), ClientPolicy.defaults());
      var search = new ManagedSearch(handle, initial);
      try (var pit = search.openPointInTime("catalog", Duration.ofSeconds(30))) {
        handle.reload(new ConfigSnapshot<>(ID, 2, settings(Duration.ofMillis(1))));
        assertNotNull(pit.page(request(), JsonData.class));
        assertEquals(1, old.lastPage.sort().size());
        assertEquals(1, old.lastPage.searchAfter().size());
        assertNull(replacement.lastPage);
        assertEquals(List.of(1L, 1L), new ArrayList<>(observed));
      }
      assertEquals(List.of("next"), new ArrayList<>(old.closedIds));
      eventually(() -> old.transportCloses.get() == 1);
    }
  }

  @Test
  void lateOpenSuccessIsDisposedBeforeRetiredTransportCanClose() throws Exception {
    TransportFixture old = new TransportFixture(), replacement = new TransportFixture();
    old.open = new CompletableFuture<>();
    Queue<TransportFixture> fixtures = new ConcurrentLinkedQueue<>(List.of(old, replacement));
    try (ClientRuntime runtime = new ClientRuntime()) {
      var nativeSettings = settings(Duration.ofSeconds(1));
      var handle =
          runtime.register(
              new ConfigSnapshot<>(ID, 1, nativeSettings),
              adapter(fixtures),
              ClientPolicy.defaults());
      var search = new ManagedSearch(handle, settings(Duration.ofMillis(50)));
      assertThrows(
          ClientException.class, () -> search.openPointInTime("catalog", Duration.ofSeconds(30)));
      assertTrue(old.openEntered.await(2, TimeUnit.SECONDS));
      handle.reload(new ConfigSnapshot<>(ID, 2, nativeSettings));
      assertEquals(0, old.transportCloses.get());
      old.open.complete(opened("late-open"));
      eventually(() -> old.closedIds.contains("late-open"));
      eventually(() -> old.transportCloses.get() == 1);
      assertEquals(0, handle.status().inFlight());
    }
  }

  @Test
  void latePageUpdatesCleanupIdAndFailedSessionCannotIssueAnotherPage() throws Exception {
    TransportFixture old = new TransportFixture(), replacement = new TransportFixture();
    old.search = new CompletableFuture<>();
    Queue<TransportFixture> fixtures = new ConcurrentLinkedQueue<>(List.of(old, replacement));
    try (ClientRuntime runtime = new ClientRuntime()) {
      var settings = settings(Duration.ofSeconds(1));
      var handle =
          runtime.register(
              new ConfigSnapshot<>(ID, 1, settings), adapter(fixtures), ClientPolicy.defaults());
      var search = new ManagedSearch(handle, settings(Duration.ofMillis(50)));
      var pit = search.openPointInTime("catalog", Duration.ofSeconds(30));
      assertThrows(ClientException.class, () -> pit.page(request(), JsonData.class));
      assertThrows(IllegalStateException.class, () -> pit.page(request(), JsonData.class));
      handle.reload(new ConfigSnapshot<>(ID, 2, settings));
      assertEquals(0, old.transportCloses.get());
      old.search.complete(page("late-page"));
      eventually(() -> old.closedIds.contains("late-page"));
      eventually(() -> old.transportCloses.get() == 1);
      pit.close();
      assertEquals(List.of("late-page"), new ArrayList<>(old.closedIds));
    }
  }
}
