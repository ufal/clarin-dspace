/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.utils;

import static javax.servlet.http.HttpServletResponse.SC_NOT_MODIFIED;
import static javax.servlet.http.HttpServletResponse.SC_PRECONDITION_FAILED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import javax.servlet.http.HttpServletRequest;

import org.apache.tomcat.util.http.FastHttpDateFormat;
import org.dspace.app.rest.filter.IgnorableRangeRequestFilter;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Tests the conditional request headers handled by {@link HttpHeadersInitializer}: the ETag we
 * send out and the If-Match, If-None-Match and If-Range headers a client sends back.
 */
public class HttpHeadersInitializerTest {

    private static final String CHECKSUM = "184620a503489ab88e9e678feea682fa";
    private static final String QUOTED_CHECKSUM = "\"" + CHECKSUM + "\"";
    private static final String OTHER_QUOTED_CHECKSUM = "\"deadbeefdeadbeefdeadbeefdeadbeef\"";
    private static final long LAST_MODIFIED = 1_600_000_000_123L;

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @Before
    public void setUp() {
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
    }

    private HttpHeadersInitializer initializerFor(HttpServletRequest httpRequest) {
        return new HttpHeadersInitializer()
            .withFileName("test.txt")
            .withChecksum(CHECKSUM)
            .withLength(10)
            .withMimetype("text/plain")
            .withLastModified(LAST_MODIFIED)
            .with(httpRequest)
            .with(response);
    }

    private HttpHeadersInitializer initializer() {
        return initializerFor(request);
    }

    /**
     * Ask for a range with this If-Range value, and report whether the Range header was still there
     * when the filter chain handed the request on.
     */
    private boolean rangeSurvives(String ifRange) throws Exception {
        return rangeSurvives(ifRange, LAST_MODIFIED);
    }

    private boolean rangeSurvives(String ifRange, long lastModified) throws Exception {
        request.addHeader("Range", "bytes=1-3");
        request.addHeader("If-Range", ifRange);
        MockFilterChain chain = new MockFilterChain();
        new IgnorableRangeRequestFilter().doFilter(request, response, chain);
        HttpServletRequest filtered = (HttpServletRequest) chain.getRequest();

        assertTrue(initializerFor(filtered).withLastModified(lastModified).isValid());
        return filtered.getHeader("Range") != null;
    }

    @Test
    public void etagIsSentQuoted() throws Exception {
        assertEquals(QUOTED_CHECKSUM, initializer().initialiseHeaders().getFirst("ETag"));
    }

    @Test
    public void ifMatchOnTheEtagWeSentIsAccepted() throws Exception {
        request.addHeader("If-Match", QUOTED_CHECKSUM);

        assertTrue(initializer().isValid());
    }

    @Test
    public void ifMatchOnAnUnquotedChecksumIsAccepted() throws Exception {
        request.addHeader("If-Match", CHECKSUM);

        assertTrue(initializer().isValid());
    }

    @Test
    public void ifMatchWithAWildcardIsAccepted() throws Exception {
        request.addHeader("If-Match", "*");

        assertTrue(initializer().isValid());
    }

    @Test
    public void ifMatchListingOurEtagAmongOthersIsAccepted() throws Exception {
        request.addHeader("If-Match", OTHER_QUOTED_CHECKSUM + ", " + QUOTED_CHECKSUM);

        assertTrue(initializer().isValid());
    }

    @Test
    public void ifMatchOnAWeakEtagIsRejected() throws Exception {
        request.addHeader("If-Match", "W/" + QUOTED_CHECKSUM);

        assertFalse(initializer().isValid());
        assertEquals(SC_PRECONDITION_FAILED, response.getStatus());
    }

    @Test
    public void ifMatchOnAnotherEtagFailsWithPreconditionFailed() throws Exception {
        request.addHeader("If-Match", OTHER_QUOTED_CHECKSUM);

        assertFalse(initializer().isValid());
        assertEquals(SC_PRECONDITION_FAILED, response.getStatus());
    }

    @Test
    public void ifNoneMatchOnTheEtagWeSentReturnsNotModified() throws Exception {
        request.addHeader("If-None-Match", QUOTED_CHECKSUM);

        assertFalse(initializer().isValid());
        assertEquals(SC_NOT_MODIFIED, response.getStatus());
        assertEquals(QUOTED_CHECKSUM, response.getHeader("ETag"));
    }

    @Test
    public void ifNoneMatchOnAWeakEtagReturnsNotModified() throws Exception {
        request.addHeader("If-None-Match", "W/" + QUOTED_CHECKSUM);

        assertFalse(initializer().isValid());
        assertEquals(SC_NOT_MODIFIED, response.getStatus());
    }

    @Test
    public void ifNoneMatchOnAnotherEtagServesTheFile() throws Exception {
        request.addHeader("If-None-Match", OTHER_QUOTED_CHECKSUM);

        assertTrue(initializer().isValid());
    }

    @Test
    public void ifModifiedSinceAlsoReturnsNotModified() throws Exception {
        request.addHeader("If-Modified-Since", FastHttpDateFormat.formatDate(LAST_MODIFIED));

        assertFalse(initializer().isValid());
        assertEquals(SC_NOT_MODIFIED, response.getStatus());
        assertEquals(QUOTED_CHECKSUM, response.getHeader("ETag"));
    }

    @Test
    public void notModifiedRepeatsTheCachingHeaders() throws Exception {
        request.addHeader("If-None-Match", QUOTED_CHECKSUM);

        assertFalse(initializer().isValid());
        assertEquals(FastHttpDateFormat.formatDate(LAST_MODIFIED), response.getHeader("Last-Modified"));
        assertEquals("private,no-cache", response.getHeader("Cache-Control"));
        assertNotNull(response.getHeader("Expires"));
    }

    @Test
    public void notModifiedWithoutAChecksumSendsNoEtag() throws Exception {
        request.addHeader("If-None-Match", "*");

        assertFalse(initializer().withChecksum(null).isValid());
        assertEquals(SC_NOT_MODIFIED, response.getStatus());
        assertNull(response.getHeader("ETag"));
    }

    @Test
    public void ifMatchIsWeighedBeforeIfNoneMatch() throws Exception {
        request.addHeader("If-Match", OTHER_QUOTED_CHECKSUM);
        request.addHeader("If-None-Match", QUOTED_CHECKSUM);

        assertFalse(initializer().isValid());
        assertEquals(SC_PRECONDITION_FAILED, response.getStatus());
    }

    @Test
    public void ifUnmodifiedSinceOlderThanTheFileFailsWithPreconditionFailed() throws Exception {
        request.addHeader("If-Unmodified-Since", FastHttpDateFormat.formatDate(LAST_MODIFIED - 10_000L));

        assertFalse(initializer().isValid());
        assertEquals(SC_PRECONDITION_FAILED, response.getStatus());
    }

    @Test
    public void aConditionalDateWeCannotReadIsIgnored() throws Exception {
        request.addHeader("If-Modified-Since", "garbage");
        request.addHeader("If-Unmodified-Since", "garbage");

        assertTrue(initializer().isValid());
    }

    @Test
    public void ifMatchWithAWildcardInsideAListIsRejected() throws Exception {
        request.addHeader("If-Match", OTHER_QUOTED_CHECKSUM + ", *");

        assertFalse(initializer().isValid());
        assertEquals(SC_PRECONDITION_FAILED, response.getStatus());
    }

    @Test
    public void ifRangeOnTheEtagWeSentKeepsTheRange() throws Exception {
        assertTrue(rangeSurvives(QUOTED_CHECKSUM));
    }

    @Test
    public void ifRangeOnTheDateWeSentKeepsTheRange() throws Exception {
        assertTrue(rangeSurvives(FastHttpDateFormat.formatDate(LAST_MODIFIED)));
    }

    @Test
    public void ifRangeOnAnUnquotedChecksumKeepsTheRange() throws Exception {
        assertTrue(rangeSurvives(CHECKSUM));
    }

    @Test
    public void ifRangeOnAnotherEtagDropsTheRange() throws Exception {
        assertFalse(rangeSurvives(OTHER_QUOTED_CHECKSUM));
    }

    @Test
    public void ifRangeOnAnotherDateDropsTheRange() throws Exception {
        assertFalse(rangeSurvives(FastHttpDateFormat.formatDate(LAST_MODIFIED - 10_000L)));
    }

    @Test
    public void ifRangeOnADateDropsTheRangeWhenTheFileHasNoDate() throws Exception {
        assertFalse(rangeSurvives(FastHttpDateFormat.formatDate(0L), 0));
    }

    @Test
    public void ifRangeWithoutARangeHeaderChangesNothing() throws Exception {
        request.addHeader("If-Range", OTHER_QUOTED_CHECKSUM);

        assertTrue(initializer().isValid());
    }
}
