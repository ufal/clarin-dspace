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
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLException;
import java.text.ParseException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.servlet.http.Cookie;

import com.nimbusds.jwt.SignedJWT;
import org.dspace.app.rest.matcher.GroupMatcher;
import org.dspace.app.rest.model.AuthnRest;
import org.dspace.app.rest.security.clarin.ClarinOidcLoginConfigurer;
import org.dspace.app.rest.security.jwt.EPersonClaimProvider;
import org.dspace.app.rest.test.AbstractControllerIntegrationTest;
import org.dspace.authenticate.clarin.ClarinOidcAuthentication;
import org.dspace.authenticate.clarin.ClarinOidcAuthenticationBean;
import org.dspace.authenticate.clarin.oidc.FakeOidcProvider;
import org.dspace.builder.EPersonBuilder;
import org.dspace.builder.GroupBuilder;
import org.dspace.content.clarin.AuthenticationIdentity;
import org.dspace.content.clarin.ClarinUserRegistration;
import org.dspace.content.service.clarin.AuthenticationIdentityService;
import org.dspace.content.service.clarin.ClarinUserRegistrationService;
import org.dspace.eperson.EPerson;
import org.dspace.eperson.service.EPersonService;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.dspace.util.UUIDUtils;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Integration tests for {@link ClarinOidcAuthenticationBean}.
 * ufal/clarin-dspace#1140
 *
 * @author Milan Kuchtiak
 */
// own Spring context: the CLARIN OIDC login is only installed when the plugin is enabled at startup (see
// enablePlugin), so this context is built after that and closed afterwards, not shared with other tests
@TestPropertySource(properties = {"clarin.oidc.test.context = true"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class ClarinOidcAuthenticationRestControllerIT extends AbstractControllerIntegrationTest {

    private final static String CODE = "123456";
    private final static String SUB = "sub";
    private final static String EMAIL = "email";
    private final static String FIRST_NAME = "given_name";
    private final static String LAST_NAME = "family_name";
    private final static String ORGANIZATION = "voperson_external_affiliation";
    private final static String GROUPS = "eduperson_entitlement";
    private final static String EMAIL_VERIFIED = "email_verified";
    private final static String PROVIDER = "oidc";

    private final static String START_URL = "/api/authn/oidc/start/clarin-oidc";
    private final static String CALLBACK_URL = ClarinOidcLoginConfigurer.CALLBACK_PATH;
    private final static String PLUGIN_SEQUENCE = "plugin.sequence.org.dspace.authenticate.AuthenticationMethod";
    private final static List<String> CLARIN_PLUGIN_SEQUENCE = asList(
            ClarinOidcAuthentication.class.getName(), "org.dspace.authenticate.PasswordAuthentication");

    private static String[] originalPluginSequence;

    /** The OIDC provider: discovery, JWKS, token and UserInfo endpoints on a local MockWebServer. */
    private static final FakeOidcProvider provider = new FakeOidcProvider();

    // EPersons whose CLARIN user registrations are removed after the test
    private final List<EPerson> ePersons = new ArrayList<>();

    // EPersons registered by OIDC login, which are not removed by the builder cleanup
    private final List<EPerson> registeredEPersons = new ArrayList<>();

    @Autowired
    private ClarinOidcAuthenticationBean oidcAuthentication;

    @Autowired
    private AuthenticationIdentityService authenticationIdentityService;

    @Autowired
    private ClarinUserRegistrationService clarinUserRegistrationService;

    @Autowired
    private ConfigurationService configurationService;

    @Autowired
    private EPersonService ePersonService;

    @BeforeClass
    public static void startProvider() throws Exception {
        provider.start();
    }

    /** Runs before this class's Spring context is built, i.e. before WebSecurityConfiguration reads it. */
    @BeforeClass
    public static void enablePlugin() {
        ConfigurationService configuration = DSpaceServicesFactory.getInstance().getConfigurationService();
        originalPluginSequence = configuration.getArrayProperty(PLUGIN_SEQUENCE);
        configuration.setProperty(PLUGIN_SEQUENCE, CLARIN_PLUGIN_SEQUENCE);
    }

    @AfterClass
    public static void stopProvider() throws Exception {
        provider.shutdown();
        DSpaceServicesFactory.getInstance().getConfigurationService()
                .setProperty(PLUGIN_SEQUENCE, asList(originalPluginSequence));
    }

    @Before
    public void setup() throws Exception {
        provider.reset();

        // use CLARIN extension of the OIDC plugin, see org.dspace.authenticate.OidcAuthentication
        configurationService.setProperty("authentication-oidc.issuer", provider.getIssuer());
        configurationService.setProperty("authentication-oidc.client-id", FakeOidcProvider.CLIENT_ID);
        configurationService.setProperty("authentication-oidc.client-secret", FakeOidcProvider.CLIENT_SECRET);

        configurationService.setProperty("authentication-oidc.user-info.email", EMAIL);
        configurationService.setProperty("authentication-oidc.user-info.first-name", FIRST_NAME);
        configurationService.setProperty("authentication-oidc.user-info.last-name", LAST_NAME);
        configurationService.setProperty("authentication-oidc.user-info.id", SUB);
        configurationService.setProperty("authentication-oidc.user-info.organization", ORGANIZATION);
        configurationService.setProperty("authentication-oidc.user-info.email-verified", EMAIL_VERIFIED);
        configurationService.setProperty("authentication-oidc.provider", PROVIDER);
        configurationService.setProperty("authentication-oidc.can-self-register", "true");
        configurationService.setProperty("authentication-oidc.require-verified-email", "true");
        // disabled by default, enabled explicitly by the tests that need it
        configurationService.setProperty("authentication-oidc.user-info.groups", null);
        configurationService.setProperty("authentication-oidc.group.staff", null);
        configurationService.setProperty("authentication-oidc.default.auth.group", null);

        configurationService.setProperty(PLUGIN_SEQUENCE, CLARIN_PLUGIN_SEQUENCE);
    }

    @After
    public void after() throws Exception {
        configurationService.setProperty("authentication-oidc.issuer", null);
        configurationService.setProperty("authentication-oidc.client-id", null);
        configurationService.setProperty("authentication-oidc.client-secret", null);

        context.turnOffAuthorisationSystem();
        // user_registration has no foreign key to eperson, remove the registrations explicitly
        List<EPerson> registrationOwners = new ArrayList<>(ePersons);
        registrationOwners.addAll(registeredEPersons);
        for (EPerson ePerson : registrationOwners) {
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
    public void testEPersonNotLoggedInWhenEmailNotVerified() throws Exception {
        EPerson ePerson = createEPerson("perun@email.it", true);
        AuthenticationIdentity identity = createIdentity(ePerson, PROVIDER, "perun1");

        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put(SUB, "perun1");
        userInfo.put(EMAIL, "perun@email.it");
        userInfo.put(EMAIL_VERIFIED, false);
        provider.userInfo = new HashMap<>(userInfo);

        loginFails();

        // the login was rejected before any side effects, even though the identity is already linked
        assertThat(context.reloadEntity(identity).getLastLogin().getTime(), equalTo(0L));
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

        loginFails();

        assertThat(authenticationIdentityService.find(context, AUTH_METHOD_OIDC, PROVIDER, "perun1"), nullValue());
    }

    @Test
    public void testEPersonNotLinkedByEmailWhenNotVerified() throws Exception {
        EPerson ePerson = createEPerson("perun@email.it", true);

        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put(SUB, "perun1");
        userInfo.put(EMAIL, "perun@email.it");
        userInfo.put(EMAIL_VERIFIED, false);
        provider.userInfo = new HashMap<>(userInfo);

        loginFails();

        assertThat(authenticationIdentityService.find(context, AUTH_METHOD_OIDC, PROVIDER, "perun1"), nullValue());
        assertThat(ePersonService.find(context, ePerson.getID()).getEmail(), equalTo("perun@email.it"));
    }

    @Test
    public void testEPersonNotLinkedByEmailWhenVerifiedClaimMissing() throws Exception {
        createEPerson("perun@email.it", true);

        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put(SUB, "perun1");
        userInfo.put(EMAIL, "perun@email.it");
        provider.userInfo = new HashMap<>(userInfo);

        loginFails();

        assertThat(authenticationIdentityService.find(context, AUTH_METHOD_OIDC, PROVIDER, "perun1"), nullValue());
    }

    @Test
    public void testEPersonLinkedByEmailWhenVerificationNotRequired() throws Exception {
        configurationService.setProperty("authentication-oidc.require-verified-email", "false");
        EPerson ePerson = createEPerson("perun@email.it", true);

        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put(SUB, "perun1");
        userInfo.put(EMAIL, "perun@email.it");
        userInfo.put(EMAIL_VERIFIED, false);
        provider.userInfo = new HashMap<>(userInfo);

        assertThat(login(), equalTo(ePerson.getID().toString()));
        assertThat(authenticationIdentityService.find(context, AUTH_METHOD_OIDC, PROVIDER, "perun1").getEPerson(),
                equalTo(ePerson));
    }

    @Test
    public void testNewEPersonRegistered() throws Exception {
        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put(SUB, "perun1");
        userInfo.put(EMAIL, "perun@email.it");
        userInfo.put(FIRST_NAME, "Master");
        userInfo.put(LAST_NAME, "Perun");
        userInfo.put(ORGANIZATION, "Charles University");
        userInfo.put(EMAIL_VERIFIED, true);
        provider.userInfo = new HashMap<>(userInfo);

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
    public void testOnlyFirstOrganizationValueStoredWhenMultipleValues() throws Exception {
        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put(SUB, "perun1");
        userInfo.put(EMAIL, "perun@email.it");
        userInfo.put(ORGANIZATION, List.of("affiliate@einfra.cesnet.cz", "affiliate@google.extidp.cesnet.cz"));
        userInfo.put(EMAIL_VERIFIED, true);
        provider.userInfo = new HashMap<>(userInfo);

        EPerson ePerson = ePersonService.find(context, UUIDUtils.fromString(login()));
        registeredEPersons.add(ePerson);
        ePersons.add(ePerson);

        List<ClarinUserRegistration> registrations = findRegistrations(ePerson);
        assertThat(registrations, hasSize(1));
        assertThat(registrations.get(0).getOrganization(), equalTo("affiliate@einfra.cesnet.cz"));
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

        loginFails();

        assertThat(ePersonService.findByEmail(context, "perun@email.it"), nullValue());
        assertThat(authenticationIdentityService.find(context, AUTH_METHOD_OIDC, PROVIDER, "perun1"), nullValue());
    }

    @Test
    public void testNewEPersonNotRegisteredWhenEmailNotVerified() throws Exception {
        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put(SUB, "perun1");
        userInfo.put(EMAIL, "perun@email.it");
        userInfo.put(EMAIL_VERIFIED, false);
        provider.userInfo = new HashMap<>(userInfo);

        loginFails();

        assertThat(ePersonService.findByEmail(context, "perun@email.it"), nullValue());
        assertThat(authenticationIdentityService.find(context, AUTH_METHOD_OIDC, PROVIDER, "perun1"), nullValue());
    }

    @Test
    public void testWithoutEmailAndIdentity() throws Exception {
        provider.userInfo = new HashMap<>(Map.of(SUB, "perun1"));

        loginFails();
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
    public void testOrganizationSetToUnknownWhenMissingAndNoRegistration() throws Exception {
        EPerson ePerson = createEPerson("perun@email.it", true);
        createIdentity(ePerson, PROVIDER, "perun1");

        mockUserInfo("perun1", "perun@email.it", null);
        login();

        List<ClarinUserRegistration> registrations = findRegistrations(ePerson);
        assertThat(registrations, hasSize(1));
        assertThat(registrations.get(0).getOrganization(), equalTo(ClarinUserRegistration.UNKNOWN_USER_REGISTRATION));
    }

    @Test
    public void testOrganizationNotUpdatedWhenEmailMissing() throws Exception {
        EPerson ePerson = createEPerson("perun@email.it", true);
        createIdentity(ePerson, PROVIDER, "perun1");

        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put(SUB, "perun1");
        userInfo.put(ORGANIZATION, "New University");
        provider.userInfo = new HashMap<>(userInfo);

        assertThat(login(), equalTo(ePerson.getID().toString()));

        // no email was returned, so neither the email nor the organization is updated
        assertThat(context.reloadEntity(ePerson).getEmail(), equalTo("perun@email.it"));
        assertThat(findRegistrations(ePerson), empty());
    }

    @Test
    public void testSpecialGroupsAssignedFromGroups() throws Exception {
        configurationService.setProperty("authentication-oidc.user-info.groups", GROUPS);
        configurationService.setProperty("authentication-oidc.group.staff", "Staff Test Group");

        context.turnOffAuthorisationSystem();
        GroupBuilder.createGroup(context).withName("Staff Test Group").build();
        context.restoreAuthSystemState();

        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put(SUB, "perun1");
        userInfo.put(EMAIL, "perun@email.it");
        userInfo.put(GROUPS, List.of("staff", "unmapped-group"));
        userInfo.put(EMAIL_VERIFIED, true);
        provider.userInfo = new HashMap<>(userInfo);

        MvcResult mvcResult = loginResult();

        registeredEPersons.add(ePersonService.find(context, UUIDUtils.fromString(
                getEPersonIdFromAuthorizationCookie(mvcResult))));

        String token = getTokenFromAuthorizationCookie(mvcResult);
        getClient(token).perform(get("/api/" + AuthnRest.CATEGORY + "/status/specialGroups")
                        .param("projection", "full"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(contentType))
                .andExpect(jsonPath("$._embedded.specialGroups",
                        containsInAnyOrder(GroupMatcher.matchGroupWithName("Staff Test Group"))));
    }

    @Test
    public void testGroupMappingStripsAuthoritySuffixFromEntitlementUrn() throws Exception {
        configurationService.setProperty("authentication-oidc.user-info.groups", GROUPS);
        configurationService.setProperty("authentication-oidc.group.urn:geant:cesnet.cz:group:einfra",
                "eInfra Users");
        configurationService.setProperty("authentication-oidc.group.urn:geant:cesnet.cz:group:einfra:members",
                "eInfra Members");

        context.turnOffAuthorisationSystem();
        GroupBuilder.createGroup(context).withName("eInfra Users").build();
        GroupBuilder.createGroup(context).withName("eInfra Members").build();
        context.restoreAuthSystemState();

        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put(SUB, "perun1");
        userInfo.put(EMAIL, "perun@email.it");
        userInfo.put(GROUPS, List.of(
                "urn:geant:cesnet.cz:group:einfra#Perun Identity and Management System - Maintenance",
                "urn:geant:cesnet.cz:group:einfra:members#Perun Identity and Management System - Maintenance"));
        userInfo.put(EMAIL_VERIFIED, true);
        provider.userInfo = new HashMap<>(userInfo);

        MvcResult mvcResult = loginResult();

        registeredEPersons.add(ePersonService.find(context, UUIDUtils.fromString(
                getEPersonIdFromAuthorizationCookie(mvcResult))));

        String token = getTokenFromAuthorizationCookie(mvcResult);
        getClient(token).perform(get("/api/" + AuthnRest.CATEGORY + "/status/specialGroups")
                        .param("projection", "full"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(contentType))
                .andExpect(jsonPath("$._embedded.specialGroups",
                        containsInAnyOrder(GroupMatcher.matchGroupWithName("eInfra Users"),
                                GroupMatcher.matchGroupWithName("eInfra Members"))));
    }

    @Test
    public void testDefaultAuthGroupAssignedOnLogin() throws Exception {
        configurationService.setProperty("authentication-oidc.default.auth.group", "Authenticated");
        configurationService.setProperty("authentication-oidc.user-info.groups", GROUPS);
        configurationService.setProperty("authentication-oidc.group.staff", "Staff Test Group");

        context.turnOffAuthorisationSystem();
        GroupBuilder.createGroup(context).withName("Authenticated").build();
        GroupBuilder.createGroup(context).withName("Staff Test Group").build();
        context.restoreAuthSystemState();

        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put(SUB, "perun1");
        userInfo.put(EMAIL, "perun@email.it");
        userInfo.put(GROUPS, List.of("staff"));
        userInfo.put(EMAIL_VERIFIED, true);
        provider.userInfo = new HashMap<>(userInfo);

        MvcResult mvcResult = loginResult();

        registeredEPersons.add(ePersonService.find(context, UUIDUtils.fromString(
                getEPersonIdFromAuthorizationCookie(mvcResult))));

        String token = getTokenFromAuthorizationCookie(mvcResult);
        getClient(token).perform(get("/api/" + AuthnRest.CATEGORY + "/status/specialGroups")
                        .param("projection", "full"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(contentType))
                .andExpect(jsonPath("$._embedded.specialGroups",
                        containsInAnyOrder(GroupMatcher.matchGroupWithName("Authenticated"),
                                GroupMatcher.matchGroupWithName("Staff Test Group"))));
    }

    @Test
    public void testNoDefaultAuthGroupWhenNotConfigured() throws Exception {
        context.turnOffAuthorisationSystem();
        GroupBuilder.createGroup(context).withName("Authenticated").build();
        context.restoreAuthSystemState();

        mockUserInfo("perun1", "perun@email.it", null);

        MvcResult mvcResult = loginResult();

        registeredEPersons.add(ePersonService.find(context, UUIDUtils.fromString(
                getEPersonIdFromAuthorizationCookie(mvcResult))));

        String token = getTokenFromAuthorizationCookie(mvcResult);
        getClient(token).perform(get("/api/" + AuthnRest.CATEGORY + "/status/specialGroups")
                        .param("projection", "full"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(contentType))
                .andExpect(jsonPath("$._embedded.specialGroups", empty()));
    }

    @Test
    public void testNoSpecialGroupsWhenGroupsAttributeNotConfigured() throws Exception {
        context.turnOffAuthorisationSystem();
        GroupBuilder.createGroup(context).withName("Staff Test Group").build();
        context.restoreAuthSystemState();

        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put(SUB, "perun1");
        userInfo.put(EMAIL, "perun@email.it");
        userInfo.put(GROUPS, List.of("staff"));
        userInfo.put(EMAIL_VERIFIED, true);
        provider.userInfo = new HashMap<>(userInfo);

        MvcResult mvcResult = loginResult();

        registeredEPersons.add(ePersonService.find(context, UUIDUtils.fromString(
                getEPersonIdFromAuthorizationCookie(mvcResult))));

        String token = getTokenFromAuthorizationCookie(mvcResult);
        getClient(token).perform(get("/api/" + AuthnRest.CATEGORY + "/status/specialGroups")
                        .param("projection", "full"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(contentType))
                .andExpect(jsonPath("$._embedded.specialGroups", empty()));
    }

    @Test
    public void testLoginFailsWithoutSub() throws Exception {
        createEPerson("perun@email.it", true);

        // sub is a required ID token claim
        provider.idTokenSubject = null;
        provider.userInfo = new HashMap<>(Map.of(EMAIL, "perun@email.it", EMAIL_VERIFIED, true));

        loginFails();
    }

    @Test
    public void testLoginFailsWithBadIdTokenSignature() throws Exception {
        provider.idTokenSigningKey = FakeOidcProvider.generateRsaKey("other-key");
        mockUserInfo("perun1", "perun@email.it", null);

        loginFails();
    }

    @Test
    public void testLoginFailsWithUnsignedIdToken() throws Exception {
        provider.unsignedIdToken = true;
        mockUserInfo("perun1", "perun@email.it", null);

        loginFails();
    }

    @Test
    public void testLoginFailsWithExpiredIdToken() throws Exception {
        provider.idTokenExpiresAt = Instant.now().minusSeconds(3600);
        mockUserInfo("perun1", "perun@email.it", null);

        loginFails();
    }

    @Test
    public void testLoginFailsWithWrongIssuer() throws Exception {
        provider.idTokenIssuer = "https://wrong-issuer.example.org";
        mockUserInfo("perun1", "perun@email.it", null);

        loginFails();
    }

    @Test
    public void testLoginFailsWithWrongAudience() throws Exception {
        provider.idTokenAudience = "wrong-client-id";
        mockUserInfo("perun1", "perun@email.it", null);

        loginFails();
    }

    @Test
    public void testLoginFailsWithWrongNonce() throws Exception {
        provider.idTokenNonce = "nonce-of-another-login";
        mockUserInfo("perun1", "perun@email.it", null);

        loginFails();
    }

    @Test
    public void testLoginFailsWhenUserInfoSubDoesNotMatchIdToken() throws Exception {
        // the sub returned by /userinfo doesn't match the verified ID token's sub
        mockUserInfo("other-sub", "perun@email.it", null);

        loginFails();
    }

    @Test
    public void testLoginFailsWithMismatchedAzp() throws Exception {
        // aud correctly contains the client ID, but azp (authorized party) names a different client
        provider.idTokenClaims.put("azp", "wrong-client-id");
        mockUserInfo("perun1", "perun@email.it", null);

        loginFails();
    }

    @Test
    public void testLoginFailsWithIdTokenMissingExpiration() throws Exception {
        // a missing (not just expired) exp must be rejected, exp is a required ID token claim
        provider.idTokenWithoutExpiration = true;
        mockUserInfo("perun1", "perun@email.it", null);

        loginFails();
    }

    @Test
    public void testLoginFailsWhenUserInfoHasNoSub() throws Exception {
        // user info omits sub entirely (as opposed to a mismatching one): its email must not be trusted
        // without confirming it belongs to the authenticated subject
        provider.userInfo = new HashMap<>(Map.of(EMAIL, "perun@email.it", EMAIL_VERIFIED, true));

        loginFails();
    }

    @Test
    public void testLoginFailsWhenUserInfoRequestFails() throws Exception {
        // the UserInfo endpoint answers HTTP 500
        provider.userInfo = null;

        loginFails();
    }

    @Test
    public void testLoginUsesPkce() throws Exception {
        EPerson ePerson = createEPerson("perun@email.it", true);
        createIdentity(ePerson, PROVIDER, "perun1");
        mockUserInfo("perun1", "perun@email.it", null);

        MvcResult start = startLogin();
        Map<String, String> authorizationParams = provider.authorize(start.getResponse().getRedirectedUrl());
        assertThat(authorizationParams.get("code_challenge_method"), equalTo("S256"));
        assertThat(authorizationParams.get("nonce"), notNullValue());

        assertThat(getEPersonIdFromAuthorizationCookie(callback(start, authorizationParams.get("state"))
                .andExpect(status().is3xxRedirection())
                .andReturn()), equalTo(ePerson.getID().toString()));
        assertThat(FakeOidcProvider.parseQuery(provider.getTokenRequestBodies().get(0)).get("code_verifier"),
                notNullValue());
    }

    /**
     * Login CSRF: an attacker sends the victim's browser to the callback with a code (and state) issued for the
     * attacker's own account. Without the session of a login started in this browser, the code is
     * not even exchanged.
     */
    @Test
    public void testCallbackWithoutSessionFails() throws Exception {
        EPerson ePerson = createEPerson("perun@email.it", true);
        createIdentity(ePerson, PROVIDER, "perun1");
        mockUserInfo("perun1", "perun@email.it", null);

        MvcResult attackersStart = startLogin();
        String attackersState = provider.authorize(attackersStart.getResponse().getRedirectedUrl()).get("state");

        getClient().perform(get(CALLBACK_URL)
                        .param("code", CODE).param("state", attackersState))
                .andExpect(status().isUnauthorized())
                .andExpect(cookie().doesNotExist("Authorization-cookie"));
        assertThat(provider.getTokenRequestBodies(), empty());
    }

    @Test
    public void testCallbackWithStateOfAnotherLoginFails() throws Exception {
        EPerson ePerson = createEPerson("perun@email.it", true);
        createIdentity(ePerson, PROVIDER, "perun1");
        mockUserInfo("perun1", "perun@email.it", null);

        MvcResult attackersStart = startLogin();
        String attackersState = provider.authorize(attackersStart.getResponse().getRedirectedUrl()).get("state");
        MvcResult victimsStart = startLogin();

        callback(victimsStart, attackersState)
                .andExpect(status().isUnauthorized())
                .andExpect(cookie().doesNotExist("Authorization-cookie"));
        assertThat(provider.getTokenRequestBodies(), empty());
    }

    @Test
    public void testRedirectsToUrlGivenAtStartAndIgnoresCallbackParameter() throws Exception {
        EPerson ePerson = createEPerson("perun@email.it", true);
        createIdentity(ePerson, PROVIDER, "perun1");
        mockUserInfo("perun1", "perun@email.it", null);

        String itemPage = configurationService.getProperty("dspace.ui.url") + "/items/123";
        MvcResult start = getClient().perform(get(START_URL)
                        .param("redirectUrl", itemPage))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        String state = provider.authorize(start.getResponse().getRedirectedUrl()).get("state");

        getClient().perform(get(CALLBACK_URL)
                        .param("code", CODE).param("state", state)
                        .param("redirectUrl", "https://evil.example.org/")
                        .session(session(start)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(itemPage));
    }

    /** A redirectUrl that isn't the server, UI or an allowed origin is replaced by the UI URL. */
    @Test
    public void testForeignRedirectUrlFallsBackToUi() throws Exception {
        EPerson ePerson = createEPerson("perun@email.it", true);
        createIdentity(ePerson, PROVIDER, "perun1");
        mockUserInfo("perun1", "perun@email.it", null);

        String uiHost = java.net.URI.create(configurationService.getProperty("dspace.ui.url")).getHost();
        for (String redirectUrl : List.of("https://evil.example.org/",
                "https://evil.example.org\\@" + uiHost + "/",
                "https://user@" + uiHost + "/",
                "javascript:alert(1)")) {
            MvcResult start = getClient().perform(get(START_URL).param("redirectUrl", redirectUrl))
                    .andExpect(status().is3xxRedirection())
                    .andReturn();
            String state = provider.authorize(start.getResponse().getRedirectedUrl()).get("state");
            callback(start, state)
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl(configurationService.getProperty("dspace.ui.url")));
        }
    }

    /**
     * The stock OidcLoginFilter stays registered on /api/authn/oidc: a callback sent there (code and state of a
     * login started in this browser) must not log anyone in, nor reach the provider.
     */
    @Test
    public void testStockCallbackPathDoesNotLogIn() throws Exception {
        EPerson ePerson = createEPerson("perun@email.it", true);
        createIdentity(ePerson, PROVIDER, "perun1");
        mockUserInfo("perun1", "perun@email.it", null);

        MvcResult start = startLogin();
        String state = provider.authorize(start.getResponse().getRedirectedUrl()).get("state");

        getClient().perform(get("/api/" + AuthnRest.CATEGORY + "/oidc")
                        .param("code", CODE).param("state", state)
                        .session(session(start)))
                .andExpect(status().isUnauthorized())
                .andExpect(cookie().doesNotExist("Authorization-cookie"));
        assertThat(provider.getTokenRequestBodies(), empty());
    }

    @Test
    public void testRedirectUriIsTheCallbackPath() throws Exception {
        MvcResult start = startLogin();
        assertThat(provider.authorize(start.getResponse().getRedirectedUrl()).get("redirect_uri"),
                is(configurationService.getProperty("dspace.server.url") + CALLBACK_URL));
    }

    @Test
    public void testLoginPageUrlPointsToStartEndpoint() throws Exception {
        MvcResult mvcResult = getClient().perform(get("/api/" + AuthnRest.CATEGORY + "/status"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(mvcResult.getResponse().getHeader("WWW-Authenticate"),
                containsString("location=\"" + configurationService.getProperty("dspace.server.url")
                        + ClarinOidcAuthenticationBean.START_PATH + "?redirectUrl="));
    }

    @Test
    public void testProviderDefaultsToVerifiedIssuer() throws Exception {
        configurationService.setProperty("authentication-oidc.provider", null);
        mockUserInfo("perun1", "perun@email.it", null);

        String ePersonId = login();
        registeredEPersons.add(ePersonService.find(context, UUIDUtils.fromString(ePersonId)));

        AuthenticationIdentity identity = authenticationIdentityService.find(context, AUTH_METHOD_OIDC,
                provider.getIssuer(), "perun1");
        assertThat(identity, notNullValue());
        assertThat(identity.getEPerson().getID().toString(), equalTo(ePersonId));
    }

    /**
     * The ID token's email_verified=true must not vouch for a different, unverified email returned by
     * UserInfo (email and email_verified are always taken from the same source).
     */
    @Test
    public void testIdTokenEmailVerifiedDoesNotVouchForUserInfoEmail() throws Exception {
        createEPerson("admin@email.it", true);
        provider.idTokenClaims.put(EMAIL, "perun@email.it");
        provider.idTokenClaims.put(EMAIL_VERIFIED, true);
        provider.userInfo = new HashMap<>(Map.of(SUB, "perun1", EMAIL, "admin@email.it"));

        loginFails();
        assertThat(authenticationIdentityService.find(context, AUTH_METHOD_OIDC, PROVIDER, "perun1"), nullValue());
    }

    /** Start a login like the UI does: GET the start endpoint, which redirects to the provider. */
    private MvcResult startLogin() throws Exception {
        MvcResult start = getClient().perform(get(START_URL)
                        .param("redirectUrl", configurationService.getProperty("dspace.ui.url")))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        assertThat(session(start), notNullValue());
        return start;
    }

    /** The browser's HTTP session (JSESSIONID) created by the start of the login. */
    private static MockHttpSession session(MvcResult start) {
        return (MockHttpSession) start.getRequest().getSession(false);
    }

    /** The browser comes back from the provider to the redirect_uri with a code and the given state. */
    private ResultActions callback(MvcResult start, String state) throws Exception {
        return getClient().perform(get(CALLBACK_URL)
                .param("code", CODE)
                .param("state", state)
                .session(session(start)));
    }

    /** Start a login, let the (fake) provider authorize it and complete it at the redirect_uri. */
    private MvcResult completeLogin(ResultMatcherGroup expectations) throws Exception {
        MvcResult start = startLogin();
        // keep a reference: once invalidated, the request no longer returns the session
        MockHttpSession session = session(start);
        String state = provider.authorize(start.getResponse().getRedirectedUrl()).get("state");
        MvcResult result = expectations.apply(callback(start, state)).andReturn();
        // the session (pending login, provider's tokens) only lives until the end of the login
        assertThat(session.isInvalid(), is(true));
        return result;
    }

    private interface ResultMatcherGroup {
        ResultActions apply(ResultActions actions) throws Exception;
    }

    private MvcResult loginResult() throws Exception {
        return completeLogin(actions -> actions
                .andExpect(status().is3xxRedirection())
                .andExpect(cookie().exists("Authorization-cookie")));
    }

    private String login() throws Exception {
        return getEPersonIdFromAuthorizationCookie(loginResult());
    }

    private void loginFails() throws Exception {
        completeLogin(actions -> actions
                .andExpect(status().isUnauthorized())
                .andExpect(cookie().doesNotExist("Authorization-cookie")));
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
        // most IdPs verify the email before issuing it in a claim; tests covering the unverified case
        // build their own userInfo map instead of using this helper
        userInfo.put(EMAIL_VERIFIED, true);
        if (organization != null) {
            userInfo.put(ORGANIZATION, organization);
        }
        provider.userInfo = new HashMap<>(userInfo);
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

    private String getEPersonIdFromAuthorizationCookie(MvcResult mvcResult) throws ParseException {
        Cookie authorizationCookie = mvcResult.getResponse().getCookie("Authorization-cookie");
        assertThat(authorizationCookie, notNullValue());
        SignedJWT jwt = SignedJWT.parse(authorizationCookie.getValue());
        return (String) jwt.getJWTClaimsSet().getClaim(EPersonClaimProvider.EPERSON_ID);
    }

    private String getTokenFromAuthorizationCookie(MvcResult mvcResult) {
        Cookie authorizationCookie = mvcResult.getResponse().getCookie("Authorization-cookie");
        assertThat(authorizationCookie, notNullValue());
        return authorizationCookie.getValue();
    }
}
