/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;

import org.apache.commons.codec.CharEncoding;
import org.apache.commons.io.IOUtils;
import org.dspace.app.rest.test.AbstractControllerIntegrationTest;
import org.dspace.authorize.AuthorizeException;
import org.dspace.builder.BitstreamBuilder;
import org.dspace.builder.BundleBuilder;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.GroupBuilder;
import org.dspace.builder.ItemBuilder;
import org.dspace.builder.PreviewContentBuilder;
import org.dspace.content.Bitstream;
import org.dspace.content.Bundle;
import org.dspace.content.Collection;
import org.dspace.content.Community;
import org.dspace.content.Item;
import org.dspace.content.PreviewContent;
import org.dspace.content.service.PreviewContentService;
import org.dspace.eperson.Group;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;

public class PreviewContentRestRepositoryIT  extends AbstractControllerIntegrationTest {

    private PreviewContent previewContent;
    private Bitstream bitstream;

    @Autowired
    private PreviewContentService previewContentService;

    @Before
    public void setup() throws SQLException, AuthorizeException, IOException {
        context.turnOffAuthorisationSystem();
        // create bitstream
        Community comm = CommunityBuilder.createCommunity(context)
                .withName("Community Test")
                .build();
        Collection col = CollectionBuilder.createCollection(context, comm).withName("Collection Test").build();
        Item publicItem = ItemBuilder.createItem(context, col)
                .withTitle("Test")
                .withIssueDate("2010-10-17")
                .withAuthor("Smith, Donald")
                .withSubject("ExtraEntry")
                .build();
        Bundle bundle1 = BundleBuilder.createBundle(context, publicItem)
                .withName("Bundle Test")
                .build();
        String bitstreamContent = "ThisIsSomeDummyText";
        bitstream = null;
        try (InputStream is = IOUtils.toInputStream(bitstreamContent, CharEncoding.UTF_8)) {
            bitstream = BitstreamBuilder.
                    createBitstream(context, bundle1, is)
                    .withName("Bitstream1 Test")
                    .withDescription("description")
                    .withMimeType("text/plain")
                    .build();
        }
        // create content previews
        previewContent =  PreviewContentBuilder.createPreviewContent(context, bitstream, "test1.txt",
                null, false, "100", null).build();
    }

    @After
    @Override
    public void destroy() throws Exception {
        //clean all
        BitstreamBuilder.deleteBitstream(bitstream.getID());
        PreviewContentBuilder.deletePreviewContent(previewContent.getID());
        super.destroy();
    }

    @Test
    public void findOne() throws Exception {
        String adminToken = getAuthToken(admin.getEmail(), password);

        getClient(adminToken).perform(get("/api/core/previewContents/" + previewContent.getID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("test1.txt")));
    }

    @Test
    public void findOneAnonymousIsUnauthorized() throws Exception {
        getClient().perform(get("/api/core/previewContents/" + previewContent.getID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void findOneNonAdminIsForbidden() throws Exception {
        String epersonToken = getAuthToken(eperson.getEmail(), password);

        getClient(epersonToken).perform(get("/api/core/previewContents/" + previewContent.getID()))
                .andExpect(status().isForbidden());
    }

    @Test
    public void findOnePrivateBitstreamIsNotReadable() throws Exception {
        context.turnOffAuthorisationSystem();
        Group readers = GroupBuilder.createGroup(context).withName("Preview readers").build();
        Bitstream privateBitstream;
        try (InputStream is = IOUtils.toInputStream("PrivateText", CharEncoding.UTF_8)) {
            privateBitstream = BitstreamBuilder.createBitstream(context, bitstream.getBundles().get(0), is)
                    .withName("Private bitstream")
                    .withReaderGroup(readers)
                    .build();
        }
        PreviewContent privatePreview = PreviewContentBuilder.createPreviewContent(context, privateBitstream,
                "private.txt", "PrivateText", false, "100", null).build();
        context.restoreAuthSystemState();

        getClient().perform(get("/api/core/previewContents/" + privatePreview.getID()))
                .andExpect(status().isUnauthorized());

        String epersonToken = getAuthToken(eperson.getEmail(), password);
        getClient(epersonToken).perform(get("/api/core/previewContents/" + privatePreview.getID()))
                .andExpect(status().isForbidden());
    }

    @Test
    public void findAll() throws Exception {
        String adminToken = getAuthToken(admin.getEmail(), password);

        getClient(adminToken).perform(get("/api/core/previewContents")
                        .param("size", String.valueOf(100)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements", is(1)))
                .andExpect(jsonPath("$._embedded.previewContents[0].name", is("test1.txt")));
    }

    @Test
    public void findAllAnonymousIsUnauthorized() throws Exception {
        getClient().perform(get("/api/core/previewContents"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void findAllNonAdminIsForbidden() throws Exception {
        String epersonToken = getAuthToken(eperson.getEmail(), password);

        getClient(epersonToken).perform(get("/api/core/previewContents"))
                .andExpect(status().isForbidden());
    }
}
