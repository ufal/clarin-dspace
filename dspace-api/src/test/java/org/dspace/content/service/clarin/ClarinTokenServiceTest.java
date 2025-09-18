/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.content.service.clarin;

import static org.dspace.administer.ClarinTokenUtils.getTokenId;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import java.sql.SQLException;
import java.text.ParseException;
import java.util.Date;
import java.util.UUID;
import javax.ws.rs.BadRequestException;

import org.dspace.AbstractIntegrationTestWithDatabase;
import org.dspace.authorize.AuthorizeException;
import org.dspace.content.clarin.ClarinToken;
import org.dspace.content.factory.ClarinServiceFactory;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.junit.Before;
import org.junit.Test;

public class ClarinTokenServiceTest extends AbstractIntegrationTestWithDatabase {

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
    public void testCreateToken() throws Exception {
        String token = clarinTokenService.createToken(context, ePersonID, expirationTimeIn24Hours);
        assertToken(token, ePersonID);
    }

    @Test
    public void testCreateTokenByAdmin() throws Exception {
        context.setCurrentUser(admin);
        String token = clarinTokenService.createToken(context, ePersonID, expirationTimeIn24Hours);
        assertToken(token, ePersonID);
    }

    @Test
    public void testCreateTokenForAdminUser() {
        UUID adminUserID = this.admin.getID();
        assertThrows(AuthorizeException.class, () ->
                clarinTokenService.createToken(context, adminUserID, expirationTimeIn24Hours));
    }

    @Test
    public void testCreateTokenForInvalidUserId() {
        context.setCurrentUser(admin);
        assertThrows(BadRequestException.class, () ->
                clarinTokenService.createToken(context, UUID.randomUUID(), expirationTimeIn24Hours));
    }

    @Test
    public void testCreateExpiredToken() throws Exception {
        String token = clarinTokenService.createToken(context, ePersonID,
                new Date(new Date().getTime() - 1000 * 60 * 60 * 24));
        assertToken(token, ePersonID);
    }

    @Test
    public void testDelete() throws Exception {
        context.setCurrentUser(admin);
        String token = clarinTokenService.createToken(context, ePersonID, expirationTimeIn24Hours);
        assertToken(token, ePersonID);

        clarinTokenService.delete(context, ePersonID);
        assertNull(clarinTokenService.find(context, getTokenId(token)));
    }

    @Test
    public void testDeleteOtherUserToken() throws Exception {
        context.setCurrentUser(admin);
        String adminToken = clarinTokenService.createToken(context, admin.getID(), expirationTimeIn24Hours);
        assertToken(adminToken, admin.getID());

        context.setCurrentUser(eperson);
        assertThrows(AuthorizeException.class, () -> clarinTokenService.delete(context, adminToken));
    }

    @Test
    public void testDeleteByUserID() throws Exception {
        context.setCurrentUser(admin);
        String adminToken = clarinTokenService.createToken(context, admin.getID(), expirationTimeIn24Hours);
        assertToken(adminToken, admin.getID());

        context.setCurrentUser(eperson);
        String token = clarinTokenService.createToken(context, ePersonID, expirationTimeIn24Hours);
        assertToken(token, ePersonID);

        // non admin user cannot delete another user tokens
        assertThrows(AuthorizeException.class, () -> clarinTokenService.delete(context, admin.getID()));

        // non admin user can delete own tokens
        clarinTokenService.delete(context, ePersonID);
        assertNull(clarinTokenService.find(context, getTokenId(token)));
    }

    @Test
    public void testDeleteForInvalidatedToken() throws Exception {
        context.setCurrentUser(admin);
        String adminToken = clarinTokenService.createToken(context, admin.getID(), expirationTimeIn24Hours);

        clarinTokenService.delete(context, adminToken);
        assertThrows(BadRequestException.class, () -> clarinTokenService.delete(context, adminToken));
    }

    @Test
    public void testDeleteAll() throws Exception {
        context.setCurrentUser(admin);
        String token = clarinTokenService.createToken(context, ePersonID, expirationTimeIn24Hours);
        String adminToken = clarinTokenService.createToken(context, admin.getID(), expirationTimeIn24Hours);

        clarinTokenService.deleteAll(context);
        assertNull(clarinTokenService.find(context, getTokenId(token)));
        assertNull(clarinTokenService.find(context, getTokenId(adminToken)));
    }

    @Test
    public void testDeleteAllByNonAdminUser() throws Exception {
        clarinTokenService.createToken(context, ePersonID, expirationTimeIn24Hours);
        assertThrows(AuthorizeException.class, () -> clarinTokenService.deleteAll(context));
    }

    @Test
    public void testFindToken() throws Exception {
        context.setCurrentUser(admin);
        String adminToken = clarinTokenService.createToken(context, admin.getID(), expirationTimeIn24Hours);
        String token = clarinTokenService.createToken(context, ePersonID, expirationTimeIn24Hours);

        ClarinToken pat1 = clarinTokenService.find(context, getTokenId(adminToken));
        assertNotNull(pat1);

        context.setCurrentUser(eperson);

        // any user can get clarin token for given ID
        ClarinToken pat2 = clarinTokenService.find(context, getTokenId(token));
        assertNotNull(pat2);

        ClarinToken pat3 = clarinTokenService.find(context, pat1.getID());
        assertEquals(pat1, pat3);
    }

    private void assertToken(String token, UUID ePersonID) throws SQLException, ParseException {
        ClarinToken pat = clarinTokenService.find(context, getTokenId(token));
        assertNotNull(pat);
        assertEquals(ePersonID, pat.getEPersonID());
        assertFalse(pat.getSignKey().isBlank());
    }
}
