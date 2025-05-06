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
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.util.Date;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;
import org.dspace.AbstractUnitTest;
import org.dspace.services.ConfigurationService;
import org.dspace.storage.bitstore.service.S3DirectDownloadService;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Test class for S3DirectDownloadService.
 *
 * @author Milan Majchrak (dspace at dataquest.sk)
 */
public class S3DirectDownloadServiceTest extends AbstractUnitTest {

    private S3DirectDownloadService s3DirectDownloadService;

    @Mock
    private S3BitStoreService s3BitstoreService;
    @Mock
    private ConfigurationService configService;
    @Mock
    private AmazonS3 amazonS3;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        when(amazonS3.doesObjectExist(anyString(), anyString())).thenReturn(true);

        s3DirectDownloadService = new S3DirectDownloadServiceImpl();
        ReflectionTestUtils.setField(s3DirectDownloadService, "s3BitStoreService", s3BitstoreService);
        ReflectionTestUtils.setField(s3DirectDownloadService, "configurationService", configService);

        // Reflectively set the mock’s private/public field
        ReflectionTestUtils.setField(s3BitstoreService, "s3Service", amazonS3);
        ReflectionTestUtils.invokeMethod(s3DirectDownloadService, "init");
    }

    @Test
    public void generatePresignedUrl() throws Exception {
        // Mock the presigned URL generation
        URL fakeUrl = new URL("https://example.com/foo");
        when(amazonS3.generatePresignedUrl(any(GeneratePresignedUrlRequest.class)))
                .thenReturn(fakeUrl);

        // Rum the method to generate the presigned URL
        String url = s3DirectDownloadService.generatePresignedUrl("bucket", "key", 120, "myfile.txt");

        // Compare the generated URL with the mocked one
        assertEquals("https://example.com/foo", url);

        // Verify that the presigned URL was generated with the correct parameters
        GeneratePresignedUrlRequest req = captureRequest();
        assertEquals("bucket", req.getBucketName());
        assertEquals("key", req.getKey());
        assertTrue(req.getRequestParameters()
                .get("response-content-disposition")
                .contains("attachment; filename=\"myfile.txt\""));
        assertTrue(req.getExpiration().after(new Date()));
    }

    // Zero expiration → URL still generated with expiration == now (or slightly after)
    @Test
    public void zeroExpiration() throws Exception {
        URL fake = new URL("https://zero");
        when(amazonS3.generatePresignedUrl(any(GeneratePresignedUrlRequest.class))).thenReturn(fake);

        s3DirectDownloadService.generatePresignedUrl("b", "k", 0, "f");
        GeneratePresignedUrlRequest req = captureRequest();
        // Expiration should be >= now
        Date expiration = req.getExpiration();
        assertTrue("Expiration should not be in the past by more than 1 second",
                expiration.getTime() >= new Date().getTime() - 1000);
    }

    // Negative expiration → expiration in the past
    @Test
    public void negativeExpiration() throws Exception {
        URL fake = new URL("https://neg");
        when(amazonS3.generatePresignedUrl(any(GeneratePresignedUrlRequest.class))).thenReturn(fake);

        s3DirectDownloadService.generatePresignedUrl("b", "k", -30, "f");
        GeneratePresignedUrlRequest req = captureRequest();
        // Expiration < now + a small slack (1s)
        assertTrue(req.getExpiration().before(new Date(System.currentTimeMillis() + 1000)));
    }

    // DesiredFilename with control chars / path traversal
    @Test
    public void weirdFilename() throws Exception {
        URL fake = new URL("https://weird");
        when(amazonS3.generatePresignedUrl(any(GeneratePresignedUrlRequest.class))).thenReturn(fake);

        String weird = "../secret\nname\t.txt";
        s3DirectDownloadService.generatePresignedUrl("b", "k", 60, weird);
        GeneratePresignedUrlRequest req = captureRequest();

        String cd = req.getRequestParameters().get("response-content-disposition");

        // Should start with attachment and include both filename and filename*
        assertTrue(cd.startsWith("attachment; filename=\""));
        assertTrue(cd.contains("filename="));
        assertTrue(cd.contains("filename*="));

        // Make sure the filename is sanitized, sanitized are only `\\r\\n\"`
        String fallbackName = cd.split("filename=\"")[1].split("\"")[0];
        assertTrue(fallbackName.contains("../"));
        assertTrue(fallbackName.contains("\t"));
        assertFalse(fallbackName.contains("\n"));
        assertFalse(fallbackName.contains("\""));

        // It's valid and desirable to include UTF-8
        assertTrue(cd.contains("UTF-8"));
    }

    // Underlying AmazonS3 throws → IllegalArgumentException
    @Test(expected = IllegalArgumentException.class)
    public void nullFilename() throws Exception {
        s3DirectDownloadService.generatePresignedUrl("b", "k", 60, null);
    }

    // Underlying AmazonS3 throws → bubbles up
    @Test(expected = RuntimeException.class)
    public void amazonThrows() throws UnsupportedEncodingException {
        when(amazonS3.generatePresignedUrl(any())).thenThrow(new RuntimeException("boom"));
        s3DirectDownloadService.generatePresignedUrl("b", "k", 1, "f");
    }

    // Bucket key == null → should IllegalArgumentException
    @Test(expected = IllegalArgumentException.class)
    public void nullBucket() throws UnsupportedEncodingException {
        s3DirectDownloadService.generatePresignedUrl(null, "k", 60, "f");
    }

    // Bucket key == null → should NPE
    @Test(expected = IllegalArgumentException.class)
    public void nullKey() throws UnsupportedEncodingException {
        s3DirectDownloadService.generatePresignedUrl("b", null, 60, "f");
    }

    // helper to pull out the single captured request
    private GeneratePresignedUrlRequest captureRequest() {
        ArgumentCaptor<GeneratePresignedUrlRequest> cap =
                ArgumentCaptor.forClass(GeneratePresignedUrlRequest.class);
        verify(amazonS3, atLeastOnce()).generatePresignedUrl(cap.capture());
        return cap.getValue();
    }
}