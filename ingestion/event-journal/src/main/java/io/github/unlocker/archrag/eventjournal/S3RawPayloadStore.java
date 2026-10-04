package io.github.unlocker.archrag.eventjournal;

import io.github.unlocker.archrag.eventschemas.RawPayloadRef;
import io.github.unlocker.archrag.eventschemas.RawPayloadStore;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Raw payload в S3-совместимом хранилище. Ключ {@code raw/<source>/<sha256>}: запись идемпотентна,
 * чтение проверяет hash. Клиент создаётся с path-style и регионом-заглушкой {@code us-east-1}.
 */
public final class S3RawPayloadStore implements RawPayloadStore, AutoCloseable {

  private final S3Client s3;
  private final String bucket;

  /** Создаёт клиент по конфигурации; ключи берутся извне (env/secrets), в логи не попадают. */
  public static S3RawPayloadStore create(URI endpoint, String accessKey, String secretKey, String bucket) {
    S3Client client = S3Client.builder()
        .endpointOverride(endpoint)
        .region(Region.US_EAST_1)
        .forcePathStyle(true)
        // Целостность держит наш SHA-256 в ключе; дефолтные доп. checksum SDK не понимают не все S3-совместимые хранилища.
        .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
        .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
        .httpClient(UrlConnectionHttpClient.create())
        .build();
    return new S3RawPayloadStore(client, bucket);
  }

  /** Принимает готовый клиент (владение переходит к хранилищу: {@link #close()} закрывает его). */
  public S3RawPayloadStore(S3Client s3, String bucket) {
    this.s3 = s3;
    this.bucket = bucket;
  }

  /** Создаёт бакет, если его нет. Вызывается при старте приложения, не на горячем пути. */
  public void ensureBucket() {
    try {
      s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
    } catch (NoSuchBucketException e) {
      s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
    }
  }

  @Override
  public RawPayloadRef put(String source, byte[] content) {
    String hash = sha256(content);
    String key = "raw/" + source + "/" + hash;
    if (!exists(key)) {
      s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(), RequestBody.fromBytes(content));
    }
    return new RawPayloadRef(key, hash);
  }

  @Override
  public byte[] get(RawPayloadRef ref) {
    byte[] content = s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(ref.key()).build())
        .asByteArray();
    if (!sha256(content).equals(ref.contentHash())) {
      throw new IllegalStateException("raw payload hash mismatch for key " + ref.key());
    }
    return content;
  }

  /** Ключ содержит hash содержимого, поэтому существующий объект перезаписывать не нужно. */
  private boolean exists(String key) {
    try {
      s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
      return true;
    } catch (NoSuchKeyException e) {
      return false;
    }
  }

  /** SHA-256 в hex. */
  static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  @Override
  public void close() {
    s3.close();
  }
}
