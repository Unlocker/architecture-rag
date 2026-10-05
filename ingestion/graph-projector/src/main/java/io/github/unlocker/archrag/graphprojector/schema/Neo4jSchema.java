package io.github.unlocker.archrag.graphprojector.schema;

import io.github.unlocker.archrag.canonicalmodel.node.NodeLabel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;

/**
 * Neo4j Community schema: constraints and indexes, all applied idempotently with {@code IF NOT EXISTS}.
 *
 * <p>Invariants:
 * <ul>
 *   <li>one {@code <label>_gid} UNIQUE constraint per {@link NodeLabel#canonical()} label, generated from the enum,
 *       so a new label cannot be left without a constraint;</li>
 *   <li>only Community features are used: no NODE KEY, no existence constraints, no vector index.</li>
 * </ul>
 *
 * <p>Community does not check that key properties exist: a node without {@code sourceId} is not covered by the
 * UNIQUE constraint. That check is done by the application ({@code SourceKey} in canonical-model).
 *
 * <p>{@code IF NOT EXISTS} silently skips a constraint that already exists under the same name even if its
 * definition differs. For the PoC this is accepted: when a definition changes, its name must change too.
 */
public final class Neo4jSchema {

  private static final Pattern CAMEL_BOUNDARY = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");

  private Neo4jSchema() {
  }

  /** Constraint name for the {@code gid} uniqueness of a label, e.g. {@code it_system_gid}. */
  static String gidConstraintName(NodeLabel label) {
    return CAMEL_BOUNDARY.matcher(label.label()).replaceAll("_").toLowerCase(Locale.ROOT) + "_gid";
  }

  /** All schema statements, each with {@code IF NOT EXISTS}. The order is stable. */
  public static List<String> statements() {
    List<String> statements = new ArrayList<>();
    NodeLabel.canonical().stream()
      .sorted(Comparator.comparingInt(NodeLabel::ordinal))
      .forEach(label -> statements.add("CREATE CONSTRAINT " + gidConstraintName(label)
        + " IF NOT EXISTS FOR (n:" + label.label() + ") REQUIRE n.gid IS UNIQUE"));
    statements.add("CREATE CONSTRAINT source_record_key IF NOT EXISTS FOR (n:SourceRecord) "
      + "REQUIRE (n.source, n.sourceType, n.sourceId) IS UNIQUE");
    statements.add("CREATE CONSTRAINT source_system_code IF NOT EXISTS FOR (n:SourceSystem) "
      + "REQUIRE n.code IS UNIQUE");
    statements.add("CREATE CONSTRAINT sync_run_id IF NOT EXISTS FOR (n:SyncRun) REQUIRE n.runId IS UNIQUE");
    statements.add("CREATE CONSTRAINT pending_relation_key IF NOT EXISTS FOR (n:PendingRelation) "
      + "REQUIRE n.key IS UNIQUE");
    statements.add("CREATE CONSTRAINT environment_code IF NOT EXISTS FOR (n:Environment) "
      + "REQUIRE n.code IS UNIQUE");
    statements.add("CREATE FULLTEXT INDEX asset_text IF NOT EXISTS FOR (n:ITSystem|Service) "
      + "ON EACH [n.name, n.description]");
    return List.copyOf(statements);
  }

  /**
   * Applies {@link #statements()}, each in its own write transaction (schema operations must not be mixed with
   * data writes). Errors are not swallowed.
   */
  public static void apply(Driver driver) {
    for (String statement : statements()) {
      try (Session session = driver.session()) {
        session.executeWrite(tx -> {
          tx.run(statement).consume();
          return null;
        });
      }
    }
  }
}
