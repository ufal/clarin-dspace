/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.handle;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.ws.rs.ProcessingException;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Configuration;
import javax.ws.rs.core.Response;

import org.apache.commons.io.IOUtils;
import org.dspace.AbstractDSpaceTest;
import org.dspace.handle.factory.HandleClarinServiceFactory;
import org.dspace.handle.service.EpicPidService;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.glassfish.jersey.message.internal.OutboundJaxrsResponse;
import org.glassfish.jersey.message.internal.OutboundMessageContext;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

public class EpicPidServiceTest extends AbstractDSpaceTest {
    private EpicPidService epicPidService;
    private String pidServiceUrl;

    private static final boolean REAL_TEST = false;
    private static final String PREFIX = "11148";
    private static final String SUB_PREFIX = "TEST";
    private static final String SUFFIX = SUB_PREFIX + "-0000-0011-2E07-3";
    private static final String TEST_HANDLE = PREFIX + "/" + SUFFIX;
    private static final String TEST_URL = "http://www.test.cz";
    private static final String UPDATED_TEST_URL = "http://www.test.cz/news";

    @Before
    public void init() {
        epicPidService = HandleClarinServiceFactory.getInstance().getEpicPidService();
        ConfigurationService configurationService = DSpaceServicesFactory.getInstance().getConfigurationService();
        pidServiceUrl = configurationService.getProperty("lr.pid.service.url");
    }

    @Test
    public void testCreateHandle() throws IOException {
        if (REAL_TEST) {
            String createdHandle = epicPidService.createHandle(PREFIX, SUB_PREFIX, null, TEST_URL);
            System.out.println("Created handle: " + createdHandle);
        } else {
            String mockedResponse = getResource("/org/dspace/handle/epicCreateHandleResponse.json");
            try (MockedStatic<EpicPidServiceHelper> mockedHelper = Mockito.mockStatic(EpicPidServiceHelper.class)) {
                Map<String, String> queryParameters = Map.of("prefix", SUB_PREFIX);
                String requestJson = "[{\"type\":\"URL\",\"parsed_data\":\"" + TEST_URL + "\"}]";

                // TEST OK Request
                mockedHelper.when(() ->
                                EpicPidServiceHelper.postCommand(pidServiceUrl, PREFIX, queryParameters, requestJson))
                        .thenReturn(new MockResponse<>(Response.Status.CREATED, mockedResponse));
                assertEquals(TEST_HANDLE, epicPidService.createHandle(PREFIX, SUB_PREFIX, null, TEST_URL));

                // test 401 response
                mockedHelper.when(() ->
                                EpicPidServiceHelper.postCommand(pidServiceUrl, PREFIX, queryParameters, requestJson))
                        .thenReturn(Response.status(Response.Status.UNAUTHORIZED).build());
                assertThrows("HTTP 401 Unauthorized", WebApplicationException.class, () ->
                        epicPidService.createHandle(PREFIX, SUB_PREFIX, null, TEST_URL));
            }
        }
    }

    @Test
    public void testUpdateHandle() throws IOException {
        if (REAL_TEST) {
            epicPidService.updateHandle(PREFIX, SUFFIX, UPDATED_TEST_URL);
        } else {
            String requestJson = "[{\"type\":\"URL\",\"parsed_data\":\"" + UPDATED_TEST_URL + "\"}]";
            try (MockedStatic<EpicPidServiceHelper> mockedHelper = Mockito.mockStatic(EpicPidServiceHelper.class)) {
                // TEST OK Request
                mockedHelper.when(() ->
                                EpicPidServiceHelper.putCommand(pidServiceUrl, PREFIX, SUFFIX, requestJson))
                        .thenReturn(Response.status(Response.Status.NO_CONTENT).build());
                epicPidService.updateHandle(PREFIX, SUFFIX, UPDATED_TEST_URL);
                // TEST 405
                mockedHelper.when(() ->
                                EpicPidServiceHelper.putCommand(pidServiceUrl, "invalid", SUFFIX, requestJson))
                        .thenReturn(Response.status(Response.Status.METHOD_NOT_ALLOWED).build());

                assertThrows("HTTP 405 Method Not Allowed", WebApplicationException.class, () ->
                        epicPidService.updateHandle("invalid", SUFFIX, UPDATED_TEST_URL));
            }
        }
    }

    @Test
    public void testDeleteHandle() throws IOException {
        if (REAL_TEST) {
            epicPidService.deleteHandle(PREFIX, SUFFIX);
        } else {
            try (MockedStatic<EpicPidServiceHelper> mockedHelper = Mockito.mockStatic(EpicPidServiceHelper.class)) {
                // TEST OK Request
                mockedHelper.when(() ->
                                EpicPidServiceHelper.deleteCommand(pidServiceUrl, PREFIX, SUFFIX))
                        .thenReturn(Response.status(Response.Status.NO_CONTENT).build());
                epicPidService.deleteHandle(PREFIX, SUFFIX);
                // TEST 404
                mockedHelper.when(() ->
                                EpicPidServiceHelper.deleteCommand(pidServiceUrl, PREFIX, SUFFIX))
                        .thenReturn(Response.status(Response.Status.NOT_FOUND).build());

                assertThrows("HTTP 404 Not Found", WebApplicationException.class, () ->
                        epicPidService.deleteHandle(PREFIX, SUFFIX));
            }
        }
    }

    @Test
    public void testResolveURLForHandle() throws IOException {
        if (REAL_TEST) {
            System.out.println(epicPidService.resolveURLForHandle(PREFIX, SUFFIX));
        } else {
            String mockedResponse =  getResource("/org/dspace/handle/epicGetHandleResponse.json");
            try (MockedStatic<EpicPidServiceHelper> mockedHelper = Mockito.mockStatic(EpicPidServiceHelper.class)) {
                // test OK response
                mockedHelper.when(() -> EpicPidServiceHelper.getCommand(pidServiceUrl, PREFIX, SUFFIX))
                        .thenReturn(new MockResponse<>(Response.Status.OK, mockedResponse));
                assertEquals(TEST_URL, epicPidService.resolveURLForHandle(PREFIX, SUFFIX));

                // test 404 response
                mockedHelper.when(() -> EpicPidServiceHelper.getCommand(pidServiceUrl, PREFIX, SUFFIX))
                        .thenReturn(Response.status(Response.Status.NOT_FOUND).build());
                assertNull(epicPidService.resolveURLForHandle(PREFIX, SUFFIX));
            }
        }
    }

    @Test
    public void testSearch() throws IOException {
        String urlQuery = "www.test.cz";
        if (REAL_TEST) {
            List<EpicPidService.Handle> handleList = epicPidService.search(PREFIX, urlQuery, 10, 1);
            System.out.println(handleList);
            handleList.forEach(handle -> System.out.println("handle:" + handle.getHandle() + " -> " + handle.getUrl()));
        } else {
            String mockedResponse =  getResource("/org/dspace/handle/epicGetAllResponse.json");
            try (MockedStatic<EpicPidServiceHelper> mockedHelper = Mockito.mockStatic(EpicPidServiceHelper.class)) {
                // test OK response
                Map<String,String> queryParameters = Map.of("URL", "*" + urlQuery + "*", "limit", "10", "page", "1" );
                Map<String, String> headers = Map.of("Depth", "1");

                mockedHelper.when(() ->
                                EpicPidServiceHelper.getAllCommand(pidServiceUrl, PREFIX, headers, queryParameters))
                        .thenReturn(new MockResponse<>(Response.Status.OK, mockedResponse));
                List<EpicPidService.Handle> pids = epicPidService.search(PREFIX, urlQuery, 10, 1);

                EpicPidService.Handle handle1 = new EpicPidService.Handle(
                        "11148/TEST-0000-0011-2E07-3",
                        "http://www.test.cz/");
                EpicPidService.Handle handle2 = new EpicPidService.Handle(
                        "11148/TEST-0000-0011-2E03-7",
                        "http://www.test.cz/news");
                assertEquals(2, pids.size());
                assertThat(pids, containsInAnyOrder(handle1, handle2));
                // test empty response
                mockedHelper.when(() ->
                                EpicPidServiceHelper.getAllCommand(pidServiceUrl, PREFIX, headers, queryParameters))
                        .thenReturn(new MockResponse<>(Response.Status.OK, "{}"));
                List<EpicPidService.Handle> handles = epicPidService.search(PREFIX, urlQuery, 10, 1);
                assertEquals(0, handles.size());
                // test 404 response
                mockedHelper.when(() ->
                                EpicPidServiceHelper.getAllCommand(pidServiceUrl, PREFIX, headers, queryParameters))
                        .thenReturn(Response.status(Response.Status.NOT_FOUND).build());
                assertThrows("HTTP 404 Not Found", WebApplicationException.class, () ->
                        epicPidService.search(PREFIX, urlQuery, 10, 1));
            }
        }
    }

    @Test
    public void testCount() throws IOException {
        String urlQuery = "www.test.cz";
        if (REAL_TEST) {
            int count = epicPidService.count(PREFIX, urlQuery);
            System.out.println("Overall count is " + count);
        } else {
            String mockedResponse =  getResource("/org/dspace/handle/epicCountResponse.json");
            try (MockedStatic<EpicPidServiceHelper> mockedHelper = Mockito.mockStatic(EpicPidServiceHelper.class)) {
                // test OK response
                Map<String, String> queryParameters = Map.of("URL", "*" + urlQuery + "*");
                mockedHelper.when(() ->
                                EpicPidServiceHelper.getAllCommand(pidServiceUrl, PREFIX, null, queryParameters))
                        .thenReturn(new MockResponse<>(Response.Status.OK, mockedResponse));
                assertEquals(3, epicPidService.count(PREFIX, urlQuery));
                // test empty response
                mockedHelper.when(() ->
                                EpicPidServiceHelper.getAllCommand(pidServiceUrl, PREFIX, null, queryParameters))
                        .thenReturn(new MockResponse<>(Response.Status.OK, "[]"));
                assertEquals(0, epicPidService.count(PREFIX, urlQuery));
            }
        }
    }

    public static Response mockResponse(Response.ResponseBuilder respBuilder, String result) {
        Response mockResponse = Mockito.spy(respBuilder.build());
        Mockito.when(mockResponse.readEntity(Mockito.any(Class.class))).thenReturn(result);
        return mockResponse;
    }

    private static class MockResponse<T> extends OutboundJaxrsResponse {
        T responseBody;

        MockResponse(Status status, T responseBody) {
            super(status, new OutboundMessageContext((Configuration) null));
            this.responseBody = responseBody;
        }

        @Override
        public <E> E readEntity(Class<E> type) throws ProcessingException {
            return (E) responseBody;
        }
    }

    private static String getResource(String resourcePath) throws IOException {
        return IOUtils.toString(
                Objects.requireNonNull(EpicPidServiceHelper.class.getResourceAsStream(resourcePath)),
                StandardCharsets.UTF_8);
    }

}
