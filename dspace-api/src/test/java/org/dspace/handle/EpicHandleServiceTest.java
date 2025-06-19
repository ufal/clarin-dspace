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
import java.util.Objects;
import javax.ws.rs.ProcessingException;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Configuration;
import javax.ws.rs.core.Response;

import org.apache.commons.io.IOUtils;
import org.dspace.AbstractDSpaceTest;
import org.dspace.handle.factory.HandleClarinServiceFactory;
import org.dspace.handle.service.EpicHandleService;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.glassfish.jersey.message.internal.OutboundJaxrsResponse;
import org.glassfish.jersey.message.internal.OutboundMessageContext;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

public class EpicHandleServiceTest extends AbstractDSpaceTest {
    private EpicHandleService epicHandleService;
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
        epicHandleService = HandleClarinServiceFactory.getInstance().getEpicHandleService();
        ConfigurationService configurationService = DSpaceServicesFactory.getInstance().getConfigurationService();
        pidServiceUrl = configurationService.getProperty("lr.pid.service.url");
    }

    @Test
    public void testCreateHandle() throws IOException {
        if (REAL_TEST) {
            String createdHandle = epicHandleService.createHandle(PREFIX, SUB_PREFIX, null, TEST_URL);
            System.out.println("Created handle: " + createdHandle);
        } else {
            String mockedResponse = getResource("/org/dspace/handle/epicCreateHandleResponse.json");
            try (MockedStatic<EpicHandleRestHelper> mockedHelper = Mockito.mockStatic(EpicHandleRestHelper.class)) {
                String requestJson = "[{\"type\":\"URL\",\"parsed_data\":\"" + TEST_URL + "\"}]";

                // TEST OK Request
                mockedHelper.when(() ->
                                EpicHandleRestHelper.createHandle(pidServiceUrl, PREFIX, SUB_PREFIX, null, requestJson))
                        .thenReturn(new MockResponse<>(Response.Status.CREATED, mockedResponse));
                assertEquals(TEST_HANDLE, epicHandleService.createHandle(PREFIX, SUB_PREFIX, null, TEST_URL));

                // test 401 response
                mockedHelper.when(() ->
                                EpicHandleRestHelper.createHandle(pidServiceUrl, PREFIX, SUB_PREFIX, null, requestJson))
                        .thenReturn(Response.status(Response.Status.UNAUTHORIZED).build());
                assertThrows("HTTP 401 Unauthorized", WebApplicationException.class, () ->
                        epicHandleService.createHandle(PREFIX, SUB_PREFIX, null, TEST_URL));
            }
        }
    }

    @Test
    public void testUpdateHandle() throws IOException {
        if (REAL_TEST) {
            epicHandleService.updateHandle(PREFIX, SUFFIX, UPDATED_TEST_URL);
        } else {
            String requestJson = "[{\"type\":\"URL\",\"parsed_data\":\"" + UPDATED_TEST_URL + "\"}]";
            try (MockedStatic<EpicHandleRestHelper> mockedHelper = Mockito.mockStatic(EpicHandleRestHelper.class)) {
                // TEST OK Request
                mockedHelper.when(() ->
                                EpicHandleRestHelper.updateHandle(pidServiceUrl, PREFIX, SUFFIX, requestJson))
                        .thenReturn(Response.status(Response.Status.NO_CONTENT).build());
                epicHandleService.updateHandle(PREFIX, SUFFIX, UPDATED_TEST_URL);
                // TEST 405
                mockedHelper.when(() ->
                                EpicHandleRestHelper.updateHandle(pidServiceUrl, "invalid", SUFFIX, requestJson))
                        .thenReturn(Response.status(Response.Status.METHOD_NOT_ALLOWED).build());

                assertThrows("HTTP 405 Method Not Allowed", WebApplicationException.class, () ->
                        epicHandleService.updateHandle("invalid", SUFFIX, UPDATED_TEST_URL));
            }
        }
    }

    @Test
    public void testDeleteHandle() throws IOException {
        if (REAL_TEST) {
            epicHandleService.deleteHandle(PREFIX, SUFFIX);
        } else {
            try (MockedStatic<EpicHandleRestHelper> mockedHelper = Mockito.mockStatic(EpicHandleRestHelper.class)) {
                // TEST OK Request
                mockedHelper.when(() ->
                                EpicHandleRestHelper.deleteHandle(pidServiceUrl, PREFIX, SUFFIX))
                        .thenReturn(Response.status(Response.Status.NO_CONTENT).build());
                epicHandleService.deleteHandle(PREFIX, SUFFIX);
                // TEST 404
                mockedHelper.when(() ->
                                EpicHandleRestHelper.deleteHandle(pidServiceUrl, PREFIX, SUFFIX))
                        .thenReturn(Response.status(Response.Status.NOT_FOUND).build());

                assertThrows("HTTP 404 Not Found", WebApplicationException.class, () ->
                        epicHandleService.deleteHandle(PREFIX, SUFFIX));
            }
        }
    }

    @Test
    public void testResolveURLForHandle() throws IOException {
        if (REAL_TEST) {
            System.out.println(epicHandleService.resolveURLForHandle(PREFIX, SUFFIX));
        } else {
            String mockedResponse =  getResource("/org/dspace/handle/epicGetHandleResponse.json");
            try (MockedStatic<EpicHandleRestHelper> mockedHelper = Mockito.mockStatic(EpicHandleRestHelper.class)) {
                // test OK response
                mockedHelper.when(() -> EpicHandleRestHelper.getHandle(pidServiceUrl, PREFIX, SUFFIX))
                        .thenReturn(new MockResponse<>(Response.Status.OK, mockedResponse));
                assertEquals(TEST_URL, epicHandleService.resolveURLForHandle(PREFIX, SUFFIX));

                // test 404 response
                mockedHelper.when(() -> EpicHandleRestHelper.getHandle(pidServiceUrl, PREFIX, SUFFIX))
                        .thenReturn(Response.status(Response.Status.NOT_FOUND).build());
                assertNull(epicHandleService.resolveURLForHandle(PREFIX, SUFFIX));
            }
        }
    }

    @Test
    public void testSearchHandles() throws IOException {
        String urlQuery = "www.test.cz";
        if (REAL_TEST) {
            List<EpicHandleService.Handle> handleList = epicHandleService.searchHandles(PREFIX, urlQuery, 1, 10);
            System.out.println(handleList);
            handleList.forEach(handle -> System.out.println("handle:" + handle.getHandle() + " -> " + handle.getUrl()));
        } else {
            String mockedResponse =  getResource("/org/dspace/handle/epicGetAllResponse.json");
            try (MockedStatic<EpicHandleRestHelper> mockedHelper = Mockito.mockStatic(EpicHandleRestHelper.class)) {
                String urlParameter = "*" + urlQuery + "*";

                // test OK response
                mockedHelper.when(() ->
                                EpicHandleRestHelper.searchHandles(pidServiceUrl, PREFIX, urlParameter, 1, 10))
                        .thenReturn(new MockResponse<>(Response.Status.OK, mockedResponse));
                List<EpicHandleService.Handle> pids = epicHandleService.searchHandles(PREFIX, urlQuery, 1, 10);

                EpicHandleService.Handle handle1 = new EpicHandleService.Handle(
                        "11148/TEST-0000-0011-2E07-3",
                        "http://www.test.cz/");
                EpicHandleService.Handle handle2 = new EpicHandleService.Handle(
                        "11148/TEST-0000-0011-2E03-7",
                        "http://www.test.cz/news");
                assertEquals(2, pids.size());
                assertThat(pids, containsInAnyOrder(handle1, handle2));
                // test empty response
                mockedHelper.when(() ->
                                EpicHandleRestHelper.searchHandles(pidServiceUrl, PREFIX, urlParameter, 1, 10))
                        .thenReturn(new MockResponse<>(Response.Status.OK, "{}"));
                List<EpicHandleService.Handle> handles = epicHandleService.searchHandles(PREFIX, urlQuery, 1, 10);
                assertEquals(0, handles.size());
                // test 404 response
                mockedHelper.when(() ->
                                EpicHandleRestHelper.searchHandles(pidServiceUrl, PREFIX, urlParameter, 1, 10))
                        .thenReturn(Response.status(Response.Status.NOT_FOUND).build());
                assertThrows("HTTP 404 Not Found", WebApplicationException.class, () ->
                        epicHandleService.searchHandles(PREFIX, urlQuery, 1, 10));
            }
        }
    }

    @Test
    public void testCountHandles() throws IOException {
        String urlQuery = "www.test.cz";
        if (REAL_TEST) {
            int count = epicHandleService.countHandles(PREFIX, urlQuery);
            System.out.println("Overall count is " + count);
        } else {
            String mockedResponse =  getResource("/org/dspace/handle/epicCountResponse.json");
            try (MockedStatic<EpicHandleRestHelper> mockedHelper = Mockito.mockStatic(EpicHandleRestHelper.class)) {
                // test OK response
                mockedHelper.when(() ->
                                EpicHandleRestHelper.countHandles(pidServiceUrl, PREFIX, "*" + urlQuery + "*"))
                        .thenReturn(new MockResponse<>(Response.Status.OK, mockedResponse));
                assertEquals(3, epicHandleService.countHandles(PREFIX, urlQuery));
                // test empty response
                mockedHelper.when(() ->
                                EpicHandleRestHelper.countHandles(pidServiceUrl, PREFIX, "*" + urlQuery + "*"))
                        .thenReturn(new MockResponse<>(Response.Status.OK, "[]"));
                assertEquals(0, epicHandleService.countHandles(PREFIX, urlQuery));
            }
        }
    }

    public static class MockResponse<T> extends OutboundJaxrsResponse {
        T responseBody;

        public MockResponse(Status status, T responseBody) {
            super(status, new OutboundMessageContext((Configuration) null));
            this.responseBody = responseBody;
        }

        @Override
        public <E> E readEntity(Class<E> type) throws ProcessingException {
            return (E) responseBody;
        }
    }

    public static String getResource(String resourcePath) throws IOException {
        return IOUtils.toString(
                Objects.requireNonNull(EpicHandleRestHelper.class.getResourceAsStream(resourcePath)),
                StandardCharsets.UTF_8);
    }

}
