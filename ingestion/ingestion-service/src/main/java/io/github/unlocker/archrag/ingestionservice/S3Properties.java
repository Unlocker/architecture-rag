package io.github.unlocker.archrag.ingestionservice;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** S3-совместимое хранилище raw payload; ключи приходят из env/Docker secrets. */
@ConfigurationProperties("archrag.s3")
public record S3Properties(String endpoint, String accessKey, String secretKey, String bucket) {

  @Override
  public String toString() {
    return "S3Properties[endpoint=" + endpoint + ", bucket=" + bucket + "]";
  }
}
