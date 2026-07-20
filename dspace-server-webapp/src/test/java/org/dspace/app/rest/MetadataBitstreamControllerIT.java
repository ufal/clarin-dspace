/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest;

import static org.junit.Assert.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.commons.codec.CharEncoding;
import org.apache.commons.io.IOUtils;
import org.dspace.app.rest.model.ItemRest;
import org.dspace.app.rest.test.AbstractControllerIntegrationTest;
import org.dspace.authorize.service.AuthorizeService;
import org.dspace.builder.BitstreamBuilder;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.ItemBuilder;
import org.dspace.content.Bitstream;
import org.dspace.content.Collection;
import org.dspace.content.Item;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;

public class MetadataBitstreamControllerIT extends AbstractControllerIntegrationTest {
    private static final String METADATABITSTREAM_ENDPOINT = "/api/" + ItemRest.CATEGORY + "/" + ItemRest.PLURAL_NAME;
    private static final String ALL_ZIP_PATH = "allzip";
    private static final String HANDLE_PARAM = "handleId";
    private static final String AUTHOR = "Test author name";
    private static final String BITSTREAM_CONTENT = "ThisIsSomeDummyText";
    private Collection col;

    private Item publicItem;
    private Bitstream bts;

    @Autowired
    AuthorizeService authorizeService;


    @Override
    public void setUp() throws Exception {
        super.setUp();
        context.turnOffAuthorisationSystem();
        parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Parent Community")
                .build();

        col = CollectionBuilder.createCollection(context, parentCommunity).withName("Collection").build();

        publicItem = ItemBuilder.createItem(context, col)
                .withAuthor(AUTHOR)
                .build();

        String bitstreamContent = BITSTREAM_CONTENT;
        try (InputStream is = IOUtils.toInputStream(bitstreamContent, CharEncoding.UTF_8)) {
            bts = BitstreamBuilder.
                    createBitstream(context, publicItem, is)
                    .withName("Bitstream")
                    .withDescription("Description")
                    .withMimeType("application/zip")
                    .build();
        }
        context.restoreAuthSystemState();
    }

    @Test
    public void downloadAllZip() throws Exception {
        String token = getAuthToken(admin.getEmail(), password);
        byte[] zipBytes = getClient(token).perform(get(METADATABITSTREAM_ENDPOINT + "/" + publicItem.getID() +
                        "/" + ALL_ZIP_PATH).param(HANDLE_PARAM, publicItem.getHandle()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        // A ZIP entry stores a DOS last-modified timestamp that defaults to "now" at 2-second resolution, so
        // comparing the response byte-for-byte against a locally-built ZIP intermittently failed when the server
        // and the test happened to build their entries in different time buckets. Assert the meaningful payload
        // instead: the archive must contain exactly the item's bitstream, with the expected content.
        Map<String, String> entries = new HashMap<>();
        int entryCount = 0;
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entryCount++;
                entries.put(entry.getName(), new String(IOUtils.toByteArray(zis), StandardCharsets.UTF_8));
                zis.closeEntry();
            }
        }

        // count tracked separately so a duplicate entry name can't be masked by the map
        assertEquals(1, entryCount);
        assertEquals(Set.of(bts.getName()), entries.keySet());
        assertEquals(BITSTREAM_CONTENT, entries.get(bts.getName()));
    }
}
