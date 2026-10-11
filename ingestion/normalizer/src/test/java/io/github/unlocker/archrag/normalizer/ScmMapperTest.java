package io.github.unlocker.archrag.normalizer;

import static io.github.unlocker.archrag.normalizer.Fixtures.RAW;
import static io.github.unlocker.archrag.normalizer.Fixtures.event;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.unlocker.archrag.canonicalmodel.command.UpsertNode;
import io.github.unlocker.archrag.canonicalmodel.node.Repository;
import io.github.unlocker.archrag.normalizer.NormalizationResult.Normalized;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** SCM: {@code Repository.url} хранится в каноническом виде {@code GitUrls.normalize}. */
class ScmMapperTest {

  private final Normalizer normalizer = Normalizer.standard(key -> true);

  private Normalized repository(String url) {
    var result =
        normalizer.normalize(
            event("urn:corp:scm", "urn:corp:schema:asset-upserted:1", "REPOSITORY", "r", Map.of("url", url)), RAW);
    assertThat(result).isInstanceOf(Normalized.class);
    return (Normalized) result;
  }

  @Test
  void sshAndHttpsUrlsAreNormalized() {
    for (String raw :
        new String[] {
          "git@git.example.org:pay/payments-api.git",
          "ssh://git@Git.Example.org/pay/payments-api.git",
          "https://user:pw@GIT.example.org/pay/payments-api/",
        }) {
      var n = repository(raw);
      assertThat(((UpsertNode) n.commands().get(0)).data())
          .isEqualTo(new Repository("https://git.example.org/pay/payments-api", null, null));
      assertThat(n.warnings()).isEmpty();
    }
  }

  @Test
  void unrecognizedUrlIsQuarantinedWithoutLeakingValue() {
    var result =
        normalizer.normalize(
            event("urn:corp:scm", "urn:corp:schema:asset-upserted:1", "REPOSITORY", "r", Map.of("url", "not a git url s3cr3t")),
            RAW);
    assertThat(result)
        .isInstanceOfSatisfying(
            NormalizationResult.Quarantined.class,
            q -> {
              assertThat(q.errorCode()).isEqualTo("INVALID_PAYLOAD");
              assertThat(q.reason()).doesNotContain("s3cr3t");
            });
  }

  @Test
  void seedRepositoryUrlIsAlreadyCanonical() {
    var n = repository("https://git.example.org/pay/payments-api");
    assertThat(((UpsertNode) n.commands().get(0)).data())
        .isEqualTo(new Repository("https://git.example.org/pay/payments-api", null, null));
  }
}
