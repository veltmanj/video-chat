package nl.nextend.videobackoffice.social.media;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.UploadObjectArgs;
import nl.nextend.videobackoffice.config.BackofficeSocialProperties;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;

/**
 * MinIO-backed {@link SocialMediaStorage} implementation.
 *
 * <p>The bucket is initialized lazily on first use so environments that have media disabled do not
 * pay the startup cost or fail early because the object store is unavailable.
 */
public class MinioSocialMediaStorage implements SocialMediaStorage {

    private final MinioClient minioClient;
    private final String bucket;
    private final AtomicBoolean bucketReady = new AtomicBoolean(false);

    public MinioSocialMediaStorage(MinioClient minioClient, BackofficeSocialProperties properties) {
        this.minioClient = minioClient;
        this.bucket = properties.getMedia().getBucket();
    }

    @Override
    public void putObject(String objectKey, Path file, String contentType) {
        ensureBucketExists();
        try {
            minioClient.uploadObject(
                UploadObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .filename(file.toString())
                    .contentType(contentType)
                    .build()
            );
        } catch (Exception exception) {
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "Unable to store media.", exception);
        }
    }

    @Override
    public byte[] getObject(String objectKey) {
        ensureBucketExists();
        try (InputStream inputStream = minioClient.getObject(
            GetObjectArgs.builder()
                .bucket(bucket)
                .object(objectKey)
                .build()
        )) {
            return inputStream.readAllBytes();
        } catch (Exception exception) {
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "Unable to read media.", exception);
        }
    }

    private void ensureBucketExists() {
        if (bucketReady.get()) {
            return;
        }

        synchronized (bucketReady) {
            if (bucketReady.get()) {
                return;
            }

            try {
                boolean exists = minioClient.bucketExists(
                    BucketExistsArgs.builder()
                        .bucket(bucket)
                        .build()
                );
                if (!exists) {
                    minioClient.makeBucket(
                        MakeBucketArgs.builder()
                            .bucket(bucket)
                            .build()
                    );
                }
                bucketReady.set(true);
            } catch (Exception exception) {
                throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "Unable to initialize media storage.", exception);
            }
        }
    }
}
