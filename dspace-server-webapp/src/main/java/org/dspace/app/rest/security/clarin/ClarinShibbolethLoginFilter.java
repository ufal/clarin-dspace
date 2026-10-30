/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.security.clarin;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Objects;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.dspace.app.rest.security.DSpaceAuthentication;
import org.dspace.app.rest.security.RestAuthenticationService;
import org.dspace.app.rest.security.StatelessLoginFilter;
import org.dspace.authenticate.clarin.ClarinShibAuthentication;
import org.dspace.authenticate.clarin.ShibHeaders;
import org.dspace.content.clarin.ClarinVerificationToken;
import org.dspace.content.factory.ClarinServiceFactory;
import org.dspace.content.service.clarin.ClarinVerificationTokenService;
import org.dspace.core.Context;
import org.dspace.core.Utils;
import org.dspace.eperson.EPerson;
import org.dspace.eperson.factory.EPersonServiceFactory;
import org.dspace.eperson.service.EPersonService;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.dspace.web.ContextUtil;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ProviderNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;

/**
 * This class is copied from `ShibbolethLoginFilter` and modified by the
 * @author Milan Majchrak (milan.majchrak at dataquest.sk).
 *
 * This class will filter Shibboleth requests to see if the user has been authenticated via Shibboleth.
 * <P>
 * The overall Shibboleth login process is as follows:
 *   1. When Shibboleth plugin is enabled, client/UI receives Shibboleth's absolute URL in WWW-Authenticate header.
 *      See {@link ClarinShibAuthentication} loginPageURL() method.
 *   2. Client sends the user to that URL when they select Shibboleth authentication.
 *   3. User logs in using Shibboleth
 *   4. If successful, they are redirected by Shibboleth to the path where this Filter is "listening" (that path
 *      is passed to Shibboleth as a URL param in step 1)
 *   4.1. This filter then intercepts the request in order to check for a valid Shibboleth login (see
 *      ShibAuthentication.authenticate()) and stores that user info in a JWT. It also saves that JWT in a *temporary*
 *      authentication cookie.
 *   4.2. At that point, the client reads the JWT from the Cookie, and sends it back in a request to /api/authn/login,
 *      which triggers the server-side to destroy the Cookie and move the JWT into a Header
 *   5. If not successful:
 *   5.1. The IdP hasn't sent the `Shib-Identity-Provider` or `SHIB-NETID` header. The user is redirected to the
 *      static error page.
 *   5.2. The IdP hasn't sent the `SHIB-EMAIL` header.
 *      The request headers passed by IdP are stored into the `verification_token` table the `shib_headers` column
 *      together with a one-time registration token. The user is redirected to the page where he must fill his
 *      email; the page sends the registration token back, which proves the caller owns the netid.
 * <P>
 * This Shibboleth Authentication process is tested in ClarinShibbolethLoginFilterIT.
 *
 * @author Giuseppe Digilio (giuseppe dot digilio at 4science dot it)
 * @author Tim Donohue
 * @see ClarinShibAuthentication
 */
public class ClarinShibbolethLoginFilter extends StatelessLoginFilter {
    public static final String USER_WITHOUT_EMAIL_EXCEPTION = "UserWithoutEmailException";
    public static final String MISSING_HEADERS_FROM_IDP = "MissingHeadersFromIpd";
    private static final String AUTHORIZATION_HEADER = "Authorization";

    public static final String VERIFICATION_TOKEN_HEADER = "Verification-Token";

    /**
     * Request attribute with the UI URL a failed login is redirected to. It is decided per request in
     * {@link #attemptAuthentication}; the filter is a singleton, so concurrent logins must not share fields.
     */
    private static final String FAILURE_REDIRECT_ATTRIBUTE =
            ClarinShibbolethLoginFilter.class.getName() + ".failureRedirect";

    private static final Logger log = LogManager.getLogger(org.dspace.app.rest.security.clarin.
            ClarinShibbolethLoginFilter.class);

    private ConfigurationService configurationService = DSpaceServicesFactory.getInstance().getConfigurationService();
    private ClarinVerificationTokenService clarinVerificationTokenService = ClarinServiceFactory.getInstance()
            .getClarinVerificationTokenService();
    private EPersonService ePersonService = EPersonServiceFactory.getInstance().getEPersonService();
    private final ClarinShibAuthentication shibAuthentication = new ClarinShibAuthentication();

    public ClarinShibbolethLoginFilter(String url, AuthenticationManager authenticationManager,
                                 RestAuthenticationService restAuthenticationService) {
        super(url, HttpMethod.GET.name(), authenticationManager, restAuthenticationService);
        // CLARIN: the verification-token flow POSTs to this endpoint, so match ANY method
        // (dtq-dev used the v7 AntPathRequestMatcher(url) which was method-agnostic)
        setRequiresAuthenticationRequestMatcher(
                new org.springframework.security.web.util.matcher.AntPathRequestMatcher(url));
    }

    @Override
    public Authentication attemptAuthentication(HttpServletRequest req,
                                                HttpServletResponse res) throws AuthenticationException {
        // First, if Shibboleth is not enabled, throw an immediate ProviderNotFoundException
        // This tells Spring Security that authentication failed
        if (!ClarinShibAuthentication.isEnabled()) {
            throw new ProviderNotFoundException("Shibboleth is disabled.");
        }
        // Same rule as the authentication method, applied before any header is read or a registration row written:
        // the Shibboleth SP protects exactly this path.
        if (!ClarinShibAuthentication.isShibbolethLoginRequest(req)) {
            throw new BadCredentialsException("Not the Shibboleth login endpoint.");
        }

        String emailHeader = configurationService.getProperty("authentication-shibboleth.email-header");

        Context context = ContextUtil.obtainContext(req);
        if (Objects.isNull(context)) {
            throw new RuntimeException("Cannot load the context");
        }

        // If the verification token is not null the user wants to login.
        ClarinVerificationToken clarinVerificationToken =
                findVerifiedToken(context, req.getHeader(VERIFICATION_TOKEN_HEADER));

        // Load ShibHeader and the email from the clarin verification token object or from the request.
        ShibHeaders shib_headers;
        String email;
        if (Objects.nonNull(clarinVerificationToken)) {
            // Set request attribute for authentication method.
            req.setAttribute(ClarinShibAuthentication.SHIB_HEADERS_ATTRIBUTE, clarinVerificationToken.getShibHeaders());
            req.setAttribute(ClarinShibAuthentication.VERIFICATION_TOKEN_ATTRIBUTE, clarinVerificationToken.getToken());
            shib_headers = new ShibHeaders(clarinVerificationToken.getShibHeaders());
            email = clarinVerificationToken.getEmail();
        } else {
            shib_headers = new ShibHeaders(req);
            email = shib_headers.get_single(emailHeader);
            if (StringUtils.isNotEmpty(email)) {
                email = ClarinShibAuthentication.sortEmailsAndGetFirst(email);
            }
        }

        try {
            req.setAttribute(FAILURE_REDIRECT_ATTRIBUTE, failureRedirectUrl(context, shib_headers, email));
        } catch (SQLException e) {
            throw new RuntimeException("Cannot prepare the Shibboleth authentication error redirect because: "
                    + e.getMessage(), e);
        }

        // In the case of Shibboleth, this method does NOT actually authenticate us. The authentication
        // has already happened in Shibboleth. So, this call to "authenticate()" is just triggering
        // ShibAuthentication.authenticate() to check for a valid Shibboleth login, and if found, the current user
        // is considered authenticated via Shibboleth.
        // NOTE: because this authentication is implicit, we pass in an empty DSpaceAuthentication
        return authenticationManager.authenticate(new DSpaceAuthentication());
    }

    @Override
    protected void successfulAuthentication(HttpServletRequest req,
                                            HttpServletResponse res,
                                            FilterChain chain,
                                            Authentication auth) throws IOException, ServletException {
        // Once we've gotten here, we know we have a successful login (i.e. attemptAuthentication() succeeded)

        DSpaceAuthentication dSpaceAuthentication = (DSpaceAuthentication) auth;
        log.debug("Shib authentication successful for EPerson {}. Sending back temporary auth cookie",
                dSpaceAuthentication.getName());
        // OVERRIDE DEFAULT behavior of StatelessLoginFilter to return a temporary authentication cookie containing
        // the Auth Token (JWT). This Cookie is required because we *redirect* the user back to the client/UI after
        // a successful Shibboleth login. Headers cannot be sent via a redirect, so a Cookie must be sent to provide
        // the auth token to the client. On the next request from the client, the cookie is read and destroyed & the
        // Auth token is only used in the Header from that point forward.
        String verificationToken = req.getHeader(VERIFICATION_TOKEN_HEADER);
        if (StringUtils.isNotEmpty(verificationToken) && !consumeVerificationToken(ContextUtil.obtainContext(req),
                verificationToken, dSpaceAuthentication.getName())) {
            log.error("The verification token was already used or does not belong to the authenticated user.");
            res.sendRedirect(genericFailureUrl());
            return;
        }
        restAuthenticationService.addAuthenticationDataForUser(req, res, dSpaceAuthentication, true);

        if (StringUtils.isEmpty(verificationToken)) {
            // redirect user after completing Shibboleth authentication, sending along the temporary auth cookie
            redirectAfterSuccess(req, res);
        } else {
            res.getWriter().write(res.getHeader(AUTHORIZATION_HEADER));
        }
    }

    /**
     * If the above attemptAuthentication() call was unsuccessful, then ensure that the response is a 401 Unauthorized
     * AND it includes a WWW-Authentication header. We use this header in DSpace to return all the enabled
     * authentication options available to the UI (along with the path to the login URL for each option)
     * @param request current request
     * @param response current response
     * @param failed exception that was thrown by attemptAuthentication()
     * @throws IOException
     * @throws ServletException
     */
    @Override
    protected void unsuccessfulAuthentication(HttpServletRequest request,
                                              HttpServletResponse response, AuthenticationException failed)
            throws IOException, ServletException {

        String authenticateHeaderValue = restAuthenticationService.getWwwAuthenticateHeaderValue(request, response);

        response.setHeader("WWW-Authenticate", authenticateHeaderValue);

        String redirectUrl = (String) request.getAttribute(FAILURE_REDIRECT_ATTRIBUTE);
        if (StringUtils.isEmpty(redirectUrl)) {
            redirectUrl = genericFailureUrl();
        }

        response.sendRedirect(redirectUrl);
        log.error("Authentication failed (status:{})",
                HttpServletResponse.SC_UNAUTHORIZED, failed);
    }

    /**
     * The UI page a failed login is redirected to, decided before the authentication runs. Precedence: the IdP sent
     * no identity, the e-mail belongs to an account this login must not take over, the IdP sent no e-mail (the user
     * registers through the verification e-mail), generic failure.
     */
    private String failureRedirectUrl(Context context, ShibHeaders shibHeaders, String email) throws SQLException {
        String loginUrl = configurationService.getProperty("dspace.ui.url") + "/login/";
        if (StringUtils.isEmpty(shibHeaders.get_idp())) {
            log.error("Cannot load the idp from the request headers.");
            return loginUrl + "missing-headers";
        }

        EPerson ePerson = ClarinShibAuthentication.findEpersonByNetId(shibHeaders.getNetIdHeaders(), shibHeaders,
                ePersonService, context, false);
        boolean foundByNetId = Objects.nonNull(ePerson);
        if (!foundByNetId && StringUtils.isNotEmpty(email)) {
            ePerson = ePersonService.findByEmail(context, email);
        }
        if (Objects.nonNull(ePerson) && (foundByNetId
                || !shibAuthentication.canBindNetIdByEmail(context, ePerson, shibHeaders.get_idp()))) {
            log.error("The users email is already associated with a different user");
            String encodedEmail = URLEncoder.encode(Objects.toString(email, ""), StandardCharsets.UTF_8);
            return loginUrl + "duplicate-user?email=" + encodedEmail;
        }

        if (Objects.isNull(ePerson) && StringUtils.isBlank(email)) {
            log.error("Cannot load the shib email header from the request headers.");
            String registrationToken = storeRegistrationAttempt(context, shibHeaders);
            if (Objects.nonNull(registrationToken)) {
                // The UI sends the token back as the `netid` parameter of POST /api/autoregistration.
                return loginUrl + "auth-failed?netid=" + URLEncoder.encode(registrationToken, StandardCharsets.UTF_8);
            }
        }
        return genericFailureUrl();
    }

    private String genericFailureUrl() {
        return configurationService.getProperty("dspace.ui.url") + "/login?error=shibboleth-authentication-failed";
    }

    /**
     * Load the verification token sent in the request header. Only the token that was e-mailed is a login credential
     * (holding it proves access to the mailbox); a registration token (no e-mail sent yet) is refused, it must not
     * reach the authentication.
     * @return the token row, or null when the request carries no token
     */
    private ClarinVerificationToken findVerifiedToken(Context context, String verificationToken) {
        if (StringUtils.isEmpty(verificationToken)) {
            return null;
        }
        ClarinVerificationToken clarinVerificationToken;
        try {
            clarinVerificationToken = clarinVerificationTokenService.findByToken(context, verificationToken);
        } catch (SQLException e) {
            throw new RuntimeException("Cannot find clarin verification token by token because: " + e.getSQLState());
        }
        if (Objects.isNull(clarinVerificationToken) || Objects.isNull(clarinVerificationToken.getEmail())) {
            throw new BadCredentialsException("Unknown verification token.");
        }
        return clarinVerificationToken;
    }

    /**
     * The e-mailed verification token is a bearer credential; it is valid for exactly one login of the account it
     * registered. Issuing the JWT closes the request's database session, so this runs first.
     * @param authenticatedEmail the e-mail of the account the request authenticated as; a request that also carries
     *                           another user's JWT is authenticated as that user without the token
     * @return false when the token is already gone or belongs to someone else, nothing is deleted then
     */
    private boolean consumeVerificationToken(Context context, String verificationToken, String authenticatedEmail) {
        try {
            ClarinVerificationToken clarinVerificationToken =
                    clarinVerificationTokenService.findByToken(context, verificationToken);
            if (Objects.isNull(clarinVerificationToken)
                    || !StringUtils.equalsIgnoreCase(clarinVerificationToken.getEmail(), authenticatedEmail)) {
                return false;
            }
            clarinVerificationTokenService.delete(context, clarinVerificationToken);
            context.commit();
            return true;
        } catch (SQLException e) {
            throw new RuntimeException("Cannot delete the used verification token because: " + e.getMessage(), e);
        }
    }

    /**
     * After successful login, redirect to the DSpace URL specified by this Shibboleth request (in the "redirectUrl"
     * request parameter). If that 'redirectUrl' is not valid or trusted for this DSpace site, then return a 400 error.
     * @param request
     * @param response
     * @throws IOException
     */
    private void redirectAfterSuccess(HttpServletRequest request, HttpServletResponse response) throws IOException {
        // Get redirect URL from request parameter
        String redirectUrl = request.getParameter("redirectUrl");

        // If redirectUrl unspecified, default to the configured UI
        if (StringUtils.isEmpty(redirectUrl)) {
            redirectUrl = configurationService.getProperty("dspace.ui.url");
        }

        // Validate that the redirectURL matches either the server or UI hostname. It *cannot* be an arbitrary URL.
        String redirectHostName = Utils.getHostName(redirectUrl);
        String serverHostName = Utils.getHostName(configurationService.getProperty("dspace.server.url"));
        ArrayList<String> allowedHostNames = new ArrayList<>();
        allowedHostNames.add(serverHostName);
        String[] allowedUrls = configurationService.getArrayProperty("rest.cors.allowed-origins");
        for (String url : allowedUrls) {
            allowedHostNames.add(Utils.getHostName(url));
        }

        if (StringUtils.equalsAnyIgnoreCase(redirectHostName, allowedHostNames.toArray(new String[0]))) {
            log.debug("Shibboleth redirecting to " + redirectUrl);
            // Encode the UTF-8 characters from redirect URL to UTF-8, to ensure it's properly encoded for the browser
            String encodedRedirectUrl = org.dspace.app.rest.utils.Utils.encodeNonAsciiCharacters(redirectUrl);
            if (StringUtils.isEmpty(encodedRedirectUrl)) {
                log.error("Invalid Encoded Shibboleth redirectURL=" + redirectUrl + ". URL is empty!");
            }
            response.sendRedirect(encodedRedirectUrl);
        } else {
            log.error("Invalid Shibboleth redirectURL=" + redirectUrl +
                    ". URL doesn't match hostname of server or UI!");
            response.sendError(HttpServletResponse.SC_BAD_REQUEST,
                    "Invalid redirectURL! Must match server or ui hostname.");
        }
    }

    /**
     * The IdP hasn't sent the `SHIB-EMAIL` header. The headers it sent are stored into the `verification_token`
     * table under the user's netid, together with a one-time registration token that the UI must send back to
     * have the verification e-mail sent. A new login resets a previous attempt for the same netid.
     * After successful signing in the `verification_token` record is removed from the DB.
     * @return the registration token, or null when the IdP sent no netid either
     */
    private String storeRegistrationAttempt(Context context, ShibHeaders shibHeaders) throws SQLException {
        String netId = getNetIdFromShibHeaders(shibHeaders.getNetIdHeaders(), shibHeaders);
        if (Objects.isNull(netId)) {
            return null;
        }

        ClarinVerificationToken clarinVerificationToken = clarinVerificationTokenService.findByNetID(context, netId);
        if (Objects.isNull(clarinVerificationToken)) {
            clarinVerificationToken = clarinVerificationTokenService.create(context);
            clarinVerificationToken.setePersonNetID(netId);
        }
        clarinVerificationToken.setShibHeaders(shibHeaders.toString());
        clarinVerificationToken.setEmail(null);
        clarinVerificationToken.setToken(clarinVerificationTokenService.generateToken());
        clarinVerificationTokenService.update(context, clarinVerificationToken);
        context.commit();
        return clarinVerificationToken.getToken();
    }

    /**
     * Get the netId from the ShibHeaders object. The netId is stored in the headers which are defined in the
     * `authentication-shibboleth.netid-headers` property.
     * @return netId or null
     */
    private String getNetIdFromShibHeaders(String[] netIdHeaders, ShibHeaders shibHeaders) {
        for (String netidHeader : netIdHeaders) {
            String netID = shibHeaders.get_single(netidHeader);
            if (StringUtils.isNotEmpty(netID)) {
                return netID;
            }
        }
        return null;
    }
}
