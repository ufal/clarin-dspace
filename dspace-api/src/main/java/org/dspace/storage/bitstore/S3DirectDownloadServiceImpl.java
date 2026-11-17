/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.storage.bitstore;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.dspace.storage.bitstore.service.S3DirectDownloadService;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/**
 * Generates presigned S3 URLs so a bitstream is downloaded straight from the object store.
 *
 * @author Milan Majchrak (dspace at dataquest.sk)
 */
public class S3DirectDownloadServiceImpl implements S3DirectDownloadService {

    private static final Logger log = LogManager.getLogger(S3DirectDownloadServiceImpl.class);

    /** SigV4 signs only between 1 second and 7 days. */
    private static final Duration MIN_SIGNATURE_DURATION = Duration.ofSeconds(1);
    private static final Duration MAX_SIGNATURE_DURATION = Duration.ofDays(7);

    @Autowired
    private S3BitStoreService s3BitStoreService;

    private volatile S3Presigner s3Presigner;

    @Override
    public String generatePresignedUrl(String bucket, String key, int expirationSeconds, String bitstreamName) {
        if (StringUtils.isBlank(bucket)) {
            throw new IllegalArgumentException("Cannot presign an S3 URL without a bucket name");
        }
        if (StringUtils.isBlank(key)) {
            throw new IllegalArgumentException("Cannot presign an S3 URL without an object key");
        }
        if (bitstreamName == null) {
            throw new IllegalArgumentException("Cannot presign an S3 URL without a bitstream name");
        }

        Duration signatureDuration = Duration.ofSeconds(expirationSeconds);
        if (signatureDuration.compareTo(MIN_SIGNATURE_DURATION) < 0
                || signatureDuration.compareTo(MAX_SIGNATURE_DURATION) > 0) {
            throw new IllegalArgumentException("s3.download.direct.expiration is " + expirationSeconds
                    + " seconds; a presigned URL is valid for at least 1 second and at most 7 days");
        }

        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .responseContentDisposition(contentDisposition(bitstreamName))
                .build();
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(signatureDuration)
                .getObjectRequest(getObjectRequest)
                .build();

        try {
            return getS3Presigner().presignGetObject(presignRequest).url().toString();
        } catch (Exception e) {
            log.error("Failed to generate presigned URL for bucket: {}, key: {}", bucket, key, e);
            throw new IllegalStateException("Failed to generate presigned URL", e);
        }
    }

    /** Builds Content-Disposition: the quoted fallback drops CR, LF and quotes, {@code filename*} is RFC 5987. */
    private String contentDisposition(String bitstreamName) {
        String fallbackName = bitstreamName.replaceAll("[\r\n\"]", "_");
        String encodedName = URLEncoder.encode(bitstreamName, StandardCharsets.UTF_8).replace("+", "%20");
        return String.format("attachment; filename=\"%s\"; filename*=UTF-8''%s", fallbackName, encodedName);
    }

    private S3Presigner getS3Presigner() {
        if (s3Presigner == null) {
            synchronized (this) {
                if (s3Presigner == null) {
                    s3Presigner = buildS3Presigner();
                }
            }
        }
        return s3Presigner;
    }

    /** The assetstore's S3AsyncClient cannot presign, so build a second client against the same S3. */
    private S3Presigner buildS3Presigner() {
        String accessKey = s3BitStoreService.getAwsAccessKey();
        String secretKey = s3BitStoreService.getAwsSecretKey();
        boolean staticCredentials = StringUtils.isNotBlank(accessKey) && StringUtils.isNotBlank(secretKey);

        S3Presigner.Builder builder = S3Presigner.builder().region(resolveRegion(staticCredentials));
        if (staticCredentials) {
            builder.credentialsProvider(
                    StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }

        String endpoint = s3BitStoreService.getEndpoint();
        if (StringUtils.isNotBlank(endpoint)) {
            // Path style matches what S3BitStoreService forces on a custom endpoint, so both address the same URL
            builder.endpointOverride(URI.create(endpoint))
                   .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
        }
        return builder.build();
    }

    /** Resolves the region the assetstore client picks for the same kind of credentials. */
    private Region resolveRegion(boolean staticCredentials) {
        String regionName = s3BitStoreService.getAwsRegionName();
        if (StringUtils.isNotBlank(regionName)) {
            try {
                return Region.of(regionName);
            } catch (IllegalArgumentException e) {
                log.warn("Invalid aws_region: {}, resolving the region without it", regionName);
            }
        }
        if (!staticCredentials) {
            try {
                return new DefaultAwsRegionProviderChain().getRegion();
            } catch (SdkClientException e) {
                log.warn("No AWS region found by the SDK provider chain, presigning with us-east-1");
            }
        }
        return Region.US_EAST_1;
    }

    protected void setS3Presigner(S3Presigner s3Presigner) {
        this.s3Presigner = s3Presigner;
    }

    protected void setS3BitStoreService(S3BitStoreService s3BitStoreService) {
        this.s3BitStoreService = s3BitStoreService;
    }
}
