package io.github.samhom.bridle.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.json.JsonData;
import io.github.samhom.bridle.core.*;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Full query DSL remains typed in the native client; common document operations have a compact
 * facade.
 */
public final class ManagedSearch {
  private static final java.util.regex.Pattern INDEX =
      java.util.regex.Pattern.compile("[a-z0-9][a-z0-9_.-]{0,127}");

  public static final class PartialBulkFailure extends RuntimeException {
    private final BulkResponse response;

    PartialBulkFailure(BulkResponse response) {
      super("Bulk operation has failed items");
      this.response = response;
    }

    public BulkResponse response() {
      return response;
    }
  }

  private final ManagedClient<ElasticsearchClient, ElasticsearchSettings> client;
  private final Duration timeout;
  private final int maximumBatch, maximumDocument;
  private final Operation getCommand;
  private final Operation indexCommand;
  private final Operation deleteCommand;
  private final Operation bulkCommand;
  private final Operation searchCommand;
  private final Operation openPitCommand;
  private final Operation pagePitCommand;

  public ManagedSearch(
      ManagedClient<ElasticsearchClient, ElasticsearchSettings> client,
      ElasticsearchSettings settings) {
    this.client = java.util.Objects.requireNonNull(client);
    timeout = settings.readTimeout();
    maximumBatch = settings.maxBatchItems();
    maximumDocument = settings.maxDocumentBytes();
    getCommand = Operation.read("get", timeout);
    indexCommand = Operation.write("index", timeout);
    deleteCommand = Operation.write("delete", timeout);
    bulkCommand = Operation.write("bulk", timeout);
    searchCommand = Operation.read("search", timeout);
    openPitCommand = Operation.read("pit.open", timeout);
    pagePitCommand = Operation.read("pit.page", timeout);
  }

  private static void index(String value) {
    if (value == null || !INDEX.matcher(value).matches())
      throw new IllegalArgumentException("Invalid index or alias");
  }

  private static void id(String value) {
    if (value == null || value.isEmpty() || Limits.exceedsUtf8(value, 512))
      throw new IllegalArgumentException("Invalid document ID size");
  }

  public GetResponse<JsonData> get(String index, String id) {
    return client.executeAwait(getCommand, getCall(index, id));
  }

  public CompletableFuture<GetResponse<JsonData>> getAsync(String index, String id) {
    return client.executeAsync(getCommand, getCall(index, id));
  }

  private AsyncCall<ElasticsearchClient, GetResponse<JsonData>> getCall(String index, String id) {
    index(index);
    id(id);
    return (nativeClient, context) ->
        async(nativeClient, context).get(request -> request.index(index).id(id), JsonData.class);
  }

  public IndexResponse index(String index, String id, String json) {
    return client.executeAwait(indexCommand, indexCall(index, id, json));
  }

  public CompletableFuture<IndexResponse> indexAsync(String index, String id, String json) {
    return client.executeAsync(indexCommand, indexCall(index, id, json));
  }

  private AsyncCall<ElasticsearchClient, IndexResponse> indexCall(
      String index, String id, String json) {
    index(index);
    id(id);
    Limits.utf8(json, maximumDocument);
    JsonData document = JsonData.fromJson(json);
    return (nativeClient, context) -> {
      Limits.utf8(
          json,
          Math.min(
              maximumDocument, context.settings(ElasticsearchSettings.class).maxDocumentBytes()));
      return async(nativeClient, context)
          .index(request -> request.index(index).id(id).document(document));
    };
  }

  public DeleteResponse delete(String index, String id) {
    return client.executeAwait(deleteCommand, deleteCall(index, id));
  }

  public CompletableFuture<DeleteResponse> deleteAsync(String index, String id) {
    return client.executeAsync(deleteCommand, deleteCall(index, id));
  }

  private AsyncCall<ElasticsearchClient, DeleteResponse> deleteCall(String index, String id) {
    index(index);
    id(id);
    return (nativeClient, context) ->
        async(nativeClient, context).delete(request -> request.index(index).id(id));
  }

  public BulkResponse bulk(BulkRequest request) {
    return client.executeAwait(bulkCommand, bulkCall(request));
  }

  public CompletableFuture<BulkResponse> bulkAsync(BulkRequest request) {
    return client.executeAsync(bulkCommand, bulkCall(request));
  }

  private AsyncCall<ElasticsearchClient, BulkResponse> bulkCall(BulkRequest request) {
    if (request == null
        || request.operations().isEmpty()
        || request.operations().size() > maximumBatch)
      throw new IllegalArgumentException("Bulk size out of bounds");
    return (nativeClient, context) -> {
      ElasticsearchSettings current = context.settings(ElasticsearchSettings.class);
      if (request.operations().size() > Math.min(maximumBatch, current.maxBatchItems()))
        throw new IllegalArgumentException("Bulk size out of bounds");
      int documentLimit = Math.min(maximumDocument, current.maxDocumentBytes());
      ByteBudget budget = new ByteBudget(16 * 1024 * 1024);
      for (co.elastic.clients.elasticsearch.core.bulk.BulkOperation operation :
          request.operations()) {
        java.util.Iterator<?> items = operation._serializables();
        while (items.hasNext()) {
          long before = budget.bytes();
          try (jakarta.json.stream.JsonGenerator generator =
              nativeClient._jsonpMapper().jsonProvider().createGenerator(budget)) {
            nativeClient._jsonpMapper().serialize(items.next(), generator);
          }
          if (budget.bytes() - before > documentLimit)
            throw new IllegalArgumentException("Bulk document exceeds byte budget");
          budget.write('\n');
        }
      }
      return async(nativeClient, context)
          .bulk(request)
          .thenApply(
              response -> {
                if (response.errors()) throw new PartialBulkFailure(response);
                return response;
              });
    };
  }

  public <T> SearchResponse<T> search(SearchRequest request, Class<T> type) {
    return client.executeAwait(searchCommand, searchCall(request, type));
  }

  public <T> CompletableFuture<SearchResponse<T>> searchAsync(
      SearchRequest request, Class<T> type) {
    return client.executeAsync(searchCommand, searchCall(request, type));
  }

  private <T> AsyncCall<ElasticsearchClient, SearchResponse<T>> searchCall(
      SearchRequest request, Class<T> type) {
    validateSearch(request);
    return (nativeClient, context) -> async(nativeClient, context).search(request, type);
  }

  /**
   * Scroll keeps server state this facade does not pin to a generation. A non-zero {@code from}
   * makes the coordinating node materialize every skipped hit. New pagination uses PIT pages.
   */
  static void validateSearch(SearchRequest request) {
    if (request == null
        || request.scroll() != null
        || (request.from() != null && request.from() != 0)
        || request.searchAfter().size() > 32
        || request.sort().size() > 32
        || (request.size() != null && (request.size() < 0 || request.size() > 10000)))
      throw new IllegalArgumentException(
          "Search rejects scroll and deep from; use a bounded PIT page");
  }

  /**
   * Inspect every item without throwing away successful item metadata. Writes are never replayed.
   */
  public CompletableFuture<BulkResponse> bulkReportAsync(BulkRequest request) {
    return bulkAsync(request)
        .handle(
            (response, error) -> {
              Throwable cause = error;
              while (cause instanceof java.util.concurrent.CompletionException
                  && cause.getCause() != null) cause = cause.getCause();
              if (cause instanceof ClientException
                  && cause.getCause() instanceof PartialBulkFailure) cause = cause.getCause();
              if (cause instanceof PartialBulkFailure failure) return failure.response();
              if (cause != null) throw new java.util.concurrent.CompletionException(cause);
              return response;
            });
  }

  /** Pins transport, settings and observation revision as one owned generation. */
  public PointInTime openPointInTime(String index, Duration keepAlive) {
    index(index);
    Limits.timeout(keepAlive);
    if (keepAlive.compareTo(Duration.ofMinutes(1)) > 0)
      throw new IllegalArgumentException("PIT keep-alive exceeds one minute");
    ClientSession<ElasticsearchClient> lease = client.session();
    PitOwnership ownership;
    try {
      ownership = new PitOwnership(lease, keepAlive);
    } catch (RuntimeException | Error failure) {
      lease.close();
      throw failure;
    }
    try {
      client.executeAwait(
          ownership.lease,
          openPitCommand,
          (nativeClient, context) ->
              ownership.track(
                  () ->
                      async(nativeClient, context)
                          .openPointInTime(
                              r ->
                                  r.index(index)
                                      .keepAlive(t -> t.time(keepAlive.toMillis() + "ms"))),
                  OpenPointInTimeResponse::id));
      return new PointInTime(ownership);
    } catch (RuntimeException | Error failure) {
      ownership.closeWhenIdle();
      throw failure;
    }
  }

  /** Owns the persistent lease until both native work and any late-ID cleanup complete. */
  private final class PitOwnership {
    final ClientSession<ElasticsearchClient> lease;
    final ElasticsearchClient nativeClient;
    final ElasticsearchSettings settings;
    final Duration keepAlive;
    final CompletableFuture<Void> cleanup = new CompletableFuture<>();
    private int nativeCalls;
    private String id;
    private boolean closing, cleanupStarted;

    PitOwnership(ClientSession<ElasticsearchClient> lease, Duration keepAlive) {
      this.lease = lease;
      this.keepAlive = keepAlive;
      nativeClient = lease.client();
      settings = lease.settings(ElasticsearchSettings.class);
    }

    synchronized String id() {
      if (closing) throw new IllegalStateException("PIT is closed");
      return id;
    }

    <T> java.util.concurrent.CompletionStage<T> track(
        java.util.function.Supplier<? extends java.util.concurrent.CompletionStage<T>> request,
        java.util.function.Function<T, String> nextId) {
      synchronized (this) {
        if (closing) throw new IllegalStateException("PIT is closed");
        nativeCalls++;
      }
      try {
        return java.util.Objects.requireNonNull(request.get())
            .whenComplete(
                (response, error) -> {
                  synchronized (PitOwnership.this) {
                    if (response != null) {
                      String updated = nextId.apply(response);
                      if (updated != null) id = updated;
                    }
                    nativeCalls--;
                  }
                  cleanIfIdle();
                });
      } catch (RuntimeException | Error failure) {
        synchronized (this) {
          nativeCalls--;
        }
        cleanIfIdle();
        throw failure;
      }
    }

    void closeWhenIdle() {
      synchronized (this) {
        closing = true;
      }
      cleanIfIdle();
    }

    private void cleanIfIdle() {
      String toClose;
      synchronized (this) {
        if (!closing || nativeCalls != 0 || cleanupStarted) return;
        cleanupStarted = true;
        toClose = id;
      }
      if (toClose == null) {
        finishCleanup(null);
        return;
      }
      // Cleanup is resource disposal, not a newly admitted application call. It uses the original
      // transport/settings, including after logical timeout or revocation, while this lease owns
      // it.
      try {
        async(nativeClient, settings, timeout)
            .closePointInTime(r -> r.id(toClose))
            .whenComplete((ignored, error) -> finishCleanup(error));
      } catch (RuntimeException | Error failure) {
        finishCleanup(failure);
      }
    }

    private void finishCleanup(Throwable error) {
      lease.close();
      if (error == null) cleanup.complete(null);
      else cleanup.completeExceptionally(error);
    }
  }

  public final class PointInTime implements AutoCloseable {
    private final PitOwnership ownership;

    private PointInTime(PitOwnership ownership) {
      this.ownership = ownership;
    }

    /**
     * Serialized logical pages; a failed/timed-out page closes this session, including late IDs.
     */
    public synchronized <T> SearchResponse<T> page(SearchRequest request, Class<T> type) {
      String id = ownership.id();
      if (request == null
          || !request.index().isEmpty()
          || request.pit() != null
          || request.scroll() != null
          || (request.from() != null && request.from() != 0)
          || request.size() == null
          || request.size() < 1
          || request.size() > 1000
          || request.searchAfter().size() > 32
          || request.sort().isEmpty()
          || request.sort().size() > 32)
        throw new IllegalArgumentException(
            "PIT page requires bounded size, sort, and no index or PIT override");
      SearchRequest.Builder builder =
          new SearchRequest.Builder()
              .withJson(
                  new java.io.StringReader(
                      co.elastic.clients.json.JsonpUtils.toJsonString(
                          request, ownership.nativeClient._jsonpMapper())));
      // withJson already copies arrays; adding sort/search_after again would duplicate them.
      builder.pit(p -> p.id(id).keepAlive(t -> t.time(ownership.keepAlive.toMillis() + "ms")));
      SearchRequest page = builder.build();
      try {
        return client.executeAwait(
            ownership.lease,
            pagePitCommand,
            (nativeClient, context) ->
                ownership.track(
                    () -> async(nativeClient, context).search(page, type), SearchResponse::pitId));
      } catch (RuntimeException | Error failure) {
        ownership.closeWhenIdle();
        throw failure;
      }
    }

    @Override
    public synchronized void close() {
      ownership.closeWhenIdle();
      try {
        ownership.cleanup.get(timeout.toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new ClientException(
            ClientException.Kind.DRIVER_FAILURE, "PIT cleanup interrupted", interrupted);
      } catch (java.util.concurrent.TimeoutException expired) {
        throw new ClientException(
            ClientException.Kind.TIMEOUT,
            "PIT cleanup is still owned by its native generation",
            expired);
      } catch (java.util.concurrent.ExecutionException failed) {
        throw new ClientException(
            ClientException.Kind.DRIVER_FAILURE,
            "PIT cleanup failed; server expiry remains bounded",
            failed.getCause());
      }
    }
  }

  private static ElasticsearchAsyncClient async(
      ElasticsearchClient client, ElasticsearchSettings settings, Duration ceiling) {
    return async(client, stage -> stageMillis(ceiling, stage), settings);
  }

  /** Floors the smaller of the generation ceiling and the stage timeout. */
  static int stageMillis(Duration ceiling, Duration stage) {
    return CallContext.millisWithin(Math.min(ceiling.toNanos(), stage.toNanos()));
  }

  private static ElasticsearchAsyncClient async(ElasticsearchClient client, CallContext context) {
    // This view borrows the generation's transport. Only the adapter closes that transport.
    return async(client, context::remainingMillis, context.settings(ElasticsearchSettings.class));
  }

  private static ElasticsearchAsyncClient async(
      ElasticsearchClient client,
      java.util.function.ToIntFunction<Duration> remainingMillis,
      ElasticsearchSettings settings) {
    co.elastic.clients.transport.rest5_client.Rest5ClientOptions.Builder options =
        (co.elastic.clients.transport.rest5_client.Rest5ClientOptions.Builder)
            client._transportOptions().toBuilder();
    options
        .restClientRequestOptionsBuilder()
        .setRequestConfig(
            org.apache.hc.client5.http.config.RequestConfig.custom()
                .setConnectionRequestTimeout(
                    org.apache.hc.core5.util.Timeout.ofMilliseconds(
                        remainingMillis.applyAsInt(settings.connectTimeout())))
                .setConnectTimeout(
                    org.apache.hc.core5.util.Timeout.ofMilliseconds(
                        remainingMillis.applyAsInt(settings.connectTimeout())))
                .setResponseTimeout(
                    org.apache.hc.core5.util.Timeout.ofMilliseconds(
                        remainingMillis.applyAsInt(settings.readTimeout())))
                .setRedirectsEnabled(false)
                .build());
    return new ElasticsearchAsyncClient(client._transport(), options.build());
  }

  public ManagedClient<ElasticsearchClient, ElasticsearchSettings> handle() {
    return client;
  }
}
