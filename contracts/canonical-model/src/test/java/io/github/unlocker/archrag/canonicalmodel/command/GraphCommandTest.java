package io.github.unlocker.archrag.canonicalmodel.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.unlocker.archrag.canonicalmodel.node.NodeLabel;
import io.github.unlocker.archrag.canonicalmodel.node.Team;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceKey;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceRecord;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceSystemCode;
import io.github.unlocker.archrag.canonicalmodel.relation.RelationType;
import io.github.unlocker.archrag.canonicalmodel.relation.Validity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GraphCommandTest {

    private static final Instant T = Instant.parse("2026-01-01T00:00:00Z");
    private static final SourceKey DEPLOY = new SourceKey(SourceSystemCode.DEPLOYMAP, "deployment", "d1");
    private static final SourceKey HOST = new SourceKey(SourceSystemCode.CMDB, "host", "h1");
    private static final SourceKey ENV = new SourceKey(SourceSystemCode.DEPLOYMAP, "env", "e1");

    private static UpsertRelation relation(RelationType type, NodeLabel from, NodeLabel to,
                                           Map<String, Object> props, Validity validity) {
        return new UpsertRelation(type, DEPLOY, from, HOST, to, props, validity, DEPLOY);
    }

    @Test
    void validityOnlyForTemporal() {
        var validity = new Validity(T, null);
        assertThatThrownBy(() -> relation(RelationType.IN_ENVIRONMENT, NodeLabel.DEPLOYMENT, NodeLabel.ENVIRONMENT,
                Map.of(), validity)).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> relation(RelationType.RUNS_ON, NodeLabel.DEPLOYMENT, NodeLabel.VIRTUAL_MACHINE,
                Map.of(), validity)).doesNotThrowAnyException();
    }

    @Test
    void disallowedLabelsRejected() {
        assertThatThrownBy(() -> relation(RelationType.RUNS_ON, NodeLabel.DEPLOYMENT, NodeLabel.ENVIRONMENT,
                Map.of(), null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void propertiesValidatedAndImmutable() {
        assertThatThrownBy(() -> relation(RelationType.DEPENDS_ON, NodeLabel.SERVICE, NodeLabel.SERVICE,
                Map.of("kind", new Object()), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> relation(RelationType.DEPENDS_ON, NodeLabel.SERVICE, NodeLabel.SERVICE,
                Map.of("kind", List.of(new Object())), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> relation(RelationType.DEPENDS_ON, NodeLabel.SERVICE, NodeLabel.SERVICE,
                Map.of("kind", List.of(List.of("nested"))), null)).isInstanceOf(IllegalArgumentException.class);

        var tags = new ArrayList<>(List.of("a"));
        var props = new HashMap<String, Object>();
        props.put("protocol", "HTTP");
        props.put("weight", 3);
        props.put("tags", tags);
        var cmd = relation(RelationType.DEPENDS_ON, NodeLabel.SERVICE, NodeLabel.SERVICE, props, null);
        props.put("later", "x");
        tags.add("b");
        assertThat(cmd.properties()).containsOnlyKeys("protocol", "weight", "tags");
        assertThat(cmd.properties().get("tags")).isEqualTo(List.of("a"));
        assertThatThrownBy(() -> cmd.properties().put("k", "v")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void closeAssertionOnlyTemporal() {
        assertThatThrownBy(() -> new CloseAssertion(RelationType.IN_ENVIRONMENT, DEPLOY, ENV, T, DEPLOY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CloseAssertion(RelationType.RUNS_ON, DEPLOY, HOST, null, DEPLOY))
                .isInstanceOf(NullPointerException.class);
        assertThatCode(() -> new CloseAssertion(RelationType.RUNS_ON, DEPLOY, HOST, T, DEPLOY))
                .doesNotThrowAnyException();
    }

    @Test
    void upsertNodeAndTombstoneRequireFields() {
        assertThatThrownBy(() -> new UpsertNode(null, new Team("t", null))).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TombstoneSourceRecord(HOST, null)).isInstanceOf(NullPointerException.class);
        var record = new SourceRecord(HOST, "1", "hash", T, true);
        assertThat(new UpsertNode(record, new Team("t", null)).data()).isEqualTo(new Team("t", null));
    }

    @Test
    void switchIsExhaustiveWithoutDefault() {
        GraphCommand command = new TombstoneSourceRecord(HOST, T);
        String kind = switch (command) {
            case UpsertNode u -> "node";
            case UpsertRelation r -> "relation";
            case CloseAssertion c -> "close";
            case TombstoneSourceRecord t -> "tombstone";
        };
        assertThat(kind).isEqualTo("tombstone");
    }
}
