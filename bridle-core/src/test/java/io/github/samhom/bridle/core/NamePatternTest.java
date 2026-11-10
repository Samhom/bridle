package io.github.samhom.bridle.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

class NamePatternTest {
  @Test
  void compiledPatternsKeepTheSameNamesAndHosts() {
    assertEquals("cache_1", Limits.name("cache_1"));
    assertThrows(IllegalArgumentException.class, () -> Limits.name("bad name"));
    assertEquals("localhost", new Endpoint("localhost", 6379, false).host());
    assertThrows(IllegalArgumentException.class, () -> new Endpoint("bad host", 6379, false));
    assertThrows(IllegalArgumentException.class, () -> CallContexts.open(Map.of("bad key", "x")));
    try (ContextPropagation.Scope ignored = CallContexts.open(Map.of("request", "one"))) {
      assertEquals("one", CallContexts.current().get("request"));
    }
  }
}
