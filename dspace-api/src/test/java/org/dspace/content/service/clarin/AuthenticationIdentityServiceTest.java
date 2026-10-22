/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.content.service.clarin;

import static org.dspace.content.clarin.AuthenticationIdentity.AUTH_METHOD_OIDC;
import static org.dspace.content.clarin.AuthenticationIdentity.AUTH_METHOD_SHIBBOLETH;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Date;
import java.util.List;
import javax.ws.rs.BadRequestException;

import org.dspace.AbstractIntegrationTestWithDatabase;
import org.dspace.authorize.AuthorizeException;
import org.dspace.content.clarin.AuthenticationIdentity;
import org.dspace.content.factory.ClarinServiceFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Integration tests for the AuthenticationIdentityService.
 * Tests the service methods with database access and authorization checks.
 *
 * @author Milan Kuchtiak
 */
public class AuthenticationIdentityServiceTest extends AbstractIntegrationTestWithDatabase {

    private static final String PROVIDER = "einfra";
    private static final String SHIB_PROVIDER = "https://idp.example.org/idp/shibboleth";

    private AuthenticationIdentityService authenticationIdentityService;

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();
        authenticationIdentityService = ClarinServiceFactory.getInstance().getAuthenticationIdentityService();
    }

    @After
    public void cleanUp() throws Exception {
        // eperson and admin are shared between tests, remove their identities to avoid unique key conflicts
        context.turnOffAuthorisationSystem();
        authenticationIdentityService.delete(context, eperson);
        authenticationIdentityService.delete(context, admin);
        context.restoreAuthSystemState();
    }

    @Test
    public void testCreateByAdmin() throws Exception {
        context.setCurrentUser(admin);
        Date before = new Date();
        AuthenticationIdentity identity =
                authenticationIdentityService.create(context, eperson, AUTH_METHOD_OIDC, PROVIDER, "perun1");

        assertNotNull(identity.getID());
        assertEquals(eperson, identity.getEPerson());
        assertEquals(AUTH_METHOD_OIDC, identity.getAuthMethod());
        assertEquals(PROVIDER, identity.getProvider());
        assertEquals("perun1", identity.getUserId());
        assertNotNull(identity.getCreated());
        assertFalse(identity.getCreated().before(before));
        assertEquals(identity.getCreated(), identity.getLastLogin());
    }

    @Test
    public void testCreateWithAuthorizationTurnedOff() throws Exception {
        // this is the case of login, when nobody is signed in yet
        context.setCurrentUser(null);
        context.turnOffAuthorisationSystem();
        AuthenticationIdentity identity =
                authenticationIdentityService.create(context, eperson, AUTH_METHOD_OIDC, PROVIDER, "perun1");
        context.restoreAuthSystemState();

        assertNotNull(identity.getID());
        assertEquals(eperson, identity.getEPerson());
    }

    @Test
    public void testCreateByNonAdminUser() {
        // non admin user cannot link an identity, even to own account
        context.setCurrentUser(eperson);
        assertThrows(AuthorizeException.class, () ->
                authenticationIdentityService.create(context, eperson, AUTH_METHOD_OIDC, PROVIDER, "perun1"));
    }

    @Test
    public void testCreateForMissingEPerson() {
        context.setCurrentUser(admin);
        assertThrows(BadRequestException.class, () ->
                authenticationIdentityService.create(context, null, AUTH_METHOD_OIDC, PROVIDER, "perun1"));
    }

    @Test
    public void testCreateForMissingAttributes() {
        context.setCurrentUser(admin);
        assertThrows(BadRequestException.class, () ->
                authenticationIdentityService.create(context, eperson, null, PROVIDER, "perun1"));
        assertThrows(BadRequestException.class, () ->
                authenticationIdentityService.create(context, eperson, AUTH_METHOD_OIDC, "", "perun1"));
        assertThrows(BadRequestException.class, () ->
                authenticationIdentityService.create(context, eperson, AUTH_METHOD_OIDC, PROVIDER, " "));
    }

    @Test
    public void testFindByUniqueKey() throws Exception {
        context.setCurrentUser(admin);
        AuthenticationIdentity identity =
                authenticationIdentityService.create(context, eperson, AUTH_METHOD_OIDC, PROVIDER, "perun1");

        // any user can find identity, this is required during login when nobody is signed in yet
        context.setCurrentUser(null);
        AuthenticationIdentity found =
                authenticationIdentityService.find(context, AUTH_METHOD_OIDC, PROVIDER, "perun1");
        assertEquals(identity, found);
        assertEquals(eperson, found.getEPerson());

        assertEquals(identity, authenticationIdentityService.find(context, identity.getID()));
    }

    @Test
    public void testFindByUniqueKeyNotFound() throws Exception {
        context.setCurrentUser(admin);
        authenticationIdentityService.create(context, eperson, AUTH_METHOD_OIDC, PROVIDER, "perun1");

        assertNull(authenticationIdentityService.find(context, AUTH_METHOD_OIDC, PROVIDER, "makub1"));
        assertNull(authenticationIdentityService.find(context, AUTH_METHOD_OIDC, "other-provider", "perun1"));
        assertNull(authenticationIdentityService.find(context, AUTH_METHOD_SHIBBOLETH, PROVIDER, "perun1"));
    }

    @Test
    public void testSameUserIdForDifferentProviders() throws Exception {
        // the same userId from different providers belongs to different users
        context.setCurrentUser(admin);
        authenticationIdentityService.create(context, eperson, AUTH_METHOD_OIDC, PROVIDER, "user1");
        authenticationIdentityService.create(context, admin, AUTH_METHOD_OIDC, "other-provider", "user1");

        assertEquals(eperson, authenticationIdentityService.find(context, AUTH_METHOD_OIDC, PROVIDER, "user1")
                .getEPerson());
        assertEquals(admin, authenticationIdentityService.find(context, AUTH_METHOD_OIDC, "other-provider", "user1")
                .getEPerson());
    }

    @Test
    public void testFindByEPerson() throws Exception {
        context.setCurrentUser(admin);
        authenticationIdentityService.create(context, eperson, AUTH_METHOD_OIDC, PROVIDER, "perun1");
        authenticationIdentityService.create(context, eperson, AUTH_METHOD_SHIBBOLETH, SHIB_PROVIDER,
                "perun@example.org");
        authenticationIdentityService.create(context, admin, AUTH_METHOD_OIDC, PROVIDER, "makub1");

        List<AuthenticationIdentity> identities = authenticationIdentityService.findByEPerson(context, eperson);
        assertEquals(2, identities.size());
        assertTrue(identities.stream().allMatch(identity -> eperson.equals(identity.getEPerson())));

        List<AuthenticationIdentity> oidcIdentities =
                authenticationIdentityService.findByEPersonAndAuthMethod(context, eperson, AUTH_METHOD_OIDC);
        assertEquals(1, oidcIdentities.size());
        assertEquals("perun1", oidcIdentities.get(0).getUserId());
    }

    @Test
    public void testFindByEPersonWithoutIdentities() throws Exception {
        assertTrue(authenticationIdentityService.findByEPerson(context, eperson).isEmpty());
        assertTrue(authenticationIdentityService.findByEPersonAndAuthMethod(context, eperson, AUTH_METHOD_OIDC)
                .isEmpty());
    }

    @Test
    public void testUpdateLastLoginByOwner() throws Exception {
        context.setCurrentUser(admin);
        AuthenticationIdentity identity =
                authenticationIdentityService.create(context, eperson, AUTH_METHOD_OIDC, PROVIDER, "perun1");
        Date created = identity.getCreated();

        // the user who has just logged in can update own identity
        context.setCurrentUser(eperson);
        authenticationIdentityService.updateLastLogin(context, identity);

        AuthenticationIdentity found = authenticationIdentityService.find(context, AUTH_METHOD_OIDC, PROVIDER,
                "perun1");
        assertEquals(created, found.getCreated());
        assertFalse(found.getLastLogin().before(created));
    }

    @Test
    public void testUpdateLastLoginByOtherUser() throws Exception {
        context.setCurrentUser(admin);
        AuthenticationIdentity adminIdentity =
                authenticationIdentityService.create(context, admin, AUTH_METHOD_OIDC, PROVIDER, "makub1");

        context.setCurrentUser(eperson);
        assertThrows(AuthorizeException.class, () ->
                authenticationIdentityService.updateLastLogin(context, adminIdentity));
    }

    @Test
    public void testDeleteByOwner() throws Exception {
        context.setCurrentUser(admin);
        AuthenticationIdentity identity =
                authenticationIdentityService.create(context, eperson, AUTH_METHOD_OIDC, PROVIDER, "perun1");

        context.setCurrentUser(eperson);
        authenticationIdentityService.delete(context, identity);
        assertNull(authenticationIdentityService.find(context, AUTH_METHOD_OIDC, PROVIDER, "perun1"));
    }

    @Test
    public void testDeleteOtherUserIdentity() throws Exception {
        context.setCurrentUser(admin);
        AuthenticationIdentity adminIdentity =
                authenticationIdentityService.create(context, admin, AUTH_METHOD_OIDC, PROVIDER, "makub1");

        context.setCurrentUser(eperson);
        assertThrows(AuthorizeException.class, () -> authenticationIdentityService.delete(context, adminIdentity));
        assertNotNull(authenticationIdentityService.find(context, AUTH_METHOD_OIDC, PROVIDER, "makub1"));
    }

    @Test
    public void testDeleteByAdmin() throws Exception {
        context.setCurrentUser(admin);
        AuthenticationIdentity identity =
                authenticationIdentityService.create(context, eperson, AUTH_METHOD_OIDC, PROVIDER, "perun1");

        authenticationIdentityService.delete(context, identity);
        assertNull(authenticationIdentityService.find(context, AUTH_METHOD_OIDC, PROVIDER, "perun1"));
    }

    @Test
    public void testDeleteByAnonymousUser() throws Exception {
        context.setCurrentUser(admin);
        AuthenticationIdentity identity =
                authenticationIdentityService.create(context, eperson, AUTH_METHOD_OIDC, PROVIDER, "perun1");

        context.setCurrentUser(null);
        assertThrows(AuthorizeException.class, () -> authenticationIdentityService.delete(context, identity));
        assertThrows(AuthorizeException.class, () -> authenticationIdentityService.delete(context, eperson));
    }

    @Test
    public void testDeleteByEPerson() throws Exception {
        context.setCurrentUser(admin);
        authenticationIdentityService.create(context, eperson, AUTH_METHOD_OIDC, PROVIDER, "perun1");
        authenticationIdentityService.create(context, eperson, AUTH_METHOD_SHIBBOLETH, SHIB_PROVIDER,
                "perun@example.org");
        authenticationIdentityService.create(context, admin, AUTH_METHOD_OIDC, PROVIDER, "makub1");

        context.setCurrentUser(eperson);
        // non admin user cannot delete identities of another user
        assertThrows(AuthorizeException.class, () -> authenticationIdentityService.delete(context, admin));

        // non admin user can delete own identities
        authenticationIdentityService.delete(context, eperson);
        assertTrue(authenticationIdentityService.findByEPerson(context, eperson).isEmpty());
        assertEquals(1, authenticationIdentityService.findByEPerson(context, admin).size());
    }
}

