/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest;

import static com.jayway.jsonpath.matchers.JsonPathMatchers.hasJsonPath;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.InputStream;

import org.dspace.app.rest.test.AbstractControllerIntegrationTest;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.WorkspaceItemBuilder;
import org.dspace.content.Collection;
import org.dspace.content.Community;
import org.dspace.content.WorkspaceItem;
import org.dspace.content.service.CollectionService;
import org.dspace.content.service.ItemService;
import org.dspace.services.ConfigurationService;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

/**
 * Test suite for testing ClarinLicenseResourceValidation.
 *
 * @author Milan Kuchtiak
 *
 */
public class ClarinLicenseResourceValidationIT extends AbstractControllerIntegrationTest {

    @Autowired
    private CollectionService cs;
    @Autowired
    private ItemService itemService;
    @Autowired
    private ConfigurationService configurationService;

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();

        // make file upload mandatory in submissions
        configurationService.setProperty("webui.submit.upload.required", true);
    }

    @Test
    public void createWorkspaceWithFiles_TitleAndClarinLicenseMissing() throws Exception {
        context.turnOffAuthorisationSystem();

        parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Parent Community")
                .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                .withName("Sub Community")
                .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1).withName("Collection 1").build();

        String authToken = getAuthToken(eperson.getEmail(), password);

        WorkspaceItem wItem = WorkspaceItemBuilder.createWorkspaceItemWithNoClarinLicense(context, col1)
                .withIssueDate("2017-10-17")
                .grantLicense()
                .build();

        InputStream pdf = getClass().getResourceAsStream("simple-article.pdf");
        final MockMultipartFile pdfFile = new MockMultipartFile("file", "/local/path/simple-article.pdf",
                "application/pdf", pdf);

        context.restoreAuthSystemState();
        // upload the file in our workspaceitem
        getClient(authToken).perform(multipart("/api/submission/workspaceitems/" + wItem.getID())
                        .file(pdfFile))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sections.upload.files[0].metadata['dc.title'][0].value",
                        is("simple-article.pdf")))
                .andExpect(jsonPath("$.sections.upload.files[0].metadata['dc.source'][0].value",
                        is("/local/path/simple-article.pdf")));

        // Verify errors for missing title and clarin license
        getClient(authToken).perform(get("/api/submission/workspaceitems/" + wItem.getID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors", hasSize(2)))
                .andExpect(jsonPath("$.errors[?(@.message=='error.validation.required')]",
                        contains( hasJsonPath("$.paths",
                                contains(hasJsonPath("$", is("/sections/traditionalpageone/dc.title")))))))
                .andExpect(jsonPath("$.errors[?(@.message=='error.validation.clarin-license.notgranted')]",
                        contains( hasJsonPath("$.paths",
                                contains(hasJsonPath("$", is("/sections/clarin-license")))))));
    }

    @Test
    public void createWorkspaceWithoutFiles_UploadRequired() throws Exception {
        context.turnOffAuthorisationSystem();

        parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Parent Community")
                .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                .withName("Sub Community")
                .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1).withName("Collection 1").build();

        String authToken = getAuthToken(eperson.getEmail(), password);

        WorkspaceItem witem = WorkspaceItemBuilder.createWorkspaceItemWithNoClarinLicense(context, col1)
                .withTitle("Test WorkspaceItem")
                .withIssueDate("2017-10-17")
                .build();

        context.restoreAuthSystemState();
        // Verify error for missing files (with upload required to be set to true)
        // Also check whether there is no error for missing license.
        // Since there are no files, the license is not required.
        getClient(authToken).perform(get("/api/submission/workspaceitems/" + witem.getID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[?(@.message=='error.validation.filerequired')]",
                        contains(hasJsonPath("$.paths", contains(hasJsonPath("$", is("/sections/upload")))))));
    }

    @Test
    public void createWorkspaceWithoutFiles_UploadNotRequired() throws Exception {
        context.turnOffAuthorisationSystem();

        configurationService.setProperty("webui.submit.upload.required", false);

        parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Parent Community")
                .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                .withName("Sub Community")
                .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1).withName("Collection 1").build();

        String authToken = getAuthToken(eperson.getEmail(), password);

        WorkspaceItem witem = WorkspaceItemBuilder.createWorkspaceItemWithNoClarinLicense(context, col1)
                .withTitle("Test WorkspaceItem")
                .withIssueDate("2017-10-17")
                .build();

        context.restoreAuthSystemState();
        // Verify there are no errors (with upload required to be set to false).
        // Since there are no files, the license is not required.
        getClient(authToken).perform(get("/api/submission/workspaceitems/" + witem.getID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").doesNotExist());
    }

}
