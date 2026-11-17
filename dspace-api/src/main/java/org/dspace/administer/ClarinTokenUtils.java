/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.administer;

import static org.dspace.content.clarin.ClarinToken.E_PERSON_ID;

import java.text.ParseException;
import java.util.Date;
import java.util.Optional;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWEObject;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.JWTParser;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.util.DateUtils;
import org.dspace.content.clarin.ClarinToken;

/**
 * Utility methods for Clarin Token.
 *
 * @author Milan Kuchtiak
 */
public final class ClarinTokenUtils {

    private ClarinTokenUtils() {
    }

    public static boolean isClarinToken(String token) throws ParseException {
        JWT jwtToken = JWTParser.parse(token);
        return ClarinToken.TOKEN_TYPE.equals(jwtToken.getHeader().getType());
    }

    public static Integer getTokenId(String token) throws ParseException {
        JWEObject jweObj = JWEObject.parse(token);
        try {
            return Integer.parseInt(jweObj.getHeader().getKeyID());
        } catch (NumberFormatException e) {
            throw new ParseException("Invalid KeyID: not a valid integer", 0);
        }
    }

    public static SecretKey getSecretKeyFromBase64EncodedString(String encodedSecretKey) {
        byte[] decodedKey = java.util.Base64.getDecoder().decode(encodedSecretKey);
        return new SecretKeySpec(decodedKey, 0, decodedKey.length, "AES");
    }

    public static boolean isSignedJWTValid(SignedJWT signedJWT, ClarinToken clarinToken)
            throws ParseException, JOSEException {
        JWSVerifier verifier = new MACVerifier(clarinToken.getSignKey());
        if (signedJWT.verify(verifier) && JWSAlgorithm.HS256.equals(getAlgorithm(signedJWT))) {
            JWTClaimsSet jwtClaimsSet = signedJWT.getJWTClaimsSet();
            if (ClarinToken.TOKEN_ISSUER.equals(jwtClaimsSet.getIssuer()) &&
                    clarinToken.getEPerson().getID().toString().equals(jwtClaimsSet.getClaim(E_PERSON_ID))) {
                Date expirationTime = jwtClaimsSet.getExpirationTime();
                return expirationTime != null
                        // Ensure expiration timestamp is after the current time, with zero acceptable clock skew
                        && DateUtils.isAfter(expirationTime, new Date(), 0);
            }
        }
        return false;
    }

    private static JWSAlgorithm getAlgorithm(SignedJWT signedJWT) {
        return Optional.ofNullable(signedJWT.getHeader()).map(JWSHeader::getAlgorithm).orElse(null);
    }

}
