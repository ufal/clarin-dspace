/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.external;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.dspace.AbstractDSpaceTest;
import org.dspace.external.provider.orcid.xml.ExpandedSearchConverter;
import org.dspace.utils.DSpace;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.cache.Cache;
import org.springframework.cache.jcache.JCacheCacheManager;

public class CachingOrcidRestConnectorTest extends AbstractDSpaceTest {

    //This token should be valid for 20 years
    private static final String sandboxToken = "4bed1e13-7792-4129-9f07-aaf7b88ba88f";

    private static final String orcid = "0000-0002-9150-2529";
    private static final String expectedLabel = "Connor, John";

    // Canned ORCID "expanded-search" response (num-found=1725, first result -> "Connor, John").
    // Used to mock the HTTP layer so the tests don't depend on the live ORCID sandbox.
    private static final String EXPANDED_SEARCH_XML = "org/dspace/external/orcid-expanded-search.xml";

    private CachingOrcidRestConnector sut;

    /**
     * Load a canned API response from the test classpath as a fresh InputStream.
     * (A new stream is returned on every call because the connector consumes/closes it.)
     */
    private InputStream cannedResponse(String resource) {
        InputStream is = getClass().getClassLoader().getResourceAsStream(resource);
        assertNotNull("Missing test resource: " + resource, is);
        return is;
    }

    /**
     * Build a canned 200 OK ORCID "expanded-search" response for the mock HTTP server, so the cache-aware
     * tests below exercise the real Spring {@code @Cacheable} bean without depending on the live ORCID sandbox.
     */
    private MockResponse cannedOrcidResponse() throws IOException {
        try (InputStream is = cannedResponse(EXPANDED_SEARCH_XML)) {
            return new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/vnd.orcid+xml")
                    .setBody(new String(is.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Before
    public void setup() {
        sut = new CachingOrcidRestConnector();
    }

    @Test(expected = RuntimeException.class)
    public void getAccessToken_badUrl() {
        String accessToken = sut.getAccessToken("secret","id", "http://example.com");
        assertNull("Expecting accessToken to be null", accessToken);
    }

    @Test(expected = RuntimeException.class)
    public void getAccessToken_badParams() {
        //expect an exception to be thrown
        sut.getAccessToken(null, null, null);
    }

    @Test(expected = RuntimeException.class)
    public void getAccessToken() {
        String accessToken = sut.getAccessToken("DEAD", "BEEF", "https://sandbox.orcid.org/oauth/token");
        assertNotNull("Expecting accessToken to be not null", accessToken);
    }

    @Test
    public void getLabel() throws Exception {
        sut = Mockito.spy(sut);
        sut.setApiURL("https://pub.sandbox.orcid.org/v3.0");
        //Mock the CachingOrcidRestConnector so that getAccessToken returns sandboxToken
        doReturn(sandboxToken).when(sut).getAccessToken(Mockito.anyString(), Mockito.anyString(), Mockito.anyString());
        //Mock the HTTP layer with a canned response so we don't depend on the live ORCID sandbox.
        doReturn(cannedResponse(EXPANDED_SEARCH_XML)).when(sut).httpGet(Mockito.anyString(), Mockito.anyString());

        String label = sut.getLabel(orcid);
        assertEquals(expectedLabel, label);
    }
    @Test
    public void search() throws Exception {
        sut = Mockito.spy(sut);
        sut.setApiURL("https://pub.sandbox.orcid.org/v3.0");
        //Mock the CachingOrcidRestConnector so that getAccessToken returns sandboxToken
        doReturn(sandboxToken).when(sut).getAccessToken(Mockito.anyString(), Mockito.anyString(), Mockito.anyString());
        //Mock the HTTP layer with a canned ORCID expanded-search response. Previously this test hit the live
        //ORCID sandbox and asserted numFound() > 1000, which flaked whenever the sandbox dataset was reset/shrunk.
        //Mocking the transport keeps the real parsing + edismax wildcard query-building path under test, but makes
        //the result deterministic.
        doReturn(cannedResponse(EXPANDED_SEARCH_XML)).when(sut).httpGet(Mockito.anyString(), Mockito.anyString());

        ExpandedSearchConverter.Results search = sut.search("joh", 0, 1);
        assertTrue("Expected a successful ORCID response, got: " + search, search.isOk());
        //'joh' is alphabetic, so the connector turns it into an edismax wildcard query ("joh || joh*") that matches
        //many authors; the canned response carries num-found=1725.
        assertEquals("Unexpected num-found for the canned ORCID response", 1725L, (long) search.numFound());
        assertEquals("Connor, John", search.results().get(0).label());
    }

    @Test
    public void search_fail() throws Exception {
        sut = Mockito.spy(sut);
        sut.setApiURL("https://pub.sandbox.orcid.org/v3.0");
        //Mock the CachingOrcidRestConnector so that getAccessToken returns an invalid token
        doReturn("FAKE").when(sut).getAccessToken(Mockito.anyString(), Mockito.anyString(),
                Mockito.anyString());
        //Simulate the ORCID API rejecting the (fake) token: every httpGet fails. Done via the mocked HTTP layer
        //so the test is deterministic and doesn't rely on the live sandbox returning a 401.
        doThrow(new IOException("simulated ORCID auth failure")).when(sut)
                .httpGet(Mockito.anyString(), Mockito.anyString());

        ExpandedSearchConverter.Results search = sut.search("joh", 0, 1);

        assertFalse(search.isOk());

        //Further calls fail too, token is stored (so getAccessToken is only resolved once)
        search = sut.search("joh", 0, 1);
        assertFalse(search.isOk());

        verify(sut, times(1)).getAccessToken(Mockito.anyString(), Mockito.anyString(), Mockito.anyString());


    }

    @Test
    public void testCachable() throws IOException {
        CachingOrcidRestConnector c = new DSpace().getServiceManager().getServiceByName(
                "CachingOrcidRestConnector", CachingOrcidRestConnector.class);

        Cache cache = prepareCache();

        assertNull(cache.get(orcid));

        /*
        I have issues trying to mock/spy when the class a spring bean modified by cglib
        doReturn(sandboxToken).when(c).getAccessToken(Mockito.anyString(), Mockito.anyString(), Mockito.anyString());
        verify(c, times(1)).getLabel(orcid);
        */

        // Drive the real Spring @Cacheable bean against a local mock HTTP server instead of the live ORCID
        // sandbox, whose dataset is periodically reset and previously caused intermittent CI failures.
        try (MockWebServer server = new MockWebServer()) {
            // Two responses are enqueued, but with caching working only the FIRST getLabel() hits the server;
            // the second is served from the "orcid-labels" cache (asserted via getRequestCount() below).
            server.enqueue(cannedOrcidResponse());
            server.enqueue(cannedOrcidResponse());

            c.setApiURL(server.url("/v3.0").toString());
            c.forceAccessToken(sandboxToken);

            String r1 = c.getLabel(orcid);
            assertEquals(expectedLabel, r1);
            String r2 = c.getLabel(orcid);
            assertEquals(expectedLabel, r2);
            //get the orcid-labels cache and verify that the label is there
            assertEquals(expectedLabel, cache.get(orcid).get());
            //caching means two getLabel() calls produced a single ORCID API request
            assertEquals("Expected getLabel to be cached after the first call", 1, server.getRequestCount());
        }
    }

    @Test
    public void testCacheableWithError() throws IOException {
        CachingOrcidRestConnector c = new DSpace().getServiceManager().getServiceByName(
                "CachingOrcidRestConnector", CachingOrcidRestConnector.class);

        Cache cache = prepareCache();
        assertNull(cache.get(orcid));

        try (MockWebServer server = new MockWebServer()) {
            //the (mock) ORCID API returns an error first, then a valid response
            server.enqueue(new MockResponse().setResponseCode(500));
            server.enqueue(cannedOrcidResponse());

            //skip init (force a token so getAccessToken/init never reaches out to the network)
            c.forceAccessToken(sandboxToken);
            c.setApiURL(server.url("/v3.0").toString());
            String r1 = c.getLabel(orcid);
            //on error, getLabel should return null
            assertNull(r1);
            //a null result must NOT be cached (see @Cacheable(unless = "#result == null"))
            assertNull(cache.get(orcid));

            //the second call gets the valid (200) response; the error never cleared the token, so no re-init needed
            String r2 = c.getLabel(orcid);
            assertEquals(expectedLabel, r2);
            //the cache should now contain a value for this id
            assertEquals(expectedLabel, cache.get(orcid).get());
        }
    }

    private Cache prepareCache() {
        //get the cacheManager from the serviceManager
        JCacheCacheManager cacheManager = new DSpace().getServiceManager().getServiceByName("cacheManager",
                JCacheCacheManager.class);

        Cache cache = cacheManager.getCache("orcid-labels");
        //each test should have a clean cache
        cache.clear();
        return cache;
    }

}
