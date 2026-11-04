package io.github.samhom.driverkit.couchbase;

import static org.junit.jupiter.api.Assertions.*;

import com.couchbase.client.core.msg.kv.CodecFlags;
import com.couchbase.client.java.kv.GetResult;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DocumentJsonTest {
  @Test
  void validatesNativeGetResultBeforeAnyApplicationCodecAndPreservesRawValueAndCas() {
    String json = " [1, {\"value\":true}] ";
    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
    var result =
        new GetResult(
            bytes, CodecFlags.JSON_COMPAT_FLAGS, 42, Optional.empty(), new DocumentJson(128));
    var document = DocumentJson.document(result);
    assertEquals(json, document.json());
    assertEquals(42, document.cas());
    var limited =
        new GetResult(
            bytes, CodecFlags.JSON_COMPAT_FLAGS, 42, Optional.empty(), new DocumentJson(8));
    assertThrows(IllegalArgumentException.class, () -> DocumentJson.document(limited));
    var binary =
        new GetResult(
            bytes, CodecFlags.BINARY_COMPAT_FLAGS, 42, Optional.empty(), new DocumentJson(128));
    assertThrows(IllegalArgumentException.class, () -> DocumentJson.document(binary));
  }

  @Test
  void rejectsTrailingValuesWrapperEscapeDuplicateFieldsDepthAndMalformedUtf8() {
    for (String malformed :
        new String[] {
          "{} {}",
          "null], [1",
          "{\"x\":1,\"x\":2}",
          "[".repeat(65) + "0" + "]".repeat(65),
          "\"" + "\ud800" + "\""
        })
      assertThrows(IllegalArgumentException.class, () -> DocumentJson.encodeJson(malformed, 4096));
    assertThrows(
        IllegalArgumentException.class,
        () -> DocumentJson.validate(new byte[] {34, (byte) 0xc3, 34}, 128));
  }

  @Test
  void rejectsUntrustedTypeMetadataAsDataAndNeverInstantiatesClasses() {
    String json = "{\"@class\":\"java.lang.ProcessBuilder\",\"command\":[\"fixture\"]}";
    assertArrayEquals(json.getBytes(StandardCharsets.UTF_8), DocumentJson.encodeJson(json, 1024));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DocumentJson(1024)
                .decode(
                    Object.class,
                    json.getBytes(StandardCharsets.UTF_8),
                    CodecFlags.JSON_COMPAT_FLAGS));
  }
}
