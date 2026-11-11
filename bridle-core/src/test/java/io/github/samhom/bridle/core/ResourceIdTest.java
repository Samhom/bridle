package io.github.samhom.bridle.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class ResourceIdTest {
  @Test
  void identityHashAndTextAreStable() {
    ResourceId id = new ResourceId("app", "test", "cache");
    ResourceId same = new ResourceId("app", "test", "cache");
    assertEquals(id, same);
    assertEquals(id.hashCode(), same.hashCode());
    assertEquals("app/test/cache", id.toString());
    assertSame(id.toString(), id.toString());
    assertNotEquals(id, new ResourceId("app", "test", "other"));
  }
}
