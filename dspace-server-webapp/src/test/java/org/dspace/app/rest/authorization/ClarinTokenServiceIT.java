/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.authorization;

import static org.dspace.administer.ClarinTokenUtils.getTokenId;
import static org.dspace.content.clarin.ClarinToken.MASKED_TOKEN_SIZE;
import static org.dspace.content.clarin.ClarinToken.UNMASKED_TOKEN_SIZE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.SecureRandom;
import java.sql.SQLException;
import java.text.ParseException;
import java.util.Base64;
import java.util.Date;
import javax.crypto.SecretKey;

import com.nimbusds.jose.EncryptionMethod;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWEAlgorithm;
import com.nimbusds.jose.JWEHeader;
import com.nimbusds.jose.JWEObject;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.DirectEncrypter;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.dspace.administer.ClarinTokenUtils;
import org.dspace.app.rest.test.AbstractControllerIntegrationTest;
import org.dspace.content.clarin.ClarinToken;
import org.dspace.content.factory.ClarinServiceFactory;
import org.dspace.content.service.clarin.ClarinTokenService;
import org.dspace.core.Context;
import org.dspace.eperson.EPerson;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.junit.Before;
import org.junit.Test;

public class ClarinTokenServiceIT extends AbstractControllerIntegrationTest {

    private ClarinTokenService clarinTokenService;
    private ConfigurationService configurationService;

    private Date expirationTimeIn24Hours;

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();
        configurationService = DSpaceServicesFactory.getInstance().getConfigurationService();
        // Set encryption/decryption secret key for the test
        configurationService.setProperty(ClarinToken.PROPERTY_ENCRYPTION_SECRET,
                "P/uBJYtuKbuG2kHdukCp0nbnI5EZz6mg6Qtuyo8I+18=");

        clarinTokenService = ClarinServiceFactory.getInstance().getClarinTokenService();
        // expiration time set to 24 hours
        expirationTimeIn24Hours = new Date(new Date().getTime() + 1000 * 60 * 60 * 24);
    }

    @Test
    public void testRequestWithAdminToken() throws Exception {
        context.setCurrentUser(admin);
        String token = clarinTokenService.createToken(context, admin, expirationTimeIn24Hours);
        assertToken(token, admin);

        getClient(token).perform(get("/api/system/processes"))
                .andExpect(status().isOk());
    }

    @Test
    public void testRequestWithExpiredToken() throws Exception {
        context.setCurrentUser(admin);
        // expiration time set to now (token with this expiration is immediately expired)
        String token = clarinTokenService.createToken(context, admin, new Date());
        assertNotNull(token);
        assertToken(token, admin);

        getClient(token).perform(get("/api/system/processes"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void testRequestWithNonAdminToken() throws Exception {
        String token = clarinTokenService.createToken(context, eperson, expirationTimeIn24Hours);
        assertNotNull(token);
        assertToken(token, eperson);

        getClient(token).perform(get("/api/system/processes"))
                .andExpect(status().isForbidden());
    }

    @Test
    public void testRequestWithRemovedToken() throws Exception {
        String token = clarinTokenService.createToken(context, eperson, expirationTimeIn24Hours);
        assertNotNull(token);
        assertToken(token, eperson);

        context.setCurrentUser(admin);
        clarinTokenService.delete(context, token);

        getClient(token).perform(get("/api/system/processes"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void testRequestWithRemovedUserTokens() throws Exception {
        String token = clarinTokenService.createToken(context, eperson, expirationTimeIn24Hours);
        assertNotNull(token);
        assertToken(token, eperson);

        context.setCurrentUser(admin);
        clarinTokenService.delete(context, eperson);

        getClient(token).perform(get("/api/system/processes"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void testRequestWithInvalidToken() throws Exception {
        context.setCurrentUser(admin);
        String token = clarinTokenService.createToken(context, admin, expirationTimeIn24Hours);
        assertNotNull(token);
        assertToken(token, admin);

        String invalidToken = getMaskedToken(token);

        getClient(invalidToken).perform(get("/api/system/processes"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void testRequestWithFailedTokenVerification() throws Exception {
        context.setCurrentUser(eperson);
        String token = clarinTokenService.createToken(context, eperson, expirationTimeIn24Hours);
        assertNotNull(token);
        assertToken(token, eperson);

        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .issuer(ClarinToken.TOKEN_ISSUER)
                .claim(ClarinToken.E_PERSON_ID, eperson.getID().toString())
                .expirationTime(expirationTimeIn24Hours)
                .build();

        // create token with new shared secret key for verification
        SecureRandom random = new SecureRandom();
        byte[] sharedSecretArray = new byte[32];
        random.nextBytes(sharedSecretArray);

        String macSecret = Base64.getEncoder().encodeToString(sharedSecretArray);

        String tokenWithDifferentVerificationKey = createToken(context, getTokenId(token), claimsSet, macSecret);

        getClient(tokenWithDifferentVerificationKey).perform(get("/api/system/processes"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void testRequestWithInvalidTokenEPersonID() throws Exception {
        context.setCurrentUser(eperson);
        String token = clarinTokenService.createToken(context, eperson, expirationTimeIn24Hours);
        assertNotNull(token);
        assertToken(token, eperson);

        // create token with different eid (EPerson ID)
        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .issuer(ClarinToken.TOKEN_ISSUER)
                .claim(ClarinToken.E_PERSON_ID, admin.getID().toString())
                .expirationTime(expirationTimeIn24Hours)
                .build();

        ClarinToken clarinToken = clarinTokenService.find(context, getTokenId(token));

        String tokenWithDifferentEPersonID =
                createToken(context, getTokenId(token), claimsSet, clarinToken.getSignKey());

        getClient(tokenWithDifferentEPersonID).perform(get("/api/system/processes"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void testRequestsWithTwoTokens() throws Exception {
        context.setCurrentUser(admin);
        String token1 = clarinTokenService.createToken(context, admin, expirationTimeIn24Hours);
        String token2 = clarinTokenService.createToken(context, admin, expirationTimeIn24Hours);
        assertNotNull(token1);
        assertNotNull(token2);
        assertToken(token1, admin);
        assertToken(token2, admin);

        getClient(token1).perform(get("/api/system/processes"))
                .andExpect(status().isOk());

        getClient(token2).perform(get("/api/system/processes"))
                .andExpect(status().isOk());
    }

    private void assertToken(String token, EPerson ePerson) throws SQLException, ParseException {
        ClarinToken pat = clarinTokenService.find(context, getTokenId(token));
        assertNotNull(pat);
        assertEquals(ePerson, pat.getEPerson());
        assertFalse(pat.getSignKey().isBlank());
    }

    static String getMaskedToken(String token) {
        String maskedTokenPart = "*".repeat(MASKED_TOKEN_SIZE);
        String unmaskedTokenPart = token.substring(token.length() - UNMASKED_TOKEN_SIZE);
        return maskedTokenPart + unmaskedTokenPart;
    }

    private String createToken(Context context, Integer tokenId, JWTClaimsSet claimsSet, String macSecret) {
        boolean ignoreAuth = context.ignoreAuthorization();

        String encryptionSecret = configurationService.getProperty(ClarinToken.PROPERTY_ENCRYPTION_SECRET);

        SignedJWT signedJWT = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claimsSet);

        // sign JWT token
        try {
            JWSSigner signer = new MACSigner(macSecret);
            signedJWT.sign(signer);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }

        // encode JWT token
        JWEObject jweObject;
        try {
            SecretKey aesKey = ClarinTokenUtils.getSecretKeyFromBase64EncodedString(encryptionSecret);

            JWEHeader header = new JWEHeader.Builder(JWEAlgorithm.DIR, EncryptionMethod.A256GCM)
                    .keyID(String.valueOf(tokenId))
                    .type(ClarinToken.TOKEN_TYPE)
                    .build();
            jweObject = new JWEObject(header, new Payload(signedJWT));
            jweObject.encrypt(new DirectEncrypter(aesKey));

        } catch (JOSEException e) {
            throw new RuntimeException(e);
        }

        return jweObject.serialize();
    }
}
