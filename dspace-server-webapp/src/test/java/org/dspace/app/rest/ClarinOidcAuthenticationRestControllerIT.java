/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest;

import static java.util.Arrays.asList;
import static org.dspace.content.clarin.AuthenticationIdentity.AUTH_METHOD_OIDC;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.servlet.http.Cookie;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jwt.SignedJWT;
import org.dspace.app.rest.model.AuthnRest;
import org.dspace.app.rest.security.jwt.EPersonClaimProvider;
import org.dspace.app.rest.test.AbstractControllerIntegrationTest;
import org.dspace.authenticate.OidcAuthenticationBean;
import org.dspace.authenticate.clarin.ClarinOidcAuthenticationBean;
import org.dspace.authenticate.oidc.OidcClient;
import org.dspace.authenticate.oidc.model.OidcTokenResponseDTO;
import org.dspace.builder.EPersonBuilder;
import org.dspace.content.clarin.AuthenticationIdentity;
import org.dspace.content.clarin.ClarinUserRegistration;
import org.dspace.content.service.clarin.AuthenticationIdentityService;
import org.dspace.content.service.clarin.ClarinUserRegistrationService;
import org.dspace.eperson.EPerson;
import org.dspace.eperson.service.EPersonService;
import org.dspace.services.ConfigurationService;
import org.dspace.util.UUIDUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Integration tests for {@link ClarinOidcAuthenticationBean}.
 * ufal/clarin-dspace#1140
 *
 * @author Milan Kuchtiak
 */
public class ClarinOidcAuthenticationRestControllerIT extends AbstractControllerIntegrationTest {

    private final static String CODE = "123456";
    private final static String ACCESS_TOKEN = "c41e37e5-c2de-4177-91d6-ed9e9d1f31bf";
    private final static String SUB = "sub";
    private final static String EMAIL = "email";
    private final static String FIRST_NAME = "given_name";
    private final static String LAST_NAME = "family_name";
    private final static String ORGANIZATION = "organization";
    private final static String PROVIDER = "oidc";

    private OidcClient originalOidcClient;

    private final OidcClient oidcClientMock = mock(OidcClient.class);

    // EPersons whose CLARIN user registrations are removed after the test
    private final List<EPerson> ePersons = new ArrayList<>();

    // EPersons registered by OIDC login, which are not removed by the builder cleanup
    private final List<EPerson> registeredEPersons = new ArrayList<>();

    @Autowired
    private OidcAuthenticationBean oidcAuthentication;

    @Autowired
    private AuthenticationIdentityService authenticationIdentityService;

    @Autowired
    private ClarinUserRegistrationService clarinUserRegistrationService;

    @Autowired
    private ConfigurationService configurationService;

    @Autowired
    private EPersonService ePersonService;

    @Before
    public void setup() throws Exception {
        originalOidcClient = oidcAuthentication.getOidcClient();
        oidcAuthentication.setOidcClient(oidcClientMock);

        configurationService.setProperty("authentication-oidc.user-info.email", EMAIL);
        configurationService.setProperty("authentication-oidc.user-info.first-name", FIRST_NAME);
        configurationService.setProperty("authentication-oidc.user-info.last-name", LAST_NAME);
        configurationService.setProperty("authentication-oidc.user-info.id", SUB);
        configurationService.setProperty("authentication-oidc.user-info.organization", ORGANIZATION);
        configurationService.setProperty("authentication-oidc.provider", PROVIDER);
        configurationService.setProperty("authentication-oidc.can-self-register", "true");

        configurationService.setProperty("plugin.sequence.org.dspace.authenticate.AuthenticationMethod",
                asList("org.dspace.authenticate.OidcAuthentication", "org.dspace.authenticate.PasswordAuthentication"));

        when(oidcClientMock.getAccessToken(CODE)).thenReturn(buildOidcTokenResponse());
    }

    @After
    public void after() throws Exception {
        oidcAuthentication.setOidcClient(originalOidcClient);

        context.turnOffAuthorisationSystem();
        // user_registration has no foreign key to eperson, remove the registrations explicitly
        for (EPerson ePerson : ePersons) {
            for (ClarinUserRegistration registration :
                    clarinUserRegistrationService.findByEPersonUUID(context, ePerson.getID())) {
                clarinUserRegistrationService.delete(context, registration);
            }
        }
        // identities are removed by ON DELETE CASCADE
        for (EPerson ePerson : registeredEPersons) {
            ePersonService.delete(context, context.reloadEntity(ePerson));
        }
        context.restoreAuthSystemState();
        context.commit();
    }

    @Test
    public void testClarinBeanIsUsed() {
        assertThat(oidcAuthentication, instanceOf(ClarinOidcAuthenticationBean.class));
    }

    @Test
    public void testEPersonLoggedInBySub() throws Exception {
        EPerson ePerson = createEPerson("old@email.it", true);
        AuthenticationIdentity identity = createIdentity(ePerson, PROVIDER, "perun1");

        // email at the provider has changed, the user is still found by sub
        mockUserInfo("perun1", "new@email.it", null);

        assertThat(login(), equalTo(ePerson.getID().toString()));
        // no new EPerson was registered, the email of the EPerson was updated
        assertThat(ePersonService.findByEmail(context, "new@email.it").getID(), equalTo(ePerson.getID()));
        assertThat(ePersonService.findByEmail(context, "old@email.it"), nullValue());

        identity = context.reloadEntity(identity);
        assertThat(identity.getLastLogin().after(new Date(0)), equalTo(true));
    }

    @Test
    public void testEmailUpdatedInUserRegistration() throws Exception {
        EPerson ePerson = createEPerson("old@email.it", true);
        createIdentity(ePerson, PROVIDER, "perun1");

        mockUserInfo("perun1", "old@email.it", "Charles University");
        login();
        assertThat(findRegistrations(ePerson).get(0).getEmail(), equalTo("old@email.it"));

        mockUserInfo("perun1", "New@Email.it", "Charles University");
        login();

        ePerson = context.reloadEntity(ePerson);
        assertThat(ePerson.getEmail(), equalTo("new@email.it"));
        List<ClarinUserRegistration> registrations = findRegistrations(ePerson);
        context.reloadEntity(registrations.get(0));
        assertThat(findRegistrations(ePerson).get(0).getEmail(), equalTo("new@email.it"));
    }

    @Test
    public void testEmailNotUpdatedWhenUsedByOtherEPerson() throws Exception {
        EPerson ePerson = createEPerson("old@email.it", true);
        createIdentity(ePerson, PROVIDER, "perun1");
        EPerson otherEPerson = createEPerson("new@email.it", true);

        mockUserInfo("perun1", "new@email.it", null);

        // the user is still logged in, but the email is not changed
        assertThat(login(), equalTo(ePerson.getID().toString()));
        assertThat(context.reloadEntity(ePerson).getEmail(), equalTo("old@email.it"));
        assertThat(ePersonService.findByEmail(context, "new@email.it").getID(), equalTo(otherEPerson.getID()));
    }

    @Test
    public void testEPersonLoggedInBySubWhenPasswordLoginDisabled() throws Exception {
        // canLogIn flag only controls the password login, as for Shibboleth users
        EPerson ePerson = createEPerson("perun@email.it", false);
        createIdentity(ePerson, PROVIDER, "perun1");

        mockUserInfo("perun1", "perun@email.it", null);

        assertThat(login(), equalTo(ePerson.getID().toString()));
    }

    @Test
    public void testIdentityOfOtherProviderIsNotUsed() throws Exception {
        EPerson otherProviderPerson = createEPerson("other@email.it", true);
        createIdentity(otherProviderPerson, "other-provider", "perun1");
        EPerson ePerson = createEPerson("perun@email.it", true);

        mockUserInfo("perun1", "perun@email.it", null);

        // sub is not found for the configured provider, so the user is linked by email
        assertThat(login(), equalTo(ePerson.getID().toString()));
        assertThat(authenticationIdentityService.find(context, AUTH_METHOD_OIDC, PROVIDER, "perun1").getEPerson(),
                equalTo(ePerson));
    }

    @Test
    public void testEPersonLinkedByEmail() throws Exception {
        // EPerson created by Shibboleth
        EPerson ePerson = createEPerson("perun@email.it", false);
        context.turnOffAuthorisationSystem();
        ePerson.setNetid("perun@cuni.cz[https://idp.cuni.cz/idp/shibboleth]");
        ePersonService.update(context, ePerson);
        context.restoreAuthSystemState();
        context.commit();

        mockUserInfo("perun1", "perun@email.it", null);

        assertThat(login(), equalTo(ePerson.getID().toString()));

        AuthenticationIdentity identity = authenticationIdentityService.find(context, AUTH_METHOD_OIDC, PROVIDER,
                "perun1");
        assertThat(identity, notNullValue());
        assertThat(identity.getEPerson(), equalTo(ePerson));
        assertThat(identity.getCreated(), notNullValue());

        // Shibboleth netid is not changed
        ePerson = context.reloadEntity(ePerson);
        assertThat(ePerson.getNetid(), equalTo("perun@cuni.cz[https://idp.cuni.cz/idp/shibboleth]"));

        // next login is by sub, even if the email has changed
        mockUserInfo("perun1", "new@email.it", null);
        assertThat(login(), equalTo(ePerson.getID().toString()));
    }

    @Test
    public void testEPersonNotLinkedWhenAlreadyLinkedToOtherIdentity() throws Exception {
        EPerson ePerson = createEPerson("perun@email.it", true);
        createIdentity(ePerson, PROVIDER, "other-sub");

        mockUserInfo("perun1", "perun@email.it", null);

        getClient().perform(get("/api/" + AuthnRest.CATEGORY + "/oidc").param("code", CODE))
                .andExpect(status().isUnauthorized())
                .andExpect(cookie().doesNotExist("Authorization-cookie"));

        assertThat(authenticationIdentityService.find(context, AUTH_METHOD_OIDC, PROVIDER, "perun1"), nullValue());
    }

    @Test
    public void testNewEPersonRegistered() throws Exception {
        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put(SUB, "perun1");
        userInfo.put(EMAIL, "perun@email.it");
        userInfo.put(FIRST_NAME, "Master");
        userInfo.put(LAST_NAME, "Perun");
        userInfo.put(ORGANIZATION, "Charles University");
        when(oidcClientMock.getUserInfo(ACCESS_TOKEN)).thenReturn(userInfo);

        EPerson ePerson = ePersonService.find(context, UUIDUtils.fromString(login()));
        registeredEPersons.add(ePerson);
        ePersons.add(ePerson);

        assertThat(ePerson.getEmail(), equalTo("perun@email.it"));
        assertThat(ePerson.getFullName(), equalTo("Master Perun"));
        assertThat(ePerson.getNetid(), nullValue());
        assertThat(ePerson.canLogIn(), equalTo(false));
        assertThat(ePerson.getSelfRegistered(), equalTo(true));

        AuthenticationIdentity identity = authenticationIdentityService.find(context, AUTH_METHOD_OIDC, PROVIDER,
                "perun1");
        assertThat(identity.getEPerson(), equalTo(ePerson));

        List<ClarinUserRegistration> registrations = findRegistrations(ePerson);
        assertThat(registrations, hasSize(1));
        assertThat(registrations.get(0).getOrganization(), equalTo("Charles University"));
        assertThat(registrations.get(0).getEmail(), equalTo("perun@email.it"));
        assertThat(registrations.get(0).isConfirmation(), equalTo(true));
    }

    @Test
    public void testNewEPersonRegisteredWithoutOrganization() throws Exception {
        mockUserInfo("perun1", "perun@email.it", null);

        EPerson ePerson = ePersonService.find(context, UUIDUtils.fromString(login()));
        registeredEPersons.add(ePerson);
        ePersons.add(ePerson);

        List<ClarinUserRegistration> registrations = findRegistrations(ePerson);
        assertThat(registrations, hasSize(1));
        assertThat(registrations.get(0).getOrganization(), equalTo(ClarinUserRegistration.UNKNOWN_USER_REGISTRATION));
    }

    @Test
    public void testWithoutSelfRegistrationEnabled() throws Exception {
        configurationService.setProperty("authentication-oidc.can-self-register", "false");
        mockUserInfo("perun1", "perun@email.it", null);

        getClient().perform(get("/api/" + AuthnRest.CATEGORY + "/oidc").param("code", CODE))
                .andExpect(status().isUnauthorized())
                .andExpect(cookie().doesNotExist("Authorization-cookie"));

        assertThat(ePersonService.findByEmail(context, "perun@email.it"), nullValue());
        assertThat(authenticationIdentityService.find(context, AUTH_METHOD_OIDC, PROVIDER, "perun1"), nullValue());
    }

    @Test
    public void testWithoutEmailAndIdentity() throws Exception {
        when(oidcClientMock.getUserInfo(ACCESS_TOKEN)).thenReturn(Map.of(SUB, "perun1"));

        getClient().perform(get("/api/" + AuthnRest.CATEGORY + "/oidc").param("code", CODE))
                .andExpect(status().isUnauthorized())
                .andExpect(cookie().doesNotExist("Authorization-cookie"));
    }

    @Test
    public void testOrganizationUpdatedOnLogin() throws Exception {
        EPerson ePerson = createEPerson("perun@email.it", true);
        createIdentity(ePerson, PROVIDER, "perun1");

        mockUserInfo("perun1", "perun@email.it", "Old University");
        login();

        List<ClarinUserRegistration> registrations = findRegistrations(ePerson);
        assertThat(registrations, hasSize(1));
        assertThat(registrations.get(0).getOrganization(), equalTo("Old University"));

        mockUserInfo("perun1", "perun@email.it", "New University");
        login();

        context.reloadEntity(registrations.get(0));
        registrations = findRegistrations(ePerson);
        assertThat(registrations, hasSize(1));
        assertThat(registrations.get(0).getOrganization(), equalTo("New University"));
    }

    @Test
    public void testOrganizationNotChangedWhenMissing() throws Exception {
        EPerson ePerson = createEPerson("perun@email.it", true);
        createIdentity(ePerson, PROVIDER, "perun1");

        mockUserInfo("perun1", "perun@email.it", null);
        login();

        assertThat(findRegistrations(ePerson), empty());
    }

    @Test
    public void testWithoutSubFallsBackToEmail() throws Exception {
        EPerson ePerson = createEPerson("perun@email.it", true);

        when(oidcClientMock.getUserInfo(ACCESS_TOKEN)).thenReturn(Map.of(EMAIL, "perun@email.it"));

        assertThat(login(), equalTo(ePerson.getID().toString()));
        assertThat(authenticationIdentityService.findByEPerson(context, ePerson), empty());
    }

    private String login() throws Exception {
        MvcResult mvcResult = getClient().perform(get("/api/" + AuthnRest.CATEGORY + "/oidc")
                        .param("code", CODE))
                .andExpect(status().is3xxRedirection())
                .andExpect(cookie().exists("Authorization-cookie"))
                .andReturn();
        return getEPersonIdFromAuthorizationCookie(mvcResult);
    }

    private List<ClarinUserRegistration> findRegistrations(EPerson ePerson) throws Exception {
        context.turnOffAuthorisationSystem();
        try {
            return clarinUserRegistrationService.findByEPersonUUID(context, ePerson.getID());
        } finally {
            context.restoreAuthSystemState();
        }
    }

    private void mockUserInfo(String sub, String email, String organization) {
        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put(SUB, sub);
        userInfo.put(EMAIL, email);
        if (organization != null) {
            userInfo.put(ORGANIZATION, organization);
        }
        when(oidcClientMock.getUserInfo(ACCESS_TOKEN)).thenReturn(userInfo);
    }

    private EPerson createEPerson(String email, boolean canLogIn) throws SQLException {
        context.turnOffAuthorisationSystem();
        EPerson ePerson = EPersonBuilder.createEPerson(context)
                .withEmail(email)
                .withNameInMetadata("Test", "User")
                .withCanLogin(canLogIn)
                .build();
        context.restoreAuthSystemState();
        ePersons.add(ePerson);
        return ePerson;
    }

    private AuthenticationIdentity createIdentity(EPerson ePerson, String provider, String userId) throws Exception {
        context.turnOffAuthorisationSystem();
        AuthenticationIdentity identity =
                authenticationIdentityService.create(context, ePerson, AUTH_METHOD_OIDC, provider, userId);
        // set old date to verify that the last login is updated
        identity.setLastLogin(new Date(0));
        context.restoreAuthSystemState();
        // identity is removed by ON DELETE CASCADE when the EPerson is deleted by the builder cleanup
        context.commit();
        return identity;
    }

    private OidcTokenResponseDTO buildOidcTokenResponse() {
        OidcTokenResponseDTO token = new OidcTokenResponseDTO();
        token.setAccessToken(ACCESS_TOKEN);
        token.setTokenType("Bearer");
        return token;
    }

    private String getEPersonIdFromAuthorizationCookie(MvcResult mvcResult) throws ParseException, JOSEException {
        Cookie authorizationCookie = mvcResult.getResponse().getCookie("Authorization-cookie");
        assertThat(authorizationCookie, notNullValue());
        SignedJWT jwt = SignedJWT.parse(authorizationCookie.getValue());
        return (String) jwt.getJWTClaimsSet().getClaim(EPersonClaimProvider.EPERSON_ID);
    }
}
