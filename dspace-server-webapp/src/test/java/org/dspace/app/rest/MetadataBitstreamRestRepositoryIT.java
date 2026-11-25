/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest;

import static org.dspace.app.rest.utils.Utils.DEFAULT_PAGE_SIZE;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.sql.SQLException;
import java.util.Set;

import org.apache.commons.codec.CharEncoding;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.dspace.app.rest.test.AbstractControllerIntegrationTest;
import org.dspace.app.util.Util;
import org.dspace.authorize.AuthorizeException;
import org.dspace.authorize.service.AuthorizeService;
import org.dspace.builder.BitstreamBuilder;
import org.dspace.builder.ClarinLicenseBuilder;
import org.dspace.builder.ClarinLicenseLabelBuilder;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.EPersonBuilder;
import org.dspace.builder.ItemBuilder;
import org.dspace.content.Bitstream;
import org.dspace.content.Collection;
import org.dspace.content.Item;
import org.dspace.content.clarin.ClarinLicense;
import org.dspace.content.clarin.ClarinLicenseLabel;
import org.dspace.content.factory.ClarinServiceFactory;
import org.dspace.content.service.BundleService;
import org.dspace.content.service.PreviewContentService;
import org.dspace.content.service.clarin.ClarinLicenseLabelService;
import org.dspace.content.service.clarin.ClarinLicenseResourceMappingService;
import org.dspace.content.service.clarin.ClarinLicenseService;
import org.dspace.core.Constants;
import org.dspace.eperson.EPerson;
import org.dspace.eperson.Group;
import org.dspace.eperson.service.GroupService;
import org.dspace.services.ConfigurationService;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

public class MetadataBitstreamRestRepositoryIT extends AbstractControllerIntegrationTest {

    // v9: the repository bean is registered under PLURAL_NAME, so the REST path is plural
    private static final String METADATABITSTREAM_ENDPOINT = "/api/core/metadatabitstreams/";
    private static final String METADATABITSTREAM_SEARCH_BY_HANDLE_ENDPOINT =
            METADATABITSTREAM_ENDPOINT + "search/byHandle";
    private static final String FILE_GRP_TYPE = "ORIGINAL,TEXT,THUMBNAIL";
    private static final String AUTHOR = "Test author name";

    private Item publicItem;
    private Bitstream bts;
    private String url;
    @Autowired
    ClarinLicenseResourceMappingService licenseService;

    @Autowired
    AuthorizeService authorizeService;

    @Autowired
    BundleService bundleService;

    @Autowired
    ConfigurationService configurationService;

    @Autowired
    PreviewContentService previewContentService;

    @Autowired
    private GroupService groupService;

    EPerson ePerson;
    String PASSWORD = "test";

    @Before
    public void setup() throws Exception {
        context.turnOffAuthorisationSystem();

        ePerson = EPersonBuilder.createEPerson(context)
                .withEmail("test@test.edu").withPassword(PASSWORD).build();

        parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Parent Community")
                .build();

        Collection col = CollectionBuilder.createCollection(context, parentCommunity).withName("Collection").build();

        publicItem = ItemBuilder.createItem(context, col)
                .withAuthor(AUTHOR)
                .build();

        // create empty THUMBNAIL bundle
        bundleService.create(context, publicItem, "ORIGINAL");

        try (InputStream is = getClass().getResourceAsStream("assetstore/logos.tgz")) {
            bts = BitstreamBuilder.
                    createBitstream(context, publicItem, is)
                    .withName("Bitstream")
                    .withDescription("Description")
                    .withMimeType("application/x-gtar")
                    .build();
        }

        // Allow composing of file preview in the config
        configurationService.setProperty("create.file-preview.on-item-page-load", true);

        context.restoreAuthSystemState();

        if (StringUtils.isBlank(url)) {
            composeURL();
        }
    }

    @Test
    public void findByHandleNullHandle() throws Exception {
        getClient().perform(get(METADATABITSTREAM_SEARCH_BY_HANDLE_ENDPOINT))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void findByHandle() throws Exception {
        // There is no restriction, so the user could preview the file
        boolean canPreview = true;

        assertFalse("Expects preview content not created yet.", previewContentService.hasPreview(context, bts));

        getClient().perform(get(METADATABITSTREAM_SEARCH_BY_HANDLE_ENDPOINT)
                        .param("handle", publicItem.getHandle())
                        .param("fileGrpType", FILE_GRP_TYPE))
                .andExpect(status().isOk())
                .andExpect(content().contentType(contentType))
                .andExpect(jsonPath("$._embedded.metadatabitstreams").exists())
                .andExpect(jsonPath("$._embedded.metadatabitstreams").isArray())
                .andExpect(jsonPath("$._embedded.metadatabitstreams[*].name")
                        .value(Matchers.containsInAnyOrder(Matchers.containsString("Bitstream"))))
                .andExpect(jsonPath("$._embedded.metadatabitstreams[*].description")
                        .value(Matchers.containsInAnyOrder(Matchers.containsString(bts.getDescription()))))
                .andExpect(jsonPath("$._embedded.metadatabitstreams[*].format")
                        .value(Matchers.containsInAnyOrder(Matchers.containsString(
                                bts.getFormat(context).getMIMEType()))))
                // Convert the long into int because Marchers has a problem to compare long format
                .andExpect(jsonPath("$._embedded.metadatabitstreams[*].fileSize")
                        .value(hasItem(is((int) bts.getSizeBytes()))))
                .andExpect(jsonPath("$._embedded.metadatabitstreams[*].canPreview")
                        .value(Matchers.containsInAnyOrder(Matchers.is(canPreview))))
                .andExpect(jsonPath("$._embedded.metadatabitstreams[0].fileInfo").value(Matchers.hasSize(2)))
                .andExpect(jsonPath("$._embedded.metadatabitstreams[*].checksum")
                        .value(Matchers.containsInAnyOrder(Matchers.containsString(bts.getChecksum()))))
                .andExpect(jsonPath("$._embedded.metadatabitstreams[*].href")
                        .value(Matchers.containsInAnyOrder(Matchers.containsString(url))));

        assertTrue("Expects preview content created and stored.", previewContentService.hasPreview(context, bts));
    }

    @Test
    public void previewingIsDisabledByCfg() throws Exception {
        boolean canPreview = configurationService.getBooleanProperty("file.preview.enabled", true);
        // Disable previewing
        configurationService.setProperty("file.preview.enabled", false);
        // There is no restriction, so the user could preview the file
        getClient().perform(get(METADATABITSTREAM_SEARCH_BY_HANDLE_ENDPOINT)
                        .param("handle", publicItem.getHandle())
                        .param("fileGrpType", FILE_GRP_TYPE))
                .andExpect(status().isOk())
                .andExpect(content().contentType(contentType))
                .andExpect(jsonPath("$._embedded.metadatabitstreams").exists())
                .andExpect(jsonPath("$._embedded.metadatabitstreams").isArray())
                .andExpect(jsonPath("$._embedded.metadatabitstreams[*].name")
                        .value(Matchers.containsInAnyOrder(Matchers.containsString("Bitstream"))))
                .andExpect(jsonPath("$._embedded.metadatabitstreams[*].description")
                        .value(Matchers.containsInAnyOrder(Matchers.containsString(bts.getDescription()))))
                .andExpect(jsonPath("$._embedded.metadatabitstreams[*].format")
                        .value(Matchers.containsInAnyOrder(Matchers.containsString(
                                bts.getFormat(context).getMIMEType()))))
                .andExpect(jsonPath("$._embedded.metadatabitstreams[*].fileSize")
                        .value(hasItem(is((int) bts.getSizeBytes()))))
                .andExpect(jsonPath("$._embedded.metadatabitstreams[*].canPreview")
                        .value(Matchers.containsInAnyOrder(Matchers.is(false))))
                .andExpect(jsonPath("$._embedded.metadatabitstreams[*].fileInfo").exists())
                .andExpect(jsonPath("$._embedded.metadatabitstreams[*].checksum")
                        .value(Matchers.containsInAnyOrder(Matchers.containsString(bts.getChecksum()))))
                .andExpect(jsonPath("$._embedded.metadatabitstreams[*].href")
                        .value(Matchers.containsInAnyOrder(Matchers.containsString(url))));
        assertFalse(previewContentService.hasPreview(context, bts));
        configurationService.setProperty("file.preview.enabled", canPreview);
        context.restoreAuthSystemState();
    }

    @Test
    public void previewingIsDisabledByCfgForHtml() throws Exception {
        boolean canPreview = configurationService.getBooleanProperty("file.preview.enabled", true);
        context.turnOffAuthorisationSystem();
        Collection col = CollectionBuilder.createCollection(context, parentCommunity).withName("Collection2").build();
        Item item = ItemBuilder.createItem(context, col)
                .withAuthor(AUTHOR)
                .build();

        // create empty THUMBNAIL bundle
        bundleService.create(context, item, "THUMBNAIL");

        String bitstreamContent = "ThisIsSomeDummyText";
        InputStream is = IOUtils.toInputStream(bitstreamContent, CharEncoding.UTF_8);
        Bitstream bitstream = BitstreamBuilder.
                createBitstream(context, item, is)
                .withName("Bitstream")
                .withDescription("Description")
                .withMimeType("text/html")
                .build();
        context.restoreAuthSystemState();
        // Disable previewing
        configurationService.setProperty("file.preview.enabled", false);
        // There is no restriction, so the user could preview the file
        getClient().perform(get(METADATABITSTREAM_SEARCH_BY_HANDLE_ENDPOINT)
                        .param("handle", item.getHandle())
                        .param("fileGrpType", FILE_GRP_TYPE))
                .andExpect(status().isOk())
                .andExpect(content().contentType(contentType))
                .andExpect(jsonPath("$._embedded.metadatabitstreams").exists())
                .andExpect(jsonPath("$._embedded.metadatabitstreams").isArray())
                .andExpect(jsonPath("$._embedded.metadatabitstreams[*].name")
                        .value(Matchers.containsInAnyOrder(Matchers.containsString("Bitstream"))))
                .andExpect(jsonPath("$._embedded.metadatabitstreams[*].description")
                        .value(Matchers.containsInAnyOrder(
                                Matchers.containsString(bitstream.getDescription()))))
                .andExpect(jsonPath("$._embedded.metadatabitstreams[*].format")
                        .value(Matchers.containsInAnyOrder(Matchers.containsString(
                                bitstream.getFormat(context).getMIMEType()))))
                .andExpect(jsonPath("$._embedded.metadatabitstreams[*].fileSize")
                        .value(hasItem(is((int) bitstream.getSizeBytes()))))
                .andExpect(jsonPath("$._embedded.metadatabitstreams[*].canPreview")
                        .value(Matchers.containsInAnyOrder(Matchers.is(false))))
                .andExpect(jsonPath("$._embedded.metadatabitstreams[*].fileInfo").exists())
                .andExpect(jsonPath("$._embedded.metadatabitstreams[*].checksum")
                        .value(Matchers.containsInAnyOrder(Matchers.containsString(bitstream.getChecksum()))));
        ItemBuilder.deleteItem(item.getID());
        CollectionBuilder.deleteCollection(col.getID());
        configurationService.setProperty("file.preview.enabled", canPreview);
    }

    @Test
    public void findByHandleEmptyFileGrpType() throws Exception {
        getClient().perform(get(METADATABITSTREAM_SEARCH_BY_HANDLE_ENDPOINT)
                .param("handle", publicItem.getHandle())
                .param("fileGrpType", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements", is(0)))
                .andExpect(jsonPath("$.page.totalPages", is(0)))
                .andExpect(jsonPath("$.page.size", is(DEFAULT_PAGE_SIZE)))
                .andExpect(jsonPath("$.page.number", is(0)))
                .andExpect(jsonPath("$._links.self.href",
                        Matchers.containsString(METADATABITSTREAM_SEARCH_BY_HANDLE_ENDPOINT +
                                "?handle=" + publicItem.getHandle() + "&fileGrpType=")));
    }

    @Test
    public void searchMethodsExist() throws Exception {

        getClient().perform(get("/api/core/metadatabitstreams"))
                .andExpect(status().is5xxServerError());

        getClient().perform(get("/api/core/metadatabitstreams/search"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(contentType))
                .andExpect(jsonPath("$._links.byHandle", notNullValue()));
    }

    @Test
    public void previewDisabledByReadPermission() throws Exception {
        context.turnOffAuthorisationSystem();
        Collection col = CollectionBuilder.createCollection(context, parentCommunity).withName("Collection2").build();
        Item item = ItemBuilder.createItem(context, col).withAuthor(AUTHOR).build();

        try {
            // create bitstream with ADMIN reader group,
            // so the non admin user cannot read the bitstream and preview content is not available for non admin user
            try (InputStream is = getClass().getResourceAsStream("assetstore/logos.tgz")) {
                BitstreamBuilder.
                        createBitstream(context, item, is)
                        .withName("Bitstream")
                        .withDescription("Description")
                        .withMimeType("application/x-gtar")
                        .withReaderGroup(groupService.findByName(context, Group.ADMIN))
                        .build();
            }
            context.restoreAuthSystemState();

            // Admin user can preview the archive file because the bitstream has the ADMIN read permission,
            // and also the fileInfo should be generated for admin user.
            checkFilePreviewAsAdmin(item, true, 2);

            // Non admin user cannot preview the archive file because the bitstream has only ADMIN read permission,
            // and also the fileInfo should be empty in this case.
            // Note that file preview was generated in the previous check, but it's not visible for non-authorized user.
            checkFilePreview(item, false, 0);
        } finally {
            ItemBuilder.deleteItem(item.getID());
            CollectionBuilder.deleteCollection(col.getID());
        }
    }

    @Test
    public void previewDisabledForHtmlFileByReadPermission() throws Exception {
        context.turnOffAuthorisationSystem();
        Collection col = CollectionBuilder.createCollection(context, parentCommunity).withName("Collection2").build();
        Item item = ItemBuilder.createItem(context, col).withAuthor(AUTHOR).build();

        try {
            // create bitstream with ADMIN reader group,
            // so the non admin user cannot read the bitstream and preview content is not available for non admin user
            try (InputStream is = getClass().getResourceAsStream("assetstore/hello.html")) {
                BitstreamBuilder.
                        createBitstream(context, item, is)
                        .withName("hello.html")
                        .withDescription("HTML file")
                        .withMimeType("text/html")
                        .withReaderGroup(groupService.findByName(context, Group.ADMIN))
                        .build();
            }
            context.restoreAuthSystemState();

            // Admin user can preview the html file because the bitstream has the ADMIN read permission,
            // and also the fileInfo should be generated for admin user.
            checkFilePreviewAsAdmin(item, true, 1);

            // Non admin user cannot preview the html file because the bitstream has only ADMIN read permission,
            // and also the fileInfo should be empty in this case.
            checkFilePreview(item, false, 0);
        } finally {
            ItemBuilder.deleteItem(item.getID());
            CollectionBuilder.deleteCollection(col.getID());
        }
    }

    @Test
    public void previewEnabledForHtmlFile() throws Exception {
        context.turnOffAuthorisationSystem();
        Collection col = CollectionBuilder.createCollection(context, parentCommunity).withName("Collection2").build();
        Item item = ItemBuilder.createItem(context, col).withAuthor(AUTHOR).build();

        try {
            // create bitstream with ADMIN reader group,
            // so the non admin user cannot read the bitstream and preview content is not available for non admin user
            try (InputStream is = getClass().getResourceAsStream("assetstore/hello.html")) {
                BitstreamBuilder.
                        createBitstream(context, item, is)
                        .withName("hello.html")
                        .withDescription("HTML file")
                        .withMimeType("text/html")
                        .build();
            }
            context.restoreAuthSystemState();
            // user can preview the html file because the bitstream has read permission
            checkFilePreview(item, true, 1);
        } finally {
            ItemBuilder.deleteItem(item.getID());
            CollectionBuilder.deleteCollection(col.getID());
        }
    }

    @Test
    public void previewNotAllowedWhenClarinLicenceAgreementIsNeeded() throws Exception {
        context.turnOffAuthorisationSystem();
        Collection col = CollectionBuilder.createCollection(context, parentCommunity).withName("Collection2").build();
        Item item = ItemBuilder.createItem(context, col).withAuthor(AUTHOR).build();

        ClarinLicenseService clarinLicenseService = ClarinServiceFactory.getInstance().getClarinLicenseService();
        ClarinLicense clarinLicense = addClarinLicenseThatNeedsConfirmation(clarinLicenseService, item);

        try {
            Bitstream bitstream;
            try (InputStream is = getClass().getResourceAsStream("assetstore/logos.tgz")) {
                bitstream = BitstreamBuilder.
                        createBitstream(context, item, is)
                        .withName("Bitstream")
                        .withDescription("Description")
                        .withMimeType("application/x-gtar")
                        .build();
            }

            clarinLicenseService.addClarinLicenseToBitstream(context, item, bitstream.getBundles().get(0), bitstream);

            context.restoreAuthSystemState();
            // Non admin user cannot preview the archive file when the license agreement is needed.
            checkFilePreview(item, false, 0);
        } finally {
            ItemBuilder.deleteItem(item.getID());
            CollectionBuilder.deleteCollection(col.getID());
            ClarinLicenseBuilder.deleteClarinLicense(clarinLicense.getID());
            ClarinLicenseLabelBuilder.deleteClarinLicenseLabel(clarinLicense.getLicenseLabels().get(0).getID());
        }
    }

    @Test
    public void previewNotAllowedForHtmlFileWhenLicenceAgreementIsNeeded() throws Exception {
        context.turnOffAuthorisationSystem();
        Collection col = CollectionBuilder.createCollection(context, parentCommunity).withName("Collection2").build();
        Item item = ItemBuilder.createItem(context, col).withAuthor(AUTHOR).build();

        ClarinLicenseService clarinLicenseService = ClarinServiceFactory.getInstance().getClarinLicenseService();
        ClarinLicense clarinLicense = addClarinLicenseThatNeedsConfirmation(clarinLicenseService, item);

        try {
            Bitstream bitstream;
            try (InputStream is = getClass().getResourceAsStream("assetstore/hello.html")) {
                bitstream = BitstreamBuilder.
                        createBitstream(context, item, is)
                        .withName("Hello.html")
                        .withDescription("HTML file")
                        .withMimeType("text/html")
                        .build();
            }

            clarinLicenseService.addClarinLicenseToBitstream(context, item, bitstream.getBundles().get(0), bitstream);

            context.restoreAuthSystemState();
            // Non admin user cannot preview the html file when the license agreement is needed.
            checkFilePreview(item, false, 0);
        } finally {
            ItemBuilder.deleteItem(item.getID());
            CollectionBuilder.deleteCollection(col.getID());
            ClarinLicenseBuilder.deleteClarinLicense(clarinLicense.getID());
            ClarinLicenseLabelBuilder.deleteClarinLicenseLabel(clarinLicense.getLicenseLabels().get(0).getID());
        }
    }

    private void composeURL() {
        String identifier = null;
        if (publicItem != null && publicItem.getHandle() != null) {
            identifier = "handle/" + publicItem.getHandle();
        } else if (publicItem != null) {
            identifier = "item/" + publicItem.getID();
        } else {
            identifier = "id/" + bts.getID();
        }
        url = "/api/core/bitstreams/" + identifier + "/";
        try {
            if (bts.getName() != null) {
                url += Util.encodeBitstreamName(bts.getName(), "UTF-8");
            }
        } catch (UnsupportedEncodingException uee) { /* Do nothing */ }
        url += "?sequence=" + bts.getSequenceID();

        String isAllowed = "n";
        try {
            if (authorizeService.authorizeActionBoolean(context, bts, Constants.READ)) {
                isAllowed = "y";
            }
        } catch (SQLException e) { /* Do nothing */ }

        url += "&isAllowed=" + isAllowed;
    }

    /**
     *  Create a license that needs confirmation and set this license to the item,
     *  so the user has to confirm the license agreement before downloading the file(s).
     *
     * @param clarinLicenseService ClarinLicenseService
     * @param item Item
     * @return ClarinLicense that needs confirmation
     * @throws SQLException SQLException
     * @throws AuthorizeException AuthorizeException
     */
    private ClarinLicense addClarinLicenseThatNeedsConfirmation(ClarinLicenseService clarinLicenseService, Item item)
            throws SQLException, AuthorizeException {

        ClarinLicenseLabelService clarinLicenseLabelService =
                ClarinServiceFactory.getInstance().getClarinLicenseLabelService();

        ClarinLicenseLabel clarinLicenseLabel = ClarinLicenseLabelBuilder.createClarinLicenseLabel(context).build();
        clarinLicenseLabel.setLabel("CLL");
        clarinLicenseLabel.setTitle("CLL Title");
        clarinLicenseLabelService.update(context, clarinLicenseLabel);

        ClarinLicense clarinLicense = ClarinLicenseBuilder.createClarinLicense(context).build();
        clarinLicense.setName("CL Name");
        clarinLicense.setConfirmation(ClarinLicense.Confirmation.ASK_ALWAYS);
        clarinLicense.setDefinition("CL Definition");
        clarinLicense.setRequiredInfo("CL Req");
        clarinLicense.setLicenseLabels(Set.of(clarinLicenseLabel));

        clarinLicenseService.addLicenseMetadataToItem(context, clarinLicense, item);
        clarinLicenseService.update(context, clarinLicense);

        return clarinLicense;
    }

    private void checkFilePreview(Item item, boolean filePreviewExpected, int expectedFileInfoSize) throws Exception {
        MockMvc client = getClient(getAuthToken(ePerson.getEmail(), PASSWORD));
        performCheck(client, item, filePreviewExpected, expectedFileInfoSize);
    }

    private void checkFilePreviewAsAdmin(Item item, boolean filePreviewExpected, int expectedFileInfoSize)
            throws Exception {
        MockMvc client = getClient(getAuthToken(admin.getEmail(), password));
        performCheck(client, item, filePreviewExpected, expectedFileInfoSize);
    }

    private void performCheck(MockMvc client, Item item, boolean filePreviewExpected, int expectedFileInfoSize)
            throws Exception {
        client.perform(get(METADATABITSTREAM_SEARCH_BY_HANDLE_ENDPOINT)
                        .param("handle", item.getHandle())
                        .param("fileGrpType", FILE_GRP_TYPE))
                .andExpect(status().isOk())
                .andExpect(content().contentType(contentType))
                .andExpect(jsonPath("$._embedded.metadatabitstreams").exists())
                .andExpect(jsonPath("$._embedded.metadatabitstreams").isArray())
                .andExpect(jsonPath("$._embedded.metadatabitstreams", hasSize(1)))
                .andExpect(jsonPath("$._embedded.metadatabitstreams[0].canPreview").value(filePreviewExpected))
                .andExpect(jsonPath("$._embedded.metadatabitstreams[0].fileInfo").isArray())
                .andExpect(jsonPath("$._embedded.metadatabitstreams[0].fileInfo", hasSize(expectedFileInfoSize)));
    }
}
