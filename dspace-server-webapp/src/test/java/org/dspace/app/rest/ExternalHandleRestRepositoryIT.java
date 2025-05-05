/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest;

import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dspace.app.rest.matcher.ExternalHandleMatcher;
import org.dspace.app.rest.repository.RandomStringGenerator;
import org.dspace.app.rest.test.AbstractControllerIntegrationTest;
import org.dspace.authorize.AuthorizeException;
import org.dspace.builder.ClarinHandleBuilder;
import org.dspace.handle.HandlePlugin;
import org.dspace.handle.external.ExternalHandleConstants;
import org.dspace.handle.external.Handle;
import org.dspace.handle.service.HandleClarinService;
import org.dspace.services.ConfigurationService;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
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

    private String prefix;

    @SpyBean
    private RandomStringGenerator rnd;

    List<org.dspace.handle.Handle> handlesWithMagicURLs = new ArrayList<>();

    private final ObjectMapper mapper = new ObjectMapper();

    private List<org.dspace.handle.Handle> handlesBeforeTest;

    private final String urlBlacklistConfig = "shortener.post.url.blacklist.regexps";
    private final String hostBlacklistConfig = "shortener.post.host.blacklist.regexps";
    private String[] urlBlacklist;
    private String[] hostBlacklist;

    @Before
    public void setup() throws SQLException, AuthorizeException {
        prefix  = configurationService.getProperty("shortener.handle.prefix") + "/" ;
        urlBlacklist = configurationService.getArrayProperty(urlBlacklistConfig);
        hostBlacklist = configurationService.getArrayProperty(hostBlacklistConfig);
        context.turnOffAuthorisationSystem();

        handlesBeforeTest = handleClarinService.findAll(context);

        List<String> magicURLs = this.createMagicURLs();

        // create Handles with magicURLs
        int index = 0;
        for (String magicURL : magicURLs) {
            // create Handle

            org.dspace.handle.Handle handle = ClarinHandleBuilder
                    .createHandle(context, prefix + index, magicURL)
                    .build();
            this.handlesWithMagicURLs.add(handle);
            index++;
        }

        context.commit();
        context.restoreAuthSystemState();
    }

    @After
    public void cleanUp() throws SQLException, AuthorizeException {
        context.turnOffAuthorisationSystem();
        for (org.dspace.handle.Handle handle : handleClarinService.findAll(context)) {
            if (!handlesBeforeTest.contains(handle)) {
                handleClarinService.delete(context, handle);
            }
        }
        context.commit();
        context.restoreAuthSystemState();

        configurationService.setProperty(urlBlacklistConfig, urlBlacklist);
        configurationService.setProperty(hostBlacklistConfig, hostBlacklist);
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
        String newUrl = "https://lindat.mff.cuni.cz/#!/services/pmltq/";


        String authTokenAdmin = getAuthToken(admin.getEmail(), password);
        Map<String, String> updatedHandle = Map.of(
                "handle", handle,
                "url", newUrl,
                "token", "token0"
        );
        updateHandle(authTokenAdmin, updatedHandle);
    }

    // try create handle more than once
    @Test
    public void simulateHittingAnExistingHandleButThenSucceed() throws Exception {
        when(rnd.generate(4)).thenReturn("0")
                .thenReturn("1").thenReturn("2").thenReturn("3");
        MvcResult result = createHandle(null, Map.of(
                "url", "https://lindat.mff.cuni.cz/#!/services/pmltq/",
                "title", "title",
                "reportemail", "reporteMail"
        ));
        Map<String, String> handleResponse = mapper.readValue(result.getResponse().getContentAsString(),
                Map.class);
        Assert.assertEquals("Handle should be created", handleResponse.get("handle"),
                HandlePlugin.getCanonicalHandlePrefix() + prefix + "3");
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

        Map<String, String> handleResponse = mapper.readValue(createResult.getResponse().getContentAsString(),
                Map.class);
        String handle = handleResponse.get("handle");

        Assert.assertTrue("Handle contains subprefix", handle.contains("subprefix"));

        String token = handleResponse.get("token");
        String newUrl = "https://lindat.cz/#!/services/pmltq/";
        Map<String, String> updatedHandleJson = Map.of(
            "handle", handle,
            "url", newUrl,
            "token", token,
            "title", "IGNORE",
            "reportemail", "IGNORE",
            "subprefix", "IGNORE",
            "datasetName", "IGNORE",
            "datasetVersion", "IGNORE",
            "query", "IGNORE"
        );

        // remember how many handles we have
        int count = handleClarinService.count(context);

        // update the handle
        MvcResult result = updateHandle(authToken, updatedHandleJson);
        Assert.assertFalse("Only the URL should be updated", result.getResponse().getContentAsString().contains(
                "IGNORE"));
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

        Map<String, String> handleResponse = mapper.readValue(createResult.getResponse().getContentAsString(),
                Map.class);
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

    // update works only with token
    @Test
    public void updateWithValidTokenOnly() throws Exception {
        String hdl = prefix + "0";
        Map<String, String> updateWithInvalid = Map.of(
                "handle", hdl,
                "token", "INVALID",
                "url", "https://lindat.cz"
        );
        getClient().perform(put("/api/services/handles")
                .content(mapper.writeValueAsBytes(updateWithInvalid))
                .contentType(contentType))
                .andExpect(status().isNotFound());

        Map<String, String> updateWithValid = Map.of(
                "handle", HandlePlugin.getCanonicalHandlePrefix() + hdl,
                "token", "token0",
                "url", "https://lindat.cz"
        );
        MvcResult result = updateHandle(null, updateWithValid);
        Map<String, String> map = mapper.readValue(result.getResponse().getContentAsString(), Map.class);

        // did we get a token that actually works?
        Map<String, String> updateWithValid2 = Map.of(
                "handle", HandlePlugin.getCanonicalHandlePrefix() + hdl,
                "token", map.get("token"),
                "url", "https://lindat.cz/test1"
        );

        updateHandle(null, updateWithValid2);

    }

    // the token or magic url does not leak (it has the token)
    @Test
    public void noTokenInGetResponse() throws Exception {
        MvcResult result = getClient().perform(get("/api/services/handles/magic"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.valueOf("application/json;charset=UTF-8")))
                .andReturn()
        ;
        String content = result.getResponse().getContentAsString();
        Assert.assertFalse("token key should not be in the response", content.contains("\"token\":"));
        Assert.assertFalse("Token should not be in the response", content.contains("token0"));
    }

    @Test
    public void noMagicInCreateResponse() throws Exception {
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
        MvcResult result = createHandle(null, handleJson);

        String content = result.getResponse().getContentAsString();
        Assert.assertFalse("magic bean should not be in the response",
                content.contains(ExternalHandleConstants.MAGIC_BEAN));
    }

    @Test
    public void hostBlacklist() throws Exception {
        configurationService.setProperty(urlBlacklistConfig, null);
        configurationService.setProperty(hostBlacklistConfig, ".*\\.com;.*\\.cz;.*\\.app");
        Assert.assertEquals(1, configurationService.getArrayProperty(hostBlacklistConfig).length);
        for (String tld : new String[] {".com", ".cz", ".app"}) {
            getClient().perform(post("/api/services/handles")
                            .content(mapper.writeValueAsBytes(Map.of(
                                    "url", "https://example" + tld,
                                    "title", "title",
                                    "reportemail", "reporteMail"
                            )))
                            .contentType(contentType))
                    .andExpect(status().isBadRequest());
        }
    }
    @Test
    public void urlBlacklist() throws Exception {
        getClient().perform(post("/api/services/handles")
                        .content(mapper.writeValueAsBytes(Map.of(
                                "url", "https://junk",
                                "title", "title",
                                "reportemail", "reporteMail"
                        )))
                        .contentType(contentType))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void urlBlacklistRegexpWithRange() throws Exception {
        configurationService.setProperty(hostBlacklistConfig, null);
        configurationService.setProperty(urlBlacklistConfig, "http://.*;.*\\..{3\\,4}$,.*/wp-login$");
        Assert.assertEquals(2, configurationService.getArrayProperty(urlBlacklistConfig).length);
        List<String> shouldBeBlocked = List.of("http://junk", "https://example.com", "https://localhost:4000/wp-login");
        for (String url : shouldBeBlocked) {
            getClient().perform(post("/api/services/handles")
                            .content(mapper.writeValueAsBytes(Map.of(
                                    "url", url,
                                    "title", "title",
                                    "reportemail", "reporteMail"
                            )))
                            .contentType(contentType))
                    .andExpect(status().isBadRequest());
        }
        //should not be blocked
        createHandle(null, Map.of(
                "url", "https://example.cz",
                "title", "title",
                "reportemail", "email"
        ));
    }

    private MvcResult updateHandle(String authToken, Map<String, String> updatedHandleJson) throws Exception {
        return getClient(authToken).perform(put("/api/services/handles")
                        .content(mapper.writeValueAsBytes(updatedHandleJson))
                        .contentType(contentType))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.handle", anyOf(is(updatedHandleJson.get("handle")),
                        is(HandlePlugin.getCanonicalHandlePrefix() + updatedHandleJson.get("handle")))))
                .andExpect(jsonPath("$.token", notNullValue()))
                .andExpect(jsonPath("$.url", is(updatedHandleJson.get("url"))))
                .andReturn()
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
