package io.github.unlocker.archrag.canonicalmodel.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class GitUrlsTest {

  private static final String CANONICAL = "https://git.example.org/pay/payments-api";

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://git.example.org/pay/payments-api",
        "https://git.example.org/pay/payments-api.git",
        "https://git.example.org/pay/payments-api/",
        "https://git.example.org/pay/payments-api.git/",
        "HTTPS://GIT.Example.ORG/pay/payments-api",
        "https://user:secret@git.example.org/pay/payments-api.git",
        "https://token@git.example.org/pay/payments-api",
        "git@git.example.org:pay/payments-api.git",
        "git@git.example.org:pay/payments-api",
        "git@GIT.EXAMPLE.ORG:pay/payments-api.git",
        "ssh://git@git.example.org/pay/payments-api.git",
        "ssh://git@git.example.org:2222/pay/payments-api.git",
        "git://git.example.org/pay/payments-api.git",
        "  https://git.example.org/pay/payments-api  "
      })
  void normalizesToCanonicalHttps(String raw) {
    assertThat(GitUrls.normalize(raw)).isEqualTo(CANONICAL);
  }

  @Test
  void httpStaysHttp() {
    assertThat(GitUrls.normalize("http://Git.Example.org/pay/payments-api.git"))
        .isEqualTo("http://git.example.org/pay/payments-api");
  }

  @Test
  void pathKeepsCase() {
    assertThat(GitUrls.normalize("git@git.example.org:Pay/Payments-API.git"))
        .isEqualTo("https://git.example.org/Pay/Payments-API");
  }

  @Test
  void httpPortIsKeptAndSshPortDropped() {
    assertThat(GitUrls.normalize("https://git.example.org:8443/pay/api.git"))
        .isEqualTo("https://git.example.org:8443/pay/api");
  }

  @Test
  void nestedGroupsArePreserved() {
    assertThat(GitUrls.normalize("git@git.example.org:grp/sub/api.git"))
        .isEqualTo("https://git.example.org/grp/sub/api");
  }

  @Test
  void credentialsNeverAppearInResultOrError() {
    assertThat(GitUrls.normalize("https://u:pw123@git.example.org/a/b.git")).doesNotContain("pw123");
    assertThatThrownBy(() -> GitUrls.normalize("ftp://u:pw123@git.example.org/a/b"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("pw123");
  }

  @Test
  void syntaxErrorDoesNotLeakInputThroughMessageOrCause() {
    assertThatThrownBy(() -> GitUrls.normalize("https://u:pw123@git.example.org/a b"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("pw123")
        .hasNoCause();
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "   ",
        "garbage",
        "not a url",
        "ftp://git.example.org/a/b",
        "file:///srv/repo.git",
        "https://git.example.org",
        "https://git.example.org/",
        "https://git.example.org/.git",
        "git@git.example.org:",
        "https:///pay/api"
      })
  void rejectsBlankOrUnrecognized(String raw) {
    assertThatThrownBy(() -> GitUrls.normalize(raw)).isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @CsvSource({
    "git@git.example.org:pay/payments-api.git,https://git.example.org/pay/payments-api",
    "https://git.example.org/pay/payments-api,https://git.example.org/pay/payments-api"
  })
  void isIdempotent(String raw, String expected) {
    String once = GitUrls.normalize(raw);
    assertThat(once).isEqualTo(expected);
    assertThat(GitUrls.normalize(once)).isEqualTo(once);
  }
}
