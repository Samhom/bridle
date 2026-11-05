package io.github.samhom.driverkit.redis;

import static org.junit.jupiter.api.Assertions.*;

import io.github.samhom.driverkit.core.ClientException;
import java.util.List;
import org.junit.jupiter.api.Test;

class BatchOutcomesTest {
  @Test
  void discardedTransactionIsADefiniteConflictAndPipelineErrorsStayAmbiguous() {
    assertEquals(List.of("OK"), BatchOutcomes.transaction(List.of("OK")));
    ClientException discarded =
        assertThrows(ClientException.class, () -> BatchOutcomes.transaction(null));
    assertEquals(ClientException.Kind.CONFLICT, discarded.kind());

    ClientException partial =
        assertThrows(
            ClientException.class,
            () -> BatchOutcomes.pipeline(List.of("OK", new IllegalStateException("CROSSSLOT"))));
    assertEquals(ClientException.Kind.WRITE_OUTCOME_UNKNOWN, partial.kind());
    assertInstanceOf(IllegalStateException.class, partial.getCause());
    assertThrows(ClientException.class, () -> BatchOutcomes.pipeline(null));
  }
}
