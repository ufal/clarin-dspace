/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dspace.app.rest.matcher.ExternalHandleMatcher;
import org.dspace.app.rest.test.AbstractControllerIntegrationTest;
import org.dspace.authorize.AuthorizeException;
import org.dspace.builder.ClarinHandleBuilder;
import org.dspace.handle.external.ExternalHandleConstants;
import org.dspace.handle.external.Handle;
import org.dspace.handle.service.HandleClarinService;
import org.dspace.services.ConfigurationService;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.util.ObjectUtils;

/**
 * Integration test class for the ExternalHandleRestRepository
 */
public class ExternalHandleRestRepositoryIT extends AbstractControllerIntegrationTest {

    @Autowired
    HandleClarinService handleClarinService;

    @Autowired
    ConfigurationService configurationService;

    List<org.dspace.handle.Handle> handlesWithMagicURLs = new ArrayList<>();

    private ObjectMapper mapper = new ObjectMapper();

    @Before
    public void setup() throws SQLException, AuthorizeException {
        context.turnOffAuthorisationSystem();

        List<String> magicURLs = this.createMagicURLs();

        // create Handles with magicURLs
        int index = 0;
        for (String magicURL : magicURLs) {
            // create Handle

            org.dspace.handle.Handle handle = ClarinHandleBuilder
                    .createHandle(context, "123/" + index, magicURL)
                    .build();
            this.handlesWithMagicURLs.add(handle);
            index++;
        }

        context.commit();
        context.restoreAuthSystemState();
    }

    @Test
    public void findAllExternalHandles() throws Exception {
        // call endpoint which should return external handles
        List<Handle> expectedExternalHandles =
                this.handleClarinService.convertHandleWithMagicToExternalHandle(this.handlesWithMagicURLs);

        // expectedExternalHandles should not be empty
        Assert.assertFalse(ObjectUtils.isEmpty(expectedExternalHandles));
        Handle externalHandle = expectedExternalHandles.get(0);

        getClient().perform(get("/api/services/handles/magic"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.valueOf("application/json;charset=UTF-8")))
                .andExpect(jsonPath("$", ExternalHandleMatcher.matchListOfExternalHandles(
                        expectedExternalHandles
                )))
        ;
    }

    @Test
    public void updateHandle() throws Exception {

        org.dspace.handle.Handle handleToUpdate = this.handlesWithMagicURLs.get(0);
        String handle = handleToUpdate.getHandle();

        String updatedMagicURL = Handle.getMagicUrl(null, null, null, null, null, null, "https://lindat.mff.cuni.cz/#!/services/pmltq/");
        Handle updatedHandle = new Handle(handle, updatedMagicURL);

        String authTokenAdmin = getAuthToken(admin.getEmail(), password);
        getClient(authTokenAdmin).perform(put("/api/services/handles")
                .content(mapper.writeValueAsBytes(updatedHandle))
                .contentType(contentType))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url", is(updatedHandle.url)))
        ;
    }

    @Test
    public void wut() {
        // ExternalHandleRestRepository::createHandle says this throws if handle already exists
        try {
            org.dspace.handle.Handle handle = handleClarinService.findByHandle(context, "123/0");
            Assert.assertNotNull(handle);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void createAndUpdateHandleByAdmin() throws Exception {

        String authToken = getAuthToken(admin.getEmail(), password);

        String url = "https://lindat.mff.cuni.cz/#!/services/pmltq/";
        Map<String, String> handleJson = Map.of(
            "url", url,
            "title", "title",
            "reportemail", "reporteMail",
            "subprefix", "subprefix",
            "datasetName", "datasetName",
            "datasetVersion", "datasetVersion",
            "query", "query"
        );
        // create new handle
        MvcResult createResult = createHandle(authToken, handleJson);

        Map<String, String> handleResponse = mapper.readValue(createResult.getResponse().getContentAsString(), Map.class);
        String handle = handleResponse.get("handle");
        String token = handleResponse.get("token");
        String newUrl = "https://lindat.cz/#!/services/pmltq/";
        Map<String, String> updatedHandleJson = Map.of(
            "handle", handle,
            "url", newUrl,
            "token", token
        );

        // remember how many handles we have
        int count = handleClarinService.count(context);

        // update the handle
        updateHandle(authToken, updatedHandleJson);

        Assert.assertEquals("Update should not create new handles.", count, handleClarinService.count(context));
    }

    // create/update no login
    @Test
    public void createHandleAsAnonymous() throws Exception {
        String authToken = null;

        String url = "https://lindat.mff.cuni.cz/#!/services/pmltq/";
        Map<String, String> handleJson = Map.of(
                "url", url,
                "title", "title",
                "reportemail", "reporteMail",
                "subprefix", "subprefix",
                "datasetName", "datasetName",
                "datasetVersion", "datasetVersion",
                "query", "query"
        );
        // create new handle
        MvcResult createResult = createHandle(authToken, handleJson);

        Map<String, String> handleResponse = mapper.readValue(createResult.getResponse().getContentAsString(), Map.class);
        String handle = handleResponse.get("handle");
        String token = handleResponse.get("token");
        String newUrl = "https://lindat.cz/#!/services/pmltq/";
        Map<String, String> updatedHandleJson = Map.of(
                "handle", handle,
                "url", newUrl,
                "token", token
        );

        // remember how many handles we have
        int count = handleClarinService.count(context);

        // update the handle
        updateHandle(authToken, updatedHandleJson);

        Assert.assertEquals("Update should not create new handles.", count, handleClarinService.count(context));
    }

    // blacklist/whitelist works

    // update only with token
    // magic url does not leak (it has the token)
    // validace jako ve starym
    // try create handle more than once

    private void updateHandle(String authToken, Map<String, String> updatedHandleJson) throws Exception {
        getClient(authToken).perform(put("/api/services/handles")
                        .content(mapper.writeValueAsBytes(updatedHandleJson))
                        .contentType(contentType))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.handle", is(updatedHandleJson.get("handle"))))
                .andExpect(jsonPath("$.token", is(updatedHandleJson.get("token"))))
                .andExpect(jsonPath("$.url", is(updatedHandleJson.get("url"))))
        ;
    }

    private MvcResult createHandle(String authToken, Map<String, String> handleJson) throws Exception {
        return getClient(authToken).perform(post("/api/services/handles")
                        .content(mapper.writeValueAsBytes(handleJson))
                        .contentType(contentType))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.handle", notNullValue()))
                .andExpect(jsonPath("$.token", notNullValue()))
                .andExpect(jsonPath("$.url", is(handleJson.get("url"))))
                .andReturn();
    }

    private List<String> createMagicURLs() {
        // External handle attributes
        String url = "url";
        String title = "title";
        String repository = "repository";
        String submitDate = "submitDate";
        String reporteMail = "reporteMail";
        String datasetName = "datasetName";
        String datasetVersion = "datasetVersion";
        String query = "query";
        String token = "token";
        String subprefix = "subprefix";

        List<String> magicURLs = new ArrayList<>();

        for (int i = 0; i < 3; i++) {
            // create mock object
            String magicURL =
                            ExternalHandleConstants.MAGIC_BEAN + title + i +
                            ExternalHandleConstants.MAGIC_BEAN + repository + i +
                            ExternalHandleConstants.MAGIC_BEAN + submitDate + i +
                            ExternalHandleConstants.MAGIC_BEAN + reporteMail + i +
                            ExternalHandleConstants.MAGIC_BEAN + datasetName + i +
                            ExternalHandleConstants.MAGIC_BEAN + datasetVersion + i +
                            ExternalHandleConstants.MAGIC_BEAN + query + i +
                            ExternalHandleConstants.MAGIC_BEAN + token + i +
                            ExternalHandleConstants.MAGIC_BEAN + url + i;
            // add mock object to the magicURLs
            magicURLs.add(magicURL);
        }

        return magicURLs;
    }
}
