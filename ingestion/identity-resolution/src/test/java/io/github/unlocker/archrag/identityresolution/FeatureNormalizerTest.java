package io.github.unlocker.archrag.identityresolution;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class FeatureNormalizerTest {

  @Test
  void hostnameIsTrimmedLowerCasedWithoutTrailingDot() {
    assertThat(FeatureNormalizer.hostname("  Host-01.Corp.Local. ")).contains("host-01.corp.local");
  }

  @Test
  void hostnameIsNotTruncatedToShortName() {
    assertThat(FeatureNormalizer.hostname("app-01.corp")).isNotEqualTo(FeatureNormalizer.hostname("app-01"));
  }

  @Test
  void repositoryUrlFormsConvergeToOneValue() {
    for (String raw :
        List.of(
            "https://GitHub.com/Org/Repo.git/",
            "git@github.com:org/repo.git",
            "ssh://git@github.com/org/repo",
            "http://github.com/Org/Repo",
            "git://GITHUB.com/org/repo.git")) {
      assertThat(FeatureNormalizer.repositoryUrl(raw)).as(raw).contains("github.com/org/repo");
    }
  }

  @Test
  void repositoryPathIsLowerCased() {
    assertThat(FeatureNormalizer.repositoryUrl("https://host/Org/Repo"))
        .isEqualTo(FeatureNormalizer.repositoryUrl("https://host/org/repo"));
  }

  @Test
  void nameVariantsConverge() {
    for (String raw : List.of("Billing  Core", "billing-core", "BILLING_CORE", " billing.core ")) {
      assertThat(FeatureNormalizer.name(raw)).as(raw).contains("billing core");
    }
  }

  @Test
  void nameAppliesNfkc() {
    assertThat(FeatureNormalizer.name("ＢＩＬＬＩＮＧ")).contains("billing");
  }

  @Test
  void blankInputGivesNoFeature() {
    assertThat(FeatureNormalizer.hostname("   ")).isEmpty();
    assertThat(FeatureNormalizer.hostname(".")).isEmpty();
    assertThat(FeatureNormalizer.repositoryUrl("  ")).isEmpty();
    assertThat(FeatureNormalizer.name("")).isEmpty();
    assertThat(FeatureNormalizer.name(" -_- ")).isEmpty();
  }
}
