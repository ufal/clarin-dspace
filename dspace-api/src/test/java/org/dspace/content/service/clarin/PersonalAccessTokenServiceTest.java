/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.content.service.clarin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import java.sql.SQLException;
import java.util.Date;
import java.util.UUID;
import javax.ws.rs.BadRequestException;

import org.dspace.AbstractIntegrationTestWithDatabase;
import org.dspace.authorize.AuthorizeException;
import org.dspace.content.clarin.PersonalAccessToken;
import org.dspace.content.factory.ClarinServiceFactory;
import org.junit.Before;
import org.junit.Test;

public class PersonalAccessTokenServiceTest extends AbstractIntegrationTestWithDatabase {

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
    public void testCreateToken() throws Exception {
        String token = personalAccessTokenService.createToken(context, ePersonID, expirationTimeIn24Hours);

        assertFalse(token.isBlank());
        assertToken(ePersonID);
    }

    @Test
    public void testCreateTokenByAdmin() throws Exception {
        context.setCurrentUser(admin);
        String token = personalAccessTokenService.createToken(context, ePersonID, expirationTimeIn24Hours);

        assertFalse(token.isBlank());
        assertToken(ePersonID);
    }

    @Test
    public void testCreateTokenForAdminUser() {
        UUID adminUserID = this.admin.getID();
        assertThrows(AuthorizeException.class, () ->
                personalAccessTokenService.createToken(context, adminUserID, expirationTimeIn24Hours));
    }

    @Test
    public void testCreateTokenForInvalidUserId() {
        context.setCurrentUser(admin);
        assertThrows(BadRequestException.class, () ->
                personalAccessTokenService.createToken(context, UUID.randomUUID(), expirationTimeIn24Hours));
    }

    @Test
    public void testCreateExpiredToken() throws Exception {
        String token = personalAccessTokenService.createToken(context, ePersonID,
                new Date(new Date().getTime() - 1000 * 60 * 60 * 24));

        assertFalse(token.isBlank());
        assertToken(ePersonID);
    }

    @Test
    public void testDelete() throws Exception {
        context.setCurrentUser(admin);
        personalAccessTokenService.createToken(context, ePersonID, expirationTimeIn24Hours);
        assertToken(ePersonID);
        personalAccessTokenService.delete(context, ePersonID);
        assertNull(personalAccessTokenService.findByEPersonID(context, ePersonID));
    }

    @Test
    public void testDeleteByNonAdminUser() throws Exception {
        personalAccessTokenService.createToken(context, ePersonID, expirationTimeIn24Hours);
        assertThrows(AuthorizeException.class, () -> personalAccessTokenService.delete(context, ePersonID));
    }

    @Test
    public void testDeleteForInvalidUserId() throws Exception {
        context.setCurrentUser(admin);
        assertThrows(BadRequestException.class, () -> personalAccessTokenService.delete(context, UUID.randomUUID()));
    }

    @Test
    public void testDeleteAll() throws Exception {
        context.setCurrentUser(admin);
        personalAccessTokenService.createToken(context, ePersonID, expirationTimeIn24Hours);
        personalAccessTokenService.createToken(context, admin.getID(), expirationTimeIn24Hours);
        personalAccessTokenService.deleteAll(context);
        assertNull(personalAccessTokenService.findByEPersonID(context, ePersonID));
        assertNull(personalAccessTokenService.findByEPersonID(context, admin.getID()));
    }

    @Test
    public void testDeleteAllByNonAdminUser() throws Exception {
        personalAccessTokenService.createToken(context, ePersonID, expirationTimeIn24Hours);
        assertThrows(AuthorizeException.class, () -> personalAccessTokenService.deleteAll(context));
    }

    @Test
    public void testFindToken() throws Exception {
        context.setCurrentUser(admin);
        personalAccessTokenService.createToken(context, admin.getID(), expirationTimeIn24Hours);

        personalAccessTokenService.createToken(context, ePersonID, expirationTimeIn24Hours);

        PersonalAccessToken pat1 = personalAccessTokenService.findByEPersonID(context, admin.getID());
        assertNotNull(pat1);

        context.setCurrentUser(eperson);

        PersonalAccessToken pat2 = personalAccessTokenService.findByEPersonID(context, ePersonID);
        assertNotNull(pat2);

        // regular (non admin) user is not authorized to get personal access token for another user
        assertThrows(AuthorizeException.class, () ->
                personalAccessTokenService.findByEPersonID(context, admin.getID()));

        // any user can get personal access token for given ID
        PersonalAccessToken pat3 = personalAccessTokenService.find(context, pat1.getID());
        assertEquals(pat1, pat3);
    }

    private void assertToken(UUID ePersonID) throws SQLException, AuthorizeException {
        PersonalAccessToken pat = personalAccessTokenService.findByEPersonID(context, ePersonID);
        assertNotNull(pat);
        assertEquals(ePersonID, pat.getEPersonID());
        assertFalse(pat.getMacSecret().isBlank());
        assertFalse(pat.getAesKey().isBlank());
    }
}
