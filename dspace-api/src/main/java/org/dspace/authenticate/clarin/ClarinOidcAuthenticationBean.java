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
import java.util.List;
import java.util.Map;
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
import org.springframework.beans.factory.annotation.Autowired;

/**
 * CLARIN extension of the OIDC authentication.
 * The user is identified by the OIDC user ID (the "sub" claim by default) stored in the authentication_identity
 * table, instead of the email which may change:
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

    @Autowired
    private AuthenticationIdentityService authenticationIdentityService;

    @Autowired
    private ClarinUserRegistrationService clarinUserRegistrationService;

    @Override
    protected int authenticateWithUserInfo(Context context, Map<String, Object> userInfo, HttpServletRequest request)
            throws SQLException {

        String userId = getAttributeAsString(userInfo, getUserIdAttribute());
        if (StringUtils.isBlank(userId)) {
            // not expected, the "sub" claim is mandatory in OIDC
            log.warn("No user ID attribute '{}' found in the user info attributes, falling back to email",
                    getUserIdAttribute());
            return super.authenticateWithUserInfo(context, userInfo, request);
        }
        String provider = getProvider();
        String email = getAttributeAsString(userInfo, getEmailAttribute());
        String organization = getAttributeAsString(userInfo, getOrganizationAttribute());

        try {
            // 1. find the EPerson by the identity
            AuthenticationIdentity identity =
                    authenticationIdentityService.find(context, AUTH_METHOD_OIDC, provider, userId);
            if (identity != null) {
                EPerson ePerson = identity.getEPerson();
                logIn(context, ePerson, request);
                // current user is set to the identity owner, who is allowed to update it
                authenticationIdentityService.updateLastLogin(context, identity);
                updateEmail(context, ePerson, email);
                updateOrganization(context, ePerson, organization);
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
                return linkEPerson(context, ePerson, provider, userId, organization, request);
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

    private int linkEPerson(Context context, EPerson ePerson, String provider, String userId, String organization,
                            HttpServletRequest request) throws SQLException, AuthorizeException {

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
        } finally {
            context.restoreAuthSystemState();
        }

        logIn(context, ePerson, request);
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

            logIn(context, ePerson, request);
            context.dispatchEvents();
            log.info("New EPerson {} was registered for the OIDC identity {}", ePerson.getID(), userId);
            return SUCCESS;
        } finally {
            context.restoreAuthSystemState();
        }
    }

    private void logIn(Context context, EPerson ePerson, HttpServletRequest request) {
        request.setAttribute(OIDC_AUTHENTICATED, true);
        logInEPerson(context, ePerson);
    }

    /**
     * Update the email of the EPerson and its CLARIN user registration, when changed by the OIDC provider.
     * The email is not updated when it is already used by another EPerson.
     */
    private void updateEmail(Context context, EPerson ePerson, String email)
            throws SQLException, AuthorizeException {

        if (StringUtils.isBlank(email) || StringUtils.equalsIgnoreCase(email, ePerson.getEmail())) {
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
            return;
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
     * Returns the name of the user info attribute containing the user's organization, not set by default.
     *
     * @return organization attribute name or null
     */
    protected String getOrganizationAttribute() {
        return configurationService.getProperty("authentication-oidc.user-info.organization");
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
}
