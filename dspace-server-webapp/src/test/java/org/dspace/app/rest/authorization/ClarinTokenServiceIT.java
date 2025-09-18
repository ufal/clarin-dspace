/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.authorization;

import static org.dspace.administer.ClarinTokenUtils.getTokenId;
import static org.dspace.content.clarin.ClarinToken.MASKED_TOKEN_SIZE;
import static org.dspace.content.clarin.ClarinToken.UNMASKED_TOKEN_SIZE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLException;
import java.text.ParseException;
import java.util.Date;
import java.util.UUID;

import org.dspace.app.rest.test.AbstractControllerIntegrationTest;
import org.dspace.content.clarin.ClarinToken;
import org.dspace.content.factory.ClarinServiceFactory;
import org.dspace.content.service.clarin.ClarinTokenService;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.junit.Before;
import org.junit.Test;

public class ClarinTokenServiceIT extends AbstractControllerIntegrationTest {

    private ClarinTokenService clarinTokenService;
    private UUID ePersonID;
    private Date expirationTimeIn24Hours;

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();
        ConfigurationService config = DSpaceServicesFactory.getInstance().getConfigurationService();
        // Set encryption/decryption secret key for the test
        config.setProperty(ClarinToken.PROPERTY_ENCRYPTION_SECRET, "P/uBJYtuKbuG2kHdukCp0nbnI5EZz6mg6Qtuyo8I+18=");

        clarinTokenService = ClarinServiceFactory.getInstance().getClarinTokenService();
        ePersonID = this.eperson.getID();
        // expiration time set to 24 hours
        expirationTimeIn24Hours = new Date(new Date().getTime() + 1000 * 60 * 60 * 24);
    }

    @Test
    public void testRequestWithAdminToken() throws Exception {
        context.setCurrentUser(admin);
        String token = clarinTokenService.createToken(context, admin.getID(), expirationTimeIn24Hours);
        assertToken(token, admin.getID());

        getClient(token).perform(get("/api/system/processes"))
                .andExpect(status().isOk());
    }

    @Test
    public void testRequestWithExpiredToken() throws Exception {
        context.setCurrentUser(admin);
        // expiration time set to now (token with this expiration is immediately expired)
        String token = clarinTokenService.createToken(context, admin.getID(), new Date());
        assertNotNull(token);
        assertToken(token, admin.getID());

        getClient(token).perform(get("/api/system/processes"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void testRequestWithNonAdminToken() throws Exception {
        String token = clarinTokenService.createToken(context, ePersonID, expirationTimeIn24Hours);
        assertNotNull(token);
        assertToken(token, ePersonID);

        getClient(token).perform(get("/api/system/processes"))
                .andExpect(status().isForbidden());
    }

    @Test
    public void testRequestWithRemovedToken() throws Exception {
        String token = clarinTokenService.createToken(context, ePersonID, expirationTimeIn24Hours);
        assertNotNull(token);
        assertToken(token, ePersonID);

        context.setCurrentUser(admin);
        clarinTokenService.delete(context, token);

        getClient(token).perform(get("/api/system/processes"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void testRequestWithRemovedUserTokens() throws Exception {
        String token = clarinTokenService.createToken(context, ePersonID, expirationTimeIn24Hours);
        assertNotNull(token);
        assertToken(token, ePersonID);

        context.setCurrentUser(admin);
        clarinTokenService.delete(context, ePersonID);

        getClient(token).perform(get("/api/system/processes"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void testRequestWithInvalidToken() throws Exception {
        context.setCurrentUser(admin);
        String token = clarinTokenService.createToken(context,admin.getID(), expirationTimeIn24Hours);
        assertNotNull(token);
        assertToken(token, admin.getID());

        String invalidToken = getMaskedToken(token);

        getClient(invalidToken).perform(get("/api/system/processes"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void testRequestsWithTwoTokens() throws Exception {
        context.setCurrentUser(admin);
        String token1 = clarinTokenService.createToken(context, admin.getID(), expirationTimeIn24Hours);
        String token2 = clarinTokenService.createToken(context, admin.getID(), expirationTimeIn24Hours);
        assertNotNull(token1);
        assertNotNull(token2);
        assertToken(token1, admin.getID());
        assertToken(token2, admin.getID());

        getClient(token1).perform(get("/api/system/processes"))
                .andExpect(status().isOk());

        getClient(token2).perform(get("/api/system/processes"))
                .andExpect(status().isOk());
    }

    private void assertToken(String token, UUID personID) throws SQLException, ParseException {
        ClarinToken pat = clarinTokenService.find(context, getTokenId(token));
        assertNotNull(pat);
        assertEquals(personID, pat.getEPersonID());
        assertFalse(pat.getSignKey().isBlank());
    }

    static String getMaskedToken(String token) {
        String maskedTokenPart = "*".repeat(MASKED_TOKEN_SIZE);
        String unmaskedTokenPart = token.substring(token.length() - UNMASKED_TOKEN_SIZE);
        return maskedTokenPart + unmaskedTokenPart;
    }
}
