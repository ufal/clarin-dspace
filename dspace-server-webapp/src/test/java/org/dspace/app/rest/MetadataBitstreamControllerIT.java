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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

    @Test
    public void downloadAllZipWithDoubleQuotesInItemName() throws Exception {
        context.turnOffAuthorisationSystem();

        // Double quotes in the name used to close the header's quoted-string early, which browsers
        // reported as ERR_RESPONSE_HEADERS_MULTIPLE_CONTENT_DISPOSITION.
        Item itemWithQuotes = ItemBuilder.createItem(context, col)
                .withTitle("Supported data for manuscript \"Thermally-induced evolution\"")
                .withAuthor(AUTHOR)
                .build();

        try (InputStream is = IOUtils.toInputStream("QuotedItemContent", CharEncoding.UTF_8)) {
            BitstreamBuilder.createBitstream(context, itemWithQuotes, is)
                    .withName("data.csv")
                    .withMimeType("text/csv")
                    .build();
        }
        context.restoreAuthSystemState();

        String token = getAuthToken(admin.getEmail(), password);
        getClient(token).perform(get(METADATABITSTREAM_ENDPOINT + "/" + itemWithQuotes.getID() +
                        "/" + ALL_ZIP_PATH).param(HANDLE_PARAM, itemWithQuotes.getHandle()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"Supported data for manuscript"
                        + " \\\"Thermally-induced evolution\\\".zip\";"
                        + " filename*=UTF-8''Supported%20data%20for%20manuscript"
                        + "%20%22Thermally-induced%20evolution%22.zip"));
    }

    @Test
    public void downloadAllZipWithNonAsciiItemName() throws Exception {
        context.turnOffAuthorisationSystem();

        Item itemWithDiacritics = ItemBuilder.createItem(context, col)
                .withTitle("Příliš žluťoučký kůň")
                .withAuthor(AUTHOR)
                .build();

        try (InputStream is = IOUtils.toInputStream("DiacriticsContent", CharEncoding.UTF_8)) {
            BitstreamBuilder.createBitstream(context, itemWithDiacritics, is)
                    .withName("file.txt")
                    .withMimeType("text/plain")
                    .build();
        }
        context.restoreAuthSystemState();

        String token = getAuthToken(admin.getEmail(), password);
        getClient(token).perform(get(METADATABITSTREAM_ENDPOINT + "/" + itemWithDiacritics.getID() +
                        "/" + ALL_ZIP_PATH).param(HANDLE_PARAM, itemWithDiacritics.getHandle()))
                .andExpect(status().isOk())
                // fallback transliterates the diacritics away; filename* carries the real name
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"Prilis zlutoucky kun.zip\";"
                        + " filename*=UTF-8''P%C5%99%C3%ADli%C5%A1%20%C5%BElu%C5%A5ou%C4%8Dk%C3%BD"
                        + "%20k%C5%AF%C5%88.zip"));
    }
}
