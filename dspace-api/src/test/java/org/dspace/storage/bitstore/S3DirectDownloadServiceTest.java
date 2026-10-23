/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.storage.bitstore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.dspace.AbstractDSpaceTest;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import software.amazon.awssdk.core.SdkSystemSetting;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/**
 * Tests that {@link S3DirectDownloadServiceImpl} signs a working GET URL; presigning is local, so no S3 runs.
 *
 * @author Milan Majchrak (dspace at dataquest.sk)
 */
public class S3DirectDownloadServiceTest extends AbstractDSpaceTest {

    private static final String ENDPOINT = "http://s3.example.test:9000";
    private static final String BUCKET = "dspace-assetstore";
    private static final String KEY = "12/34/56/123456789";
    private static final int EXPIRATION_SECONDS = 120;
    private static final int SEVEN_DAYS = 7 * 24 * 60 * 60;
    private static final String CHAIN_REGION = "eu-west-1";
    private static final String ASSETSTORE_DEFAULT_REGION = "us-east-1";

    @Mock
    private S3BitStoreService s3BitStoreService;

    private S3DirectDownloadServiceImpl s3DirectDownloadService;

    @Before
    public void setUp() {
        when(s3BitStoreService.getAwsAccessKey()).thenReturn("test-access-key");
        when(s3BitStoreService.getAwsSecretKey()).thenReturn("test-secret-key");
        when(s3BitStoreService.getAwsRegionName()).thenReturn("eu-central-1");
        when(s3BitStoreService.getEndpoint()).thenReturn(ENDPOINT);

        s3DirectDownloadService = new S3DirectDownloadServiceImpl();
        s3DirectDownloadService.setS3BitStoreService(s3BitStoreService);
    }

    @Test
    public void generatePresignedUrl() {
        URI url = URI.create(
                s3DirectDownloadService.generatePresignedUrl(BUCKET, KEY, EXPIRATION_SECONDS, "myfile.txt"));

        assertEquals("s3.example.test", url.getHost());
        assertEquals(9000, url.getPort());
        assertEquals("/" + BUCKET + "/" + KEY, url.getPath());

        Map<String, String> query = queryOf(url);
        assertEquals("AWS4-HMAC-SHA256", query.get("X-Amz-Algorithm"));
        assertEquals(String.valueOf(EXPIRATION_SECONDS), query.get("X-Amz-Expires"));
        assertTrue(query.get("X-Amz-Credential").contains("eu-central-1"));
        assertFalse(query.get("X-Amz-Signature").isEmpty());
        assertEquals("attachment; filename=\"myfile.txt\"; filename*=UTF-8''myfile.txt",
                query.get("response-content-disposition"));
    }

    @Test
    public void presignedUrlUsesResolvedRegion() {
        when(s3BitStoreService.getAwsAccessKey()).thenReturn("");
        when(s3BitStoreService.getAwsSecretKey()).thenReturn("");
        when(s3BitStoreService.getAwsRegionName()).thenReturn("");

        Map<String, String> restore = setProperties(
                SdkSystemSetting.AWS_REGION.property(), CHAIN_REGION,
                SdkSystemSetting.AWS_ACCESS_KEY_ID.property(), "test-access-key",
                SdkSystemSetting.AWS_SECRET_ACCESS_KEY.property(), "test-secret-key");
        try {
            URI url = URI.create(
                    s3DirectDownloadService.generatePresignedUrl(BUCKET, KEY, EXPIRATION_SECONDS, "myfile.txt"));

            assertEquals(CHAIN_REGION, regionOf(url));
        } finally {
            restoreProperties(restore);
        }
    }

    @Test
    public void staticCredentialsKeepTheAssetstoreDefaultRegion() {
        when(s3BitStoreService.getAwsRegionName()).thenReturn("");

        Map<String, String> restore = setProperties(SdkSystemSetting.AWS_REGION.property(), CHAIN_REGION);
        try {
            URI url = URI.create(
                    s3DirectDownloadService.generatePresignedUrl(BUCKET, KEY, EXPIRATION_SECONDS, "myfile.txt"));

            assertEquals(ASSETSTORE_DEFAULT_REGION, regionOf(url));
        } finally {
            restoreProperties(restore);
        }
    }

    @Test
    public void expirationIsTheSignatureDuration() {
        URI url = URI.create(s3DirectDownloadService.generatePresignedUrl(BUCKET, KEY, 3600, "myfile.txt"));

        assertEquals("3600", queryOf(url).get("X-Amz-Expires"));
    }

    @Test
    public void nonAsciiFilenameKeepsBothDispositionForms() {
        URI url = URI.create(
                s3DirectDownloadService.generatePresignedUrl(BUCKET, KEY, EXPIRATION_SECONDS, "rates €.txt"));

        assertEquals("attachment; filename=\"rates €.txt\"; filename*=UTF-8''rates%20%E2%82%AC.txt",
                queryOf(url).get("response-content-disposition"));
    }

    @Test
    public void filenameCannotCloseTheDispositionHeader() {
        URI url = URI.create(
                s3DirectDownloadService.generatePresignedUrl(BUCKET, KEY, EXPIRATION_SECONDS,
                        "../secret\nname\".txt"));

        String disposition = queryOf(url).get("response-content-disposition");
        String fallbackName = disposition.split("filename=\"")[1].split("\"")[0];
        assertFalse(fallbackName.contains("\n"));
        assertFalse(fallbackName.contains("\""));
        assertTrue(fallbackName.contains("../"));
        assertTrue(disposition.contains("filename*=UTF-8''"));
    }

    @Test
    public void signsUpToSevenDaysAndNoFurther() {
        URI url = URI.create(s3DirectDownloadService.generatePresignedUrl(BUCKET, KEY, SEVEN_DAYS, "myfile.txt"));
        assertEquals(String.valueOf(SEVEN_DAYS), queryOf(url).get("X-Amz-Expires"));

        assertThrows(IllegalArgumentException.class,
                () -> s3DirectDownloadService.generatePresignedUrl(BUCKET, KEY, SEVEN_DAYS + 1, "myfile.txt"));
        assertThrows(IllegalArgumentException.class,
                () -> s3DirectDownloadService.generatePresignedUrl(BUCKET, KEY, 0, "myfile.txt"));
        assertThrows(IllegalArgumentException.class,
                () -> s3DirectDownloadService.generatePresignedUrl(BUCKET, KEY, -30, "myfile.txt"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void nullFilename() {
        s3DirectDownloadService.generatePresignedUrl(BUCKET, KEY, EXPIRATION_SECONDS, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void nullBucket() {
        s3DirectDownloadService.generatePresignedUrl(null, KEY, EXPIRATION_SECONDS, "myfile.txt");
    }

    @Test(expected = IllegalArgumentException.class)
    public void nullKey() {
        s3DirectDownloadService.generatePresignedUrl(BUCKET, null, EXPIRATION_SECONDS, "myfile.txt");
    }

    @Test(expected = RuntimeException.class)
    public void presignerFailureBubblesUp() {
        S3Presigner failing = org.mockito.Mockito.mock(S3Presigner.class);
        when(failing.presignGetObject(any(GetObjectPresignRequest.class))).thenThrow(new RuntimeException("boom"));
        s3DirectDownloadService.setS3Presigner(failing);

        s3DirectDownloadService.generatePresignedUrl(BUCKET, KEY, EXPIRATION_SECONDS, "myfile.txt");
    }

    /** Signing region of the credential scope: {@code <key>/<date>/<region>/s3/aws4_request}. */
    private String regionOf(URI url) {
        return queryOf(url).get("X-Amz-Credential").split("/")[2];
    }

    private Map<String, String> setProperties(String... keysAndValues) {
        Map<String, String> previous = new HashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            previous.put(keysAndValues[i], System.getProperty(keysAndValues[i]));
            System.setProperty(keysAndValues[i], keysAndValues[i + 1]);
        }
        return previous;
    }

    private void restoreProperties(Map<String, String> previous) {
        previous.forEach((key, value) -> {
            if (value == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, value);
            }
        });
    }

    private Map<String, String> queryOf(URI url) {
        Map<String, String> params = new HashMap<>();
        for (String pair : url.getRawQuery().split("&")) {
            int separator = pair.indexOf('=');
            params.put(URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8));
        }
        return params;
    }
}
