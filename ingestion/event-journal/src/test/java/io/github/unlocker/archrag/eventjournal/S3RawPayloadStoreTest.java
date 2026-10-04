package io.github.unlocker.archrag.eventjournal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class S3RawPayloadStoreTest {

  @Test
  void sha256IsStableHex() {
    assertThat(S3RawPayloadStore.sha256("abc".getBytes(StandardCharsets.UTF_8)))
        .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  }

  @Test
  void rejectsSourceThatCouldEscapeKeyPrefix() {
    var store = new S3RawPayloadStore(null, "bucket");
    for (String bad : new String[] {"x/../../y", "a/b", "..", "", "a b"}) {
      assertThatThrownBy(() -> store.put(bad, new byte[0])).as(bad).isInstanceOf(IllegalArgumentException.class);
    }
  }
}
