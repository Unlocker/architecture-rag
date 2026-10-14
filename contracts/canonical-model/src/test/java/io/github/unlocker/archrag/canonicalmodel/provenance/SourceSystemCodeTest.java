package io.github.unlocker.archrag.canonicalmodel.provenance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class SourceSystemCodeTest {

    @Test
    void urnRoundTripsForEveryValue() {
        for (SourceSystemCode code : SourceSystemCode.values()) {
            assertThat(SourceSystemCode.fromUrn(code.urn())).contains(code);
        }
    }

    @Test
    void codeIsLowerCaseNameAndUrnHasCorpPrefix() {
        assertThat(SourceSystemCode.DEPLOYMAP.code()).isEqualTo("deploymap");
        assertThat(SourceSystemCode.EAM.urn()).isEqualTo("urn:corp:eam");
    }

    @Test
    void manualIsParsedLikeAnyOtherValue() {
        assertThat(SourceSystemCode.fromUrn("urn:corp:manual")).contains(SourceSystemCode.MANUAL);
    }

    @Test
    void unknownCodeAndForeignPrefixAreEmpty() {
        assertThat(SourceSystemCode.fromUrn("urn:corp:unknown")).isEqualTo(Optional.empty());
        assertThat(SourceSystemCode.fromUrn("urn:other:eam")).isEmpty();
        assertThat(SourceSystemCode.fromUrn("eam")).isEmpty();
        assertThat(SourceSystemCode.fromUrn("urn:corp:EAM")).isEmpty();
        assertThat(SourceSystemCode.fromUrn("")).isEmpty();
        assertThat(SourceSystemCode.fromUrn(null)).isEmpty();
    }
}
