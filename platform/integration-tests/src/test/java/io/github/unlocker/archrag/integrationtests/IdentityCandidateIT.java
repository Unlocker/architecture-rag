package io.github.unlocker.archrag.integrationtests;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.unlocker.archrag.canonicalmodel.node.NodeLabel;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceKey;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceSystemCode;
import io.github.unlocker.archrag.eventjournal.JournalMigrations;
import io.github.unlocker.archrag.identityresolution.Crosswalk;
import io.github.unlocker.archrag.identityresolution.Feature;
import io.github.unlocker.archrag.identityresolution.FeatureType;
import io.github.unlocker.archrag.identityresolution.IdentityCandidate;
import io.github.unlocker.archrag.identityresolution.PostgresIdentityCandidates;
import io.github.unlocker.archrag.identityresolution.PostgresIdentityMapping;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Кандидаты на совпадение на реальном PostgreSQL: пары, идемпотентность, общий gid, OWNER, потолок пар. */
@Testcontainers
class IdentityCandidateIT {

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

  private static PostgresIdentityMapping mapping;
  private static PostgresIdentityCandidates candidates;

  @BeforeAll
  static void migrate() {
    var ds = new PGSimpleDataSource();
    ds.setUrl(POSTGRES.getJdbcUrl());
    ds.setUser(POSTGRES.getUsername());
    ds.setPassword(POSTGRES.getPassword());
    DataSource dataSource = ds;
    JournalMigrations.apply(dataSource);
    mapping = new PostgresIdentityMapping(dataSource);
    candidates = new PostgresIdentityCandidates(dataSource);
  }

  private static SourceKey key(SourceSystemCode source, String type) {
    return new SourceKey(source, type, UUID.randomUUID().toString());
  }

  private static UUID observe(SourceKey key, NodeLabel label, Feature... features) {
    UUID gid = mapping.resolve(key);
    candidates.observe(key, gid, label, Set.of(features));
    return gid;
  }

  private static Feature host(String value) {
    return new Feature(FeatureType.HOSTNAME, value);
  }

  private static Feature name(String value) {
    return new Feature(FeatureType.NAME, value);
  }

  private static Feature owner(UUID gid) {
    return new Feature(FeatureType.OWNER, gid.toString());
  }

  @Test
  void sameHostnameInTwoRecordsCreatesOneCandidateAndKeepsGids() {
    String hostname = "web-" + UUID.randomUUID();
    var a = key(SourceSystemCode.CMDB, "COMPUTE_INSTANCE");
    var b = key(SourceSystemCode.CMDB, "COMPUTE_INSTANCE");

    UUID ga = observe(a, NodeLabel.COMPUTE_INSTANCE, host(hostname));
    UUID gb = observe(b, NodeLabel.COMPUTE_INSTANCE, host(hostname));

    assertThat(ga).isNotEqualTo(gb);
    assertThat(mapping.find(a)).contains(ga);
    assertThat(mapping.find(b)).contains(gb);
    List<IdentityCandidate> found = candidates.candidatesOf(ga);
    assertThat(found).singleElement().satisfies(c -> {
      assertThat(Set.of(c.leftGid(), c.rightGid())).containsExactlyInAnyOrder(ga, gb);
      assertThat(c.leftGid().compareTo(c.rightGid())).isNegative();
      assertThat(c.features()).containsExactly(FeatureType.HOSTNAME);
      assertThat(c.score().toPlainString()).isEqualTo("0.900");
      assertThat(c.status()).isEqualTo("OPEN");
    });
    assertThat(candidates.candidatesOf(gb)).hasSize(1);
  }

  @Test
  void repeatedObserveAndReplayKeepOneCandidateWithSameCreatedAt() {
    String hostname = "db-" + UUID.randomUUID();
    var a = key(SourceSystemCode.CMDB, "COMPUTE_INSTANCE");
    var b = key(SourceSystemCode.CMDB, "COMPUTE_INSTANCE");
    UUID ga = observe(a, NodeLabel.COMPUTE_INSTANCE, host(hostname));
    observe(b, NodeLabel.COMPUTE_INSTANCE, host(hostname));
    IdentityCandidate first = candidates.candidatesOf(ga).get(0);

    observe(b, NodeLabel.COMPUTE_INSTANCE, host(hostname));
    observe(a, NodeLabel.COMPUTE_INSTANCE, host(hostname));

    assertThat(candidates.candidatesOf(ga)).singleElement().satisfies(c -> {
      assertThat(c.createdAt()).isEqualTo(first.createdAt());
      assertThat(c.features()).isEqualTo(first.features());
      assertThat(c.score()).isEqualByComparingTo(first.score());
    });
  }

  @Test
  void recordsLinkedByCrosswalkShareGidAndGiveNoCandidate() {
    String hostname = "shared-" + UUID.randomUUID();
    var a = key(SourceSystemCode.CMDB, "COMPUTE_INSTANCE");
    var b = key(SourceSystemCode.DEPLOYMAP, "COMPUTE_INSTANCE");
    mapping.approve(new Crosswalk(a, b, "admin", "same host", Instant.parse("2026-10-04T12:00:00Z")));

    UUID ga = observe(a, NodeLabel.COMPUTE_INSTANCE, host(hostname));
    UUID gb = observe(b, NodeLabel.COMPUTE_INSTANCE, host(hostname));

    assertThat(ga).isEqualTo(gb);
    assertThat(candidates.candidatesOf(ga)).isEmpty();
  }

  @Test
  void onlyOwnerInCommonGivesNoCandidate() {
    UUID team = UUID.randomUUID();
    var a = key(SourceSystemCode.EAM, "SERVICE");
    var b = key(SourceSystemCode.EAM, "SERVICE");

    UUID ga = observe(a, NodeLabel.SERVICE, name("alpha-" + team), owner(team));
    observe(b, NodeLabel.SERVICE, name("beta-" + team), owner(team));

    assertThat(candidates.candidatesOf(ga)).isEmpty();
  }

  @Test
  void ownerRaisesScoreOfNameMatch() {
    UUID team = UUID.randomUUID();
    String nm = "payments-" + team;
    var a1 = key(SourceSystemCode.EAM, "SERVICE");
    var b1 = key(SourceSystemCode.SCM, "SERVICE");
    UUID g1 = observe(a1, NodeLabel.SERVICE, name(nm));
    observe(b1, NodeLabel.SERVICE, name(nm));
    String nm2 = "billing-" + team;
    var a2 = key(SourceSystemCode.EAM, "SERVICE");
    var b2 = key(SourceSystemCode.SCM, "SERVICE");
    UUID g2 = observe(a2, NodeLabel.SERVICE, name(nm2), owner(team));
    observe(b2, NodeLabel.SERVICE, name(nm2), owner(team));

    IdentityCandidate nameOnly = candidates.candidatesOf(g1).get(0);
    IdentityCandidate nameAndOwner = candidates.candidatesOf(g2).get(0);

    assertThat(nameOnly.score().toPlainString()).isEqualTo("0.600");
    assertThat(nameAndOwner.features()).containsExactlyInAnyOrder(FeatureType.NAME, FeatureType.OWNER);
    assertThat(nameAndOwner.score().toPlainString()).isEqualTo("0.720");
  }

  @Test
  void differentLabelsWithSameValueDoNotMatch() {
    String shared = "core-" + UUID.randomUUID();
    var team = key(SourceSystemCode.EAM, "TEAM");
    var service = key(SourceSystemCode.EAM, "SERVICE");

    UUID gt = observe(team, NodeLabel.TEAM, name(shared));
    observe(service, NodeLabel.SERVICE, name(shared));

    assertThat(candidates.candidatesOf(gt)).isEmpty();
  }

  @Test
  void pairsPerObservationAreCapped() {
    String nm = "api-" + UUID.randomUUID();
    int total = PostgresIdentityCandidates.MAX_PAIRS + 10;
    for (int i = 0; i < total; i++) {
      observe(key(SourceSystemCode.SCM, "SERVICE"), NodeLabel.SERVICE, name(nm));
    }
    var last = key(SourceSystemCode.SCM, "SERVICE");

    UUID gid = observe(last, NodeLabel.SERVICE, name(nm));

    assertThat(candidates.candidatesOf(gid)).hasSize(PostgresIdentityCandidates.MAX_PAIRS);
  }
}
