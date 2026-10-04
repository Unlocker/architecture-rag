package io.github.unlocker.archrag.canonicalmodel.authority;

import static io.github.unlocker.archrag.canonicalmodel.provenance.SourceSystemCode.*;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.unlocker.archrag.canonicalmodel.node.NodeLabel;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceSystemCode;
import io.github.unlocker.archrag.canonicalmodel.relation.RelationType;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class AuthorityMatrixTest {

    private final AuthorityMatrix matrix = AuthorityMatrix.defaults();

    private static EnumSet<SourceSystemCode> allExcept(SourceSystemCode master) {
        var others = EnumSet.allOf(SourceSystemCode.class);
        others.remove(master);
        return others;
    }

    @Test
    void itSystemCriticalityOnlyEam() {
        assertThat(matrix.isAuthoritative(NodeLabel.IT_SYSTEM, "criticality", EAM)).isTrue();
        for (var other : allExcept(EAM)) {
            assertThat(matrix.isAuthoritative(NodeLabel.IT_SYSTEM, "criticality", other)).as(other.name()).isFalse();
        }
    }

    @Test
    void dependsOnOnlyEam() {
        assertThat(matrix.relationAuthorities(RelationType.DEPENDS_ON)).containsExactly(EAM);
        assertThat(matrix.isAuthoritative(RelationType.DEPENDS_ON, SCM)).isFalse();
        assertThat(matrix.isAuthoritative(RelationType.DEPENDS_ON, MANUAL)).isFalse();
    }

    @Test
    void deploymentOnlyDeploymap() {
        assertThat(matrix.nodeAuthorities(NodeLabel.DEPLOYMENT)).containsExactly(DEPLOYMAP);
        assertThat(matrix.isAuthoritative(NodeLabel.DEPLOYMENT, "version", DEPLOYMAP)).isTrue();
        for (var other : allExcept(DEPLOYMAP)) {
            assertThat(matrix.isAuthoritative(NodeLabel.DEPLOYMENT, other)).as(other.name()).isFalse();
            assertThat(matrix.isAuthoritative(NodeLabel.DEPLOYMENT, "version", other)).as(other.name()).isFalse();
        }
    }

    @Test
    void masterPerNodeLabel() {
        assertThat(matrix.nodeAuthorities(NodeLabel.SERVICE)).containsExactly(SCM);
        assertThat(matrix.nodeAuthorities(NodeLabel.REPOSITORY)).containsExactly(SCM);
        assertThat(matrix.nodeAuthorities(NodeLabel.VIRTUAL_MACHINE)).containsExactly(CMDB);
        assertThat(matrix.propertyAuthorities(NodeLabel.SERVICE, "language")).containsExactly(SCM);
        assertThat(matrix.propertyAuthorities(NodeLabel.COMPUTE_INSTANCE, "state")).containsExactly(CMDB);
    }

    @Test
    void propertyWithoutOwnRuleInheritsNodeRule() {
        assertThat(matrix.propertyAuthorities(NodeLabel.IT_SYSTEM, "description")).containsExactly(EAM);
        assertThat(matrix.isAuthoritative(NodeLabel.IT_SYSTEM, "description", SCM)).isFalse();
    }

    @Test
    void everyCanonicalLabelAndDomainRelationHasExactlyOneMaster() {
        for (var label : NodeLabel.canonical()) {
            assertThat(matrix.nodeAuthorities(label)).as(label.name()).hasSize(1);
        }
        for (var type : RelationType.values()) {
            boolean provenance = type == RelationType.ASSERTS || type == RelationType.OWNS_RECORD
                    || type == RelationType.PROCESSED;
            assertThat(matrix.relationAuthorities(type)).as(type.name()).hasSize(provenance ? 0 : 1);
        }
    }

    @Test
    void unknownTypesFailClosed() {
        assertThat(matrix.nodeAuthorities(NodeLabel.SOURCE_RECORD)).isEmpty();
        assertThat(matrix.isAuthoritative(NodeLabel.SOURCE_RECORD, EAM)).isFalse();
        assertThat(matrix.isAuthoritative(RelationType.ASSERTS, EAM)).isFalse();
    }
}
