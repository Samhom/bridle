package io.github.samhom.driverkit.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class DeadlineBudgetTest {
  @Test
  void millisecondConversionFloorsAndRejectsASubMillisecondRemainder() {
    assertEquals(1, CallContext.millisWithin(1_000_000L));
    assertEquals(1, CallContext.millisWithin(1_999_999L));
    assertEquals(2, CallContext.millisWithin(2_000_000L));
    ClientException tooSmall =
        assertThrows(ClientException.class, () -> CallContext.millisWithin(999_999L));
    assertEquals(ClientException.Kind.TIMEOUT, tooSmall.kind());
    assertThrows(ClientException.class, () -> CallContext.millisWithin(0));
  }
}
