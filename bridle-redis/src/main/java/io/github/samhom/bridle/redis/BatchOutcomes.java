package io.github.samhom.bridle.redis;

import io.github.samhom.bridle.core.ClientException;
import java.util.List;

/**
 * Redis MULTI/EXEC and pipelines do not share one outcome. A null EXEC means the transaction was
 * discarded and nothing was applied. A pipeline can apply earlier commands and then fail, so a
 * throwable element is an ambiguous write rather than a clean rejection.
 */
final class BatchOutcomes {
  private BatchOutcomes() {}

  static List<Object> transaction(List<Object> responses) {
    if (responses == null)
      throw new ClientException(
          ClientException.Kind.CONFLICT, "Redis transaction was discarded before EXEC");
    return applied(responses);
  }

  static List<Object> pipeline(List<Object> responses) {
    if (responses == null)
      throw new ClientException(
          ClientException.Kind.WRITE_OUTCOME_UNKNOWN,
          "Redis pipeline returned no per-command result");
    return applied(responses);
  }

  private static List<Object> applied(List<Object> responses) {
    for (Object item : responses) {
      if (item instanceof Throwable failure)
        throw new ClientException(
            ClientException.Kind.WRITE_OUTCOME_UNKNOWN,
            "Redis batch applied an unknown prefix of commands",
            failure);
    }
    return List.copyOf(responses);
  }
}
