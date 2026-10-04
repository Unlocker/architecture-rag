package io.github.unlocker.archrag.canonicalmodel.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class NodeTest {

    @Test
    void requiredFieldsRejectNullAndBlank() {
        assertThatThrownBy(() -> new ITSystem(null, null, null, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ITSystem("", null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Service(null, null, null, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Service(" ", null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Repository(null, null, false)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Repository("", null, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Team(null, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Team("", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Environment(null, "n", EnvironmentClass.DEV)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Environment("", "n", EnvironmentClass.DEV)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Environment("c", "n", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Deployment(null, "n", null, null, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Deployment("", "n", null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> vm(null, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ComputeInstance("", ComputeKind.UNSPECIFIED, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Namespace(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Namespace("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KubernetesCluster(null, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new KubernetesCluster("", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void optionalFieldRejectsEmptyString() {
        assertThatThrownBy(() -> new Team("t", "")).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> new Team("t", null)).doesNotThrowAnyException();
    }

    private static ComputeInstance vm(String hostname, String hypervisor) {
        return new ComputeInstance(hostname, ComputeKind.VIRTUAL_MACHINE, null, null, null, hypervisor, null);
    }

    @Test
    void computeInstanceLabelFollowsKind() {
        assertThat(vm("h", null).label()).isEqualTo(NodeLabel.VIRTUAL_MACHINE);
        assertThat(new ComputeInstance("h", ComputeKind.PHYSICAL_SERVER, null, null, null, null, "sn").label())
                .isEqualTo(NodeLabel.PHYSICAL_SERVER);
        assertThat(new ComputeInstance("h", ComputeKind.UNSPECIFIED, null, null, null, null, null).label())
                .isEqualTo(NodeLabel.COMPUTE_INSTANCE);
    }

    @Test
    void computeInstanceKindSpecificFields() {
        assertThatThrownBy(() -> new ComputeInstance("h", ComputeKind.PHYSICAL_SERVER, null, null, null, "hv", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ComputeInstance("h", ComputeKind.UNSPECIFIED, null, null, null, "hv", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ComputeInstance("h", ComputeKind.VIRTUAL_MACHINE, null, null, null, null, "sn"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> vm("h", "hv")).doesNotThrowAnyException();
    }

    @Test
    void labelSupertypes() {
        assertThat(NodeLabel.VIRTUAL_MACHINE.isA(NodeLabel.COMPUTE_INSTANCE)).isTrue();
        assertThat(NodeLabel.PHYSICAL_SERVER.isA(NodeLabel.COMPUTE_INSTANCE)).isTrue();
        assertThat(NodeLabel.COMPUTE_INSTANCE.isA(NodeLabel.VIRTUAL_MACHINE)).isFalse();
        assertThat(NodeLabel.SERVICE.isA(NodeLabel.SERVICE)).isTrue();
        assertThat(NodeLabel.VIRTUAL_MACHINE.label()).isEqualTo("VirtualMachine");
    }

    @Test
    void lifecycleInvariants() {
        var t1 = Instant.parse("2026-01-01T00:00:00Z");
        var t2 = Instant.parse("2026-01-02T00:00:00Z");
        assertThatThrownBy(() -> new Lifecycle(t2, t1, null, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Lifecycle(t1, t2, t2, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Lifecycle(t1, t2, null, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Lifecycle(null, t2, null, true)).isInstanceOf(NullPointerException.class);
        assertThatCode(() -> new Lifecycle(t1, t2, null, true)).doesNotThrowAnyException();
        assertThatCode(() -> new Lifecycle(t1, t2, t2, false)).doesNotThrowAnyException();
    }
}
