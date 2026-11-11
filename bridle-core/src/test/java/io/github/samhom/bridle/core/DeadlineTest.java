package io.github.samhom.bridle.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class DeadlineTest {
  @Test
  void remainingBudgetStaysInNanosecondsUntilADurationIsRequested() {
    CallContext context = new CallContext(Duration.ofMillis(50));
    long nanos = context.remainingNanos();
    assertTrue(nanos > 0 && nanos <= 50_000_000L);
    assertTrue(context.remaining().toNanos() <= 50_000_000L);
    context.checkDeadline();
    assertTrue(context.remainingMillis(Duration.ofSeconds(1)) >= 1);
    assertEquals(1, CallContext.millisWithin(1_500_000));
    assertThrows(ClientException.class, () -> CallContext.millisWithin(999_999));
  }

  @Test
  void anExpiredDeadlineRejectsBeforeANativeMillisecondBudget() throws Exception {
    CallContext context = new CallContext(Duration.ofMillis(1));
    Thread.sleep(20);
    assertEquals(0, context.remainingNanos());
    assertThrows(ClientException.class, context::checkDeadline);
    assertThrows(ClientException.class, () -> context.remainingMillis(Duration.ofSeconds(1)));
  }
}
