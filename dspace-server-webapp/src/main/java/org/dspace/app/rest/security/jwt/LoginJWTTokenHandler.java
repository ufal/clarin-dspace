/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.security.jwt;

import java.sql.SQLException;
import java.text.ParseException;
import java.util.Date;
import java.util.UUID;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.util.DateUtils;
import org.dspace.content.clarin.PersonalAccessToken;
import org.dspace.content.service.clarin.PersonalAccessTokenService;
import org.dspace.core.Context;
import org.dspace.eperson.EPerson;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Class responsible for creating and parsing JSON Web Tokens (JWTs), supports both JWS and JWE
 * https://jwt.io/
 */
@Component
public class LoginJWTTokenHandler extends JWTTokenHandler {

    @Autowired
    PersonalAccessTokenService personalAccessTokenService;

    @Override
    protected String getTokenSecretConfigurationKey() {
        return "jwt.login.token.secret";
    }

    @Override
    protected String getEncryptionSecretConfigurationKey() {
        return "jwt.login.encryption.secret";
    }

    @Override
    protected String getTokenExpirationConfigurationKey() {
        return "jwt.login.token.expiration";
    }

    @Override
    protected String getEncryptionEnabledConfigurationKey() {
        return "jwt.login.encryption.enabled";
    }

    @Override
    protected String getCompressionEnabledConfigurationKey() {
        return "jwt.login.compression.enabled";
    }

    protected EPerson parseEPersonFromPersonalAccessToken(String token, Context context)
            throws JOSEException, ParseException, SQLException {
        SignedJWT signedJWT = SignedJWT.parse(token);
        // get the claims set from the parsed token
        JWTClaimsSet jwtClaimsSet = signedJWT.getJWTClaimsSet();
        // retrieve the EPerson from the claims set
        EPerson ePerson = getEPerson(context, jwtClaimsSet);

        if (ePerson != null && isValid(ePerson.getID(), signedJWT, jwtClaimsSet, context)) {
            context.setCurrentUser(ePerson);
            return ePerson;
        } else {
            return null;
        }
    }

    private boolean isValid(UUID ePersonID, SignedJWT signedJWT, JWTClaimsSet jwtClaimsSet, Context context)
            throws SQLException, JOSEException {
        PersonalAccessToken pat = personalAccessTokenService.find(context, ePersonID);
        if (pat != null) {
            JWSVerifier verifier = new MACVerifier(pat.getSharedSecret());
            if (signedJWT.verify(verifier)) {
                Date expirationTime = jwtClaimsSet.getExpirationTime();
                return expirationTime != null
                        // Ensure expiration timestamp is after the current time, with zero acceptable clock skew
                        && DateUtils.isAfter(expirationTime, new Date(), 0);
            }
        }
        return false;
    }
}
