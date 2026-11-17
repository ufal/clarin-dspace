/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.InputStream;

import org.apache.commons.codec.CharEncoding;
import org.apache.commons.io.IOUtils;
import org.dspace.app.rest.test.AbstractControllerIntegrationTest;
import org.dspace.builder.BitstreamBuilder;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.ItemBuilder;
import org.dspace.content.Bitstream;
import org.dspace.content.Collection;
import org.dspace.content.Item;
import org.dspace.services.ConfigurationService;
import org.dspace.storage.bitstore.service.S3DirectDownloadService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;

/**
 * Integration tests for the 302-to-presigned-URL branch of the bitstream content endpoints.
 *
 * @author Milan Majchrak (dspace at dataquest.sk)
 */
public class BitstreamS3DirectDownloadIT extends AbstractControllerIntegrationTest {

    private static final String PRESIGNED_URL = "https://s3.example.test/dspace/12345?X-Amz-Signature=abc";
    private static final String BUCKET = "test-bucket";
    private static final String FILE_NAME = "testfile.txt";
    private static final String FILE_CONTENT = "TestBitstreamContent";
    private static final int EXPIRATION_SECONDS = 120;

    @Autowired
    private ConfigurationService configurationService;

    @MockBean(name = "s3DirectDownload")
    private S3DirectDownloadService s3DirectDownloadService;

    private Item item;

    @Before
    public void setUpConfigAndItem() throws Exception {
        configurationService.setProperty("assetstore.s3.enabled", true);
        configurationService.setProperty("s3.download.direct.enabled", true);
        configurationService.setProperty("s3.download.direct.expiration", EXPIRATION_SECONDS);
        configurationService.setProperty("assetstore.s3.bucketName", BUCKET);

        context.turnOffAuthorisationSystem();
        parentCommunity = CommunityBuilder.createCommunity(context).withName("Parent Community").build();
        Collection collection = CollectionBuilder.createCollection(context, parentCommunity)
                .withName("Collection").build();
        item = ItemBuilder.createItem(context, collection).withTitle("Public item").build();
        context.restoreAuthSystemState();
    }

    @After
    public void resetConfig() {
        configurationService.setProperty("assetstore.s3.enabled", false);
        configurationService.setProperty("s3.download.direct.enabled", false);
        configurationService.setProperty("s3.download.direct.expiration", null);
        configurationService.setProperty("assetstore.s3.bucketName", null);
    }

    @Test
    public void redirectsToPresignedUrl() throws Exception {
        when(s3DirectDownloadService.generatePresignedUrl(anyString(), anyString(), anyInt(), anyString()))
                .thenReturn(PRESIGNED_URL);
        Bitstream bitstream = originalBundleBitstream();

        getClient().perform(get("/api/core/bitstreams/" + bitstream.getID() + "/content"))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, PRESIGNED_URL));

        verify(s3DirectDownloadService)
                .generatePresignedUrl(eq(BUCKET), anyString(), eq(EXPIRATION_SECONDS), eq(FILE_NAME));
    }

    @Test
    public void redirectsToPresignedUrlByHandle() throws Exception {
        when(s3DirectDownloadService.generatePresignedUrl(anyString(), anyString(), anyInt(), anyString()))
                .thenReturn(PRESIGNED_URL);
        originalBundleBitstream();
        String[] handleParts = item.getHandle().split("/");

        getClient().perform(get("/api/core/bitstreams/handle/" + handleParts[0] + "/" + handleParts[1]
                        + "/" + FILE_NAME))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, PRESIGNED_URL));

        verify(s3DirectDownloadService)
                .generatePresignedUrl(eq(BUCKET), anyString(), eq(EXPIRATION_SECONDS), eq(FILE_NAME));
    }

    @Test
    public void streamsWhenDirectDownloadIsDisabled() throws Exception {
        configurationService.setProperty("s3.download.direct.enabled", false);
        Bitstream bitstream = originalBundleBitstream();

        getClient().perform(get("/api/core/bitstreams/" + bitstream.getID() + "/content"))
                .andExpect(status().isOk())
                .andExpect(content().string(FILE_CONTENT));
    }

    @Test
    public void streamsWhenAssetstoreIsNotOnS3() throws Exception {
        configurationService.setProperty("assetstore.s3.enabled", false);
        Bitstream bitstream = originalBundleBitstream();

        getClient().perform(get("/api/core/bitstreams/" + bitstream.getID() + "/content"))
                .andExpect(status().isOk())
                .andExpect(content().string(FILE_CONTENT));
    }

    @Test
    public void streamsBitstreamOutsideTheOriginalBundle() throws Exception {
        context.turnOffAuthorisationSystem();
        Bitstream bitstream;
        try (InputStream is = IOUtils.toInputStream(FILE_CONTENT, CharEncoding.UTF_8)) {
            bitstream = BitstreamBuilder.createBitstream(context, item, is, false)
                    .withName("process_output.txt")
                    .build();
        }
        context.restoreAuthSystemState();

        getClient(getAuthToken(admin.getEmail(), password))
                .perform(get("/api/core/bitstreams/" + bitstream.getID() + "/content"))
                .andExpect(status().isOk())
                .andExpect(content().string(FILE_CONTENT));
    }

    @Test
    public void serverErrorWhenBucketIsNotConfigured() throws Exception {
        configurationService.setProperty("assetstore.s3.bucketName", null);
        Bitstream bitstream = originalBundleBitstream();

        getClient().perform(get("/api/core/bitstreams/" + bitstream.getID() + "/content"))
                .andExpect(status().isInternalServerError());
    }

    private Bitstream originalBundleBitstream() throws Exception {
        context.turnOffAuthorisationSystem();
        Bitstream bitstream;
        try (InputStream is = IOUtils.toInputStream(FILE_CONTENT, CharEncoding.UTF_8)) {
            bitstream = BitstreamBuilder.createBitstream(context, item, is)
                    .withName(FILE_NAME)
                    .withMimeType("text/plain")
                    .build();
        }
        context.restoreAuthSystemState();
        return bitstream;
    }
}
