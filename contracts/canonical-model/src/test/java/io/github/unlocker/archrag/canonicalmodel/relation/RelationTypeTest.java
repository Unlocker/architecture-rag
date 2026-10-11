package io.github.unlocker.archrag.canonicalmodel.relation;

import static io.github.unlocker.archrag.canonicalmodel.node.NodeLabel.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class RelationTypeTest {

    @Test
    void runsOnTargets() {
        for (var target : new io.github.unlocker.archrag.canonicalmodel.node.NodeLabel[] {
                COMPUTE_INSTANCE, VIRTUAL_MACHINE, PHYSICAL_SERVER, NAMESPACE}) {
            assertThat(RelationType.RUNS_ON.allows(DEPLOYMENT, target)).as(target.name()).isTrue();
        }
        assertThat(RelationType.RUNS_ON.allows(DEPLOYMENT, ENVIRONMENT)).isFalse();
        assertThat(RelationType.RUNS_ON.allows(DEPLOYMENT, SERVICE)).isFalse();
        assertThat(RelationType.RUNS_ON.allows(SERVICE, NAMESPACE)).isFalse();
    }

    @Test
    void hostedOnOnlyVmToPhysical() {
        assertThat(RelationType.HOSTED_ON.allows(VIRTUAL_MACHINE, PHYSICAL_SERVER)).isTrue();
        assertThat(RelationType.HOSTED_ON.allows(PHYSICAL_SERVER, VIRTUAL_MACHINE)).isFalse();
        assertThat(RelationType.HOSTED_ON.allows(COMPUTE_INSTANCE, PHYSICAL_SERVER)).isFalse();
    }

    @Test
    void ownedByAndAsserts() {
        assertThat(RelationType.OWNED_BY.allows(IT_SYSTEM, TEAM)).isTrue();
        assertThat(RelationType.OWNED_BY.allows(SERVICE, TEAM)).isTrue();
        assertThat(RelationType.OWNED_BY.allows(TEAM, SERVICE)).isFalse();
        assertThat(RelationType.ASSERTS.allows(SOURCE_RECORD, VIRTUAL_MACHINE)).isTrue();
        assertThat(RelationType.ASSERTS.allows(SOURCE_RECORD, SYNC_RUN)).isFalse();
    }

    @Test
    void temporalTypes() {
        for (var type : RelationType.values()) {
            boolean expected = java.util.Set.of(
                    RelationType.RUNS_ON, RelationType.DEPENDS_ON, RelationType.OWNED_BY,
                    RelationType.HAS_DEPLOYMENT, RelationType.IN_ENVIRONMENT,
                    RelationType.DECOMPOSED_INTO, RelationType.IMPLEMENTED_IN).contains(type);
            assertThat(type.temporal()).as(type.name()).isEqualTo(expected);
        }
    }

    @Test
    void validityInvariants() {
        var t1 = Instant.parse("2026-01-01T00:00:00Z");
        var t2 = Instant.parse("2026-01-02T00:00:00Z");
        assertThat(new Validity(t1, null).validTo()).isNull();
        assertThat(new Validity(t1, t1).validFrom()).isEqualTo(t1);
        assertThatThrownBy(() -> new Validity(t2, t1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Validity(null, t1)).isInstanceOf(NullPointerException.class);
    }
}
