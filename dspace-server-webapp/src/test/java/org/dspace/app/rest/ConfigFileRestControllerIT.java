/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.dspace.app.rest.test.AbstractControllerIntegrationTest;
import org.dspace.builder.EPersonBuilder;
import org.dspace.eperson.EPerson;
import org.dspace.eperson.service.GroupService;
import org.dspace.services.ConfigurationService;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * Integration tests for Configuration File Management REST API
 *
 * @author Milan Majchrak (dspace at dataquest.sk)
 */
public class ConfigFileRestControllerIT extends AbstractControllerIntegrationTest {

    @Autowired
    private GroupService groupService;

    @Autowired
    private ConfigurationService configurationService;

    @Before
    public void setup() throws Exception {
        configurationService.setProperty("config.admin.updateable.files",
                "dspace.cfg,local.cfg,item-submission.xml,submission-forms.xml,test-dspace.cfg");
    }


    /**
     * Test that configuration files endpoint requires authentication
     */
    @Test
    public void testConfigFilesEndpoint_RequiresAuthentication() throws Exception {
        // Test that unauthenticated access returns 401
        getClient().perform(get("/api/admin/configfiles"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Test that non-admin user gets forbidden access
     */
    @Test
    public void testConfigFilesEndpoint_RequiresAdminRole() throws Exception {

        context.turnOffAuthorisationSystem();

        // Create a regular user (non-admin)
        EPerson regularUser = EPersonBuilder.createEPerson(context)
                .withEmail("regular@test.com")
                .withPassword(password)
                .build();

        context.restoreAuthSystemState();

        String token = getAuthToken(regularUser.getEmail(), password);

        // Test that non-admin user gets 403
        getClient(token).perform(get("/api/admin/configfiles"))
                .andExpect(status().isForbidden());
    }

    /**
     * Test admin access to config files list endpoint
     */
    @Test
    public void testConfigFilesEndpoint_AdminAccess() throws Exception {

        String adminToken = getAuthToken(admin.getEmail(), password);

        // Test admin access to list endpoint
        // Admin should be able to access the endpoint (may return empty list but should not get authorization errors)
        getClient(adminToken).perform(get("/api/admin/configfiles"))
                .andExpect(status().isOk());
    }

    /**
     * Test individual config file endpoint security
     */
    @Test
    public void testConfigFileEndpoint_AdminAccess() throws Exception {

        String adminToken = getAuthToken(admin.getEmail(), password);

        // First test the list endpoint to see what files are available
        getClient(adminToken).perform(get("/api/admin/configfiles"))
                .andExpect(status().isOk());

        // Test admin access to individual file endpoint - use dspace.cfg which should always be allowed
        getClient(adminToken).perform(get("/api/admin/configfiles/dspace.cfg"))
                .andExpect(status().isOk());
    }

    /**
     * Test PUT endpoint
     */
    @Test
    public void testConfigFileUpdate() throws Exception {
        String adminToken = getAuthToken(admin.getEmail(), password);

        getClient(adminToken).perform(put("/api/admin/configfiles/test-dspace.cfg/content")
                .contentType(MediaType.TEXT_PLAIN)
                .content("# Test content"))
                .andExpect(status().isOk());
    }

    /**
     * Test GET endpoint returns proper file content
     */
    @Test
    public void testConfigFileRead_ValidContent() throws Exception {

        String adminToken = getAuthToken(admin.getEmail(), password);

        // Test reading a configuration file
        getClient(adminToken).perform(get("/api/admin/configfiles/dspace.cfg/content"))
                .andExpect(status().isOk());
    }

    /**
     * Test file update with PUT request
     */
    @Test
    public void testConfigFileUpdate_ValidRequest() throws Exception {

        String adminToken = getAuthToken(admin.getEmail(), password);

        String testContent = "# Test configuration\ntest.property = test.value\n";

        // Test updating a configuration file
        getClient(adminToken).perform(put("/api/admin/configfiles/test-dspace.cfg/content")
                .contentType(MediaType.TEXT_PLAIN)
                .content(testContent))
                .andExpect(status().isOk());
    }
}