package com.batchforge.storage;

import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.errors.MinioException;
import io.minio.Http;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import io.minio.GetObjectArgs;
import java.io.InputStream;
import io.minio.PutObjectArgs;
import java.io.ByteArrayInputStream;

@Service
public class MinioStorageService {

    private final MinioClient minioClient;
    private final StorageProperties properties;
    private volatile boolean bucketEnsured = false;

    public MinioStorageService(MinioClient minioClient, StorageProperties properties) {
        this.minioClient = minioClient;
        this.properties = properties;
    }

    /** Presigned PUT URL the client uses to upload directly to MinIO (bytes never touch this app). */
    public String presignedUploadUrl(String objectKey) {
        ensureBucketExists();
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Http.Method.PUT)
                    .bucket(properties.bucket())
                    .object(objectKey)
                    .expiry((int) properties.uploadUrlTtl().toSeconds(), TimeUnit.SECONDS)
                    .build());
        } catch (MinioException e) {
            throw new StorageException("Failed to presign upload URL for '" + objectKey + "'", e);
        }
    }

    /** Size in bytes if the object exists; empty if it does not. */
    public Optional<Long> objectSize(String objectKey) {
        ensureBucketExists();
        try {
            StatObjectResponse stat = minioClient.statObject(StatObjectArgs.builder()
                    .bucket(properties.bucket())
                    .object(objectKey)
                    .build());
            return Optional.of(stat.size());
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code())) {
                return Optional.empty();
            }
            throw new StorageException("Failed to stat object '" + objectKey + "'", e);
        } catch (MinioException e) {
            throw new StorageException("Failed to stat object '" + objectKey + "'", e);
        }
    }

    private void ensureBucketExists() {
        if (bucketEnsured) {
            return;
        }
        synchronized (this) {
            if (bucketEnsured) {
                return;
            }
            try {
                boolean exists = minioClient.bucketExists(
                        BucketExistsArgs.builder().bucket(properties.bucket()).build());
                if (!exists) {
                    minioClient.makeBucket(MakeBucketArgs.builder().bucket(properties.bucket()).build());
                }
                bucketEnsured = true;
            } catch (MinioException e) {
                throw new StorageException("Failed to ensure bucket '" + properties.bucket() + "' exists", e);
            }
        }
    }

    /** Streaming read of an object. The caller must close the returned stream. */
    public InputStream getObject(String objectKey) {
        try {
            return minioClient.getObject(GetObjectArgs.builder()
                    .bucket(properties.bucket())
                    .object(objectKey)
                    .build());
        } catch (MinioException e) {
            throw new StorageException("Failed to read object '" + objectKey + "'", e);
        }
    }

    public void putObject(String objectKey, byte[] content, String contentType) {
        ensureBucketExists();
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(properties.bucket())
                    .object(objectKey)
                    .stream(new ByteArrayInputStream(content), (long) content.length, -1L)
                    .contentType(contentType)
                    .build());
        } catch (MinioException e) {
            throw new StorageException("Failed to store object " + objectKey, e);
        }
    }

    public String presignedDownloadUrl(String objectKey) {
        ensureBucketExists();
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Http.Method.GET)
                    .bucket(properties.bucket())
                    .object(objectKey)
                    .expiry((int) properties.uploadUrlTtl().toSeconds(), TimeUnit.SECONDS)
                    .build());
        } catch (MinioException e) {
            throw new StorageException("Failed to presign download URL for '" + objectKey + "'", e);
        }
    }
}