package io.github.samhom.bridle.couchbase.legacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.samhom.bridle.core.ClientException;
import org.junit.jupiter.api.Test;

class TimeoutFloorTest {
  @Test
  void connectBudgetFloorsInsteadOfStretchingASubMillisecondRemainder() {
    assertEquals(1, Couchbase2Client.connectMillis(1_500_000L));
    assertEquals(
        ClientException.Kind.TIMEOUT,
        assertThrows(ClientException.class, () -> Couchbase2Client.connectMillis(999_999L)).kind());
  }
}
