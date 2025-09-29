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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import java.sql.SQLException;
import java.text.ParseException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.UUID;
import javax.ws.rs.BadRequestException;

import org.dspace.AbstractIntegrationTestWithDatabase;
import org.dspace.authorize.AuthorizeException;
import org.dspace.content.clarin.ClarinToken;
import org.dspace.content.factory.ClarinServiceFactory;
import org.dspace.eperson.EPerson;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.junit.Before;
import org.junit.Test;

public class ClarinTokenServiceTest extends AbstractIntegrationTestWithDatabase {

    private ClarinTokenService clarinTokenService;
    private Date expirationTimeIn24Hours;

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();

        ConfigurationService config = DSpaceServicesFactory.getInstance().getConfigurationService();
        // Set encryption/decryption secret key for the test
        config.setProperty(ClarinToken.PROPERTY_ENCRYPTION_SECRET, "P/uBJYtuKbuG2kHdukCp0nbnI5EZz6mg6Qtuyo8I+18=");

        clarinTokenService = ClarinServiceFactory.getInstance().getClarinTokenService();
        // expiration time set to 24 hours
        LocalDateTime nowPlus24Hours = LocalDateTime.now(ZoneId.of("UTC")).plusHours(24);
        expirationTimeIn24Hours = Date.from(nowPlus24Hours.toInstant(ZoneOffset.UTC));
    }

    @Test
    public void testCreateToken() throws Exception {
        String token = clarinTokenService.createToken(context, eperson, expirationTimeIn24Hours);
        assertToken(token, eperson);
    }

    @Test
    public void testCreateTokenByAdmin() throws Exception {
        context.setCurrentUser(admin);
        String token = clarinTokenService.createToken(context, eperson, expirationTimeIn24Hours);
        assertToken(token, eperson);
    }

    @Test
    public void testCreateTokenForAdminUser() {
        UUID adminUserID = this.admin.getID();
        assertThrows(AuthorizeException.class, () ->
                clarinTokenService.createToken(context, admin, expirationTimeIn24Hours));
    }

    @Test
    public void testCreateTokenForMissingEPerson() {
        context.setCurrentUser(admin);
        assertThrows(BadRequestException.class, () ->
                clarinTokenService.createToken(context, null, expirationTimeIn24Hours));
    }

    @Test
    public void testCreateExpiredToken() throws Exception {
        LocalDateTime nowPlus24Hours = LocalDateTime.now(ZoneId.of("UTC")).minusHours(24);
        Date expiredTime = Date.from(nowPlus24Hours.toInstant(ZoneOffset.UTC));
        String token = clarinTokenService.createToken(context, eperson, expiredTime);
        assertToken(token, eperson);
    }

    @Test
    public void testCreateTwoTokens() throws Exception {
        String token1 = clarinTokenService.createToken(context, eperson, expirationTimeIn24Hours);
        assertToken(token1, eperson);
        String token2 = clarinTokenService.createToken(context, eperson, new Date());
        assertToken(token2, eperson);
        assertNotEquals(getTokenId(token1), getTokenId(token2));
    }

    @Test
    public void testDelete() throws Exception {
        context.setCurrentUser(admin);
        String token = clarinTokenService.createToken(context, eperson, expirationTimeIn24Hours);
        assertToken(token, eperson);

        clarinTokenService.delete(context, eperson);
        assertNull(clarinTokenService.find(context, getTokenId(token)));
    }

    @Test
    public void testDeleteOtherUserToken() throws Exception {
        context.setCurrentUser(admin);
        String adminToken = clarinTokenService.createToken(context, admin, expirationTimeIn24Hours);
        assertToken(adminToken, admin);

        context.setCurrentUser(eperson);
        assertThrows(AuthorizeException.class, () -> clarinTokenService.delete(context, adminToken));
    }

    @Test
    public void testDeleteByUserID() throws Exception {
        context.setCurrentUser(admin);
        String adminToken = clarinTokenService.createToken(context, admin, expirationTimeIn24Hours);
        assertToken(adminToken, admin);

        context.setCurrentUser(eperson);
        String token = clarinTokenService.createToken(context, eperson, expirationTimeIn24Hours);
        assertToken(token, eperson);

        // non admin user cannot delete another user tokens
        assertThrows(AuthorizeException.class, () -> clarinTokenService.delete(context, admin));

        // non admin user can delete own tokens
        clarinTokenService.delete(context, eperson);
        assertNull(clarinTokenService.find(context, getTokenId(token)));
    }

    @Test
    public void testDeleteForInvalidatedToken() throws Exception {
        context.setCurrentUser(admin);
        String adminToken = clarinTokenService.createToken(context, admin, expirationTimeIn24Hours);

        clarinTokenService.delete(context, adminToken);
        assertThrows(BadRequestException.class, () -> clarinTokenService.delete(context, adminToken));
    }

    @Test
    public void testDeleteAll() throws Exception {
        context.setCurrentUser(admin);
        String token = clarinTokenService.createToken(context, eperson, expirationTimeIn24Hours);
        String adminToken = clarinTokenService.createToken(context, admin, expirationTimeIn24Hours);

        clarinTokenService.deleteAll(context);
        assertNull(clarinTokenService.find(context, getTokenId(token)));
        assertNull(clarinTokenService.find(context, getTokenId(adminToken)));
    }

    @Test
    public void testDeleteAllByNonAdminUser() throws Exception {
        clarinTokenService.createToken(context, eperson, expirationTimeIn24Hours);
        assertThrows(AuthorizeException.class, () -> clarinTokenService.deleteAll(context));
    }

    @Test
    public void testFindToken() throws Exception {
        context.setCurrentUser(admin);
        String adminToken = clarinTokenService.createToken(context, admin, expirationTimeIn24Hours);
        String token = clarinTokenService.createToken(context, eperson, expirationTimeIn24Hours);

        ClarinToken pat1 = clarinTokenService.find(context, getTokenId(adminToken));
        assertNotNull(pat1);

        context.setCurrentUser(eperson);

        // any user can get ClarinToken object for given ID
        // this is due to allow DSpace, during authentication, to get the userID + sign key for given token
        ClarinToken pat2 = clarinTokenService.find(context, getTokenId(token));
        assertNotNull(pat2);

        ClarinToken pat3 = clarinTokenService.find(context, pat1.getID());
        assertEquals(pat1, pat3);
    }

    private void assertToken(String token, EPerson ePerson) throws SQLException, ParseException {
        ClarinToken pat = clarinTokenService.find(context, getTokenId(token));
        assertNotNull(pat);
        assertEquals(ePerson, pat.getEPerson());
        assertFalse(pat.getSignKey().isBlank());
    }
}
