package io.github.samhom.bridle.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class LimitsUtf8Test {
  @Test
  void utf8WidthMatchesTheCharsetAndStopsAtTheLimit() {
    String[] samples = {
      "", "ascii", "©", "中", "😀", "\uD800", "\uDC00", "\uD800a", "a\uD800\uDC00b"
    };
    for (String sample : samples) {
      int actual = sample.getBytes(StandardCharsets.UTF_8).length;
      assertEquals(actual, Limits.utf8Bytes(sample));
      assertFalse(Limits.exceedsUtf8(sample, actual));
      if (actual > 0) assertTrue(Limits.exceedsUtf8(sample, actual - 1));
    }
    assertThrows(IllegalArgumentException.class, () -> Limits.utf8("😀", 3));
    assertThrows(IllegalArgumentException.class, () -> Limits.messageKey("bad key"));
    assertThrows(IllegalArgumentException.class, () -> Limits.messageKey("a".repeat(513)));
    Limits.messageKey("a".repeat(508) + "😀");
    assertThrows(IllegalArgumentException.class, () -> Limits.messageKey("a".repeat(509) + "😀"));
  }
}
