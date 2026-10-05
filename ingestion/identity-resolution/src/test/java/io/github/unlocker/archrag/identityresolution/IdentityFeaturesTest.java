package io.github.unlocker.archrag.identityresolution;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.unlocker.archrag.canonicalmodel.node.ComputeInstance;
import io.github.unlocker.archrag.canonicalmodel.node.ComputeKind;
import io.github.unlocker.archrag.canonicalmodel.node.Deployment;
import io.github.unlocker.archrag.canonicalmodel.node.Environment;
import io.github.unlocker.archrag.canonicalmodel.node.EnvironmentClass;
import io.github.unlocker.archrag.canonicalmodel.node.ITSystem;
import io.github.unlocker.archrag.canonicalmodel.node.Repository;
import io.github.unlocker.archrag.canonicalmodel.node.Service;
import io.github.unlocker.archrag.canonicalmodel.node.Team;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdentityFeaturesTest {

  private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");

  @Test
  void hostnameIsTrimmedLowerCasedWithoutTrailingDot() {
    assertThat(IdentityFeatures.hostname("  Web-01.Corp.Example.  ")).isEqualTo("web-01.corp.example");
  }

  @Test
  void repositoryUrlFormsConvergeToHostAndPath() {
    String expected = "git.corp.example/org/payments";
    for (String raw :
        List.of(
            "https://Git.Corp.Example/org/payments.git",
            "https://git.corp.example/org/payments/",
            "git@git.corp.example:org/payments.git",
            "ssh://git@GIT.corp.example/org/payments",
            "git.corp.example/org/payments.git/")) {
      assertThat(IdentityFeatures.repositoryUrl(raw)).as(raw).isEqualTo(expected);
    }
    assertThat(IdentityFeatures.repositoryUrl("https://git.corp.example/Org/Payments").contains("Org/Payments")).isTrue();
  }

  @Test
  void nameCollapsesNonAlphanumericRuns() {
    assertThat(IdentityFeatures.name("  Payment--Gateway_API ")).isEqualTo("payment gateway api");
  }

  @Test
  void featuresPerLabel() {
    var vm = new ComputeInstance("Web-01.", ComputeKind.VIRTUAL_MACHINE, null, null, null, null, null);
    assertThat(IdentityFeatures.of(vm, Optional.empty())).containsExactly(new Feature(FeatureType.HOSTNAME, "web-01"));
    assertThat(IdentityFeatures.of(new Repository("git@h:o/x.git", null, null), Optional.empty()))
        .containsExactly(new Feature(FeatureType.REPOSITORY_URL, "h/o/x"));
    assertThat(IdentityFeatures.of(new Team("Core Team", null), Optional.empty()))
        .containsExactly(new Feature(FeatureType.NAME, "core team"));
    assertThat(IdentityFeatures.of(new Service("API", null, null, null), Optional.empty()))
        .containsExactly(new Feature(FeatureType.NAME, "api"));
  }

  @Test
  void ownerIsAddedOnlyToNodesWithOwnFeatures() {
    var features = IdentityFeatures.of(new ITSystem("Billing", null, null, null), Optional.of(OWNER));

    assertThat(features)
        .containsExactlyInAnyOrder(
            new Feature(FeatureType.NAME, "billing"), new Feature(FeatureType.OWNER, OWNER.toString()));
  }

  @Test
  void environmentAndDeploymentGiveNoFeatures() {
    var env = new Environment("prod", "Production", EnvironmentClass.PROD);
    var deployment = new Deployment("svc@prod", "svc", "1.0", "RUNNING", Instant.parse("2026-10-01T10:00:00Z"));

    assertThat(IdentityFeatures.of(env, Optional.of(OWNER))).isEmpty();
    assertThat(IdentityFeatures.of(deployment, Optional.of(OWNER))).isEmpty();
  }

  @Test
  void scoreIsNoisyOrOfWeights() {
    assertThat(IdentityFeatures.score(Set.of(FeatureType.NAME)).toPlainString()).isEqualTo("0.600");
    assertThat(IdentityFeatures.score(Set.of(FeatureType.NAME, FeatureType.OWNER)).toPlainString()).isEqualTo("0.720");
    assertThat(IdentityFeatures.score(Set.of(FeatureType.HOSTNAME, FeatureType.OWNER)).toPlainString()).isEqualTo("0.930");
  }
}
