/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.zip.Deflater;

import org.apache.commons.codec.CharEncoding;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
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
import org.dspace.content.service.BitstreamService;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;

public class MetadataBitstreamControllerIT extends AbstractControllerIntegrationTest {
    private static final String METADATABITSTREAM_ENDPOINT = "/api/" + ItemRest.CATEGORY + "/" + ItemRest.PLURAL_NAME;
    private static final String ALL_ZIP_PATH = "allzip";
    private static final String HANDLE_PARAM = "handleId";
    private static final String AUTHOR = "Test author name";
    private Collection col;

    private Item publicItem;
    private Bitstream bts;

    @Autowired
    AuthorizeService authorizeService;

    @Autowired
    BitstreamService bitstreamService;


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

        String bitstreamContent = "ThisIsSomeDummyText";
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
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        ZipArchiveOutputStream zip = new ZipArchiveOutputStream(byteArrayOutputStream);
        zip.setCreateUnicodeExtraFields(ZipArchiveOutputStream.UnicodeExtraFieldPolicy.ALWAYS);
        zip.setLevel(Deflater.NO_COMPRESSION);
        ZipArchiveEntry ze = new ZipArchiveEntry(bts.getName());
        zip.putArchiveEntry(ze);
        InputStream is = bitstreamService.retrieve(context, bts);
        org.apache.commons.compress.utils.IOUtils.copy(is, zip);
        zip.closeArchiveEntry();
        is.close();
        zip.close();

        String token = getAuthToken(admin.getEmail(), password);
        getClient(token).perform(get(METADATABITSTREAM_ENDPOINT + "/" + publicItem.getID() +
                        "/" + ALL_ZIP_PATH).param(HANDLE_PARAM, publicItem.getHandle()))
                .andExpect(status().isOk())
                .andExpect(content().bytes(byteArrayOutputStream.toByteArray()));

    }

    @Test
    public void downloadAllZipWithDoubleQuotesInItemName() throws Exception {
        context.turnOffAuthorisationSystem();

        // Create an item with double quotes in the name — reproduces the
        // ERR_RESPONSE_HEADERS_MULTIPLE_CONTENT_DISPOSITION browser error.
        Item itemWithQuotes = ItemBuilder.createItem(context, col)
                .withTitle("Supported data for manuscript \"Thermally-induced evolution\"")
                .withAuthor(AUTHOR)
                .build();

        String bitstreamContent = "QuotedItemContent";
        try (InputStream is = IOUtils.toInputStream(bitstreamContent, CharEncoding.UTF_8)) {
            BitstreamBuilder
                    .createBitstream(context, itemWithQuotes, is)
                    .withName("data.csv")
                    .withMimeType("text/csv")
                    .build();
        }
        context.restoreAuthSystemState();

        String token = getAuthToken(admin.getEmail(), password);
        getClient(token).perform(get(METADATABITSTREAM_ENDPOINT + "/" + itemWithQuotes.getID() +
                        "/" + ALL_ZIP_PATH).param(HANDLE_PARAM, itemWithQuotes.getHandle()))
                .andExpect(status().isOk())
                // The filename must have escaped quotes so the header is valid
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

        String bitstreamContent = "DiacriticsContent";
        try (InputStream is = IOUtils.toInputStream(bitstreamContent, CharEncoding.UTF_8)) {
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
                // Non-ASCII chars replaced with _ in filename, full UTF-8 in filename*
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"P__li_ _lu_ou_k_ k__.zip\";"
                        + " filename*=UTF-8''P%C5%99%C3%ADli%C5%A1%20%C5%BElu%C5%A5ou%C4%8Dk%C3%BD"
                        + "%20k%C5%AF%C5%88.zip"));
    }
}
