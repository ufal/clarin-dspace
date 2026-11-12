/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.storage.bitstore;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CompletionException;

import org.apache.commons.lang3.StringUtils;
import org.dspace.services.ConfigurationService;
import org.dspace.storage.bitstore.service.S3DirectDownloadService;
import org.dspace.util.ContentDispositionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.HttpStatusCode;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/**
 * Implementation of the S3DirectDownloadService interface for generating presigned URLs for S3 downloads.
 * This implementation reuses the S3 client provided by the S3BitStoreService and derives the presigner
 * from the same configuration.
 *
 * @author Milan Majchrak (dspace at dataquest.sk)
 */
public class S3DirectDownloadServiceImpl implements S3DirectDownloadService {

    private static final Logger log = LoggerFactory.getLogger(S3DirectDownloadServiceImpl.class);

    @Autowired
    private ConfigurationService configurationService;

    @Autowired
    private S3BitStoreService s3BitStoreService;

    private S3AsyncClient s3Client;

    /**
     * Unlike the v1 SDK, presigning in v2 is done by a separate object rather than by the client itself.
     */
    private S3Presigner s3Presigner;

    private void init() {
        // Use the S3BitStoreService to get the S3 client - do not create a new one
        this.s3Client = s3BitStoreService.s3AsyncClient;

        if (this.s3Client == null) {
            try {
                s3BitStoreService.init();
                this.s3Client = s3BitStoreService.s3AsyncClient;
            } catch (IOException e) {
                throw new RuntimeException("Failed to initialize S3 client from S3BitStoreService", e);
            }

            if (this.s3Client == null) {
                throw new IllegalStateException("S3 client was not initialized after calling init() " +
                        "on S3BitStoreService.");
            }
        }

        if (this.s3Presigner == null) {
            this.s3Presigner = buildPresigner();
        }
    }

    /**
     * Build a presigner from the same credentials, region and endpoint the bitstore client uses, so that
     * the signed URL points at the same S3 provider the assets actually live on.
     */
    private S3Presigner buildPresigner() {
        S3Presigner.Builder builder = S3Presigner.builder();

        String accessKey = s3BitStoreService.getAwsAccessKey();
        String secretKey = s3BitStoreService.getAwsSecretKey();
        if (StringUtils.isNotBlank(accessKey) && StringUtils.isNotBlank(secretKey)) {
            builder.credentialsProvider(
                    StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
        }
        // otherwise fall back to the default provider chain (IAM role / environment), as S3BitStoreService does

        // Mirror S3BitStoreService exactly. With explicit credentials it falls back to us-east-1; without
        // them it leaves the region unset so the default provider chain resolves it. Hardcoding us-east-1
        // in the no-credentials case would sign against the wrong region on an IAM role outside us-east-1,
        // and the signature would be rejected.
        String regionName = s3BitStoreService.getAwsRegionName();
        if (StringUtils.isNotBlank(accessKey) && StringUtils.isNotBlank(secretKey)) {
            Region region = Region.US_EAST_1;
            if (StringUtils.isNotBlank(regionName)) {
                try {
                    region = Region.of(regionName);
                } catch (IllegalArgumentException e) {
                    log.warn("Invalid aws_region: {}", regionName);
                }
            }
            builder.region(region);
        } else if (StringUtils.isNotBlank(regionName)) {
            try {
                builder.region(Region.of(regionName));
            } catch (IllegalArgumentException e) {
                log.warn("Invalid aws_region: {}", regionName);
            }
        }

        String endpoint = s3BitStoreService.getEndpoint();
        if (StringUtils.isNotBlank(endpoint)) {
            builder.endpointOverride(URI.create(endpoint));
            builder.serviceConfiguration(S3Configuration.builder()
                    .pathStyleAccessEnabled(s3BitStoreService.getPathStyleAccessEnabled())
                    .build());
        }

        return builder.build();
    }

    /**
     * Whether the object is really there. A URL we cannot verify is never signed.
     *
     * The v1 SDK's `doesObjectExist` rethrew anything that was not a 404. There is no v2 equivalent, so
     * this stays fail-closed - but a 403, an expired credential or a timeout is a misconfiguration the
     * operator has to see, not a missing object, so it is logged at ERROR rather than DEBUG.
     */
    private boolean doesObjectExist(String bucket, String key) {
        try {
            s3Client.headObject(r -> r.bucket(bucket).key(key)).join();
            return true;
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof NoSuchKeyException
                    || (cause instanceof S3Exception
                        && ((S3Exception) cause).statusCode() == HttpStatusCode.NOT_FOUND)) {
                log.debug("headObject(bucket={}, key={}): object not found", bucket, key);
            } else {
                log.error("headObject(bucket={}, key={}) failed for a reason other than a missing object; "
                        + "refusing to sign a URL", bucket, key, cause);
            }
            return false;
        } catch (Exception e) {
            log.error("headObject(bucket={}, key={}) failed; refusing to sign a URL", bucket, key, e);
            return false;
        }
    }

    @Override
    public String generatePresignedUrl(String bucket, String key, int expirationSeconds, String desiredFilename) {
        return generatePresignedUrl(bucket, key, expirationSeconds, desiredFilename, null);
    }

    @Override
    public String generatePresignedUrl(String bucket, String key, int expirationSeconds, String desiredFilename,
                                       String contentDispositionOverride) {
        if (desiredFilename == null) {
            log.error("Cannot generate presigned URL – desired filename is null");
            throw new IllegalArgumentException("Desired filename cannot be null");
        }
        if (s3Client == null || s3Presigner == null) {
            init();
        }
        // Verify object exists before generating URL
        if (!doesObjectExist(bucket, key)) {
            log.error("Cannot generate presigned URL – object does not exist: bucket={}, key={}", bucket, key);
            throw new IllegalArgumentException("Requested S3 object does not exist");
        }

        // Add custom response header for filename - to download the file with the desired name.
        // The caller passes the disposition it would have served itself, so that redirecting to S3 does not
        // silently turn an inline preview into a download; falling back to `attachment` is the safe default.
        // S3 sends this header, not Tomcat, so control characters are replaced with spaces here.
        String contentDisposition = StringUtils.isNotBlank(contentDispositionOverride)
                ? contentDispositionOverride.replaceAll("[\\x00-\\x1F\\x7F]", " ")
                : ContentDispositionUtils.build(ContentDispositionUtils.ATTACHMENT, desiredFilename);

        try {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .responseContentDisposition(contentDisposition)
                    .build();

            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofSeconds(expirationSeconds))
                    .getObjectRequest(getObjectRequest)
                    .build();

            return s3Presigner.presignGetObject(presignRequest).url().toString();
        } catch (Exception e) {
            log.error("Failed to generate presigned URL for bucket: {}, key: {}", bucket, key, e);
            throw new RuntimeException("Failed to generate presigned URL", e);
        }
    }
}
