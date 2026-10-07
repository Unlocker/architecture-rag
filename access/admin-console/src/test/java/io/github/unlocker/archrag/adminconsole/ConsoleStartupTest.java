package io.github.unlocker.archrag.adminconsole;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.unlocker.archrag.adminconsole.config.ConsoleProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** Обязательный audience: без {@code ARCHRAG_CONSOLE_RESOURCE_URI} сервис не стартует. */
class ConsoleStartupTest {

  @Test
  void blankResourceUriFailsStartup() {
    assertThatThrownBy(
            () ->
                new SpringApplicationBuilder(AdminConsoleApplication.class)
                    .web(WebApplicationType.NONE)
                    // Аргументы командной строки, а не properties(): последние имеют низший приоритет и
                    // не перекрывают плейсхолдеры application.yml.
                    .run(
                        "--archrag.console.resource-uri=",
                        "--spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
                        "--spring.security.oauth2.resourceserver.jwt.issuer-uri=" + TestJwt.ISSUER,
                        "--archrag.neo4j.reader.uri=bolt://localhost:7687",
                        "--archrag.neo4j.reader.username=u",
                        "--archrag.neo4j.reader.password=p"))
        .hasStackTraceContaining("archrag.console.resource-uri");
  }

  @Test
  void propertiesRecordRejectsMissingResourceUri() {
    assertThatThrownBy(() -> new ConsoleProperties(null, "c", "s")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ConsoleProperties("  ", "c", "s")).isInstanceOf(IllegalArgumentException.class);
    assertThat(new ConsoleProperties("urn:x", "c", "s").resourceUri()).isEqualTo("urn:x");
  }

  @Test
  void configurationHasReadOnlyCredentialsOnly() throws Exception {
    String yml;
    try (var in = AdminConsoleApplication.class.getResourceAsStream("/application.yml")) {
      yml = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }

    assertThat(yml).contains("reader:").contains("ARCHRAG_NEO4J_READER_PASSWORD");
    // PostgreSQL: только роль archrag_console_ro (по умолчанию) и пароль из env; ни писателя, ни Flyway.
    assertThat(yml).contains("ARCHRAG_CONSOLE_PG_PASSWORD").contains("archrag_console_ro");
    assertThat(yml.toLowerCase()).doesNotContain("writer").doesNotContain("flyway").doesNotContain("datasource");
  }
}
