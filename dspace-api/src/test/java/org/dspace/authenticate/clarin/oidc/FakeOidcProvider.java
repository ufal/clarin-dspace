/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.authenticate.clarin.oidc;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * A minimal, scriptable OIDC provider on top of MockWebServer for testing the CLARIN OIDC login end to end:
 * discovery, JWKS, token and UserInfo endpoints. The ID token is built at the token request from the fields
 * below, with the nonce taken from the last authorization request passed to {@link #authorize}.
 * Also used by ClarinOidcAuthenticationRestControllerIT (dspace-server-webapp) via the dspace-api test-jar.
 */
public class FakeOidcProvider {

    public static final String CLIENT_ID = "clarin-client";
    public static final String CLIENT_SECRET = "clarin-secret";
    public static final String ACCESS_TOKEN = "c41e37e5-c2de-4177-91d6-ed9e9d1f31bf";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MockWebServer server = new MockWebServer();
    private final RSAKey providerKey = generateRsaKey("provider-key");
    private final List<String> tokenRequestBodies = new ArrayList<>();

    private String issuer;

    // --- scriptable behaviour, reset by reset() ---
    /** sub claim of the ID token. */
    public String idTokenSubject;
    /** extra claims of the ID token, e.g. email/email_verified. */
    public Map<String, Object> idTokenClaims;
    /** UserInfo response body; null means HTTP 500. */
    public Map<String, Object> userInfo;
    /** overrides of the standard ID token claims; null means the correct value. */
    public String idTokenIssuer;
    public String idTokenAudience;
    public Instant idTokenExpiresAt;
    /** issue an ID token without the (required) exp claim. */
    public boolean idTokenWithoutExpiration;
    /** nonce put into the ID token; null means the nonce of the last authorization request. */
    public String idTokenNonce;
    /** key the ID token is signed with; null means the provider's published key. */
    public RSAKey idTokenSigningKey;
    /** issue an unsigned (alg=none) ID token. */
    public boolean unsignedIdToken;
    /** token endpoint answers with this OAuth error instead of tokens. */
    public String tokenError;

    private String lastNonce;

    public FakeOidcProvider() {
        reset();
    }

    public void start() throws IOException {
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                try {
                    return handle(request);
                } catch (Exception ex) {
                    return new MockResponse().setResponseCode(500).setBody(String.valueOf(ex));
                }
            }
        });
        server.start();
        // a path with a trailing slash in the issuer, like e-INFRA's https://login.e-infra.cz/oidc/
        issuer = server.url("/oidc/").toString();
    }

    public void shutdown() throws IOException {
        server.shutdown();
    }

    /** Restore the behaviour of a well-behaved provider for user "perun1". */
    public final void reset() {
        idTokenSubject = "perun1";
        idTokenClaims = new HashMap<>();
        userInfo = new HashMap<>(Map.of("sub", "perun1"));
        idTokenIssuer = null;
        idTokenAudience = null;
        idTokenExpiresAt = null;
        idTokenWithoutExpiration = false;
        idTokenNonce = null;
        idTokenSigningKey = null;
        unsignedIdToken = false;
        tokenError = null;
        tokenRequestBodies.clear();
    }

    public String getIssuer() {
        return issuer;
    }

    public List<String> getTokenRequestBodies() {
        return tokenRequestBodies;
    }

    /**
     * Act as the provider's authorization endpoint for the given authorization request URI (where the start
     * endpoint redirected the browser): remember its nonce and return its query parameters, e.g. to read the
     * state the browser would bring back to the redirect_uri together with the code.
     */
    public Map<String, String> authorize(String authorizationRequestUri) {
        Map<String, String> params = parseQuery(URI.create(authorizationRequestUri).getRawQuery());
        lastNonce = params.get("nonce");
        return params;
    }

    /** A different RSA key, for signature tests. */
    public static RSAKey generateRsaKey(String keyId) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                    .privateKey((RSAPrivateKey) keyPair.getPrivate())
                    .keyID(keyId)
                    .build();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> params = new LinkedHashMap<>();
        if (rawQuery == null) {
            return params;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            params.put(key, value);
        }
        return params;
    }

    private MockResponse handle(RecordedRequest request) throws Exception {
        String path = request.getRequestUrl().encodedPath();
        if (path.endsWith("/.well-known/openid-configuration")) {
            return json(200, discovery());
        } else if (path.endsWith("/oidc/jwks")) {
            return new MockResponse().setHeader("Content-Type", "application/json")
                    .setBody(new JWKSet(providerKey.toPublicJWK()).toString());
        } else if (path.endsWith("/oidc/token")) {
            tokenRequestBodies.add(request.getBody().readUtf8());
            if (tokenError != null) {
                return json(400, Map.of("error", tokenError));
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("access_token", ACCESS_TOKEN);
            body.put("token_type", "Bearer");
            body.put("expires_in", 3600);
            body.put("scope", "openid email profile");
            body.put("id_token", buildIdToken());
            return json(200, body);
        } else if (path.endsWith("/oidc/userinfo")) {
            if (userInfo == null || !("Bearer " + ACCESS_TOKEN).equals(request.getHeader("Authorization"))) {
                return new MockResponse().setResponseCode(userInfo == null ? 500 : 401);
            }
            return json(200, userInfo);
        }
        return new MockResponse().setResponseCode(404);
    }

    private Map<String, Object> discovery() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("issuer", issuer);
        metadata.put("authorization_endpoint", issuer + "authorize");
        metadata.put("token_endpoint", issuer + "token");
        metadata.put("userinfo_endpoint", issuer + "userinfo");
        metadata.put("jwks_uri", issuer + "jwks");
        metadata.put("response_types_supported", List.of("code"));
        metadata.put("subject_types_supported", List.of("public"));
        // like e-INFRA, "none" is advertised: the client must not accept it
        metadata.put("id_token_signing_alg_values_supported", List.of("RS256", "none"));
        metadata.put("token_endpoint_auth_methods_supported", List.of("client_secret_basic", "client_secret_post"));
        metadata.put("code_challenge_methods_supported", List.of("S256"));
        metadata.put("scopes_supported", List.of("openid", "email", "profile"));
        return metadata;
    }

    private String buildIdToken() throws JOSEException {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(idTokenIssuer != null ? idTokenIssuer : issuer)
                .subject(idTokenSubject)
                .audience(idTokenAudience != null ? idTokenAudience : CLIENT_ID)
                .issueTime(Date.from(now));
        if (!idTokenWithoutExpiration) {
            claims.expirationTime(Date.from(idTokenExpiresAt != null ? idTokenExpiresAt : now.plusSeconds(300)));
        }
        String nonce = idTokenNonce != null ? idTokenNonce : lastNonce;
        if (nonce != null) {
            claims.claim("nonce", nonce);
        }
        idTokenClaims.forEach(claims::claim);
        if (unsignedIdToken) {
            return new PlainJWT(claims.build()).serialize();
        }
        RSAKey key = idTokenSigningKey != null ? idTokenSigningKey : providerKey;
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(providerKey.getKeyID()).build(),
                claims.build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private static MockResponse json(int status, Object body) throws IOException {
        return new MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json")
                .setBody(MAPPER.writeValueAsString(body));
    }
}
