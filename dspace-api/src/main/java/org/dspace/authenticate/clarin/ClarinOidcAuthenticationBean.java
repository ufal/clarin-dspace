/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.authenticate.clarin;

import static java.lang.String.format;
import static java.net.URLEncoder.encode;
import static org.apache.commons.lang.BooleanUtils.toBoolean;
import static org.apache.commons.lang3.StringUtils.isAnyBlank;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.dspace.content.clarin.AuthenticationIdentity.AUTH_METHOD_OIDC;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.stream.Collectors;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.dspace.authenticate.AuthenticationMethod;
import org.dspace.authenticate.OidcAuthenticationBean;
import org.dspace.authenticate.oidc.OidcClient;
import org.dspace.authenticate.oidc.model.OidcTokenResponseDTO;
import org.dspace.authorize.AuthorizeException;
import org.dspace.content.clarin.AuthenticationIdentity;
import org.dspace.content.clarin.ClarinUserRegistration;
import org.dspace.content.service.clarin.AuthenticationIdentityService;
import org.dspace.content.service.clarin.ClarinUserRegistrationService;
import org.dspace.core.Context;
import org.dspace.eperson.EPerson;
import org.dspace.eperson.Group;
import org.dspace.eperson.service.EPersonService;
import org.dspace.eperson.service.GroupService;
import org.dspace.services.ConfigurationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * CLARIN extension of the OIDC authentication, implemented independently of {@link OidcAuthenticationBean}
 * (not by extending it) so that it can verify the ID token instead of only trusting the user info endpoint
 * response. The ID token's signature is verified against the provider's JSON Web Key Set
 * (authentication-oidc.jwks-uri), and its "iss" and "aud" claims are checked against
 * authentication-oidc.issuer and authentication-oidc.client-id; expiry is checked as part of the same
 * verification. The verified ID token claims and the user info endpoint claims are then merged (user info
 * claims take precedence on overlapping keys, per OIDC Core 5.3.2) into the same claims map used below.
 * <p>
 * If the provider returns an email that it doesn't confirm is verified, authentication fails immediately
 * (see {@link #isEmailVerified}). Otherwise, the user is identified by the OIDC user ID (the "sub" claim by
 * default) stored in the authentication_identity table, instead of the email which may change:
 * <ol>
 *     <li>the EPerson linked to the (oidc, provider, user ID) identity is logged in</li>
 *     <li>otherwise the identity is linked to the existing EPerson with the same email (e.g. created by Shibboleth)
 *     </li>
 *     <li>otherwise a new EPerson is registered, together with the identity and CLARIN user registration</li>
 * </ol>
 * As in {@link ClarinShibAuthentication}, the EPerson canLogIn flag is not checked, because it only controls
 * the password login in CLARIN.
 * ufal/clarin-dspace#1140
 *
 * @author Milan Kuchtiak
 */
public class ClarinOidcAuthenticationBean implements AuthenticationMethod {

    private static final Logger log = LogManager.getLogger(ClarinOidcAuthenticationBean.class);

    private static final String LOGIN_PAGE_URL_FORMAT =
            "%s?client_id=%s&response_type=code&scope=%s&redirect_uri=%s&state=%s";

    private static final String OIDC_AUTHENTICATED = "oidc.authenticated";

    public static final String DEFAULT_PROVIDER = "oidc";
    public static final String DEFAULT_USER_ID_ATTRIBUTE = "sub";
    public static final String DEFAULT_EMAIL_VERIFIED_ATTRIBUTE = "email_verified";

    /**
     * Name of the cookie binding the OIDC authorization request to the browser that initiated it, to
     * prevent login CSRF (an attacker completing their own login, then tricking a victim's browser into
     * loading the resulting callback URL, logging the victim into the attacker's account). Public so
     * {@link org.dspace.app.rest.security.OidcLoginFilter} can clear it after a successful login.
     */
    public static final String STATE_COOKIE_NAME = "dspace.oidc.state";

    private static final int STATE_COOKIE_MAX_AGE_SECONDS = 600;

    private static final SecureRandom STATE_RANDOM = new SecureRandom();

    @Autowired
    private ConfigurationService configurationService;

    @Autowired
    private OidcClient oidcClient;

    @Autowired
    private EPersonService ePersonService;

    @Autowired
    private AuthenticationIdentityService authenticationIdentityService;

    @Autowired
    private ClarinUserRegistrationService clarinUserRegistrationService;

    @Autowired
    private GroupService groupService;

    private JwtDecoder jwtDecoder;

    @Override
    public int authenticate(Context context, String username, String password, String realm, HttpServletRequest request)
            throws SQLException {

        if (request == null) {
            log.warn("Unable to authenticate using OIDC because the request object is null.");
            return BAD_ARGS;
        }

        if (request.getAttribute(OidcAuthenticationBean.OIDC_AUTH_ATTRIBUTE) == null) {
            return NO_SUCH_USER;
        }

        if (!isStateValid(request)) {
            log.warn("OIDC authentication failed, because the state parameter is missing or does not match " +
                    "the " + STATE_COOKIE_NAME + " cookie set when the login was initiated");
            return BAD_CREDENTIALS;
        }

        String code = request.getParameter("code");
        if (StringUtils.isEmpty(code)) {
            log.warn("The incoming request has not code parameter");
            return NO_SUCH_USER;
        }

        return authenticateWithOidc(context, code, request);
    }

    /**
     * Returns whether the "state" request parameter returned by the OIDC provider matches the value this
     * bean set in the {@link #STATE_COOKIE_NAME} cookie when it built the authorization redirect URL (see
     * {@link #loginPageURL}). This binds the authorization request to the browser that initiated it,
     * preventing an attacker from tricking a victim's browser into completing the attacker's own login
     * (login CSRF / authorization code injection, see RFC 9700).
     */
    private boolean isStateValid(HttpServletRequest request) {
        String state = request.getParameter("state");
        if (StringUtils.isBlank(state)) {
            return false;
        }
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return false;
        }
        for (Cookie cookie : cookies) {
            if (STATE_COOKIE_NAME.equals(cookie.getName())) {
                // constant-time comparison, so a timing side-channel can't be used to guess the cookie
                // value one character at a time
                return MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8),
                        cookie.getValue().getBytes(StandardCharsets.UTF_8));
            }
        }
        return false;
    }

    private int authenticateWithOidc(Context context, String code, HttpServletRequest request) throws SQLException {
        OidcTokenResponseDTO tokenResponse = getOidcAccessToken(code);
        if (tokenResponse == null || StringUtils.isBlank(tokenResponse.getIdToken())) {
            log.warn("No access/ID token retrieved by code");
            return NO_SUCH_USER;
        }

        OidcIdToken idToken;
        try {
            Jwt jwt = getJwtDecoder().decode(tokenResponse.getIdToken());
            idToken = new OidcIdToken(jwt.getTokenValue(), jwt.getIssuedAt(), jwt.getExpiresAt(), jwt.getClaims());
        } catch (JwtException ex) {
            log.warn("OIDC authentication failed, because the ID token could not be verified", ex);
            return BAD_CREDENTIALS;
        }

        Map<String, Object> userInfoClaims = getOidcUserInfo(tokenResponse.getAccessToken());
        if (userInfoClaims == null || userInfoClaims.isEmpty()) {
            // OidcUserInfo rejects an empty claims map with IllegalArgumentException; getOidcUserInfo()
            // already returns an empty map on any provider failure (timeout, non-2xx, empty body), so this
            // must be treated as an authentication failure here rather than letting that exception escape
            // as an unhandled server error
            log.warn("OIDC authentication failed, because no user info could be retrieved for the access token");
            return BAD_CREDENTIALS;
        }
        OidcUserInfo userInfo = new OidcUserInfo(userInfoClaims);
        // the sub claim is REQUIRED in the user info response (OIDC Core 5.3.2) and MUST match the verified
        // ID token's subject; a blank sub is not skipped, since that would let its email/group claims be
        // used without confirming they belong to the authenticated subject
        if (StringUtils.isBlank(userInfo.getSubject()) || !userInfo.getSubject().equals(idToken.getSubject())) {
            log.warn("OIDC authentication failed, because the sub claim of the user info ({}) does not match the " +
                    "verified ID token ({})", userInfo.getSubject(), idToken.getSubject());
            return BAD_CREDENTIALS;
        }

        // The ID token claims are signature-verified and form the trusted base; user info claims (only
        // TLS-trusted, not signature-verified) take precedence for overlapping keys, per OIDC Core 5.3.2.
        Map<String, Object> claims = new HashMap<>(idToken.getClaims());
        String emailAttribute = getEmailAttribute();
        String emailVerifiedAttribute = getEmailVerifiedAttribute();
        if (StringUtils.isNotBlank(emailAttribute) && userInfoClaims.containsKey(emailAttribute)
                && !userInfoClaims.containsKey(emailVerifiedAttribute)
                && !Objects.equals(claims.get(emailAttribute), userInfoClaims.get(emailAttribute))) {
            // user info overrides the email without (re-)confirming it is verified; the inherited verified
            // flag from the ID token was only ever vouching for the ID token's own email value, so it must
            // not be allowed to carry over and apply to a different, unverified email from user info
            claims.remove(emailVerifiedAttribute);
        }
        claims.putAll(userInfoClaims);

        return authenticateWithClaims(context, claims, request);
    }

    /**
     * Returns the {@link JwtDecoder} used to verify the signature, issuer, audience and expiry of the ID
     * token, built on first use from authentication-oidc.jwks-uri/issuer/client-id and cached.
     * Synchronized because this bean is a singleton invoked concurrently by authenticate() across requests.
     */
    private synchronized JwtDecoder getJwtDecoder() {
        if (jwtDecoder == null) {
            jwtDecoder = buildJwtDecoder();
        }
        return jwtDecoder;
    }

    private JwtDecoder buildJwtDecoder() {
        String jwksUri = configurationService.getProperty("authentication-oidc.jwks-uri");
        String issuer = configurationService.getProperty("authentication-oidc.issuer");
        String clientId = configurationService.getProperty("authentication-oidc.client-id");
        if (isAnyBlank(jwksUri, issuer, clientId)) {
            throw new IllegalStateException("authentication-oidc.jwks-uri, authentication-oidc.issuer and "
                    + "authentication-oidc.client-id must all be set to use the CLARIN OIDC extension");
        }

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri).build();
        decoder.setJwtValidator(buildJwtValidator(issuer, clientId));
        return decoder;
    }

    /**
     * Builds the ID token validator chain (issuer, audience, required claims), kept separate from
     * {@link #buildJwtDecoder} so tests can exercise this exact production validator logic (via
     * ReflectionTestUtils) against a decoder backed by a local test key, instead of maintaining a
     * separately duplicated copy that wouldn't notice if a validator were ever removed here.
     */
    private static OAuth2TokenValidator<Jwt> buildJwtValidator(String issuer, String clientId) {
        OAuth2TokenValidator<Jwt> withIssuer = JwtValidators.createDefaultWithIssuer(issuer);
        OAuth2TokenValidator<Jwt> withAudience = jwt -> jwt.getAudience() != null
                && jwt.getAudience().contains(clientId)
                && (!jwt.getClaims().containsKey("azp") || clientId.equals(jwt.getClaimAsString("azp")))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token",
                        "The aud or azp claim does not identify the configured client ID", null));
        // JwtValidators.createDefaultWithIssuer only checks exp/nbf when present, it doesn't require them;
        // exp, iat and sub are REQUIRED ID token claims (OIDC Core 2), so a token omitting them must not
        // be trusted, e.g. a correctly signed token with no exp would otherwise never expire
        OAuth2TokenValidator<Jwt> withRequiredClaims = jwt -> jwt.getExpiresAt() != null
                && jwt.getIssuedAt() != null
                && StringUtils.isNotBlank(jwt.getSubject())
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token",
                        "The exp, iat and sub claims are required", null));
        return new DelegatingOAuth2TokenValidator<>(withIssuer, withAudience, withRequiredClaims);
    }

    private int authenticateWithClaims(Context context, Map<String, Object> userInfo, HttpServletRequest request)
            throws SQLException {
        String userIdAttribute = getUserIdAttribute();
        String userId = StringUtils.isBlank(userIdAttribute) || userInfo.get(userIdAttribute) == null
                ? null : getAttributeAsString(userInfo, userIdAttribute);
        if (StringUtils.isBlank(userId)) {
            log.warn("No user ID attribute '{}' found in the user info attributes", getUserIdAttribute());
            return NO_SUCH_USER;
        }
        String provider = getProvider();
        String emailAttribute = getEmailAttribute();
        String email = StringUtils.isBlank(emailAttribute) || userInfo.get(emailAttribute) == null
                ? null : getAttributeAsString(userInfo, emailAttribute);
        String organization = getAttributeAsFirstValue(userInfo, getOrganizationAttribute());

        // A verified ownership is required before trusting the claimed email for anything: updating the
        // email of an already logged-in EPerson, taking over an existing account found by email (e.g. one
        // created by Shibboleth), or registering a new EPerson under an address its owner never confirmed
        // (which could pre-empt the real owner, e.g. blocking them from later registering/linking via
        // Shibboleth with that same email). A blank email isn't checked, since there is nothing to verify.
        if (StringUtils.isNotBlank(email) && !isEmailVerified(userInfo)) {
            log.warn("OIDC identity {} was not authenticated, because the provider did not confirm the email " +
                    "{} is verified (check authentication-oidc.user-info.email-verified)", userId, email);
            return BAD_CREDENTIALS;
        }

        try {
            // 1. find the EPerson by the identity
            AuthenticationIdentity identity =
                    authenticationIdentityService.find(context, AUTH_METHOD_OIDC, provider, userId);
            if (identity != null) {
                EPerson ePerson = identity.getEPerson();
                logIn(context, ePerson, userInfo, request);
                // current user is set to the identity owner, who is allowed to update it
                authenticationIdentityService.updateLastLogin(context, identity);
                // neither is updated when the provider doesn't return an email this time, since the
                // organization is only meaningful together with a (verified) email identifying its owner
                if (StringUtils.isNotBlank(email)) {
                    updateEmail(context, ePerson, email);
                    updateOrganization(context, ePerson, organization);
                }
                log.info("EPerson {} logged in via OIDC identity {}", ePerson.getID(), userId);
                return SUCCESS;
            }

            if (StringUtils.isBlank(email)) {
                log.warn("No email found in the user info attributes of the OIDC identity {}", userId);
                return NO_SUCH_USER;
            }

            // 2. link the identity to the existing EPerson with the same email
            EPerson ePerson = ePersonService.findByEmail(context, email);
            if (ePerson != null) {
                return linkEPerson(context, ePerson, userInfo, provider, userId, organization, request);
            }

            // 3. register a new EPerson
            if (!canSelfRegister()) {
                log.warn("Self registration is currently disabled for OIDC, and no ePerson could be found for " +
                        "OIDC identity {} or email {}", userId, email);
                return NO_SUCH_USER;
            }
            return registerNewEPerson(context, userInfo, email, provider, userId, organization, request);

        } catch (AuthorizeException ex) {
            log.error("Unable to authenticate OIDC identity {}", userId, ex);
            context.setCurrentUser(null);
            return NO_SUCH_USER;
        }
    }

    private int linkEPerson(Context context, EPerson ePerson, Map<String, Object> userInfo, String provider,
                            String userId, String organization, HttpServletRequest request)
            throws SQLException, AuthorizeException {

        // the EPerson already linked to another identity of this provider must not be taken over
        List<AuthenticationIdentity> identities =
                authenticationIdentityService.findByEPersonAndAuthMethod(context, ePerson, AUTH_METHOD_OIDC);
        if (identities.stream().anyMatch(identity -> provider.equals(identity.getProvider()))) {
            log.warn("EPerson {} with the same email is already linked to another identity of the OIDC provider " +
                    "{}, the OIDC identity {} is not linked", ePerson.getID(), provider, userId);
            return BAD_ARGS;
        }

        context.turnOffAuthorisationSystem();
        try {
            authenticationIdentityService.create(context, ePerson, AUTH_METHOD_OIDC, provider, userId);
            // commit immediately, so a concurrent first login of a different OIDC identity racing past the
            // check above to link the same EPerson+provider is caught right here as a constraint violation
            // (authentication_identity_eperson_auth_method_provider_unique), instead of silently succeeding
            // or surfacing much later at the end-of-request commit. Depending on the database, the actual
            // violation may surface as a plain RuntimeException rather than specifically a Hibernate
            // ConstraintViolationException (e.g. once the broken Hibernate session is touched again by the
            // post-commit entity reload), so any failure at this specific, narrowly-scoped commit is treated
            // the same way: the identity was not reliably linked, so the login must not succeed.
            context.commit();
        } catch (RuntimeException ex) {
            context.rollback();
            log.warn("EPerson {} was not linked to the OIDC identity {} of provider {}, because creating the " +
                    "authentication identity failed, possibly due to a concurrent login of a different identity " +
                    "linking to the same EPerson and provider", ePerson.getID(), userId, provider, ex);
            return BAD_ARGS;
        } finally {
            context.restoreAuthSystemState();
        }

        ePerson = context.reloadEntity(ePerson);
        logIn(context, ePerson, userInfo, request);
        updateOrganization(context, ePerson, organization);
        log.info("EPerson {} found by email was linked to the OIDC identity {}", ePerson.getID(), userId);
        return SUCCESS;
    }

    private int registerNewEPerson(Context context, Map<String, Object> userInfo, String email, String provider,
                                   String userId, String organization, HttpServletRequest request)
            throws SQLException, AuthorizeException {

        context.turnOffAuthorisationSystem();
        try {
            EPerson ePerson = ePersonService.create(context);
            // the user is identified by the authentication identity, netid is left for Shibboleth
            ePerson.setEmail(email);

            String firstName = getAttributeAsString(userInfo, getFirstNameAttribute());
            if (firstName != null) {
                ePerson.setFirstName(context, firstName);
            }
            String lastName = getAttributeAsString(userInfo, getLastNameAttribute());
            if (lastName != null) {
                ePerson.setLastName(context, lastName);
            }

            // as in ClarinShibAuthentication, the user cannot log in with password
            ePerson.setCanLogIn(false);
            ePerson.setSelfRegistered(true);
            ePersonService.update(context, ePerson);

            authenticationIdentityService.create(context, ePerson, AUTH_METHOD_OIDC, provider, userId);

            ClarinUserRegistration clarinUserRegistration = new ClarinUserRegistration();
            clarinUserRegistration.setConfirmation(true);
            clarinUserRegistration.setEmail(ePerson.getEmail());
            clarinUserRegistration.setPersonID(ePerson.getID());
            clarinUserRegistration.setOrganization(StringUtils.isNotBlank(organization) ? organization :
                    ClarinUserRegistration.UNKNOWN_USER_REGISTRATION);
            clarinUserRegistrationService.create(context, clarinUserRegistration);

            logIn(context, ePerson, userInfo, request);
            context.dispatchEvents();
            log.info("New EPerson {} was registered for the OIDC identity {}", ePerson.getID(), userId);
            return SUCCESS;
        } finally {
            context.restoreAuthSystemState();
        }
    }

    private void logIn(Context context, EPerson ePerson, Map<String, Object> userInfo, HttpServletRequest request)
            throws SQLException {
        request.setAttribute(OIDC_AUTHENTICATED, true);
        resolveSpecialGroups(context, userInfo);
        logInEPerson(context, ePerson);
    }

    /**
     * Map the OIDC groups (attribute configured by authentication-oidc.user-info.groups) to DSpace groups
     * (authentication-oidc.group.&lt;group value&gt;), and add the default authentication group
     * (authentication-oidc.default.auth.group), caching them all as the context's special groups,
     * the same way {@link ClarinShibAuthentication} does for Shibboleth attributes.
     * The OIDC group claims are only available here, during the user info exchange, so unlike
     * Shibboleth the special groups cannot be recomputed later from the request on every call
     * to {@link #getSpecialGroups}.
     */
    private void resolveSpecialGroups(Context context, Map<String, Object> userInfo) throws SQLException {
        String groupsAttribute = getGroupsAttribute();
        if (StringUtils.isNotBlank(groupsAttribute)) {
            for (String rawGroup : getAttributeAsList(userInfo, groupsAttribute)) {
                String externalGroup = stripAuthoritySuffix(rawGroup);
                String[] groupNames =
                        configurationService.getArrayProperty("authentication-oidc.group." + externalGroup);
                if (groupNames == null || groupNames.length == 0) {
                    log.debug("No DSpace group mapping found for the OIDC group '{}', check " +
                            "authentication-oidc.group.{} in authentication-oidc.cfg", externalGroup, externalGroup);
                    continue;
                }
                for (String groupName : groupNames) {
                    addSpecialGroup(context, groupName, "group '" + externalGroup + "'");
                }
            }
        }

        String defaultAuthGroup = configurationService.getProperty("authentication-oidc.default.auth.group");
        if (StringUtils.isNotBlank(defaultAuthGroup)) {
            addSpecialGroup(context, defaultAuthGroup, "authentication-oidc.default.auth.group");
        }
    }

    /**
     * Strips the authority suffix from a group value, i.e. everything from the first '#' onwards. AARC-style
     * eduPersonEntitlement URNs append the responsible authority there (e.g. the issuing Perun instance), which
     * is constant across all entitlements of the same IdP and, unlike the rest of the URN, often contains
     * spaces that would otherwise have to be escaped in the authentication-oidc.group.&lt;group value&gt;
     * configuration key. A value without '#' is returned unchanged.
     */
    private String stripAuthoritySuffix(String group) {
        int hashIndex = group.indexOf('#');
        return (hashIndex >= 0 ? group.substring(0, hashIndex) : group).trim();
    }

    /**
     * Find the DSpace group by name and, if found, add it to the context's special groups.
     *
     * @param context   DSpace context
     * @param groupName DSpace group name, matched exactly (case sensitive)
     * @param source    description of where the group name came from, used in the warning when not found
     */
    private void addSpecialGroup(Context context, String groupName, String source) throws SQLException {
        Group group = groupService.findByName(context, groupName.trim());
        if (group != null) {
            context.setSpecialGroup(group.getID());
        } else {
            log.warn("DSpace group '{}' configured by {} was not found", groupName.trim(), source);
        }
    }

    /**
     * Returns the values of the given user info attribute as a list of strings, whether the attribute is a
     * single value (e.g. a string claim) or a JSON array claim (deserialized as a List).
     */
    @SuppressWarnings("unchecked")
    private List<String> getAttributeAsList(Map<String, Object> userInfo, String attribute) {
        if (StringUtils.isBlank(attribute) || !userInfo.containsKey(attribute)) {
            return List.of();
        }
        Object value = userInfo.get(attribute);
        if (value instanceof List) {
            return ((List<Object>) value).stream()
                    .filter(Objects::nonNull)
                    .map(String::valueOf)
                    .collect(Collectors.toList());
        }
        return value != null ? List.of(String.valueOf(value)) : List.of();
    }

    /**
     * Returns the first value of a user info attribute that may contain multiple values, either as a JSON
     * array claim or as a single comma-separated string (e.g. multiple organization affiliations).
     */
    private String getAttributeAsFirstValue(Map<String, Object> userInfo, String attribute) {
        List<String> values = getAttributeAsList(userInfo, attribute);
        return values.isEmpty() ? null : values.get(0).split(",", 2)[0].trim();
    }

    /**
     * Update the email of the EPerson and its CLARIN user registration, when changed by the OIDC provider.
     * The email is not updated when it is already used by another EPerson.
     *
     * @param email the OIDC provider's email, never blank (the only caller checks that already)
     */
    private void updateEmail(Context context, EPerson ePerson, String email)
            throws SQLException, AuthorizeException {

        if (StringUtils.equalsIgnoreCase(email, ePerson.getEmail())) {
            return;
        }
        EPerson emailOwner = ePersonService.findByEmail(context, email);
        if (emailOwner != null && !emailOwner.equals(ePerson)) {
            log.warn("Email of EPerson {} was not changed to {} provided by OIDC, because it is already used by " +
                    "EPerson {}", ePerson.getID(), email, emailOwner.getID());
            return;
        }

        context.turnOffAuthorisationSystem();
        try {
            String oldEmail = ePerson.getEmail();
            ePerson.setEmail(email);
            ePersonService.update(context, ePerson);

            for (ClarinUserRegistration registration :
                    clarinUserRegistrationService.findByEPersonUUID(context, ePerson.getID())) {
                registration.setEmail(ePerson.getEmail());
                clarinUserRegistrationService.update(context, registration);
            }
            log.info("Email of EPerson {} was changed from {} to {} provided by OIDC", ePerson.getID(), oldEmail,
                    ePerson.getEmail());
        } finally {
            context.restoreAuthSystemState();
        }
    }

    /**
     * Update the organization of the CLARIN user registration, when provided by the OIDC provider.
     * The registration is created when missing, e.g. for the EPerson created by an administrator.
     */
    private void updateOrganization(Context context, EPerson ePerson, String organization)
            throws SQLException, AuthorizeException {

        if (StringUtils.isBlank(organization)) {
            if (!clarinUserRegistrationService.findByEPersonUUID(context, ePerson.getID()).isEmpty()) {
                return;
            }
            organization = ClarinUserRegistration.UNKNOWN_USER_REGISTRATION;
        }
        context.turnOffAuthorisationSystem();
        try {
            List<ClarinUserRegistration> registrations =
                    clarinUserRegistrationService.findByEPersonUUID(context, ePerson.getID());
            if (registrations.isEmpty()) {
                ClarinUserRegistration clarinUserRegistration = new ClarinUserRegistration();
                clarinUserRegistration.setConfirmation(true);
                clarinUserRegistration.setEmail(ePerson.getEmail());
                clarinUserRegistration.setPersonID(ePerson.getID());
                clarinUserRegistration.setOrganization(organization);
                clarinUserRegistrationService.create(context, clarinUserRegistration);
                return;
            }
            for (ClarinUserRegistration registration : registrations) {
                if (!organization.equals(registration.getOrganization())) {
                    registration.setOrganization(organization);
                    clarinUserRegistrationService.update(context, registration);
                }
            }
        } finally {
            context.restoreAuthSystemState();
        }
    }

    private String getAttributeAsString(Map<String, Object> userInfo, String attribute) {
        if (isBlank(attribute)) {
            return null;
        }
        return userInfo.containsKey(attribute) ? String.valueOf(userInfo.get(attribute)) : null;
    }

    private String getEmailAttribute() {
        return configurationService.getProperty("authentication-oidc.user-info.email", "email");
    }

    private String getFirstNameAttribute() {
        return configurationService.getProperty("authentication-oidc.user-info.first-name", "given_name");
    }

    private String getLastNameAttribute() {
        return configurationService.getProperty("authentication-oidc.user-info.last-name", "family_name");
    }

    private boolean canSelfRegister() {
        String canSelfRegister = configurationService.getProperty("authentication-oidc.can-self-register", "true");
        if (isBlank(canSelfRegister)) {
            return true;
        }
        return toBoolean(canSelfRegister);
    }

    /**
     * Returns the name of the user info attribute containing the unique user ID, "sub" by default.
     *
     * @return user ID attribute name
     */
    private String getUserIdAttribute() {
        return configurationService.getProperty("authentication-oidc.user-info.id", DEFAULT_USER_ID_ATTRIBUTE);
    }

    /**
     * Returns the name of the boolean user info attribute confirming the email is verified, "email_verified"
     * by default, as defined by the OIDC standard claims.
     *
     * @return email verified attribute name
     */
    private String getEmailVerifiedAttribute() {
        return configurationService.getProperty("authentication-oidc.user-info.email-verified",
                DEFAULT_EMAIL_VERIFIED_ATTRIBUTE);
    }

    /**
     * Returns whether a verified email is required to link the OIDC identity to an existing EPerson found by
     * email (see {@link #isEmailVerified}). Enabled by default, since otherwise any OIDC provider that doesn't
     * actually verify a claimed email (or a user who can set their own email at the provider) could take over
     * an existing account, including an administrator's. Only disable this for a deployment whose provider is
     * known not to emit the verified-email claim at all (see authentication-oidc.cfg).
     *
     * @return true unless disabled by authentication-oidc.require-verified-email
     */
    private boolean isVerifiedEmailRequired() {
        return configurationService.getBooleanProperty("authentication-oidc.require-verified-email", true);
    }

    /**
     * Returns whether the email in the user info attributes can be trusted to link the OIDC identity to an
     * existing EPerson, i.e. whether a verified email is not required (see {@link #isVerifiedEmailRequired}),
     * or the provider confirms it's verified by the {@link #getEmailVerifiedAttribute} attribute.
     *
     * @param userInfo attributes returned by the OIDC user info endpoint
     * @return true when the email is trusted enough to link an existing EPerson
     */
    private boolean isEmailVerified(Map<String, Object> userInfo) {
        if (!isVerifiedEmailRequired()) {
            return true;
        }
        Object emailVerified = userInfo.get(getEmailVerifiedAttribute());
        return Boolean.TRUE.equals(emailVerified) || "true".equalsIgnoreCase(String.valueOf(emailVerified));
    }

    /**
     * Returns the name of the user info attribute containing the user's organization, not set by default.
     *
     * @return organization attribute name or null
     */
    private String getOrganizationAttribute() {
        return configurationService.getProperty("authentication-oidc.user-info.organization");
    }

    /**
     * Returns the name of the user info attribute containing the user's groups, used to assign special
     * groups (authentication-oidc.group.&lt;group value&gt;), not set by default.
     *
     * @return groups attribute name or null
     */
    private String getGroupsAttribute() {
        return configurationService.getProperty("authentication-oidc.user-info.groups");
    }

    /**
     * Returns the stable label of the OIDC provider, stored together with the user ID.
     * It must not be changed after users have logged in, otherwise they won't be found by their user ID.
     *
     * @return provider label
     */
    private String getProvider() {
        return configurationService.getProperty("authentication-oidc.provider", DEFAULT_PROVIDER);
    }

    private OidcTokenResponseDTO getOidcAccessToken(String code) {
        try {
            return oidcClient.getAccessToken(code);
        } catch (Exception ex) {
            log.error("An error occurs retriving the OIDC access_token", ex);
            return null;
        }
    }

    private Map<String, Object> getOidcUserInfo(String accessToken) {
        try {
            return oidcClient.getUserInfo(accessToken);
        } catch (Exception ex) {
            log.error("An error occurs retriving the OIDC user info", ex);
            return Map.of();
        }
    }

    private void logInEPerson(Context context, EPerson ePerson) {
        context.setCurrentUser(ePerson);
    }

    @Override
    public String loginPageURL(Context context, HttpServletRequest request, HttpServletResponse response) {

        String authorizeUrl = configurationService.getProperty("authentication-oidc.authorize-endpoint");
        String clientId = configurationService.getProperty("authentication-oidc.client-id");
        String clientSecret = configurationService.getProperty("authentication-oidc.client-secret");
        String redirectUri = configurationService.getProperty("authentication-oidc.redirect-url");
        String tokenUrl = configurationService.getProperty("authentication-oidc.token-endpoint");
        String userInfoUrl = configurationService.getProperty("authentication-oidc.user-info-endpoint");
        String[] defaultScopes =
            new String[] {
                "openid", "email", "profile"
            };
        String scopes = String.join(" ", configurationService.getArrayProperty("authentication-oidc.scopes",
            defaultScopes));

        if (isAnyBlank(authorizeUrl, clientId, redirectUri, clientSecret, tokenUrl, userInfoUrl)) {
            log.error("Missing mandatory configuration properties for ClarinOidcAuthenticationBean");

            // prepare a Map of the properties which can not have sane defaults, but are still required
            final Map<String, String> map = Map.of("authorizeUrl", authorizeUrl, "clientId", clientId, "redirectUri",
                redirectUri, "clientSecret", clientSecret, "tokenUrl", tokenUrl, "userInfoUrl", userInfoUrl);
            final Iterator<Entry<String, String>> iterator = map.entrySet().iterator();

            while (iterator.hasNext()) {
                final Entry<String, String> entry = iterator.next();

                if (isBlank(entry.getValue())) {
                    log.error(" * {} is missing", entry.getKey());
                }
            }
            return "";
        }

        String state = getOrCreateState(request, response);

        try {
            return format(LOGIN_PAGE_URL_FORMAT, authorizeUrl, clientId, scopes, encode(redirectUri, "UTF-8"),
                    encode(state, "UTF-8"));
        } catch (UnsupportedEncodingException e) {
            log.error(e.getMessage(), e);
            return "";
        }

    }

    /**
     * Returns the value of the {@link #STATE_COOKIE_NAME} cookie already set on this browser, or generates
     * a new unpredictable one if absent, (re-)setting the cookie either way so its expiry keeps extending
     * while the browser keeps checking the authentication status (see {@link #loginPageURL}'s callers,
     * which run on every unauthenticated request, not only when the user deliberately starts an OIDC
     * login). Reusing rather than always regenerating avoids invalidating an in-flight login started in
     * another tab/request.
     */
    private String getOrCreateState(HttpServletRequest request, HttpServletResponse response) {
        String state = null;
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (STATE_COOKIE_NAME.equals(cookie.getName()) && StringUtils.isNotBlank(cookie.getValue())) {
                    state = cookie.getValue();
                    break;
                }
            }
        }
        if (state == null) {
            byte[] randomBytes = new byte[32];
            STATE_RANDOM.nextBytes(randomBytes);
            state = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        }
        response.addHeader("Set-Cookie", buildStateCookieHeader(state, STATE_COOKIE_MAX_AGE_SECONDS));
        return state;
    }

    /**
     * Builds a Set-Cookie header value for the {@link #STATE_COOKIE_NAME} cookie, matching the attributes
     * used elsewhere in DSpace for similar cookies (HttpOnly, Secure, SameSite=None, since the UI and REST
     * API are typically cross-origin), built manually since dspace-api does not depend on spring-web's
     * ResponseCookie.
     */
    private String buildStateCookieHeader(String value, int maxAgeSeconds) {
        return STATE_COOKIE_NAME + "=" + value + "; Max-Age=" + maxAgeSeconds
                + "; Path=/; HttpOnly; Secure; SameSite=None";
    }

    public OidcClient getOidcClient() {
        return this.oidcClient;
    }

    public void setOidcClient(OidcClient oidcClient) {
        this.oidcClient = oidcClient;
    }

    @Override
    public boolean allowSetPassword(Context context, HttpServletRequest request, String username) throws SQLException {
        return false;
    }

    @Override
    public boolean isImplicit() {
        return false;
    }

    @Override
    public boolean canSelfRegister(Context context, HttpServletRequest request, String username) throws SQLException {
        return canSelfRegister();
    }

    @Override
    public void initEPerson(Context context, HttpServletRequest request, EPerson eperson) throws SQLException {
    }

    @Override
    public String getName() {
        return OidcAuthenticationBean.OIDC_AUTH_ATTRIBUTE;
    }

    @Override
    public boolean isUsed(final Context context, final HttpServletRequest request) {
        if (request != null &&
                context.getCurrentUser() != null &&
                request.getAttribute(OIDC_AUTHENTICATED) != null) {
            return true;
        }
        return false;
    }

    @Override
    public boolean canChangePassword(Context context, EPerson ePerson, String currentPassword) {
        return false;
    }

    /**
     * Returns the special groups resolved from the OIDC groups and the default authentication group during
     * {@link #authenticateWithClaims} and cached on the context, either just now (fresh login) or restored
     * from the JWT token special groups claim of a previous OIDC login (see SpecialGroupClaimProvider).
     */
    @Override
    public List<Group> getSpecialGroups(Context context, HttpServletRequest request) throws SQLException {
        if (request == null || context.getCurrentUser() == null) {
            return Collections.emptyList();
        }
        return context.getSpecialGroups();
    }

    /**
     * Always true, as in {@link ClarinShibAuthentication}, because the context's authentication method is not
     * necessarily "oidc" when the special groups are restored from the JWT token of a previous OIDC login.
     */
    @Override
    public boolean areSpecialGroupsApplicable(Context context, HttpServletRequest request) {
        return true;
    }
}
