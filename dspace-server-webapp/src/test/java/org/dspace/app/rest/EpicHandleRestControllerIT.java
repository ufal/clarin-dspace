/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */

package org.dspace.app.rest;

import static com.jayway.jsonpath.matchers.JsonPathMatchers.hasJsonPath;
import static org.dspace.handle.EpicHandleServiceTest.MockResponse;
import static org.dspace.handle.EpicHandleServiceTest.getResource;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import javax.ws.rs.core.Response;

import org.dspace.app.rest.test.AbstractControllerIntegrationTest;
import org.dspace.handle.EpicHandleRestHelper;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

public class EpicHandleRestControllerIT extends AbstractControllerIntegrationTest {

    private static final String HANDLES_URL = "/api/core/epichandles";
    private static final String PREFIX = "11148";
    private static final String PREFIX_URL = HANDLES_URL + "/" + PREFIX;
    private static final String SUB_PREFIX = "TEST";
    private static final String SUFFIX = SUB_PREFIX + "-0000-0011-2E07-3";
    private static final String TEST_URL = "http://www.test.cz";
    private static final String UPDATED_TEST_URL = "http://www.test.cz/news";
    private String adminToken;
    private String nonAdminToken;
    private String pidServiceUrl;

    @Before
    public void setup() throws Exception {
        adminToken = getAuthToken(admin.getEmail(), password);
        nonAdminToken = getAuthToken(eperson.getEmail(), password);
        ConfigurationService configurationService = DSpaceServicesFactory.getInstance().getConfigurationService();
        pidServiceUrl = configurationService.getProperty("lr.pid.service.url");
    }

    @Test
    public void testGetHandle() throws Exception {
        String mockedResponse = getResource("/org/dspace/handle/epicGetHandleResponse.json");
        try (MockedStatic<EpicHandleRestHelper> mockedHelper = Mockito.mockStatic(EpicHandleRestHelper.class)) {
            // test OK response
            mockedHelper.when(() -> EpicHandleRestHelper.getHandle(pidServiceUrl, PREFIX, SUFFIX))
                    .thenReturn(new MockResponse<>(Response.Status.OK, mockedResponse));
            getClient(adminToken).perform(get(PREFIX_URL + "/" + SUFFIX))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id", is(PREFIX + "/" + SUFFIX)))
                    .andExpect(jsonPath("$.url", is(TEST_URL)));

            // test 404 response
            mockedHelper.when(() -> EpicHandleRestHelper.getHandle(pidServiceUrl, PREFIX, SUFFIX))
                    .thenReturn(Response.status(Response.Status.NOT_FOUND).build());
            getClient(adminToken).perform(get(PREFIX_URL + "/" + SUFFIX))
                    .andExpect(status().isNotFound());

            // test 403 response
            getClient(nonAdminToken).perform(get(PREFIX_URL + "/" + SUFFIX))
                    .andExpect(status().isForbidden());

            // test 401 response
            getClient("invalid_token").perform(get(PREFIX_URL + "/" + SUFFIX))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    public void testSearchHandles() throws Exception {
        String urlQuery = "www.test.cz";
        String mockedFirstPage = getResource("/org/dspace/handle/epicSearchFirstPageResponse.json");
        String mockedLastPage = getResource("/org/dspace/handle/epicSearchLastPageResponse.json");
        String mockedCountResponse = getResource("/org/dspace/handle/epicCountResponse.json");
        String mockedAllItems = getResource("/org/dspace/handle/epicSearchAllResponse.json");
        try (MockedStatic<EpicHandleRestHelper> mockedHelper = Mockito.mockStatic(EpicHandleRestHelper.class)) {
            String urlParameter = "*" + urlQuery + "*";

            // test OK response (looking for first page with unknown nr. of totalElements)
            mockedHelper.when(() ->
                            EpicHandleRestHelper.countHandles(pidServiceUrl, PREFIX, urlParameter))
                    .thenReturn(new MockResponse<>(Response.Status.OK, mockedCountResponse));
            mockedHelper.when(() ->
                            EpicHandleRestHelper.searchHandles(pidServiceUrl, PREFIX, urlParameter, 1, 2))
                    .thenReturn(new MockResponse<>(Response.Status.OK, mockedFirstPage));
            getClient(adminToken).perform(get(PREFIX_URL +
                            "?url=" + urlQuery + "&page=0&size=2&runCountSynchronously=true"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.pageable.offset", is(0)))
                    .andExpect(jsonPath("$.pageable.pageSize", is(2)))
                    .andExpect(jsonPath("$.pageable.pageNumber", is(0)))
                    .andExpect(jsonPath("$.totalElements", is(3)))
                    .andExpect(jsonPath("$.last", is(false)))
                    .andExpect(jsonPath("$.numberOfElements", is(2)))
                    .andExpect(jsonPath("$.totalPages", is(2)))
                    .andExpect(jsonPath("$.content", hasSize(2)))
                    .andExpect(jsonPath("$.content", containsInAnyOrder(
                            allOf(
                                    hasJsonPath("$.id", is("11148/TEST-0000-0011-2E03-7")),
                                    hasJsonPath("$.url", is("http://www.test.cz/news"))
                            ),
                            allOf(
                                    hasJsonPath("$.id", is("11148/TEST-0000-0011-2E07-3")),
                                    hasJsonPath("$.url", is("http://www.test.cz/"))
                            )
                    )));

            // looking for the first page with known nr. of total elements, which is 1000
            getClient(adminToken).perform(get(PREFIX_URL +
                            "?url=" + urlQuery + "&page=0&size=2&totalElements=1000"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.pageable.offset", is(0)))
                    .andExpect(jsonPath("$.pageable.pageSize", is(2)))
                    .andExpect(jsonPath("$.pageable.pageNumber", is(0)))
                    .andExpect(jsonPath("$.totalElements", is(1000)))
                    .andExpect(jsonPath("$.last", is(false)))
                    .andExpect(jsonPath("$.numberOfElements", is(2)))
                    .andExpect(jsonPath("$.totalPages", is(500)))
                    .andExpect(jsonPath("$.content", hasSize(2)));

            // search for the third page (with one item) with known nr. of elements 21
            // first 20 items are skipped
            mockedHelper.when(() ->
                            EpicHandleRestHelper.searchHandles(pidServiceUrl, PREFIX, urlParameter, 3, 10))
                    .thenReturn(new MockResponse<>(Response.Status.OK, mockedLastPage));
            getClient(adminToken).perform(get(PREFIX_URL +
                            "?url=" + urlQuery + "&page=2&totalElements=21"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.pageable.offset", is(20)))
                    .andExpect(jsonPath("$.pageable.pageSize", is(10)))
                    .andExpect(jsonPath("$.pageable.pageNumber", is(2)))
                    .andExpect(jsonPath("$.totalElements", is(21)))
                    .andExpect(jsonPath("$.last", is(true)))
                    .andExpect(jsonPath("$.numberOfElements", is(1)))
                    .andExpect(jsonPath("$.totalPages", is(3)))
                    .andExpect(jsonPath("$.content", hasSize(1)))
                    .andExpect(jsonPath("$.content", contains(
                            allOf(
                                hasJsonPath("$.id", is("11148/TEST-0000-0011-2E05-5")),
                                hasJsonPath("$.url", is("http://www.test.cz/sport"))
                            ))));

            // search for all items with any URL
            mockedHelper.when(() ->
                            EpicHandleRestHelper.countHandles(pidServiceUrl, PREFIX, "*"))
                    .thenReturn(new MockResponse<>(Response.Status.OK, mockedCountResponse));
            mockedHelper.when(() ->
                            EpicHandleRestHelper.searchHandles(pidServiceUrl, PREFIX, "*", 1, 1000))
                    .thenReturn(new MockResponse<>(Response.Status.OK, mockedAllItems));
            getClient(adminToken).perform(get(PREFIX_URL + "?size=1000&runCountSynchronously=true"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.pageable.offset", is(0)))
                    .andExpect(jsonPath("$.pageable.pageSize", is(1000)))
                    .andExpect(jsonPath("$.pageable.pageNumber", is(0)))
                    .andExpect(jsonPath("$.totalElements", is(3)))
                    .andExpect(jsonPath("$.last", is(true)))
                    .andExpect(jsonPath("$.numberOfElements", is(3)))
                    .andExpect(jsonPath("$.totalPages", is(1)))
                    .andExpect(jsonPath("$.content", hasSize(3)));
        }
    }

    @Test
    public void testCreateHandle() throws Exception {
        String requestJson = "[{\"type\":\"URL\",\"parsed_data\":\"" + TEST_URL + "\"}]";
        String mockedResponse = getResource("/org/dspace/handle/epicCreateHandleResponse.json");
        try (MockedStatic<EpicHandleRestHelper> mockedHelper = Mockito.mockStatic(EpicHandleRestHelper.class)) {
            // TEST OK Request
            mockedHelper.when(() ->
                            EpicHandleRestHelper.createHandle(pidServiceUrl, PREFIX, SUB_PREFIX, null, requestJson))
                    .thenReturn(new MockResponse<>(Response.Status.CREATED, mockedResponse));
            getClient(adminToken).perform(post(PREFIX_URL + "?prefix=TEST&url=" + TEST_URL))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", endsWith(PREFIX + "/" + SUFFIX)))
                    .andExpect(jsonPath("$.id", is(PREFIX + "/" + SUFFIX)))
                    .andExpect(jsonPath("$.url", is(TEST_URL)))
                    .andExpect(jsonPath("$._links.self.href", endsWith(PREFIX + "/" + SUFFIX)));

            // test 403 response
            getClient(nonAdminToken).perform(post(PREFIX_URL + "?prefix=TEST&url=" + TEST_URL))
                    .andExpect(status().isForbidden());

            // test 405 response (try to call put request on url without prefix)
            getClient(adminToken).perform(post(HANDLES_URL + "?url=" + TEST_URL)
                            .contentType("application/json").content("{}"))
                    .andExpect(status().isMethodNotAllowed());
        }
    }

    @Test
    public void testCreateOrUpdateHandle() throws Exception {
        String requestJson = "[{\"type\":\"URL\",\"parsed_data\":\"" + UPDATED_TEST_URL + "\"}]";
        String mockedResponse = getResource("/org/dspace/handle/epicCreateHandleResponse.json");
        // String mockedGetResponse = getResource("/org/dspace/handle/epicGetHandleResponse.json");
        try (MockedStatic<EpicHandleRestHelper> mockedHelper = Mockito.mockStatic(EpicHandleRestHelper.class)) {
            // TEST OK Request in case handle is created
            mockedHelper.when(() -> EpicHandleRestHelper.updateHandle(pidServiceUrl, PREFIX, SUFFIX, requestJson))
                    .thenReturn(new MockResponse<>(Response.Status.CREATED, mockedResponse));
            getClient(adminToken).perform(put(PREFIX_URL + "/" + SUFFIX + "?url=" + UPDATED_TEST_URL))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", endsWith(PREFIX + "/" + SUFFIX)))
                    .andExpect(jsonPath("$.id", is(PREFIX + "/" + SUFFIX)))
                    .andExpect(jsonPath("$.url", is(UPDATED_TEST_URL)))
                    .andExpect(jsonPath("$._links.self.href", endsWith(PREFIX + "/" + SUFFIX)));

            // TEST OK Request in case existing handle is updated
            mockedHelper.when(() -> EpicHandleRestHelper.updateHandle(pidServiceUrl, PREFIX, SUFFIX, requestJson))
                    .thenReturn(Response.status(Response.Status.NO_CONTENT).build());
            getClient(adminToken).perform(put(PREFIX_URL + "/" + SUFFIX + "?url=" + UPDATED_TEST_URL))
                    .andExpect(status().isNoContent());

            // test 405 response (try to call put request on prefix url)
            getClient(adminToken).perform(put(PREFIX_URL + "?url=" + UPDATED_TEST_URL)
                            .contentType("application/json").content("{}"))
                    .andExpect(status().isMethodNotAllowed());
        }
    }

    @Test
    public void testDeleteHandle() throws Exception {
        try (MockedStatic<EpicHandleRestHelper> mockedHelper = Mockito.mockStatic(EpicHandleRestHelper.class)) {
            // TEST OK Request
            mockedHelper.when(() ->
                            EpicHandleRestHelper.deleteHandle(pidServiceUrl, PREFIX, SUFFIX))
                    .thenReturn(Response.status(Response.Status.NO_CONTENT).build());
            getClient(adminToken).perform(delete(PREFIX_URL + "/" + SUFFIX))
                    .andExpect(status().isNoContent());
            // TEST 404
            mockedHelper.when(() ->
                            EpicHandleRestHelper.deleteHandle(pidServiceUrl, PREFIX, SUFFIX))
                    .thenReturn(Response.status(Response.Status.NOT_FOUND).build());
            getClient(adminToken).perform(delete(PREFIX_URL + "/" + SUFFIX))
                    .andExpect(status().isNotFound());
            // TEST 405 try to call delete request on prefix url
            getClient(adminToken).perform(delete(PREFIX_URL))
                    .andExpect(status().isMethodNotAllowed());
        }
    }

}
