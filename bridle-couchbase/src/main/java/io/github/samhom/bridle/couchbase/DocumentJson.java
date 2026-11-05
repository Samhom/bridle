package io.github.samhom.bridle.couchbase;

import com.couchbase.client.core.deps.com.fasterxml.jackson.core.*;
import com.couchbase.client.core.msg.kv.CodecFlags;
import com.couchbase.client.java.codec.*;
import io.github.samhom.bridle.core.Limits;
import java.nio.*;
import java.nio.charset.*;

/**
 * Per-request limits. Uses the driver's pinned shaded streaming parser, never a polymorphic mapper.
 */
final class DocumentJson implements Transcoder {
  private static final JsonFactory JSON =
      JsonFactory.builder()
          .streamReadConstraints(
              StreamReadConstraints.builder()
                  .maxNestingDepth(64)
                  .maxTokenCount(1000000)
                  .maxStringLength(16 * 1024 * 1024)
                  .maxNameLength(65536)
                  .maxNumberLength(1000)
                  .build())
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .build();
  private final int maximum;

  DocumentJson(int maximum) {
    this.maximum = Limits.count(maximum, 16 * 1024 * 1024);
  }

  static byte[] encodeJson(String value, int maximum) {
    if (value == null || value.length() > maximum) throw invalid();
    try {
      ByteBuffer buffer =
          StandardCharsets.UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .encode(CharBuffer.wrap(value));
      byte[] bytes = new byte[buffer.remaining()];
      buffer.get(bytes);
      validate(bytes, maximum);
      return bytes;
    } catch (CharacterCodingException malformed) {
      throw invalid();
    }
  }

  static void validate(byte[] bytes, int maximum) {
    Limits.bytes(bytes, maximum);
    try (JsonParser parser = JSON.createParser(bytes)) {
      if (parser.nextToken() == null) throw invalid();
      parser.skipChildren();
      if (parser.nextToken() != null) throw invalid();
    } catch (java.io.IOException malformed) {
      throw invalid();
    }
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Expected one bounded UTF-8 JSON value");
  }

  @Override
  public EncodedValue encode(Object value) {
    if (!(value instanceof byte[] bytes)) throw invalid();
    validate(bytes, maximum);
    return RawJsonTranscoder.INSTANCE.encode(bytes);
  }

  @Override
  public <T> T decode(Class<T> type, byte[] bytes, int flags) {
    if (CodecFlags.hasCommonFlags(flags)
        && !CodecFlags.hasCommonFormat(flags, CodecFlags.JSON_COMMON_FLAGS))
      throw new IllegalArgumentException("Document is not JSON");
    validate(bytes, maximum);
    if (type != byte[].class)
      throw new IllegalArgumentException("Only bounded raw JSON bytes are exposed");
    return type.cast(bytes);
  }

  static ManagedDocuments.Document document(com.couchbase.client.java.kv.GetResult result) {
    // contentAsBytes() bypasses the native transcoder in java-client 3.13.0.
    // contentAs(byte[].class) deliberately enters the per-request validation boundary.
    byte[] bytes = result.contentAs(byte[].class);
    return new ManagedDocuments.Document(new String(bytes, StandardCharsets.UTF_8), result.cas());
  }
}
