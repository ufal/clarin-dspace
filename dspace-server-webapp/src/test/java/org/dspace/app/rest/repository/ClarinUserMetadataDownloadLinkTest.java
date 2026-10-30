/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.repository;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * The download link that {@link ClarinUserMetadataRestController} e-mails when the licence sends the token.
 */
public class ClarinUserMetadataDownloadLinkTest {

    private static final String LINK = "http://ui/bitstreams/1234/download";

    @Test
    public void linkCarriesTheRequestACopyAccessToken() {
        assertEquals(LINK + "?dtoken=d-token&accessToken=a%2Bb%3Dc",
                ClarinUserMetadataRestController.downloadLinkWithTokens(LINK, "d-token", "a+b=c"));
    }

    @Test
    public void linkWithoutAnAccessTokenIsUnchanged() {
        assertEquals(LINK + "?dtoken=d-token",
                ClarinUserMetadataRestController.downloadLinkWithTokens(LINK, "d-token", null));
        assertEquals(LINK + "?dtoken=d-token",
                ClarinUserMetadataRestController.downloadLinkWithTokens(LINK, "d-token", ""));
    }
}
