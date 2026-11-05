package io.github.samhom.driverkit.elasticsearch;

import static org.junit.jupiter.api.Assertions.*;

import co.elastic.clients.elasticsearch.core.SearchRequest;
import org.junit.jupiter.api.Test;

class SearchBoundaryTest {
  @Test
  void managedSearchRejectsScrollAndNonZeroFrom() {
    SearchRequest scroll =
        new SearchRequest.Builder().index("orders").scroll(time -> time.time("1m")).build();
    SearchRequest deep = new SearchRequest.Builder().index("orders").from(1).size(10).build();
    SearchRequest bounded = new SearchRequest.Builder().index("orders").size(10).build();
    assertThrows(IllegalArgumentException.class, () -> ManagedSearch.validateSearch(scroll));
    assertThrows(IllegalArgumentException.class, () -> ManagedSearch.validateSearch(deep));
    assertDoesNotThrow(() -> ManagedSearch.validateSearch(bounded));
    assertThrows(IllegalArgumentException.class, () -> ManagedSearch.validateSearch(null));
  }
}
