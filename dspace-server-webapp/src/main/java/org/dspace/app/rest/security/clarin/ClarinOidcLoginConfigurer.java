/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.security.clarin;

import static org.dspace.authenticate.clarin.ClarinOidcAuthenticationBean.REGISTRATION_ID;
import static org.dspace.authenticate.clarin.ClarinOidcAuthenticationBean.START_BASE_PATH;
import static org.dspace.authenticate.clarin.ClarinOidcAuthenticationBean.START_PATH;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.dspace.app.rest.security.DSpaceAuthentication;
import org.dspace.app.rest.security.RestAuthenticationService;
import org.dspace.authenticate.OidcAuthenticationBean;
import org.dspace.authenticate.clarin.ClarinOidcAuthentication;
import org.dspace.authenticate.clarin.ClarinOidcAuthenticationBean;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.endpoint.DefaultAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.client.RestTemplate;

/**
 * CLARIN OIDC login (ufal/clarin-dspace#1140) on Spring Security's oauth2Login(). Spring does the whole OIDC
 * authorization code flow:
 * <ul>
 *     <li>GET {@link ClarinOidcAuthenticationBean#START_PATH}: state, nonce and PKCE (S256), kept in the HTTP
 *     session, then a redirect to the provider;</li>
 *     <li>GET {@link #CALLBACK_PATH} (the redirect URL registered at the provider): state check, code exchange with the
 *     code_verifier, ID token validation (signature against the JWKS, RS256 only, iss, aud, azp, exp, iat,
 *     nonce) and UserInfo (its sub must match the ID token's).</li>
 * </ul>
 * The verified OidcUser is then handed to the DSpace authentication chain, where
 * {@link ClarinOidcAuthenticationBean} decides which EPerson it is; DSpace issues its usual JWT. The session only
 * lives between the two requests above and is invalidated at the end of the login, successful or not.
 * <p>
 * Only installed when {@link ClarinOidcAuthentication} is enabled in the plugin sequence at startup: a deployment
 * using the stock OIDC plugin (or none) gets no additional filters. The callback has its own path, so the stock
 * OidcLoginFilter (registered in every deployment, on /api/authn/oidc) never sees it.
 */
public final class ClarinOidcLoginConfigurer {

    private static final Logger log = LogManager.getLogger(ClarinOidcLoginConfigurer.class);

    /** Where the provider redirects back to: dspace.server.url + this must be registered at the provider. */
    public static final String CALLBACK_PATH = "/api/authn/oidc/callback/" + REGISTRATION_ID;

    private static final String REDIRECT_URL_SESSION_ATTRIBUTE =
            ClarinOidcLoginConfigurer.class.getName() + ".redirectUrl";

    private ClarinOidcLoginConfigurer() {
    }

    /**
     * Add the CLARIN OIDC login to the DSpace REST API security configuration, if {@link ClarinOidcAuthentication}
     * is enabled.
     *
     * @param http                      the DSpace REST API security configuration
     * @param authenticationManager     the DSpace authentication manager (EPerson authentication chain)
     * @param restAuthenticationService issues the DSpace JWT
     * @throws Exception from Spring's configurers
     */
    public static void configure(HttpSecurity http, AuthenticationManager authenticationManager,
                                 RestAuthenticationService restAuthenticationService) throws Exception {
        ConfigurationService configurationService = DSpaceServicesFactory.getInstance().getConfigurationService();
        if (!ClarinOidcAuthentication.isEnabled(configurationService)) {
            return;
        }
        log.info("CLARIN OIDC login enabled, provider redirect URL {}", redirectUri(configurationService));
        ClientRegistrationRepository registrations = new ClarinClientRegistrations(configurationService);

        http.oauth2Login(oauth2 -> oauth2
                .clientRegistrationRepository(registrations)
                // the provider's access token is not kept: this session is invalidated right after the login
                .authorizedClientRepository(new HttpSessionOAuth2AuthorizedClientRepository())
                // a custom login page keeps Spring from generating its own /login page
                .loginPage(START_PATH)
                .authorizationEndpoint(authorization -> authorization
                        .baseUri(START_BASE_PATH)
                        .authorizationRequestResolver(new StartResolver(registrations, configurationService)))
                .loginProcessingUrl(CALLBACK_PATH)
                .tokenEndpoint(token -> token.accessTokenResponseClient(tokenClient(configurationService)))
                .userInfoEndpoint(userInfo -> userInfo.oidcUserService(userInfoClient(configurationService)))
                .successHandler((request, response, authentication) -> loginToDSpace(request, response,
                        authentication, authenticationManager, restAuthenticationService, configurationService))
                .failureHandler((request, response, exception) ->
                        fail(request, response, exception, restAuthenticationService)));
    }

    /** The redirect URL sent to the provider (authentication-oidc.redirect-url is the stock plugin's). */
    private static String redirectUri(ConfigurationService configurationService) {
        return configurationService.getProperty("dspace.server.url") + CALLBACK_PATH;
    }

    /** Spring's token client, with timeouts. */
    private static DefaultAuthorizationCodeTokenResponseClient tokenClient(ConfigurationService configuration) {
        RestTemplate restTemplate = new RestTemplate(Arrays.asList(
                new FormHttpMessageConverter(), new OAuth2AccessTokenResponseHttpMessageConverter()));
        restTemplate.setErrorHandler(new OAuth2ErrorResponseErrorHandler());
        restTemplate.setRequestFactory(requestFactory(configuration));
        DefaultAuthorizationCodeTokenResponseClient client = new DefaultAuthorizationCodeTokenResponseClient();
        client.setRestOperations(restTemplate);
        return client;
    }

    /** Spring's UserInfo client (checks that UserInfo's sub is the ID token's), with timeouts. */
    private static OidcUserService userInfoClient(ConfigurationService configuration) {
        RestTemplate restTemplate = new RestTemplate();
        restTemplate.setErrorHandler(new OAuth2ErrorResponseErrorHandler());
        restTemplate.setRequestFactory(requestFactory(configuration));
        DefaultOAuth2UserService oauth2UserService = new DefaultOAuth2UserService();
        oauth2UserService.setRestOperations(restTemplate);
        OidcUserService oidcUserService = new OidcUserService();
        oidcUserService.setOauth2UserService(userRequest -> {
            try {
                return oauth2UserService.loadUser(userRequest);
            } catch (IllegalArgumentException e) {
                // e.g. UserInfo without sub: Spring throws this instead of an OAuth2AuthenticationException,
                // which would escape the login filter as a 500 instead of a failed login
                throw new OAuth2AuthenticationException(new OAuth2Error("invalid_user_info_response",
                        e.getMessage(), null), e);
            }
        });
        // always call UserInfo (email, groups), not only when profile/email/address/phone scopes were granted
        oidcUserService.setAccessibleScopes(Collections.emptySet());
        return oidcUserService;
    }

    private static SimpleClientHttpRequestFactory requestFactory(ConfigurationService configuration) {
        int timeout = configuration.getIntProperty("authentication-oidc.http.timeout", 10000);
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        return factory;
    }

    /**
     * Spring has verified the provider's response: log the user in to DSpace, like the stock OidcLoginFilter
     * does, and redirect to the page the login was started from.
     */
    private static void loginToDSpace(HttpServletRequest request, HttpServletResponse response,
                                      Authentication oidcAuthentication,
                                      AuthenticationManager authenticationManager,
                                      RestAuthenticationService restAuthenticationService,
                                      ConfigurationService configurationService) throws IOException {
        HttpSession session = request.getSession(false);
        String redirectUrl = session == null ? null : (String) session.getAttribute(REDIRECT_URL_SESSION_ATTRIBUTE);
        if (session != null) {
            // also drops the provider's access token
            session.invalidate();
        }
        // only the DSpace JWT represents the login, not Spring's OAuth2AuthenticationToken
        SecurityContextHolder.clearContext();

        request.setAttribute(OidcAuthenticationBean.OIDC_AUTH_ATTRIBUTE, OidcAuthenticationBean.OIDC_AUTH_ATTRIBUTE);
        request.setAttribute(ClarinOidcAuthenticationBean.OIDC_USER_ATTRIBUTE, oidcAuthentication.getPrincipal());
        DSpaceAuthentication dspaceAuthentication;
        try {
            // NOTE: the authentication is implicit (see ClarinOidcAuthenticationBean), so it's empty
            dspaceAuthentication = (DSpaceAuthentication) authenticationManager.authenticate(
                    new DSpaceAuthentication());
        } catch (AuthenticationException ex) {
            fail(request, response, ex, restAuthenticationService);
            return;
        }
        restAuthenticationService.addAuthenticationDataForUser(request, response, dspaceAuthentication, true);

        String target = ClarinOidcRedirectUrlValidator.validate(redirectUrl, configurationService);
        response.sendRedirect(target != null ? target : configurationService.getProperty("dspace.ui.url"));
    }

    /**
     * Same response as a failed login at the stock OidcLoginFilter: 401 with the WWW-Authenticate header.
     */
    private static void fail(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex,
                             RestAuthenticationService restAuthenticationService) throws IOException {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        // the OAuth2 error code (invalid_state_parameter, invalid_nonce, invalid_id_token, ...) is safe to log
        log.warn("CLARIN OIDC login failed: {}", ex instanceof OAuth2AuthenticationException
                ? ((OAuth2AuthenticationException) ex).getError() : ex.getMessage());
        response.setHeader("WWW-Authenticate", restAuthenticationService.getWwwAuthenticateHeaderValue(request,
                response));
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Authentication failed!");
    }

    /**
     * Spring's authorization request resolver, active only for the CLARIN OIDC login, with PKCE (Spring adds it
     * by default only for public clients; the nonce is added for the openid scope) and the page to return to.
     */
    private static final class StartResolver implements OAuth2AuthorizationRequestResolver {

        private final AntPathRequestMatcher startMatcher =
                new AntPathRequestMatcher(START_BASE_PATH + "/{registrationId}");
        private final DefaultOAuth2AuthorizationRequestResolver delegate;
        private final ConfigurationService configurationService;

        StartResolver(ClientRegistrationRepository registrations, ConfigurationService configurationService) {
            this.delegate = new DefaultOAuth2AuthorizationRequestResolver(registrations, START_BASE_PATH);
            this.delegate.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
            this.configurationService = configurationService;
        }

        @Override
        public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
            if (!startMatcher.matches(request)) {
                return null;
            }
            OAuth2AuthorizationRequest authorizationRequest = delegate.resolve(request);
            if (authorizationRequest != null) {
                // the UI passes the current page; validated here and again when used, never read from the callback
                request.getSession().setAttribute(REDIRECT_URL_SESSION_ATTRIBUTE, ClarinOidcRedirectUrlValidator
                        .validate(request.getParameter("redirectUrl"), configurationService));
            }
            return authorizationRequest;
        }

        @Override
        public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
            // only used by Spring for ClientAuthorizationRequiredException, i.e. not by the login
            return null;
        }
    }

    /**
     * The one client registration, from OIDC discovery of authentication-oidc.issuer, built on first use (an
     * unreachable provider doesn't break DSpace startup) and rebuilt when its configuration changes. Note that
     * Spring caches the ID token decoder (JWKS URI) per registration ID: a different provider needs a restart.
     * Discovery and the JWKS fetch use Spring's own RestTemplates, without timeouts.
     */
    private static final class ClarinClientRegistrations implements ClientRegistrationRepository {

        private final ConfigurationService configurationService;
        private String cachedKey;
        private ClientRegistration cachedRegistration;

        ClarinClientRegistrations(ConfigurationService configurationService) {
            this.configurationService = configurationService;
        }

        @Override
        public ClientRegistration findByRegistrationId(String registrationId) {
            if (!REGISTRATION_ID.equals(registrationId)) {
                return null;
            }
            String issuer = configurationService.getProperty("authentication-oidc.issuer");
            String clientId = configurationService.getProperty("authentication-oidc.client-id");
            String clientSecret = configurationService.getProperty("authentication-oidc.client-secret");
            String redirectUri = redirectUri(configurationService);
            String authMethod = configurationService.getProperty("authentication-oidc.token-endpoint-auth-method",
                    ClientAuthenticationMethod.CLIENT_SECRET_POST.getValue());
            if (StringUtils.isAnyBlank(issuer, clientId, clientSecret)) {
                throw new IllegalStateException("authentication-oidc.issuer, client-id and client-secret "
                        + "must be set to use the CLARIN OIDC authentication");
            }
            Set<String> scopes = new LinkedHashSet<>(Arrays.asList(configurationService.getArrayProperty(
                    "authentication-oidc.scopes", new String[] {"openid", "email", "profile"})));
            scopes.add(OidcScopes.OPENID);

            String key = String.join("\n", issuer, clientId, clientSecret, redirectUri, authMethod,
                    String.join(" ", scopes));
            synchronized (this) {
                if (!key.equals(cachedKey)) {
                    // OIDC discovery; the configured issuer must be exactly the discovered "issuer"
                    cachedRegistration = ClientRegistrations.fromIssuerLocation(issuer)
                            .registrationId(REGISTRATION_ID)
                            .clientId(clientId)
                            .clientSecret(clientSecret)
                            .clientAuthenticationMethod(new ClientAuthenticationMethod(authMethod))
                            .redirectUri(redirectUri)
                            .scope(scopes)
                            .build();
                    cachedKey = key;
                }
                return cachedRegistration;
            }
        }
    }
}
