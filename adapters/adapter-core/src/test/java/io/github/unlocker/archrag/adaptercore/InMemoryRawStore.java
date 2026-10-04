package io.github.unlocker.archrag.adaptercore;

import io.github.unlocker.archrag.eventschemas.RawPayloadRef;
import io.github.unlocker.archrag.eventschemas.RawPayloadStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Raw store в памяти с адресацией по SHA-256, как S3-реализация. */
final class InMemoryRawStore implements RawPayloadStore {

  final Map<String, byte[]> objects = new LinkedHashMap<>();

  @Override
  public RawPayloadRef put(String source, byte[] content) {
    try {
      String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
      String key = "raw/" + source + "/" + hash;
      objects.put(key, content);
      return new RawPayloadRef(key, hash);
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public byte[] get(RawPayloadRef ref) {
    return objects.get(ref.key());
  }

  String text(RawPayloadRef ref) {
    return new String(get(ref), StandardCharsets.UTF_8);
  }
}
