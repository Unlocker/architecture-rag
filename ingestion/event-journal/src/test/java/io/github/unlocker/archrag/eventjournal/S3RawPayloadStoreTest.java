package io.github.unlocker.archrag.eventjournal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class S3RawPayloadStoreTest {

  @Test
  void sha256IsStableHex() {
    assertThat(S3RawPayloadStore.sha256("abc".getBytes(StandardCharsets.UTF_8)))
        .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  }
}
