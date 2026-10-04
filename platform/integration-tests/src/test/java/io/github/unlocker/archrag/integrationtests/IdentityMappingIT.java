package io.github.unlocker.archrag.integrationtests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.unlocker.archrag.canonicalmodel.provenance.SourceKey;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceSystemCode;
import io.github.unlocker.archrag.eventjournal.JournalMigrations;
import io.github.unlocker.archrag.identityresolution.Crosswalk;
import io.github.unlocker.archrag.identityresolution.CrosswalkConflictException;
import io.github.unlocker.archrag.identityresolution.PostgresIdentityMapping;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Mapping и crosswalk на реальном PostgreSQL: стабильный gid, общий gid для связанных записей, конфликты. */
@Testcontainers
class IdentityMappingIT {

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

  private static PostgresIdentityMapping mapping;

  @BeforeAll
  static void migrate() {
    var ds = new PGSimpleDataSource();
    ds.setUrl(POSTGRES.getJdbcUrl());
    ds.setUser(POSTGRES.getUsername());
    ds.setPassword(POSTGRES.getPassword());
    DataSource dataSource = ds;
    JournalMigrations.apply(dataSource);
    mapping = new PostgresIdentityMapping(dataSource);
  }

  private static SourceKey key(SourceSystemCode source, String type) {
    return new SourceKey(source, type, UUID.randomUUID().toString());
  }

  private static Crosswalk crosswalk(SourceKey a, SourceKey b) {
    return new Crosswalk(a, b, "admin", Instant.parse("2026-10-04T12:00:00Z"));
  }

  @Test
  void findIsEmptyUntilResolved() {
    var key = key(SourceSystemCode.EAM, "IT_SYSTEM");

    assertThat(mapping.find(key)).isEmpty();
    UUID gid = mapping.resolve(key);
    assertThat(mapping.find(key)).contains(gid);
  }

  @Test
  void repeatedResolveReturnsSameGidWithNewMappingInstance() {
    var key = key(SourceSystemCode.SCM, "SERVICE");
    UUID first = mapping.resolve(key);

    // «Rebuild»: граф пересобран, а mapping в PostgreSQL остался — gid те же.
    var afterRebuild = new PostgresIdentityMapping(dataSource());

    assertThat(afterRebuild.resolve(key)).isEqualTo(first);
    assertThat(mapping.resolve(key)).isEqualTo(first);
  }

  @Test
  void sameSourceIdInDifferentSystemsIsNotSameEntity() {
    var eam = new SourceKey(SourceSystemCode.EAM, "IT_SYSTEM", "1042");
    var scm = new SourceKey(SourceSystemCode.SCM, "IT_SYSTEM", "1042");

    assertThat(mapping.resolve(eam)).isNotEqualTo(mapping.resolve(scm));
  }

  @Test
  void concurrentResolveConvergesToOneGid() throws Exception {
    var key = key(SourceSystemCode.CMDB, "COMPUTE_INSTANCE");
    try (var pool = Executors.newFixedThreadPool(8)) {
      var calls = IntStream.range(0, 16).<Callable<UUID>>mapToObj(i -> () -> mapping.resolve(key)).toList();
      Set<UUID> gids = new java.util.HashSet<>();
      for (var f : pool.invokeAll(calls)) {
        gids.add(f.get());
      }
      assertThat(gids).hasSize(1);
    }
  }

  @Test
  void crosswalkGivesEamAndScmRecordsOneGid() {
    var eam = key(SourceSystemCode.EAM, "IT_SYSTEM");
    var scm = key(SourceSystemCode.SCM, "CATALOG_SYSTEM");

    UUID gid = mapping.approve(crosswalk(eam, scm));

    assertThat(mapping.resolve(eam)).isEqualTo(gid);
    assertThat(mapping.resolve(scm)).isEqualTo(gid);
    assertThat(mapping.keysOf(gid)).containsExactlyInAnyOrder(eam, scm);
  }

  @Test
  void crosswalkAdoptsGidOfAlreadyMappedKey() {
    var eam = key(SourceSystemCode.EAM, "IT_SYSTEM");
    var scm = key(SourceSystemCode.SCM, "CATALOG_SYSTEM");
    UUID existing = mapping.resolve(eam);

    assertThat(mapping.approve(crosswalk(scm, eam))).isEqualTo(existing);
    assertThat(mapping.resolve(scm)).isEqualTo(existing);
  }

  @Test
  void crosswalkIsIdempotentAndOrderInsensitive() {
    var eam = key(SourceSystemCode.EAM, "IT_SYSTEM");
    var scm = key(SourceSystemCode.SCM, "CATALOG_SYSTEM");

    UUID gid = mapping.approve(crosswalk(eam, scm));

    assertThat(mapping.approve(crosswalk(eam, scm))).isEqualTo(gid);
    assertThat(mapping.approve(crosswalk(scm, eam))).isEqualTo(gid);
    assertThat(mapping.keysOf(gid)).hasSize(2);
  }

  @Test
  void crosswalkOfKeysWithDifferentGidsIsRejectedAndChangesNothing() {
    var eam = key(SourceSystemCode.EAM, "IT_SYSTEM");
    var scm = key(SourceSystemCode.SCM, "CATALOG_SYSTEM");
    UUID gidEam = mapping.resolve(eam);
    UUID gidScm = mapping.resolve(scm);

    assertThatThrownBy(() -> mapping.approve(crosswalk(eam, scm))).isInstanceOf(CrosswalkConflictException.class);

    assertThat(mapping.find(eam)).contains(gidEam);
    assertThat(mapping.find(scm)).contains(gidScm);
  }

  private static DataSource dataSource() {
    var ds = new PGSimpleDataSource();
    ds.setUrl(POSTGRES.getJdbcUrl());
    ds.setUser(POSTGRES.getUsername());
    ds.setPassword(POSTGRES.getPassword());
    return ds;
  }
}
