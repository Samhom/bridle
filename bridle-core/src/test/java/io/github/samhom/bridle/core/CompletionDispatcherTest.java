package io.github.samhom.bridle.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class CompletionDispatcherTest {
  @Test
  void rejectedDeliveryReturnsTheReservation() {
    ThreadPoolExecutor executor =
        new ThreadPoolExecutor(
            1,
            1,
            1,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1),
            new ThreadPoolExecutor.AbortPolicy());
    executor.shutdown();
    CompletionDispatcher dispatcher = new CompletionDispatcher(executor, 1);
    CompletionDispatcher.Reservation reservation = dispatcher.reserve();
    assertThrows(RejectedExecutionException.class, () -> reservation.deliver(() -> {}));
    assertDoesNotThrow(() -> dispatcher.reserve().release());
    dispatcher.close();
  }
}
