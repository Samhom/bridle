package io.github.samhom.bridle.core;

public interface Observer {
  Observation start(ResourceId resource, String driver, Operation operation, long revision);

  Observer NOOP = (resource, driver, operation, revision) -> Observation.NOOP;
}
