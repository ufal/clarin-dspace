/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest;

import static org.hamcrest.Matchers.equalTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.InputStream;
import java.net.URI;

import org.apache.commons.codec.CharEncoding;
import org.apache.commons.io.IOUtils;
import org.dspace.app.rest.test.AbstractControllerIntegrationTest;
import org.dspace.authorize.service.AuthorizeService;
import org.dspace.builder.BitstreamBuilder;
import org.dspace.builder.BundleBuilder;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.ItemBuilder;
import org.dspace.builder.ResourcePolicyBuilder;
import org.dspace.content.Bitstream;
import org.dspace.content.Bundle;
import org.dspace.content.Collection;
import org.dspace.content.Item;
import org.dspace.content.service.BitstreamService;
import org.dspace.core.Constants;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

/**
 * Integration tests for {@link BitstreamByHandleRestController}.
 */
public class BitstreamByHandleRestControllerIT extends AbstractControllerIntegrationTest {

    private static final String ENDPOINT_BASE = "/api/core/bitstreams/handle";

    @Autowired
    AuthorizeService authorizeService;

    @Autowired
    BitstreamService bitstreamService;

    @Test
    public void downloadBitstreamByHandle() throws Exception {
        context.turnOffAuthorisationSystem();
        parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Parent Community")
                .build();
        Collection col = CollectionBuilder.createCollection(context, parentCommunity)
                .withName("Collection")
                .build();
        Item item = ItemBuilder.createItem(context, col)
                .withAuthor("Test Author")
                .build();
        String bitstreamContent = "TestBitstreamContent";
        Bitstream bitstream;
        try (InputStream is = IOUtils.toInputStream(bitstreamContent, CharEncoding.UTF_8)) {
            bitstream = BitstreamBuilder.createBitstream(context, item, is)
                    .withName("testfile.txt")
                    .withDescription("A test file")
                    .withMimeType("text/plain")
                    .build();
        }
        context.restoreAuthSystemState();

        String handle = item.getHandle();
        String[] handleParts = handle.split("/");

        getClient().perform(get(ENDPOINT_BASE + "/" + handleParts[0] + "/" + handleParts[1] + "/testfile.txt"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        equalTo("attachment; filename=\"testfile.txt\"; filename*=UTF-8''testfile.txt")))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/plain;charset=UTF-8"))
                .andExpect(content().string(bitstreamContent));
    }

    @Test
    public void downloadBitstreamByHandleMultipleFiles() throws Exception {
        context.turnOffAuthorisationSystem();
        parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Parent Community")
                .build();
        Collection col = CollectionBuilder.createCollection(context, parentCommunity)
                .withName("Collection")
                .build();
        Item item = ItemBuilder.createItem(context, col)
                .withAuthor("Test Author")
                .build();

        String content1 = "FileOneContent";
        String content2 = "FileTwoContent";
        try (InputStream is1 = IOUtils.toInputStream(content1, CharEncoding.UTF_8)) {
            BitstreamBuilder.createBitstream(context, item, is1)
                    .withName("file1.txt")
                    .withMimeType("text/plain")
                    .build();
        }
        try (InputStream is2 = IOUtils.toInputStream(content2, CharEncoding.UTF_8)) {
            BitstreamBuilder.createBitstream(context, item, is2)
                    .withName("file2.txt")
                    .withMimeType("text/plain")
                    .build();
        }
        context.restoreAuthSystemState();

        String handle = item.getHandle();
        String[] handleParts = handle.split("/");

        // Download first file
        getClient().perform(get(ENDPOINT_BASE + "/" + handleParts[0] + "/" + handleParts[1] + "/file1.txt"))
                .andExpect(status().isOk())
                .andExpect(content().string(content1));

        // Download second file
        getClient().perform(get(ENDPOINT_BASE + "/" + handleParts[0] + "/" + handleParts[1] + "/file2.txt"))
                .andExpect(status().isOk())
                .andExpect(content().string(content2));
    }

    @Test
    public void downloadBitstreamByHandleUnauthorizedForNonAdmin() throws Exception {
        context.turnOffAuthorisationSystem();
        parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Parent Community")
                .build();
        Collection col = CollectionBuilder.createCollection(context, parentCommunity)
                .withName("Collection")
                .build();
        Item item = ItemBuilder.createItem(context, col)
                .withAuthor("Test Author")
                .build();
        String bitstreamContent = "RestrictedContent";
        Bitstream bitstream;
        try (InputStream is = IOUtils.toInputStream(bitstreamContent, CharEncoding.UTF_8)) {
            bitstream = BitstreamBuilder.createBitstream(context, item, is)
                    .withName("restricted.txt")
                    .withMimeType("text/plain")
                    .build();
        }
        // Remove all read policies from the bitstream
        authorizeService.removeAllPolicies(context, bitstream);
        // Add a read policy only for admin
        ResourcePolicyBuilder.createResourcePolicy(context, admin, null)
                .withDspaceObject(bitstream)
                .withAction(Constants.READ)
                .build();
        context.restoreAuthSystemState();
        String handle = item.getHandle();
        String[] handleParts = handle.split("/");
        // Authenticated non-admin user should get 403 (Forbidden)
        String token = getAuthToken(eperson.getEmail(), password);
        getClient(token).perform(get(ENDPOINT_BASE + "/" + handleParts[0] + "/" + handleParts[1] + "/restricted.txt"))
                .andExpect(status().isForbidden());
    }

    @Test
    public void downloadBitstreamByHandleInvalidHandle() throws Exception {
        getClient().perform(get(ENDPOINT_BASE + "/99999/99999/nonexistent.txt"))
                .andExpect(status().isNotFound());
    }

    @Test
    public void downloadBitstreamByHandleMissingFile() throws Exception {
        context.turnOffAuthorisationSystem();
        parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Parent Community")
                .build();
        Collection col = CollectionBuilder.createCollection(context, parentCommunity)
                .withName("Collection")
                .build();
        Item item = ItemBuilder.createItem(context, col)
                .withAuthor("Test Author")
                .build();
        String bitstreamContent = "SomeContent";
        try (InputStream is = IOUtils.toInputStream(bitstreamContent, CharEncoding.UTF_8)) {
            BitstreamBuilder.createBitstream(context, item, is)
                    .withName("existing.txt")
                    .withMimeType("text/plain")
                    .build();
        }
        context.restoreAuthSystemState();

        String handle = item.getHandle();
        String[] handleParts = handle.split("/");

        getClient().perform(get(ENDPOINT_BASE + "/" + handleParts[0] + "/" + handleParts[1] + "/nonexistent.txt"))
                .andExpect(status().isNotFound());
    }

    @Test
    public void downloadBitstreamByHandleSpecialCharInFilename() throws Exception {
        context.turnOffAuthorisationSystem();
        parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Parent Community")
                .build();
        Collection col = CollectionBuilder.createCollection(context, parentCommunity)
                .withName("Collection")
                .build();
        Item item = ItemBuilder.createItem(context, col)
                .withAuthor("Test Author")
                .build();
        String bitstreamContent = "SpecialCharContent";
        try (InputStream is = IOUtils.toInputStream(bitstreamContent, CharEncoding.UTF_8)) {
            BitstreamBuilder.createBitstream(context, item, is)
                    .withName("my file (2).txt")
                    .withMimeType("text/plain")
                    .build();
        }
        context.restoreAuthSystemState();

        String handle = item.getHandle();
        String[] handleParts = handle.split("/");

        getClient().perform(get(ENDPOINT_BASE + "/" + handleParts[0] + "/" + handleParts[1] + "/my file (2).txt"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        equalTo("attachment; filename=\"my file (2).txt\"; "
                                + "filename*=UTF-8''my%20file%20%282%29.txt")))
                .andExpect(content().string(bitstreamContent));
    }

    @Test
    public void downloadBitstreamByHandleUtf8Filename() throws Exception {
        context.turnOffAuthorisationSystem();
        parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Parent Community")
                .build();
        Collection col = CollectionBuilder.createCollection(context, parentCommunity)
                .withName("Collection")
                .build();
        Item item = ItemBuilder.createItem(context, col)
                .withAuthor("Test Author")
                .build();
        // Filename with diacritics: "Médiá (3).jfif"
        String utf8Name = "M\u00e9di\u00e1 (3).jfif";
        String bitstreamContent = "Utf8FilenameContent";
        try (InputStream is = IOUtils.toInputStream(bitstreamContent, CharEncoding.UTF_8)) {
            BitstreamBuilder.createBitstream(context, item, is)
                    .withName(utf8Name)
                    .withMimeType("image/jpeg")
                    .build();
        }
        context.restoreAuthSystemState();

        String handle = item.getHandle();
        String[] handleParts = handle.split("/");

        // Use URI.create to pass a pre-encoded URL — get(String) would double-encode %C3 to %25C3
        getClient().perform(get(URI.create(ENDPOINT_BASE + "/" + handleParts[0] + "/" + handleParts[1]
                        + "/M%C3%A9di%C3%A1%20(3).jfif")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        // ASCII fallback transliterates the diacritics away; filename* keeps the real name
                        equalTo("attachment; filename=\"Media (3).jfif\"; "
                                + "filename*=UTF-8''M%C3%A9di%C3%A1%20%283%29.jfif")))
                .andExpect(content().string(bitstreamContent));
    }

    @Test
    public void downloadBitstreamByHandleUnauthorized() throws Exception {
        context.turnOffAuthorisationSystem();
        parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Parent Community")
                .build();
        Collection col = CollectionBuilder.createCollection(context, parentCommunity)
                .withName("Collection")
                .build();
        Item item = ItemBuilder.createItem(context, col)
                .withAuthor("Test Author")
                .build();

        String bitstreamContent = "RestrictedContent";
        Bitstream bitstream;
        try (InputStream is = IOUtils.toInputStream(bitstreamContent, CharEncoding.UTF_8)) {
            bitstream = BitstreamBuilder.createBitstream(context, item, is)
                    .withName("restricted.txt")
                    .withMimeType("text/plain")
                    .build();
        }

        // Remove all read policies from the bitstream
        authorizeService.removeAllPolicies(context, bitstream);
        // Add a read policy only for admin
        ResourcePolicyBuilder.createResourcePolicy(context, admin, null)
                .withDspaceObject(bitstream)
                .withAction(Constants.READ)
                .build();

        context.restoreAuthSystemState();

        String handle = item.getHandle();
        String[] handleParts = handle.split("/");

        // Anonymous user should get 401
        getClient().perform(get(ENDPOINT_BASE + "/" + handleParts[0] + "/" + handleParts[1] + "/restricted.txt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void headRequestBitstreamByHandle() throws Exception {
        context.turnOffAuthorisationSystem();
        parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Parent Community")
                .build();
        Collection col = CollectionBuilder.createCollection(context, parentCommunity)
                .withName("Collection")
                .build();
        Item item = ItemBuilder.createItem(context, col)
                .withAuthor("Test Author")
                .build();
        String bitstreamContent = "HeadRequestContent";
        try (InputStream is = IOUtils.toInputStream(bitstreamContent, CharEncoding.UTF_8)) {
            BitstreamBuilder.createBitstream(context, item, is)
                    .withName("headtest.txt")
                    .withMimeType("text/plain")
                    .build();
        }
        context.restoreAuthSystemState();

        String handle = item.getHandle();
        String[] handleParts = handle.split("/");

        getClient().perform(head(ENDPOINT_BASE + "/" + handleParts[0] + "/" + handleParts[1] + "/headtest.txt"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        equalTo("attachment; filename=\"headtest.txt\"; filename*=UTF-8''headtest.txt")));
    }

    @Test
    public void downloadBitstreamByHandleForbidden() throws Exception {
        context.turnOffAuthorisationSystem();
        parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Parent Community")
                .build();
        Collection col = CollectionBuilder.createCollection(context, parentCommunity)
                .withName("Collection")
                .build();
        Item item = ItemBuilder.createItem(context, col)
                .withAuthor("Test Author")
                .build();

        String bitstreamContent = "ForbiddenContent";
        Bitstream bitstream;
        try (InputStream is = IOUtils.toInputStream(bitstreamContent, CharEncoding.UTF_8)) {
            bitstream = BitstreamBuilder.createBitstream(context, item, is)
                    .withName("admin-only.txt")
                    .withMimeType("text/plain")
                    .build();
        }

        // Remove all read policies and grant access only to admin
        authorizeService.removeAllPolicies(context, bitstream);
        ResourcePolicyBuilder.createResourcePolicy(context, admin, null)
                .withDspaceObject(bitstream)
                .withAction(Constants.READ)
                .build();

        context.restoreAuthSystemState();

        String handle = item.getHandle();
        String[] handleParts = handle.split("/");

        // Authenticated non-admin user should get 403 (Forbidden)
        String token = getAuthToken(eperson.getEmail(), password);
        getClient(token).perform(
                get(ENDPOINT_BASE + "/" + handleParts[0] + "/" + handleParts[1] + "/admin-only.txt"))
                .andExpect(status().isForbidden());
    }

    @Test
    public void downloadBitstreamFromNonOriginalBundle() throws Exception {
        context.turnOffAuthorisationSystem();
        parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Parent Community")
                .build();
        Collection col = CollectionBuilder.createCollection(context, parentCommunity)
                .withName("Collection")
                .build();
        Item item = ItemBuilder.createItem(context, col)
                .withAuthor("Test Author")
                .build();

        // Place a bitstream only in the TEXT bundle (not ORIGINAL)
        Bundle textBundle = BundleBuilder.createBundle(context, item)
                .withName("TEXT")
                .build();
        String bitstreamContent = "ExtractedTextContent";
        try (InputStream is = IOUtils.toInputStream(bitstreamContent, CharEncoding.UTF_8)) {
            BitstreamBuilder.createBitstream(context, textBundle, is)
                    .withName("extracted.txt")
                    .withMimeType("text/plain")
                    .build();
        }
        context.restoreAuthSystemState();

        String handle = item.getHandle();
        String[] handleParts = handle.split("/");

        // Bitstream in TEXT bundle should not be found by this endpoint
        getClient().perform(get(ENDPOINT_BASE + "/" + handleParts[0] + "/" + handleParts[1] + "/extracted.txt"))
                .andExpect(status().isNotFound());
    }

    @Test
    public void downloadBitstreamByHandleMultipleDots() throws Exception {
        // Verify that Spring {filename:.+} correctly captures filenames with multiple dots
        context.turnOffAuthorisationSystem();
        parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Parent Community")
                .build();
        Collection col = CollectionBuilder.createCollection(context, parentCommunity)
                .withName("Collection")
                .build();
        Item item = ItemBuilder.createItem(context, col)
                .withAuthor("Test Author")
                .build();
        String bitstreamContent = "TarGzContent";
        try (InputStream is = IOUtils.toInputStream(bitstreamContent, CharEncoding.UTF_8)) {
            BitstreamBuilder.createBitstream(context, item, is)
                    .withName("archive.v2.1.tar.gz")
                    .withMimeType("application/gzip")
                    .build();
        }
        context.restoreAuthSystemState();

        String handle = item.getHandle();
        String[] handleParts = handle.split("/");

        getClient().perform(get(ENDPOINT_BASE + "/" + handleParts[0] + "/" + handleParts[1]
                        + "/archive.v2.1.tar.gz"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        equalTo("attachment; filename=\"archive.v2.1.tar.gz\"; "
                                + "filename*=UTF-8''archive.v2.1.tar.gz")))
                .andExpect(content().string(bitstreamContent));
    }

    @Test
    public void downloadBitstreamByHandleQuoteInFilename() throws Exception {
        // Verify double quotes in filename are escaped in Content-Disposition
        context.turnOffAuthorisationSystem();
        parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Parent Community")
                .build();
        Collection col = CollectionBuilder.createCollection(context, parentCommunity)
                .withName("Collection")
                .build();
        Item item = ItemBuilder.createItem(context, col)
                .withAuthor("Test Author")
                .build();
        String bitstreamContent = "QuoteContent";
        try (InputStream is = IOUtils.toInputStream(bitstreamContent, CharEncoding.UTF_8)) {
            BitstreamBuilder.createBitstream(context, item, is)
                    .withName("file \"quoted\".txt")
                    .withMimeType("text/plain")
                    .build();
        }
        context.restoreAuthSystemState();

        String handle = item.getHandle();
        String[] handleParts = handle.split("/");

        // Use URI.create to pass a pre-encoded URL — get(String) would double-encode %22 to %2522
        getClient().perform(get(URI.create(ENDPOINT_BASE + "/" + handleParts[0] + "/" + handleParts[1]
                        + "/file%20%22quoted%22.txt")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        equalTo("attachment; filename=\"file \\\"quoted\\\".txt\"; "
                                + "filename*=UTF-8''file%20%22quoted%22.txt")))
                .andExpect(content().string(bitstreamContent));
    }

    @Test
    public void downloadBitstreamByHandleCjkFilename() throws Exception {
        // Verify CJK characters (beyond ISO-8859-1) are handled correctly
        context.turnOffAuthorisationSystem();
        parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Parent Community")
                .build();
        Collection col = CollectionBuilder.createCollection(context, parentCommunity)
                .withName("Collection")
                .build();
        Item item = ItemBuilder.createItem(context, col)
                .withAuthor("Test Author")
                .build();
        // "日本語.txt" — three CJK characters
        String cjkName = "\u65e5\u672c\u8a9e.txt";
        String bitstreamContent = "CjkContent";
        try (InputStream is = IOUtils.toInputStream(bitstreamContent, CharEncoding.UTF_8)) {
            BitstreamBuilder.createBitstream(context, item, is)
                    .withName(cjkName)
                    .withMimeType("text/plain")
                    .build();
        }
        context.restoreAuthSystemState();

        String handle = item.getHandle();
        String[] handleParts = handle.split("/");

        // Use URI.create to pass a pre-encoded URL — get(String) would double-encode CJK sequences
        getClient().perform(get(URI.create(ENDPOINT_BASE + "/" + handleParts[0] + "/" + handleParts[1]
                        + "/%E6%97%A5%E6%9C%AC%E8%AA%9E.txt")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        // CJK has no ASCII decomposition, so it drops out of the fallback entirely
                        equalTo("attachment; filename=\".txt\"; "
                                + "filename*=UTF-8''%E6%97%A5%E6%9C%AC%E8%AA%9E.txt")))
                .andExpect(content().string(bitstreamContent));
    }

    @Test
    public void downloadBitstreamByHandleSameNameDifferentBundles() throws Exception {
        // A file with the same name in ORIGINAL and TEXT bundles — only ORIGINAL should be served
        context.turnOffAuthorisationSystem();
        parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Parent Community")
                .build();
        Collection col = CollectionBuilder.createCollection(context, parentCommunity)
                .withName("Collection")
                .build();
        Item item = ItemBuilder.createItem(context, col)
                .withAuthor("Test Author")
                .build();
        String originalContent = "OriginalBundleContent";
        try (InputStream is = IOUtils.toInputStream(originalContent, CharEncoding.UTF_8)) {
            BitstreamBuilder.createBitstream(context, item, is)
                    .withName("data.txt")
                    .withMimeType("text/plain")
                    .build();
        }
        // Add same name in TEXT bundle
        Bundle textBundle = BundleBuilder.createBundle(context, item)
                .withName("TEXT")
                .build();
        String textContent = "TextBundleContent";
        try (InputStream is = IOUtils.toInputStream(textContent, CharEncoding.UTF_8)) {
            BitstreamBuilder.createBitstream(context, textBundle, is)
                    .withName("data.txt")
                    .withMimeType("text/plain")
                    .build();
        }
        context.restoreAuthSystemState();

        String handle = item.getHandle();
        String[] handleParts = handle.split("/");

        // Should return ORIGINAL bundle content, not TEXT bundle
        getClient().perform(get(ENDPOINT_BASE + "/" + handleParts[0] + "/" + handleParts[1] + "/data.txt"))
                .andExpect(status().isOk())
                .andExpect(content().string(originalContent));
    }

    @Test
    public void downloadBitstreamByHandleComplexFilename() throws Exception {
        // Verify a filename with diacritics, plus, hash, and unmatched parenthesis
        context.turnOffAuthorisationSystem();
        parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Parent Community")
                .build();
        Collection col = CollectionBuilder.createCollection(context, parentCommunity)
                .withName("Collection")
                .build();
        Item item = ItemBuilder.createItem(context, col)
                .withAuthor("Test Author")
                .build();
        // "M\u00e9di\u00e1 (+)#9) ano"
        String complexName = "M\u00e9di\u00e1 (+)#9) ano";
        String bitstreamContent = "ComplexNameContent";
        try (InputStream is = IOUtils.toInputStream(bitstreamContent, CharEncoding.UTF_8)) {
            BitstreamBuilder.createBitstream(context, item, is)
                    .withName(complexName)
                    .withMimeType("application/octet-stream")
                    .build();
        }
        context.restoreAuthSystemState();

        String handle = item.getHandle();
        String[] handleParts = handle.split("/");

        // Pre-encoded URL: e=C3A9, a=C3A1, space=20, (=28, +=2B, )=29, #=23
        getClient().perform(get(URI.create(ENDPOINT_BASE + "/" + handleParts[0] + "/" + handleParts[1]
                        + "/M%C3%A9di%C3%A1%20(%2B)%239)%20ano")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        equalTo("attachment; filename=\"Media (+)#9) ano\"; "
                                + "filename*=UTF-8''M%C3%A9di%C3%A1%20%28%2B%29%239%29%20ano")))
                .andExpect(content().string(bitstreamContent));
    }
}
