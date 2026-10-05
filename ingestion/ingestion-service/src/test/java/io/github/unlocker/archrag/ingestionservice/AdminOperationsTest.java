package io.github.unlocker.archrag.ingestionservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Единый путь операции: аудит до начала, итог SUCCEEDED/FAILED, замок. */
class AdminOperationsTest {

  private final AdminLock lock = mock(AdminLock.class);
  private final AdminAudit audit = mock(AdminAudit.class);
  private final AdminOperations operations = new AdminOperations(lock, audit);

  AdminOperationsTest() {
    when(lock.acquire()).thenReturn(mock(AdminLock.Lease.class));
    when(audit.start(any(), any(), any(), any())).thenReturn(7L);
  }

  @Test
  void successIsAuditedAndAuditFailureAfterwardsDoesNotFailTheOperation() {
    doThrow(new IllegalStateException("audit down")).when(audit).finish(eq(7L), eq(true), any(), any());

    String result = operations.run("REPLAY", "alice", Map.of(), null, () -> "ok", r -> Map.of("n", 1));

    assertThat(result).isEqualTo("ok");
    verify(audit).finish(eq(7L), eq(true), any(), eq(null));
  }

  @Test
  void failureAndErrorAreRecordedAsFailedWithClassNameOnly() {
    assertThatThrownBy(() -> operations.run("REBUILD", "alice", Map.of(), null,
        () -> { throw new IllegalStateException("secret value"); }, r -> Map.of()))
        .isInstanceOf(IllegalStateException.class);
    verify(audit).finish(7L, false, null, "IllegalStateException");

    assertThatThrownBy(() -> operations.run("REBUILD", "alice", Map.of(), null,
        () -> { throw new StackOverflowError(); }, r -> Map.of()))
        .isInstanceOf(StackOverflowError.class);
    verify(audit).finish(7L, false, null, "StackOverflowError");
  }

  @Test
  void auditStartFailureMeansOperationIsNotExecuted() {
    when(audit.start(any(), any(), any(), any())).thenThrow(new IllegalStateException("audit write failed"));
    boolean[] ran = {false};

    assertThatThrownBy(() -> operations.run("REPLAY", "alice", Map.of(), null, () -> ran[0] = true, r -> Map.of()))
        .isInstanceOf(IllegalStateException.class);

    assertThat(ran[0]).isFalse();
    verify(audit, never()).finish(any(Long.class), any(Boolean.class), any(), any());
  }
}
