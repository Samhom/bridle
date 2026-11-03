package io.github.samhom.driverkit.couchbase;

import static org.junit.jupiter.api.Assertions.*;

import com.couchbase.client.core.msg.kv.DurabilityLevel;
import com.couchbase.client.java.kv.*;
import org.junit.jupiter.api.Test;

class DocumentExtensionsTest {
  @Test
  void explicitCodecPreservesCasAndTypedValue() {
    ManagedDocuments.JsonCodec<Integer> codec =
        new ManagedDocuments.JsonCodec<>() {
          public String encode(Integer value) {
            return "{\"value\":" + value + "}";
          }

          public Integer decode(String json) {
            return com.couchbase.client.java.json.JsonObject.fromJson(json).getInt("value");
          }
        };
    var document = new ManagedDocuments.TypedDocument<>(codec.decode(codec.encode(42)), 17);
    assertEquals(42, document.value());
    assertEquals(17, document.cas());
    assertNotNull(
        MutateInOptions.mutateInOptions().cas(17).durability(DurabilityLevel.MAJORITY).build());
  }
}
