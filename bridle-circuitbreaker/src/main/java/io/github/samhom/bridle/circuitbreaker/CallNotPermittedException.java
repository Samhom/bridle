package io.github.samhom.bridle.circuitbreaker;

/** The breaker refused the attempt. Managed clients translate this into a circuit-open failure. */
public final class CallNotPermittedException extends RuntimeException {
  public CallNotPermittedException(String message) {
    super(message);
  }
}
