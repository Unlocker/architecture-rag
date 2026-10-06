package io.github.unlocker.archrag.integrationtests;

import org.testcontainers.utility.DockerImageName;

/** Точные теги образов для IT: плавающий тег может сломать CI без изменений в коде. */
final class TestImages {

  /** Neo4j Community, ветка 5.26 LTS. */
  static final DockerImageName NEO4J = DockerImageName.parse("neo4j:5.26.31-community");

  private TestImages() {}
}
