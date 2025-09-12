/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.authorization;

import static org.dspace.content.clarin.PersonalAccessToken.UNMASKED_TOKEN_SIZE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLException;
import java.util.Date;
import java.util.UUID;

import org.dspace.app.rest.test.AbstractControllerIntegrationTest;
import org.dspace.authorize.AuthorizeException;
import org.dspace.content.clarin.PersonalAccessToken;
import org.dspace.content.factory.ClarinServiceFactory;
import org.dspace.content.service.clarin.PersonalAccessTokenService;
import org.junit.Before;
import org.junit.Test;

public class PersonalAccessTokenServiceIT extends AbstractControllerIntegrationTest {

    private PersonalAccessTokenService personalAccessTokenService;
    private UUID ePersonID;
    private Date expirationTimeIn24Hours;

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();
        personalAccessTokenService = ClarinServiceFactory.getInstance().getPersonalAccessTokenService();
        ePersonID = this.eperson.getID();
        // expiration time set to 24 hours
        expirationTimeIn24Hours = new Date(new Date().getTime() + 1000 * 60 * 60 * 24);
    }

    @Test
    public void testRequestWithAdminToken() throws Exception {
        context.setCurrentUser(admin);
        String token = personalAccessTokenService.createToken(context, admin.getID(), expirationTimeIn24Hours);
        assertNotNull(token);
        assertToken(admin.getID());

        getClient(token).perform(get("/api/system/processes"))
                .andExpect(status().isOk());
    }

    @Test
    public void testRequestWithExpiredToken() throws Exception {
        context.setCurrentUser(admin);
        // expiration time set to now (token with this expiration is immediately expired)
        String token = personalAccessTokenService.createToken(context, admin.getID(), new Date());
        assertNotNull(token);
        assertToken(admin.getID());

        getClient(token).perform(get("/api/system/processes"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void testRequestWithNonAdminToken() throws Exception {
        String token = personalAccessTokenService.createToken(context, ePersonID, expirationTimeIn24Hours);
        assertNotNull(token);
        assertToken(ePersonID);

        getClient(token).perform(get("/api/system/processes"))
                .andExpect(status().isForbidden());
    }

    @Test
    public void testRequestWithRemovedToken() throws Exception {
        String token = personalAccessTokenService.createToken(context, ePersonID, expirationTimeIn24Hours);
        assertNotNull(token);
        assertToken(ePersonID);

        context.setCurrentUser(admin);
        personalAccessTokenService.delete(context, ePersonID);

        getClient(token).perform(get("/api/system/processes"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void testRequestWithInvalidToken() throws Exception {
        context.setCurrentUser(admin);
        String token = personalAccessTokenService.createToken(context,admin.getID(), expirationTimeIn24Hours);
        assertNotNull(token);
        assertToken(admin.getID());

        String invalidToken = getMaskedToken(token);

        getClient(invalidToken).perform(get("/api/system/processes"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void testRequestWithUpdatedToken() throws Exception {
        context.setCurrentUser(admin);
        String token1 = personalAccessTokenService.createToken(context, admin.getID(), expirationTimeIn24Hours);
        String token2 = personalAccessTokenService.createToken(context, admin.getID(), expirationTimeIn24Hours);
        assertNotNull(token1);
        assertNotNull(token2);
        assertToken(admin.getID());

        getClient(token1).perform(get("/api/system/processes"))
                .andExpect(status().isUnauthorized());

        getClient(token2).perform(get("/api/system/processes"))
                .andExpect(status().isOk());
    }

    private void assertToken(UUID ePersonID) throws SQLException, AuthorizeException {
        PersonalAccessToken pat = personalAccessTokenService.findByEPersonID(context, ePersonID);
        assertNotNull(pat);
        assertEquals(ePersonID, pat.getEPersonID());
        assertFalse(pat.getMacSecret().isBlank());
        assertFalse(pat.getAesKey().isBlank());
    }

    static String getMaskedToken(String token) {
        String maskedTokenPart = "*".repeat(token.length() - UNMASKED_TOKEN_SIZE);
        String unmaskedTokenPart = token.substring(token.length() - UNMASKED_TOKEN_SIZE);
        return maskedTokenPart + unmaskedTokenPart;
    }
}
