package io.github.unlocker.archrag.graphquerycore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BudgetedCollectorTest {

  /** Итератор из n строк, считающий, сколько из них прочитано. */
  static Iterator<Map<String, Object>> rows(int n, AtomicInteger consumed) {
    List<Map<String, Object>> list = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      list.add(Map.of("gid", "g" + i));
    }
    Iterator<Map<String, Object>> it = list.iterator();
    return new Iterator<>() {
      public boolean hasNext() {
        return it.hasNext();
      }

      public Map<String, Object> next() {
        consumed.incrementAndGet();
        return it.next();
      }
    };
  }

  @Test
  void fewerRowsThanLimitAreAllReturned() {
    var result = new BudgetedCollector(5, 10_000).collect(rows(3, new AtomicInteger()));
    assertThat(result.rows()).hasSize(3);
    assertThat(result.truncated()).isFalse();
  }

  @Test
  void exactlyMaxRowsIsNotTruncated() {
    var result = new BudgetedCollector(5, 10_000).collect(rows(5, new AtomicInteger()));
    assertThat(result.rows()).hasSize(5);
    assertThat(result.truncated()).isFalse();
  }

  @Test
  void moreRowsThanLimitAreCutAndNeverReadBeyondMaxRowsPlusOne() {
    AtomicInteger consumed = new AtomicInteger();
    var result = new BudgetedCollector(5, 10_000).collect(rows(1000, consumed));
    assertThat(result.rows()).hasSize(5);
    assertThat(result.truncated()).isTrue();
    assertThat(consumed).hasValue(6);
  }

  @Test
  void byteThresholdTruncatesAndDropsOffendingRow() {
    // {"gid":"g0"} = 12 байт: в 30 байт влезают две строки, третья отбрасывается.
    var result = new BudgetedCollector(100, 30).collect(rows(10, new AtomicInteger()));
    assertThat(result.rows()).hasSize(2);
    assertThat(result.truncated()).isTrue();
  }

  @Test
  void rowLargerThanByteBudgetGivesEmptyTruncatedResult() {
    var result = new BudgetedCollector(100, 5).collect(rows(1, new AtomicInteger()));
    assertThat(result.rows()).isEmpty();
    assertThat(result.truncated()).isTrue();
  }

  @Test
  void nonPositiveBudgetIsRejected() {
    assertThatThrownBy(() -> new BudgetedCollector(0, 10)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new BudgetedCollector(1, 0)).isInstanceOf(IllegalArgumentException.class);
  }
}
