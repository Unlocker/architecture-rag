package io.github.unlocker.archrag.canonicalmodel.provenance;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProvenanceTest {

    private static final Instant T = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void sourceKeyRequiresAllParts() {
        assertThatThrownBy(() -> new SourceKey(null, "t", "1")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SourceKey(SourceSystemCode.EAM, "", "1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SourceKey(SourceSystemCode.EAM, "t", " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> new SourceKey(SourceSystemCode.EAM, "t", "EAM-1042")).doesNotThrowAnyException();
    }

    @Test
    void sourceRecordRequiredFields() {
        var key = new SourceKey(SourceSystemCode.SCM, "repo", "1");
        assertThatThrownBy(() -> new SourceRecord(key, "", "h", T, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SourceRecord(key, "v", null, T, true)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SourceRecord(key, "v", "h", null, true)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SourceRecord(null, "v", "h", T, true)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void sourceSystemAndSyncRun() {
        assertThatThrownBy(() -> new SourceSystem(SourceSystemCode.EAM, "")).isInstanceOf(IllegalArgumentException.class);
        var id = UUID.randomUUID();
        assertThatThrownBy(() -> new SyncRun(id, "eam", T, T.minusSeconds(1), SyncRunStatus.FAILED, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SyncRun(id, "eam", T, null, SyncRunStatus.RUNNING, -1, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> new SyncRun(id, "eam", T, null, SyncRunStatus.RUNNING, 0, 0, 0)).doesNotThrowAnyException();
    }
}
