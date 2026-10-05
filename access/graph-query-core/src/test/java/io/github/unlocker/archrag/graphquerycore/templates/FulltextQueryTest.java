package io.github.unlocker.archrag.graphquerycore.templates;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FulltextQueryTest {

  @Test
  void escapesLuceneOperatorsAndLowercases() {
    assertThat(FulltextQuery.escape("Foo AND (bar")).isEqualTo("foo and \\(bar");
    assertThat(FulltextQuery.escape("a:b")).isEqualTo("a\\:b");
    assertThat(FulltextQuery.escape("\"x\"")).isEqualTo("\\\"x\\\"");
    assertThat(FulltextQuery.escape("a\\b")).isEqualTo("a\\\\b");
    assertThat(FulltextQuery.escape("a&&b||c!d~e*f?g/h")).isEqualTo("a\\&\\&b\\|\\|c\\!d\\~e\\*f\\?g\\/h");
  }

  @Test
  void returnsNullWhenNothingSearchableRemains() {
    assertThat(FulltextQuery.escape(null)).isNull();
    assertThat(FulltextQuery.escape("   ")).isNull();
    assertThat(FulltextQuery.escape("()*~")).isNull();
  }

  @Test
  void keepsPlainTerms() {
    assertThat(FulltextQuery.escape("payment service")).isEqualTo("payment service");
  }
}
