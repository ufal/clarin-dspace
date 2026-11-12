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
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.dspace.AbstractUnitTest;
import org.dspace.services.ConfigurationService;
import org.dspace.storage.bitstore.service.S3DirectDownloadService;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

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
    private S3AsyncClient s3AsyncClient;
    @Mock
    private S3Presigner s3Presigner;

    @Before
    @SuppressWarnings("unchecked")
    public void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);

        // Resolve the consumer for real, so a null bucket/key behaves as "object not found" like the v1
        // `doesObjectExist(bucket, key)` call this replaced.
        when(s3AsyncClient.headObject(any(Consumer.class))).thenAnswer(invocation -> {
            Consumer<HeadObjectRequest.Builder> consumer = invocation.getArgument(0);
            HeadObjectRequest.Builder builder = HeadObjectRequest.builder();
            consumer.accept(builder);
            HeadObjectRequest request = builder.build();
            if (request.bucket() == null || request.key() == null) {
                return CompletableFuture.failedFuture(NoSuchKeyException.builder().build());
            }
            return CompletableFuture.completedFuture(HeadObjectResponse.builder().build());
        });

        s3DirectDownloadService = new S3DirectDownloadServiceImpl();
        ReflectionTestUtils.setField(s3DirectDownloadService, "s3BitStoreService", s3BitstoreService);
        ReflectionTestUtils.setField(s3DirectDownloadService, "configurationService", configService);

        // Reflectively set the mock's protected field
        ReflectionTestUtils.setField(s3BitstoreService, "s3AsyncClient", s3AsyncClient);
        // Pre-seed the presigner so init() does not try to build a real one from empty credentials
        ReflectionTestUtils.setField(s3DirectDownloadService, "s3Presigner", s3Presigner);
        ReflectionTestUtils.invokeMethod(s3DirectDownloadService, "init");
    }

    /** Stub the presigner so that presignGetObject returns a request pointing at the given URL. */
    private void presignReturns(String url) throws Exception {
        PresignedGetObjectRequest presigned = mock(PresignedGetObjectRequest.class);
        when(presigned.url()).thenReturn(new URL(url));
        when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presigned);
    }

    @Test
    public void generatePresignedUrl() throws Exception {
        // Mock the presigned URL generation
        presignReturns("https://example.com/foo");

        // Run the method to generate the presigned URL
        String url = s3DirectDownloadService.generatePresignedUrl("bucket", "key", 120, "myfile.txt");

        // Compare the generated URL with the mocked one
        assertEquals("https://example.com/foo", url);

        // Verify that the presigned URL was generated with the correct parameters
        GetObjectPresignRequest req = captureRequest();
        assertEquals("bucket", req.getObjectRequest().bucket());
        assertEquals("key", req.getObjectRequest().key());
        assertTrue(req.getObjectRequest().responseContentDisposition()
                .contains("attachment; filename=\"myfile.txt\""));
        assertEquals(Duration.ofSeconds(120), req.signatureDuration());
    }

    // Zero expiration → URL still generated, with a zero-length signature window
    @Test
    public void zeroExpiration() throws Exception {
        presignReturns("https://zero");

        s3DirectDownloadService.generatePresignedUrl("b", "k", 0, "f");
        assertEquals(Duration.ZERO, captureRequest().signatureDuration());
    }

    // Negative expiration → signature window already elapsed
    @Test
    public void negativeExpiration() throws Exception {
        presignReturns("https://neg");

        s3DirectDownloadService.generatePresignedUrl("b", "k", -30, "f");
        assertTrue(captureRequest().signatureDuration().isNegative());
    }

    // DesiredFilename with control chars / path traversal
    @Test
    public void weirdFilename() throws Exception {
        presignReturns("https://weird");

        String weird = "../secret\nname\t.txt";
        s3DirectDownloadService.generatePresignedUrl("b", "k", 60, weird);
        GetObjectPresignRequest req = captureRequest();

        String cd = req.getObjectRequest().responseContentDisposition();

        // Should start with attachment and include both filename and filename*
        assertTrue(cd.startsWith("attachment; filename=\""));
        assertTrue(cd.contains("filename="));
        assertTrue(cd.contains("filename*="));

        // The ASCII fallback now drops every control character, not just CR/LF - the old sanitiser let a
        // TAB through and, worse, did not escape a backslash, so a name ending in `\` terminated the
        // quoted string early and swallowed the filename* parameter.
        String fallbackName = cd.split("filename=\"")[1].split("\"")[0];
        assertTrue(fallbackName, fallbackName.contains("../"));
        assertFalse(fallbackName, fallbackName.contains("\t"));
        assertFalse(fallbackName, fallbackName.contains("\n"));
        assertFalse(fallbackName, fallbackName.contains("\r"));

        // It's valid and desirable to include UTF-8
        assertTrue(cd.contains("UTF-8"));
    }

    // A trailing backslash used to escape the closing quote and swallow filename*
    @Test
    public void backslashInFilename() throws Exception {
        presignReturns("https://backslash");

        s3DirectDownloadService.generatePresignedUrl("b", "k", 60, "evil\\");
        String cd = captureRequest().getObjectRequest().responseContentDisposition();

        assertTrue(cd, cd.contains("filename=\"evil\\\\\""));
        assertTrue(cd, cd.contains("filename*=UTF-8''evil%5C"));
    }

    // The caller's disposition has to win, otherwise enabling direct downloads kills inline preview
    @Test
    public void callerSuppliedDispositionIsUsedVerbatim() throws Exception {
        presignReturns("https://inline");

        String supplied = "inline; filename=\"paper.pdf\"; filename*=UTF-8''paper.pdf";
        s3DirectDownloadService.generatePresignedUrl("b", "k", 60, "paper.pdf", supplied);

        assertEquals(supplied, captureRequest().getObjectRequest().responseContentDisposition());
    }

    // S3 sends the caller's header itself, so control characters must not reach it
    @Test
    public void controlCharsInSuppliedDispositionAreReplaced() throws Exception {
        presignReturns("https://control");

        s3DirectDownloadService.generatePresignedUrl("b", "k", 60, "a.pdf", "attachment; filename=\"a\nb\t.pdf\"");

        assertEquals("attachment; filename=\"a b .pdf\"",
                captureRequest().getObjectRequest().responseContentDisposition());
    }

    // ... and a blank override still falls back to attachment
    @Test
    public void blankDispositionFallsBackToAttachment() throws Exception {
        presignReturns("https://fallback");

        s3DirectDownloadService.generatePresignedUrl("b", "k", 60, "paper.pdf", null);

        assertTrue(captureRequest().getObjectRequest().responseContentDisposition()
                .startsWith("attachment; "));
    }

    // Null filename → IllegalArgumentException
    @Test(expected = IllegalArgumentException.class)
    public void nullFilename() throws Exception {
        s3DirectDownloadService.generatePresignedUrl("b", "k", 60, null);
    }

    // Underlying presigner throws → bubbles up
    @Test(expected = RuntimeException.class)
    public void presignerThrows() throws UnsupportedEncodingException {
        when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class)))
                .thenThrow(new RuntimeException("boom"));
        s3DirectDownloadService.generatePresignedUrl("b", "k", 1, "f");
    }

    // Bucket == null → should IllegalArgumentException
    @Test(expected = IllegalArgumentException.class)
    public void nullBucket() throws UnsupportedEncodingException {
        s3DirectDownloadService.generatePresignedUrl(null, "k", 60, "f");
    }

    // Key == null → should IllegalArgumentException
    @Test(expected = IllegalArgumentException.class)
    public void nullKey() throws UnsupportedEncodingException {
        s3DirectDownloadService.generatePresignedUrl("b", null, 60, "f");
    }

    // helper to pull out the single captured request
    private GetObjectPresignRequest captureRequest() {
        ArgumentCaptor<GetObjectPresignRequest> cap =
                ArgumentCaptor.forClass(GetObjectPresignRequest.class);
        verify(s3Presigner, atLeastOnce()).presignGetObject(cap.capture());
        return cap.getValue();
    }
}
