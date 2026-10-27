/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.authenticate.clarin;

import static org.dspace.content.clarin.AuthenticationIdentity.AUTH_METHOD_OIDC;

import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.dspace.authenticate.OidcAuthenticationBean;
import org.dspace.authorize.AuthorizeException;
import org.dspace.content.clarin.AuthenticationIdentity;
import org.dspace.content.clarin.ClarinUserRegistration;
import org.dspace.content.service.clarin.AuthenticationIdentityService;
import org.dspace.content.service.clarin.ClarinUserRegistrationService;
import org.dspace.core.Context;
import org.dspace.eperson.EPerson;
import org.dspace.eperson.Group;
import org.dspace.eperson.service.GroupService;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * CLARIN extension of the OIDC authentication.
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
public class ClarinOidcAuthenticationBean extends OidcAuthenticationBean {

    private static final Logger log = LogManager.getLogger(ClarinOidcAuthenticationBean.class);

    public static final String DEFAULT_PROVIDER = "oidc";
    public static final String DEFAULT_USER_ID_ATTRIBUTE = "sub";
    public static final String DEFAULT_EMAIL_VERIFIED_ATTRIBUTE = "email_verified";

    @Autowired
    private AuthenticationIdentityService authenticationIdentityService;

    @Autowired
    private ClarinUserRegistrationService clarinUserRegistrationService;

    @Autowired
    private GroupService groupService;

    @Override
    protected int authenticateWithUserInfo(Context context, Map<String, Object> userInfo, HttpServletRequest request)
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
            clarinUserRegistration.setEmail(email);
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

    /**
     * Returns the name of the user info attribute containing the unique user ID, "sub" by default.
     *
     * @return user ID attribute name
     */
    protected String getUserIdAttribute() {
        return configurationService.getProperty("authentication-oidc.user-info.id", DEFAULT_USER_ID_ATTRIBUTE);
    }

    /**
     * Returns the name of the boolean user info attribute confirming the email is verified, "email_verified"
     * by default, as defined by the OIDC standard claims.
     *
     * @return email verified attribute name
     */
    protected String getEmailVerifiedAttribute() {
        return configurationService.getProperty("authentication-oidc.user-info.email-verified",
                DEFAULT_EMAIL_VERIFIED_ATTRIBUTE);
    }

    /**
     * Returns whether a verified email is required to link the OIDC identity to an existing EPerson found by
     * email (see {@link #isEmailVerified}). Enabled by default, since otherwise any OIDC provider that doesn't
     * verify a claimed email could take over an existing account.
     *
     * @return true unless disabled by authentication-oidc.require-verified-email
     */
    protected boolean isVerifiedEmailRequired() {
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
    protected boolean isEmailVerified(Map<String, Object> userInfo) {
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
    protected String getOrganizationAttribute() {
        return configurationService.getProperty("authentication-oidc.user-info.organization");
    }

    /**
     * Returns the name of the user info attribute containing the user's groups, used to assign special
     * groups (authentication-oidc.group.&lt;group value&gt;), not set by default.
     *
     * @return groups attribute name or null
     */
    protected String getGroupsAttribute() {
        return configurationService.getProperty("authentication-oidc.user-info.groups");
    }

    /**
     * Returns the stable label of the OIDC provider, stored together with the user ID.
     * It must not be changed after users have logged in, otherwise they won't be found by their user ID.
     *
     * @return provider label
     */
    protected String getProvider() {
        return configurationService.getProperty("authentication-oidc.provider", DEFAULT_PROVIDER);
    }

    /**
     * Returns the special groups resolved from the OIDC groups and the default authentication group during
     * {@link #authenticateWithUserInfo} and cached on the context, either just now (fresh login) or restored
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
