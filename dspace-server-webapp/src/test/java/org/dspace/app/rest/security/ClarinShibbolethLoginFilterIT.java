/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.security;

import static org.dspace.app.rest.security.ShibbolethLoginFilterIT.PASS_ONLY;
import static org.dspace.app.rest.security.clarin.ClarinShibbolethLoginFilter.VERIFICATION_TOKEN_HEADER;
import static org.dspace.rdf.negotiation.MediaRange.token;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import jakarta.ws.rs.core.MediaType;
import org.apache.commons.lang3.StringUtils;
import org.dspace.app.rest.authorization.impl.CanChangePasswordFeature;
import org.dspace.app.rest.converter.EPersonConverter;
import org.dspace.app.rest.model.EPersonRest;
import org.dspace.app.rest.model.patch.AddOperation;
import org.dspace.app.rest.model.patch.Operation;
import org.dspace.app.rest.projection.DefaultProjection;
import org.dspace.app.rest.test.AbstractControllerIntegrationTest;
import org.dspace.app.rest.utils.Utils;
import org.dspace.app.util.Util;
import org.dspace.builder.EPersonBuilder;
import org.dspace.builder.GroupBuilder;
import org.dspace.content.clarin.ClarinVerificationToken;
import org.dspace.content.service.clarin.ClarinVerificationTokenService;
import org.dspace.core.Context;
import org.dspace.core.I18nUtil;
import org.dspace.eperson.EPerson;
import org.dspace.eperson.service.EPersonService;
import org.dspace.services.ConfigurationService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The test class for the customized Shibboleth Authentication Process.
 *
 * @author Milan Majchrak (milan.majchrak at dataquest.sk).
 */
public class ClarinShibbolethLoginFilterIT extends AbstractControllerIntegrationTest {

    public static final String[] SHIB_ONLY = {"org.dspace.authenticate.clarin.ClarinShibAuthentication"};
    private static final String NET_ID_EPPN_HEADER = "eppn";
    private static final String NET_ID_PERSISTENT_ID = "persistent-id";
    private static final String NET_ID_TEST_EPERSON = "123456789";
    private static final String IDP_TEST_EPERSON = "Test Idp";
    private static final String KNIHOVNA_KUN_TEST_ZLUTOUCKY = "knihovna Kůň test Žluťoučký";
    private static final String EMAIL_FALLBACK_PROPERTY = "authentication-shibboleth.email-fallback";
    private static final String RECONVERT_ATTRIBUTES_PROPERTY = "authentication-shibboleth.reconvert.attributes";


    private EPersonRest ePersonRest;
    private final String feature = CanChangePasswordFeature.NAME;
    private EPerson clarinEperson;

    @Autowired
    ConfigurationService configurationService;

    @Autowired
    ClarinVerificationTokenService clarinVerificationTokenService;

    @Autowired
    EPersonService ePersonService;

    @Autowired
    private EPersonConverter ePersonConverter;

    @Autowired
    private Utils utils;


    @Before
    public void setup() throws Exception {
        super.setUp();
        // Add a second trusted host for some tests
        configurationService.setProperty("rest.cors.allowed-origins",
                "${dspace.ui.url}, http://anotherdspacehost:4000");

        // Enable Shibboleth login for all tests
        configurationService.setProperty("plugin.sequence.org.dspace.authenticate.AuthenticationMethod", SHIB_ONLY);
        ePersonRest = ePersonConverter.convert(eperson, DefaultProjection.DEFAULT);

        context.turnOffAuthorisationSystem();
        clarinEperson = EPersonBuilder.createEPerson(context)
                .withCanLogin(false)
                .withEmail("clarin@email.com")
                .withNameInMetadata("first", "last")
                .withLanguage(I18nUtil.getDefaultLocale().getLanguage())
                .withNetId(Util.formatNetId(NET_ID_TEST_EPERSON, IDP_TEST_EPERSON))
                .build();
        context.restoreAuthSystemState();
    }

    @Override
    @After
    public void destroy() throws Exception {
        configurationService.setProperty(EMAIL_FALLBACK_PROPERTY, null);
        configurationService.setProperty(RECONVERT_ATTRIBUTES_PROPERTY, null);
        // Remove the created user manually because some tests are failing
        EPersonBuilder.deleteEPerson(clarinEperson.getID());
        super.destroy();
    }

    /**
     * Test the IdP hasn't sent the `Shib-Identity-Provider` or `SHIB-NETID` header.
     */
    @Test
    public void shouldReturnMissingHeadersFromIdpExceptionBecauseOfMissingIdp() throws Exception {
        String idp = "Test Idp";

        // Try to authenticate but the Shibboleth doesn't send the email in the header, so the user won't be registered
        // but the user will be redirected to the page where he will fill in the user email.
        getClient().perform(get("/api/authn/shibboleth"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("http://localhost:4000/login/missing-headers"));
    }

    /**
     * Test:
     * The IdP hasn't sent the `SHIB-EMAIL` header.
     * The request headers passed by IdP are stored into the `verification_token` table the `shib_headers` column.
     * The user is redirected to the page when he must fill his email.
     */
    // HERE
    @Test
    public void shouldReturnUserWithoutEmailException() throws Exception {
        // Create a new netId because the user shouldn't exist
        String netId  = NET_ID_TEST_EPERSON + 986;
        // Try to authenticate but the Shibboleth doesn't send the email in the header, so the user won't be registered
        // but the user will be redirected to the page where he will fill in the user email.
        String redirect = getClient().perform(get("/api/authn/shibboleth")
                        .header("SHIB-NETID", netId)
                        .header("Shib-Identity-Provider", IDP_TEST_EPERSON))
                .andExpect(status().isFound())
                .andReturn().getResponse().getRedirectedUrl();
        assertEquals(authFailedUrl(registrationTokenOf(netId, IDP_TEST_EPERSON)), redirect);
    }

    /**
     * Test the authentication process:
     * 1. The IdP hasn't sent the `SHIB-EMAIL` header and the user is redirected to the page to fill in his email.
     * 2. Test to `ClarinAutoregistrationController.sendEmail` method to send the verification email to the users
     * email.
     * 3. Validate the users verification token and authenticate the user by the verification token. The user is
     * automatically registered and signed in.
     * 4. If the user is registered he is automatically signed in by the NETID which is passed from the IdP.
     * @throws Exception
     */
    @Test
    public void userFillInEmailAndShouldBeRegisteredByVerificationToken() throws Exception {
        String email = "test@mail.epic";
        String netId = email;
        String idp = "Test Idp";

        // Try to authenticate but the Shibboleth doesn't send the email in the header, so the user won't be registered
        // but the user will be redirected to the page where he will fill in the user email.
        String registrationToken = loginWithoutEmail(netId, idp);

        // Send the email with the verification token.
        getClient().perform(post("/api/autoregistration").param("netid", registrationToken).param("email", email))
                .andExpect(status().isOk());

        // Load the created verification token.
        ClarinVerificationToken clarinVerificationToken = clarinVerificationTokenService.findByNetID(context,
                Util.formatNetId(netId, idp));
        assertTrue(Objects.nonNull(clarinVerificationToken));
        String verificationToken = clarinVerificationToken.getToken();

        // Register the user by the verification token.
        getClient().perform(get("/api/autoregistration").param("verification-token", verificationToken))
                .andExpect(status().isOk());

        // Check if was created a user with such email and netid.
        EPerson ePerson = checkUserWasCreated(netId, idp, email, null);

        // The user is registered now log him
        getClient().perform(post("/api/authn/shibboleth")
                        .header(VERIFICATION_TOKEN_HEADER, verificationToken))
                .andExpect(status().isOk())
                .andExpect(header().exists("Authorization"));

        // The e-mailed token was consumed by the login.
        assertNull(clarinVerificationTokenService.findByToken(context, verificationToken));
        getClient().perform(post("/api/authn/shibboleth")
                        .header(VERIFICATION_TOKEN_HEADER, verificationToken))
                .andExpect(status().isFound())
                .andExpect(header().doesNotExist("Authorization"));

        // Try to sign in the user by the email if the eperson exist
        getClient().perform(get("/api/authn/shibboleth")
                        .header("Shib-Identity-Provider", idp)
                        .header("SHIB-NETID", netId)
                        .header("SHIB-MAIL", email))
                .andExpect(status().isFound());

        // Try to sign in the user by the netid if the eperson exist
        getClient().perform(get("/api/authn/shibboleth")
                        .header("Shib-Identity-Provider", idp)
                        .header("SHIB-NETID", netId))
                .andExpect(status().isFound());

        // Delete created eperson - clean after the test
        deleteShibbolethUser(ePerson);
    }

    @Test
    public void testShouldReturnDuplicateUserError() throws Exception {
        String email = "test@mail.epic";
        String netId = email;

        String differentIdP = "Different IdP";

        // login through shibboleth
        String token = getClient().perform(get("/api/authn/shibboleth")
                        .header("SHIB-MAIL", email)
                        .header("SHIB-NETID", netId)
                        .header("Shib-Identity-Provider", IDP_TEST_EPERSON))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost:4000"))
                .andReturn().getResponse().getHeader("Authorization");

        checkUserIsSignedIn(token);

        // Check if was created a user with such email and netid.
        EPerson ePerson = ePersonService.findByNetid(context, Util.formatNetId(netId, IDP_TEST_EPERSON));
        assertTrue(Objects.nonNull(ePerson));
        assertEquals(ePerson.getEmail(), email);
        assertEquals(ePerson.getNetid(), Util.formatNetId(netId, IDP_TEST_EPERSON));

        // Try to login with the same email, but from the different IdP, the user should be redirected to the
        // duplicate error page
        getClient().perform(get("/api/authn/shibboleth")
                        .header("SHIB-MAIL", email)
                        .header("SHIB-NETID", netId)
                        .header("Shib-Identity-Provider", differentIdP))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(duplicateUserUrl(email)))
                .andReturn().getResponse().getHeader("Authorization");

        // Delete created eperson - clean after the test
        EPersonBuilder.deleteEPerson(ePerson.getID());
    }

    // Login with email without netid, but the user with such email already exists and it has assigned netid.
    @Test
    public void testShouldReturnDuplicateUserErrorLoginWithoutNetId() throws Exception {
        String email = "test@email.sk";
        String netId = email;

        // login through shibboleth
        String token = getClient().perform(get("/api/authn/shibboleth")
                        .header("SHIB-MAIL", email)
                        .header("SHIB-NETID", netId)
                        .header("Shib-Identity-Provider", IDP_TEST_EPERSON))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost:4000"))
                .andReturn().getResponse().getHeader("Authorization");

        checkUserIsSignedIn(token);

        // Should not login because the user with such email already exists
        getClient().perform(get("/api/authn/shibboleth")
                        .header("SHIB-MAIL", email)
                        .header("Shib-Identity-Provider", IDP_TEST_EPERSON))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(duplicateUserUrl(email)))
                .andReturn().getResponse().getHeader("Authorization");

        // Check if was created a user with such email and netid.
        EPerson ePerson = checkUserWasCreated(netId, IDP_TEST_EPERSON, email, null);
        deleteShibbolethUser(ePerson);
    }

    // This test is copied from the `ShibbolethLoginFilterIT` and modified following the Clarin updates.
    @Test
    public void testRedirectToGivenTrustedUrl() throws Exception {
        String token = getClient().perform(get("/api/authn/shibboleth")
                        .param("redirectUrl", "http://localhost:8080/server/api/authn/status")
                        .header("SHIB-MAIL", clarinEperson.getEmail())
                        .header("Shib-Identity-Provider", IDP_TEST_EPERSON)
                        .header("SHIB-NETID", NET_ID_TEST_EPERSON))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost:8080/server/api/authn/status"))
                .andReturn().getResponse().getHeader("Authorization");

        checkUserIsSignedIn(token);

        getClient(token).perform(
                        get("/api/authz/authorizations/search/object")
                                .param("embed", "feature")
                                .param("feature", feature)
                                .param("uri", utils.linkToSingleResource(ePersonRest, "self").getHref()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements", is(0)))
                .andExpect(jsonPath("$._embedded").doesNotExist());

    }

    // This test is copied from the `ShibbolethLoginFilterIT` and modified following the Clarin updates.
    @Test
    public void patchPassword() throws Exception {
        String newPassword = "newpassword";

        List<Operation> ops = new ArrayList<Operation>();
        AddOperation addOperation = new AddOperation("/password", newPassword);
        ops.add(addOperation);
        String patchBody = getPatchContent(ops);

        // login through shibboleth
        String token = getClient().perform(get("/api/authn/shibboleth")
                    .header("SHIB-MAIL", clarinEperson.getEmail())
                    .header("Shib-Identity-Provider", IDP_TEST_EPERSON)
                    .header("SHIB-NETID", NET_ID_TEST_EPERSON))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost:4000"))
                .andReturn().getResponse().getHeader("Authorization");

        checkUserIsSignedIn(token);

        // updates password
        getClient(token).perform(patch("/api/eperson/epersons/" + clarinEperson.getID())
                        .content(patchBody)
                        .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                .andExpect(status().isForbidden());

    }

    // This test is copied from the `ShibbolethLoginFilterIT` and modified following the Clarin updates.
    @Test
    public void testRedirectToDefaultDspaceUrl() throws Exception {
        // NOTE: The initial call to /shibboleth comes *from* an external Shibboleth site. So, it is always
        // unauthenticated, but it must include some expected SHIB attributes.
        // SHIB-MAIL attribute is the default email header sent from Shibboleth after a successful login.
        // In this test we are simply mocking that behavior by setting it to an existing EPerson.
        String token = getClient().perform(get("/api/authn/shibboleth")
                    .header("SHIB-MAIL", clarinEperson.getEmail())
                    .header("Shib-Identity-Provider", IDP_TEST_EPERSON)
                    .header("SHIB-NETID", NET_ID_TEST_EPERSON))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost:4000"))
                .andReturn().getResponse().getHeader("Authorization");

        checkUserIsSignedIn(token);

        getClient(token).perform(
                        get("/api/authz/authorizations/search/object")
                                .param("embed", "feature")
                                .param("feature", feature)
                                .param("uri", utils.linkToSingleResource(ePersonRest, "self").getHref()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements", is(0)))
                .andExpect(jsonPath("$._embedded").doesNotExist());

    }

    // This test is copied from the `AuthenticationRestControllerIT` and modified following the Clarin updates.
    @Test
    public void testShibbolethEndpointCannotBeUsedWithShibDisabled() throws Exception {
        // Enable only password login
        configurationService.setProperty("plugin.sequence.org.dspace.authenticate.AuthenticationMethod", PASS_ONLY);

        String uiURL = configurationService.getProperty("dspace.ui.url");

        // Verify /api/authn/shibboleth endpoint does not work
        // NOTE: this is the same call as in testStatusShibAuthenticatedWithCookie())
        String token = getClient().perform(get("/api/authn/shibboleth")
                        .header("Referer", "https://myshib.example.com")
                        .param("redirectUrl", uiURL)
                        .requestAttr("SHIB-MAIL", eperson.getEmail())
                        .requestAttr("SHIB-SCOPED-AFFILIATION", "faculty;staff"))
                .andExpect(status().isFound())
                .andReturn().getResponse().getHeader("Authorization");

        getClient(token).perform(get("/api/authn/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated", is(false)))
                .andExpect(jsonPath("$.authenticationMethod").doesNotExist());

        getClient(token).perform(
                        get("/api/authz/authorizations/search/object")
                                .param("embed", "feature")
                                .param("feature", feature)
                                .param("uri", utils.linkToSingleResource(ePersonRest, "self").getHref()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements", is(0)))
                .andExpect(jsonPath("$._embedded").doesNotExist());
    }

    // This test is copied from the `ShibbolethLoginFilterIT` and modified following the Clarin updates.
    @Test
    public void testNoRedirectIfInvalidShibAttributes() throws Exception {
        // In this request, we use a SHIB-MAIL attribute which does NOT match an EPerson.
        getClient().perform(get("/api/authn/shibboleth")
                        .requestAttr("SHIB-MAIL", "not-an-eperson@example.com"))
                .andExpect(status().isFound());
    }

    // This test is copied from the `ShibbolethLoginFilterIT` and modified following the Clarin updates.
    @Test
    public void testNoRedirectIfShibbolethDisabled() throws Exception {
        // Enable Password authentication ONLY
        configurationService.setProperty("plugin.sequence.org.dspace.authenticate.AuthenticationMethod", PASS_ONLY);

        // Test redirecting to a trusted URL (same as previous test).
        // This time we should be unauthorized as Shibboleth is disabled.
        getClient().perform(get("/api/authn/shibboleth")
                        .param("redirectUrl", "http://localhost:8080/server/api/authn/status")
                        .requestAttr("SHIB-MAIL", eperson.getEmail()))
                .andExpect(status().isFound());
    }

    // This test is copied from the `ShibbolethLoginFilterIT` and modified following the Clarin updates.
    @Test
    public void testRedirectRequiresShibAttributes2() throws Exception {
        String token = getAuthToken(eperson.getEmail(), password);

        // Verify this endpoint also doesn't work using a regular auth token (again if SHIB-* attributes missing)
        getClient(token).perform(get("/api/authn/shibboleth"))
                .andExpect(status().isFound());
    }

    // This test is copied from the `ShibbolethLoginFilterIT` and modified following the Clarin updates.
    @Test
    public void testRedirectRequiresShibAttributes() throws Exception {
        // Verify this endpoint doesn't work if no SHIB-* attributes are set
        getClient().perform(get("/api/authn/shibboleth"))
                .andExpect(status().isFound());
    }

    // This test is copied from the `ShibbolethLoginFilterIT` and modified following the Clarin updates.
    @Test
    public void testRedirectToAnotherGivenTrustedUrl() throws Exception {
        getClient().perform(get("/api/authn/shibboleth")
                        .param("redirectUrl", "http://anotherdspacehost:4000/home")
                        .header("SHIB-MAIL", clarinEperson.getEmail())
                        .header("Shib-Identity-Provider", IDP_TEST_EPERSON)
                        .header("SHIB-NETID", NET_ID_TEST_EPERSON))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://anotherdspacehost:4000/home"));
    }

    // This test is copied from the `ShibbolethLoginFilterIT` and modified following the Clarin updates.
    @Test
    public void testRedirectToGivenUntrustedUrl() throws Exception {
        // Now attempt to redirect to a URL that is NOT trusted (i.e. not in 'rest.cors.allowed-origins').

        // Should result in a 400 error.
        getClient().perform(get("/api/authn/shibboleth")
                        .param("redirectUrl", "http://dspace.org")
                        .header("SHIB-MAIL", clarinEperson.getEmail())
                        .header("Shib-Identity-Provider", IDP_TEST_EPERSON)
                        .header("SHIB-NETID", NET_ID_TEST_EPERSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void testISOShibHeaders() throws Exception {
        String testMail = "test@email.edu";
        String testIdp = IDP_TEST_EPERSON + "test";
        String testNetId = NET_ID_TEST_EPERSON + "000";
        // NOTE: The initial call to /shibboleth comes *from* an external Shibboleth site. So, it is always
        // unauthenticated, but it must include some expected SHIB attributes.
        // SHIB-MAIL attribute is the default email header sent from Shibboleth after a successful login.
        // In this test we are simply mocking that behavior by setting it to an existing EPerson.
        String token = getClient().perform(get("/api/authn/shibboleth")
                        .header("SHIB-MAIL", testMail)
                        .header("Shib-Identity-Provider", testIdp)
                        .header("SHIB-NETID", testNetId)
                        .header("SHIB-GIVENNAME", "knihovna KÅ¯Å\u0088 test Å½luÅ¥ouÄ\u008DkÃ½"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost:4000"))
                .andReturn().getResponse().getHeader("Authorization");

        checkUserIsSignedIn(token);
        // Check if was created a user with such email and netid.
        EPerson ePerson = checkUserWasCreated(testNetId, testIdp, testMail, KNIHOVNA_KUN_TEST_ZLUTOUCKY);
        deleteShibbolethUser(ePerson);
    }

    @Test
    public void testUTF8ShibHeaders() throws Exception {
        String testMail = "test@email.edu";
        String testIdp = IDP_TEST_EPERSON + "test";
        String testNetId = NET_ID_TEST_EPERSON + "000";
        // NOTE: The initial call to /shibboleth comes *from* an external Shibboleth site. So, it is always
        // unauthenticated, but it must include some expected SHIB attributes.
        // SHIB-MAIL attribute is the default email header sent from Shibboleth after a successful login.
        // In this test we are simply mocking that behavior by setting it to an existing EPerson.
        String token = getClient().perform(get("/api/authn/shibboleth")
                        .header("SHIB-MAIL", testMail)
                        .header("Shib-Identity-Provider", testIdp)
                        .header("SHIB-NETID", testNetId)
                        .header("SHIB-GIVENNAME", KNIHOVNA_KUN_TEST_ZLUTOUCKY))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost:4000"))
                .andReturn().getResponse().getHeader("Authorization");

        checkUserIsSignedIn(token);
        // Check if was created a user with such email and netid.
        EPerson ePerson = checkUserWasCreated(testNetId, testIdp, testMail, KNIHOVNA_KUN_TEST_ZLUTOUCKY);
        deleteShibbolethUser(ePerson);
    }

    @Test
    public void testRedirectToMissingHeadersWithRedirectUrlParam() throws Exception {
        String expectedMissingHeadersUrl = configurationService.getProperty("dspace.ui.url") + "/login/missing-headers";

        getClient().perform(get("/api/authn/shibboleth")
                        .param("redirectUrl", "http://localhost:8080/server/api/authn/status")
                        .header("SHIB-MAIL", clarinEperson.getEmail())
                        .header("SHIB-NETID", NET_ID_TEST_EPERSON))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(expectedMissingHeadersUrl));
    }

    // eppn is set
    @Test
    public void testSuccessFullLoginEppnNetId() throws Exception {
        String token = getClient().perform(get("/api/authn/shibboleth")
                        .header("Shib-Identity-Provider", IDP_TEST_EPERSON)
                        .header("SHIB-MAIL", clarinEperson.getEmail())
                        .header(NET_ID_EPPN_HEADER, NET_ID_TEST_EPERSON))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost:4000"))
                .andReturn().getResponse().getHeader("Authorization");

        checkUserIsSignedIn(token);

        EPerson ePerson = checkUserWasCreated(NET_ID_TEST_EPERSON, IDP_TEST_EPERSON, clarinEperson.getEmail(), null);
        deleteShibbolethUser(ePerson);
    }

    // persistent-id is set
    @Test
    public void testSuccessFullLoginPersistentIdNetId() throws Exception {
        String token = getClient().perform(get("/api/authn/shibboleth")
                        .header("Shib-Identity-Provider", IDP_TEST_EPERSON)
                        .header("SHIB-MAIL", clarinEperson.getEmail())
                        .header(NET_ID_PERSISTENT_ID, NET_ID_TEST_EPERSON))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost:4000"))
                .andReturn().getResponse().getHeader("Authorization");

        checkUserIsSignedIn(token);
        EPerson ePerson = checkUserWasCreated(NET_ID_TEST_EPERSON, IDP_TEST_EPERSON, clarinEperson.getEmail(), null);
        deleteShibbolethUser(ePerson);
    }

    @Test
    public void testSuccessFullLoginWithTwoEmails() throws Exception {
        String firstEmail = "efg@test.edu";
        String secondEmail = "abc@test.edu";
        String token = getClient().perform(get("/api/authn/shibboleth")
                        .header("Shib-Identity-Provider", IDP_TEST_EPERSON)
                        .header("SHIB-MAIL", firstEmail + ";" + secondEmail))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost:4000"))
                .andReturn().getResponse().getHeader("Authorization");

        checkUserIsSignedIn(token);
        // Find the user by the second email
        EPerson ePerson = checkUserWasCreated(null, IDP_TEST_EPERSON, secondEmail, null);
        assertTrue(Objects.nonNull(ePerson));
        deleteShibbolethUser(ePerson);
    }

    // The user has changed the email. But that email is already used by another user.
    @Test
    public void testDuplicateEmailError() throws Exception {
        String userWithEppnEmail = "user@eppn.sk";
        String customEppn = "custom eppn";

        // Create a user with netid and email
        String tokenEppnUser = getClient().perform(get("/api/authn/shibboleth")
                        .header("Shib-Identity-Provider", IDP_TEST_EPERSON)
                        .header(NET_ID_PERSISTENT_ID, customEppn)
                        .header("SHIB-MAIL", userWithEppnEmail))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost:4000"))
                .andReturn().getResponse().getHeader("Authorization");

        checkUserIsSignedIn(tokenEppnUser);

        // Try to update an email of existing user - the email is already used by another user - the user should be
        // redirected to the login page
        getClient().perform(get("/api/authn/shibboleth")
                        .header("Shib-Identity-Provider", IDP_TEST_EPERSON)
                        .header(NET_ID_PERSISTENT_ID, NET_ID_TEST_EPERSON)
                        .header("SHIB-MAIL", userWithEppnEmail))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(duplicateUserUrl(userWithEppnEmail)));

        // Check if was created a user with such email and netid.
        EPerson ePerson = checkUserWasCreated(customEppn, IDP_TEST_EPERSON, userWithEppnEmail, null);
        // Delete created eperson - clean after the test
        deleteShibbolethUser(ePerson);
    }

    //  mail=null, eppn=null, persistent-id=somestring
    @Test
    public void shouldAskForEmailWhenHasPersistentId() throws Exception {
        String persistentId = "some pid";

        // Try to log in a user without email, but with persistent id. The user should be redirected to the page where
        // he will fill in the user email.
        String redirect = getClient().perform(get("/api/authn/shibboleth")
                        .header("Shib-Identity-Provider", IDP_TEST_EPERSON)
                        .header(NET_ID_PERSISTENT_ID, persistentId))
                .andExpect(status().isFound())
                .andReturn().getResponse().getRedirectedUrl();
        assertEquals(authFailedUrl(registrationTokenOf(persistentId, IDP_TEST_EPERSON)), redirect);
    }

    // The user was registered and signed in with the verification token on the second attempt, after the email
    // containing the verification token was sent.
    @Test
    public void shouldNotAuthenticateOnSecondAttemptWithoutVerificationTokenInRequest() throws Exception {
        String email = "test@mail.epic";
        String netId = email;
        String idp = "Test Idp";

        // Try to authenticate but the Shibboleth doesn't send the email in the header, so the user won't be registered
        // but the user will be redirected to the page where he will fill in the user email.
        String registrationToken = loginWithoutEmail(netId, idp);

        // Send the email with the verification token.
        getClient().perform(post("/api/autoregistration").param("netid", registrationToken).param("email", email))
                .andExpect(status().isOk());

        // Load the created verification token.
        ClarinVerificationToken clarinVerificationToken = clarinVerificationTokenService.findByNetID(context,
                Util.formatNetId(netId, idp));
        assertTrue(Objects.nonNull(clarinVerificationToken));
        assertEquals(email, clarinVerificationToken.getEmail());

        // Try to authenticate the user again, and it should NOT to be automatically registered and signed in,
        // because the verification token is not passed in the request header. The new attempt starts over.
        String secondRegistrationToken = loginWithoutEmail(netId, idp);
        assertNotEquals(registrationToken, secondRegistrationToken);
        assertNotEquals(clarinVerificationToken.getToken(), secondRegistrationToken);
    }

    @Test
    public void shouldSendShibbolethAuthError() throws Exception {
        String idp = "Test Idp";

        // Try to authenticate but the Shibboleth doesn't send the email or netid in the header,
        // so the user won't be registered but the user will be redirected to the login page with the error message.
        getClient().perform(get("/api/authn/shibboleth")
                        .header("Shib-Identity-Provider", idp))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("http://localhost:4000/login?error=shibboleth-authentication-failed"));
    }

    // Only the browser that did the Shibboleth login can have the e-mail sent.
    @Test
    public void autoregistrationRequiresTheRegistrationToken() throws Exception {
        String netId = "registration-" + UUID.randomUUID();
        String email = "registration@mail.epic";
        String registrationToken = loginWithoutEmail(netId, IDP_TEST_EPERSON);

        // Knowing the netid is not enough.
        for (String guess : new String[] {netId, Util.formatNetId(netId, IDP_TEST_EPERSON)}) {
            getClient().perform(post("/api/autoregistration").param("netid", guess).param("email", email))
                    .andExpect(status().isForbidden());
        }
        assertNull(reloadToken(netId).getEmail());

        // The registration token can neither register nor log in before the e-mail is verified.
        getClient().perform(get("/api/autoregistration").param("verification-token", registrationToken))
                .andExpect(status().isNotFound());
        getClient().perform(post("/api/authn/shibboleth").header(VERIFICATION_TOKEN_HEADER, registrationToken))
                .andExpect(status().isFound())
                .andExpect(header().doesNotExist("Authorization"));
        assertNull(ePersonService.findByNetid(context, Util.formatNetId(netId, IDP_TEST_EPERSON)));

        // The registration token sends the e-mail exactly once and is replaced by the e-mailed token.
        getClient().perform(post("/api/autoregistration").param("netid", registrationToken).param("email", email))
                .andExpect(status().isOk());
        ClarinVerificationToken row = reloadToken(netId);
        assertEquals(email, row.getEmail());
        assertNotEquals(registrationToken, row.getToken());
        for (String used : new String[] {registrationToken, row.getToken()}) {
            getClient().perform(post("/api/autoregistration").param("netid", used).param("email", "other@mail.epic"))
                    .andExpect(status().isForbidden());
        }
        assertEquals(email, reloadToken(netId).getEmail());
        assertEquals(row.getToken(), reloadToken(netId).getToken());

        deleteToken(netId);
    }

    // The registration by token uses the verified e-mail and the stored IdP headers.
    @Test
    public void registrationByTokenIgnoresLiveHeadersAndNeverBindsAnExistingAccount() throws Exception {
        String netId = "registration-" + UUID.randomUUID();
        String registrationToken = loginWithoutEmail(netId, IDP_TEST_EPERSON);

        // The verified e-mail belongs to the admin account, which has no netid: refused, the admin keeps netid NULL.
        getClient().perform(post("/api/autoregistration").param("netid", registrationToken)
                        .param("email", admin.getEmail()))
                .andExpect(status().isOk());
        getClient().perform(get("/api/autoregistration").param("verification-token", reloadToken(netId).getToken())
                        .header("SHIB-MAIL", "attacker@mail.epic"))
                .andExpect(status().isForbidden());
        assertNull(ePersonService.find(context, admin.getID()).getNetid());
        assertNull(ePersonService.findByNetid(context, Util.formatNetId(netId, IDP_TEST_EPERSON)));

        // A fresh e-mail registers; a live SHIB-MAIL header on the confirmation request does not replace it.
        String email = "verified@mail.epic";
        registrationToken = loginWithoutEmail(netId, IDP_TEST_EPERSON);
        getClient().perform(post("/api/autoregistration").param("netid", registrationToken).param("email", email))
                .andExpect(status().isOk());
        getClient().perform(get("/api/autoregistration").param("verification-token", reloadToken(netId).getToken())
                        .header("SHIB-MAIL", "attacker@mail.epic"))
                .andExpect(status().isOk());
        EPerson ePerson = checkUserWasCreated(netId, IDP_TEST_EPERSON, email, null);

        deleteToken(netId);
        deleteShibbolethUser(ePerson);
    }

    // The token login maps groups from the stored IdP headers; role headers on the live request are ignored.
    @Test
    public void tokenLoginIgnoresLiveGroupHeaders() throws Exception {
        context.turnOffAuthorisationSystem();
        GroupBuilder.createGroup(context).withName("UFAL_MEMBER").build();
        context.restoreAuthSystemState();
        String netId = "registration-" + UUID.randomUUID();
        String email = "groups@mail.epic";
        String registrationToken = loginWithoutEmail(netId, IDP_TEST_EPERSON);
        getClient().perform(post("/api/autoregistration").param("netid", registrationToken).param("email", email))
                .andExpect(status().isOk());
        String verificationToken = reloadToken(netId).getToken();

        String token = getClient().perform(post("/api/authn/shibboleth")
                        .header(VERIFICATION_TOKEN_HEADER, verificationToken)
                        .header("entitlement", "staff@org1297.mff.cuni.cz"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader("Authorization").replace("Bearer ", "");

        checkUserIsSignedIn(token);
        getClient(token).perform(get("/api/test/auth/ufal-member"))
                .andExpect(status().isForbidden());

        deleteShibbolethUser(checkUserWasCreated(netId, IDP_TEST_EPERSON, email, null));
    }

    // The stored IdP headers keep every value of a multi-valued attribute, so the token login grants the groups the
    // IdP entitlements map to.
    @Test
    public void tokenLoginMapsGroupsFromStoredMultiValuedHeaders() throws Exception {
        context.turnOffAuthorisationSystem();
        GroupBuilder.createGroup(context).withName("UFAL_MEMBER").build();
        context.restoreAuthSystemState();
        String netId = "registration-" + UUID.randomUUID();
        String email = "entitled@mail.epic";
        getClient().perform(get("/api/authn/shibboleth")
                        .header("Shib-Identity-Provider", IDP_TEST_EPERSON)
                        .header("SHIB-NETID", netId)
                        .header("entitlement", "member@other.org;staff@org1297.mff.cuni.cz"))
                .andExpect(status().isFound());
        getClient().perform(post("/api/autoregistration").param("netid", registrationTokenOf(netId, IDP_TEST_EPERSON))
                .param("email", email)).andExpect(status().isOk());
        String verificationToken = reloadToken(netId).getToken();

        String token = getClient().perform(post("/api/authn/shibboleth")
                        .header(VERIFICATION_TOKEN_HEADER, verificationToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader("Authorization").replace("Bearer ", "");
        getClient(token).perform(get("/api/test/auth/ufal-member"))
                .andExpect(status().isOk());

        deleteShibbolethUser(checkUserWasCreated(netId, IDP_TEST_EPERSON, email, null));
    }

    // A request that carries another user's JWT is authenticated as that user (token refresh) without the token; it
    // must not get a JWT back from the Shibboleth endpoint, and the token stays valid for its owner.
    @Test
    public void tokenLoginWithAnotherUsersJwtDoesNotConsumeTheToken() throws Exception {
        configurationService.setProperty("plugin.sequence.org.dspace.authenticate.AuthenticationMethod",
                new String[] {PASS_ONLY[0], SHIB_ONLY[0]});
        String netId = "registration-" + UUID.randomUUID();
        String email = "owner@mail.epic";
        getClient().perform(post("/api/autoregistration").param("netid", loginWithoutEmail(netId, IDP_TEST_EPERSON))
                .param("email", email)).andExpect(status().isOk());
        String verificationToken = reloadToken(netId).getToken();
        getClient().perform(get("/api/autoregistration").param("verification-token", verificationToken))
                .andExpect(status().isOk());

        String otherUsersJwt = getAuthToken(eperson.getEmail(), password);
        assertTrue(StringUtils.isNotBlank(otherUsersJwt));
        getClient(otherUsersJwt).perform(post("/api/authn/shibboleth")
                        .header(VERIFICATION_TOKEN_HEADER, verificationToken))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("http://localhost:4000/login?error=shibboleth-authentication-failed"));
        assertNotNull(clarinVerificationTokenService.findByToken(context, verificationToken));

        getClient().perform(post("/api/authn/shibboleth").header(VERIFICATION_TOKEN_HEADER, verificationToken))
                .andExpect(status().isOk());
        assertNull(clarinVerificationTokenService.findByToken(context, verificationToken));

        deleteShibbolethUser(checkUserWasCreated(netId, IDP_TEST_EPERSON, email, null));
    }

    // The confirmation registers the identity and the e-mail of the one token it validated; a different token in
    // the login header does not mix in.
    @Test
    public void confirmationUsesOneTokenRow() throws Exception {
        String netIdA = "registration-a-" + UUID.randomUUID();
        String netIdB = "registration-b-" + UUID.randomUUID();
        String emailA = "a@mail.epic";
        String emailB = "b@mail.epic";
        getClient().perform(post("/api/autoregistration").param("netid", loginWithoutEmail(netIdA, IDP_TEST_EPERSON))
                .param("email", emailA)).andExpect(status().isOk());
        getClient().perform(post("/api/autoregistration").param("netid", loginWithoutEmail(netIdB, IDP_TEST_EPERSON))
                .param("email", emailB)).andExpect(status().isOk());

        getClient().perform(get("/api/autoregistration").param("verification-token", reloadToken(netIdA).getToken())
                        .header(VERIFICATION_TOKEN_HEADER, reloadToken(netIdB).getToken()))
                .andExpect(status().isOk());

        EPerson ePersonA = checkUserWasCreated(netIdA, IDP_TEST_EPERSON, emailA, null);
        assertNull(ePersonService.findByNetid(context, Util.formatNetId(netIdB, IDP_TEST_EPERSON)));
        assertNull(ePersonService.findByEmail(context, emailB));

        deleteToken(netIdA);
        deleteToken(netIdB);
        deleteShibbolethUser(ePersonA);
    }

    // The IdP headers stored for the registration were already converted from the IdP charset; the registration by
    // token must not convert them again, with or without the attribute reconversion.
    @Test
    public void registrationByTokenKeepsAccentedNames() throws Exception {
        configurationService.setProperty(RECONVERT_ATTRIBUTES_PROPERTY, true);
        String netId = "registration-" + UUID.randomUUID();
        String email = "accented@mail.epic";
        getClient().perform(get("/api/authn/shibboleth")
                        .header("Shib-Identity-Provider", IDP_TEST_EPERSON)
                        .header("SHIB-NETID", netId)
                        .header("SHIB-GIVENNAME", "knihovna KÅ¯Å\u0088 test Å½luÅ¥ouÄ\u008DkÃ½"))
                .andExpect(status().isFound());
        String registrationToken = registrationTokenOf(netId, IDP_TEST_EPERSON);

        getClient().perform(post("/api/autoregistration").param("netid", registrationToken).param("email", email))
                .andExpect(status().isOk());
        getClient().perform(get("/api/autoregistration").param("verification-token", reloadToken(netId).getToken()))
                .andExpect(status().isOk());
        EPerson ePerson = checkUserWasCreated(netId, IDP_TEST_EPERSON, email, KNIHOVNA_KUN_TEST_ZLUTOUCKY);

        deleteToken(netId);
        deleteShibbolethUser(ePerson);
    }

    // Shibboleth headers are trusted only on /api/authn/shibboleth.
    @Test
    public void loginEndpointIgnoresShibbolethHeaders() throws Exception {
        getClient().perform(post("/api/authn/login")
                        .header("Shib-Identity-Provider", IDP_TEST_EPERSON)
                        .header("SHIB-NETID", NET_ID_TEST_EPERSON)
                        .header("SHIB-MAIL", clarinEperson.getEmail()))
                .andExpect(status().isUnauthorized());

        // 9-base: the login filter matches POST only, so HEAD never reaches an authentication method.
        getClient().perform(head("/api/authn/login")
                        .header("Shib-Identity-Provider", IDP_TEST_EPERSON)
                        .header("SHIB-NETID", NET_ID_TEST_EPERSON)
                        .header("SHIB-MAIL", clarinEperson.getEmail()))
                .andExpect(status().isMethodNotAllowed());

        configurationService.setProperty("plugin.sequence.org.dspace.authenticate.AuthenticationMethod",
                new String[] {PASS_ONLY[0], SHIB_ONLY[0]});
        getClient().perform(post("/api/authn/login")
                        .param("user", clarinEperson.getEmail())
                        .param("password", "wrong")
                        .header("Shib-Identity-Provider", IDP_TEST_EPERSON)
                        .header("SHIB-NETID", NET_ID_TEST_EPERSON)
                        .header("SHIB-MAIL", clarinEperson.getEmail()))
                .andExpect(status().isUnauthorized());

        // The check compares the full request URI, so a context path must not break the real endpoint.
        getClient().perform(get("/server/api/authn/shibboleth").contextPath("/server")
                        .header("Shib-Identity-Provider", IDP_TEST_EPERSON)
                        .header("SHIB-NETID", NET_ID_TEST_EPERSON)
                        .header("SHIB-MAIL", clarinEperson.getEmail()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("http://localhost:4000"));
    }

    // An account found only by e-mail is never bound to the incoming netid.
    @Test
    public void emailOfAccountWithoutNetIdIsNotBoundToNewNetId() throws Exception {
        context.turnOffAuthorisationSystem();
        EPerson localUser = EPersonBuilder.createEPerson(context)
                .withEmail("local+password-user@email.com")
                .withPassword(password)
                .build();
        context.restoreAuthSystemState();

        getClient().perform(get("/api/authn/shibboleth")
                        .header("Shib-Identity-Provider", IDP_TEST_EPERSON)
                        .header("SHIB-NETID", "new-netid")
                        .header("SHIB-MAIL", localUser.getEmail()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl(
                        "http://localhost:4000/login/duplicate-user?email=local%2Bpassword-user%40email.com"));

        assertNull(ePersonService.find(context, localUser.getID()).getNetid());
    }

    @Test
    public void adminEmailIsNotBoundToNewNetIdEvenWithEmailFallback() throws Exception {
        configurationService.setProperty(EMAIL_FALLBACK_PROPERTY, true);

        getClient().perform(get("/api/authn/shibboleth")
                        .header("Shib-Identity-Provider", IDP_TEST_EPERSON)
                        .header("SHIB-NETID", "new-netid")
                        .header("SHIB-MAIL", admin.getEmail()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl(duplicateUserUrl(admin.getEmail())));

        assertNull(ePersonService.find(context, admin.getID()).getNetid());
    }

    @Test
    public void emailFallbackBindsPlainAccountWithoutNetId() throws Exception {
        configurationService.setProperty(EMAIL_FALLBACK_PROPERTY, true);
        context.turnOffAuthorisationSystem();
        EPerson localUser = EPersonBuilder.createEPerson(context)
                .withEmail("local-password-user@email.com")
                .withPassword(password)
                .build();
        context.restoreAuthSystemState();

        getClient().perform(get("/api/authn/shibboleth")
                        .header("Shib-Identity-Provider", IDP_TEST_EPERSON)
                        .header("SHIB-NETID", "new-netid")
                        .header("SHIB-MAIL", localUser.getEmail()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("http://localhost:4000"));

        assertEquals(Util.formatNetId("new-netid", IDP_TEST_EPERSON),
                ePersonService.find(context, localUser.getID()).getNetid());
    }

    // An IdP that sends no netid creates an account without one, so the next login finds it by e-mail.
    @Test
    public void accountWithoutNetIdSignsInAgainOnlyThroughItsIdp() throws Exception {
        String email = "no-netid@mail.epic";
        for (int login = 0; login < 2; login++) {
            String token = getClient().perform(get("/api/authn/shibboleth")
                            .header("Shib-Identity-Provider", IDP_TEST_EPERSON)
                            .header("SHIB-MAIL", email))
                    .andExpect(status().isFound())
                    .andExpect(redirectedUrl("http://localhost:4000"))
                    .andReturn().getResponse().getHeader("Authorization");
            checkUserIsSignedIn(token);
        }

        getClient().perform(get("/api/authn/shibboleth")
                        .header("Shib-Identity-Provider", "Different IdP")
                        .header("SHIB-MAIL", email))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl(duplicateUserUrl(email)));

        // Once the IdP sends a netid, the account gets it.
        getClient().perform(get("/api/authn/shibboleth")
                        .header("Shib-Identity-Provider", IDP_TEST_EPERSON)
                        .header("SHIB-NETID", "late-netid")
                        .header("SHIB-MAIL", email))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("http://localhost:4000"));

        deleteShibbolethUser(checkUserWasCreated("late-netid", IDP_TEST_EPERSON, email, null));
    }

    // The e-mail in a verification token is typed by the user, so it must not take an account without netid.
    @Test
    public void registrationByTokenDoesNotTakeAccountRegisteredWithoutNetId() throws Exception {
        String email = "no-netid-owner@mail.epic";
        getClient().perform(get("/api/authn/shibboleth")
                        .header("Shib-Identity-Provider", IDP_TEST_EPERSON)
                        .header("SHIB-MAIL", email))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("http://localhost:4000"));

        String netId = "registration-" + UUID.randomUUID();
        getClient().perform(post("/api/autoregistration").param("netid", loginWithoutEmail(netId, IDP_TEST_EPERSON))
                        .param("email", email))
                .andExpect(status().isOk());
        getClient().perform(get("/api/autoregistration").param("verification-token", reloadToken(netId).getToken()))
                .andExpect(status().isForbidden());

        EPerson ePerson = checkUserWasCreated(null, null, email, null);
        assertNull(ePerson.getNetid());
        deleteToken(netId);
        deleteShibbolethUser(ePerson);
    }

    /**
     * Shibboleth login that carries a netid but no e-mail: the user is redirected to the page where he fills in the
     * e-mail. The redirect carries the one-time registration token of the pending registration, not the netid.
     * @return the registration token
     */
    private String loginWithoutEmail(String netId, String idp) throws Exception {
        String redirect = getClient().perform(get("/api/authn/shibboleth")
                        .header("Shib-Identity-Provider", idp)
                        .header("SHIB-NETID", netId))
                .andExpect(status().isFound())
                .andReturn().getResponse().getRedirectedUrl();
        String registrationToken = registrationTokenOf(netId, idp);
        assertEquals(authFailedUrl(registrationToken), redirect);
        return registrationToken;
    }

    private String registrationTokenOf(String netId, String idp) throws SQLException {
        ClarinVerificationToken row = reloadToken(netId, idp);
        assertNull(row.getEmail());
        assertNotNull(row.getToken());
        return row.getToken();
    }

    private static String authFailedUrl(String registrationToken) {
        return "http://localhost:4000/login/auth-failed?netid=" + registrationToken;
    }

    private static String duplicateUserUrl(String email) {
        return "http://localhost:4000/login/duplicate-user?email=" + URLEncoder.encode(email, StandardCharsets.UTF_8);
    }

    private ClarinVerificationToken reloadToken(String netId) throws SQLException {
        return reloadToken(netId, IDP_TEST_EPERSON);
    }

    private ClarinVerificationToken reloadToken(String netId, String idp) throws SQLException {
        // A fresh context sees the state committed by the request, not this test's first-level cache.
        try (Context fresh = new Context()) {
            ClarinVerificationToken row = clarinVerificationTokenService.findByNetID(fresh,
                    Util.formatNetId(netId, idp));
            assertNotNull(row);
            return row;
        }
    }

    private void deleteToken(String netId) throws SQLException {
        try (Context fresh = new Context()) {
            ClarinVerificationToken row = clarinVerificationTokenService.findByNetID(fresh,
                    Util.formatNetId(netId, IDP_TEST_EPERSON));
            if (row != null) {
                clarinVerificationTokenService.delete(fresh, row);
            }
            fresh.complete();
        }
    }

    private EPerson checkUserWasCreated(String netIdValue, String idpValue, String email, String name)
            throws SQLException {
        // Check if was created a user with such email and netid.
        EPerson ePerson = null;
        if (netIdValue != null) {
            ePerson = ePersonService.findByNetid(context, Util.formatNetId(netIdValue, idpValue));
        } else {
            ePerson = ePersonService.findByEmail(context, email);
        }
        assertTrue(Objects.nonNull(ePerson));
        if (email != null) {
            assertEquals(ePerson.getEmail(), email);
        }

        if (name != null) {
            assertEquals(ePerson.getFirstName(), name);
        }
        return ePerson;
    }

    private void checkUserIsSignedIn(String token) throws Exception {
        getClient(token).perform(get("/api/authn/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated", is(true)))
                .andExpect(jsonPath("$.authenticationMethod", is("shibboleth")));
    }


    private void deleteShibbolethUser(EPerson ePerson) throws Exception {
        EPersonBuilder.deleteEPerson(ePerson.getID());

        // Check it was correctly deleted
        getClient(token).perform(
                        get("/api/authz/authorizations/search/object")
                                .param("embed", "feature")
                                .param("feature", feature)
                                .param("uri", utils.linkToSingleResource(ePersonRest, "self").getHref()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements", is(0)))
                .andExpect(jsonPath("$._embedded").doesNotExist());
    }
}
