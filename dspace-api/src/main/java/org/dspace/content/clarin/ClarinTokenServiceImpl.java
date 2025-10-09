/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.content.clarin;

import java.security.SecureRandom;
import java.sql.SQLException;
import java.text.ParseException;
import java.util.Base64;
import java.util.Date;
import javax.crypto.SecretKey;
import javax.ws.rs.BadRequestException;

import com.nimbusds.jose.EncryptionMethod;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWEAlgorithm;
import com.nimbusds.jose.JWEHeader;
import com.nimbusds.jose.JWEObject;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.DirectDecrypter;
import com.nimbusds.jose.crypto.DirectEncrypter;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.dspace.administer.ClarinTokenUtils;
import org.dspace.authorize.AuthorizeException;
import org.dspace.authorize.service.AuthorizeService;
import org.dspace.content.dao.clarin.ClarinTokenDAO;
import org.dspace.content.service.clarin.ClarinTokenService;
import org.dspace.core.Context;
import org.dspace.eperson.EPerson;
import org.dspace.services.ConfigurationService;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Service implementation for ClarinToken object.
 * This class is responsible for all business logic calls for the ClarinToken object and is autowired
 * by spring.
 * This class should never be accessed directly.
 *
 * @author Milan Kuchtiak
 */
public class ClarinTokenServiceImpl implements ClarinTokenService {

    @Autowired
    ClarinTokenDAO clarinTokenDAO;

    @Autowired
    AuthorizeService authorizeService;

    @Autowired
    private ConfigurationService configurationService;

    @Override
    public ClarinToken find(Context context, Integer id) throws SQLException {
        return clarinTokenDAO.findByID(context, ClarinToken.class, id);
    }

    @Override
    public String createToken(Context context, EPerson ePerson, Date expirationTime)
            throws SQLException, AuthorizeException {
        boolean ignoreAuth = context.ignoreAuthorization();

        String encryptionSecret = configurationService.getProperty(ClarinToken.PROPERTY_ENCRYPTION_SECRET);

        if (encryptionSecret == null) {
            throw new RuntimeException("Missing clarin.token.encryption.secret configuration key");
        }

        if (ePerson == null) {
            throw new BadRequestException("EPerson must be defined.");
        }

        if (!ignoreAuth && context.getCurrentUser() == null) {
            throw new AuthorizeException("You must be authenticated user");
        }

        if (!ignoreAuth && !authorizeService.isAdmin(context) && !context.getCurrentUser().equals(ePerson)) {
            throw new AuthorizeException("You must be admin user to create clarin token for this User ID");
        }

        SecureRandom random = new SecureRandom();
        byte[] sharedSecretArray = new byte[32];
        random.nextBytes(sharedSecretArray);

        String macSecret = Base64.getEncoder().encodeToString(sharedSecretArray);

        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .issuer(ClarinToken.TOKEN_ISSUER)
                .claim(ClarinToken.E_PERSON_ID, ePerson.getID().toString())
                .expirationTime(expirationTime)
                .build();

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

            ClarinToken pat = new ClarinToken();
            pat.setEPerson(ePerson);
            pat.setSignKey(macSecret);

            pat = clarinTokenDAO.create(context, pat);

            JWEHeader header = new JWEHeader.Builder(JWEAlgorithm.DIR, EncryptionMethod.A256GCM)
                    .keyID(String.valueOf(pat.getID()))
                    .type(ClarinToken.TOKEN_TYPE)
                    .build();
            jweObject = new JWEObject(header, new Payload(signedJWT));
            jweObject.encrypt(new DirectEncrypter(aesKey));

        } catch (JOSEException e) {
            throw new RuntimeException(e);
        }

        return jweObject.serialize();
    }

    @Override
    public void delete(Context context, String token) throws SQLException, AuthorizeException {
        boolean ignoreAuth = context.ignoreAuthorization();

        if (!ignoreAuth && context.getCurrentUser() == null) {
            throw new AuthorizeException("You must be authenticated user");
        }

        try {
            if (ClarinTokenUtils.isClarinToken(token)) {
                ClarinToken clarinToken = find(context, ClarinTokenUtils.getTokenId(token));
                if (clarinToken != null) {

                    if (!ignoreAuth &&
                            !authorizeService.isAdmin(context) &&
                            !context.getCurrentUser().equals(clarinToken.getEPerson())) {
                        throw new AuthorizeException("You must be admin user to delete this token");
                    }

                    clarinTokenDAO.delete(context, clarinToken);
                } else {
                    throw new BadRequestException("This token is not valid.");
                }
            }
        } catch (ParseException ex) {
            throw new BadRequestException(ex.getMessage());
        }
    }

    @Override
    public void delete(Context context, EPerson ePerson) throws SQLException, AuthorizeException {
        boolean ignoreAuth = context.ignoreAuthorization();
        if (!ignoreAuth && context.getCurrentUser() == null) {
            throw new AuthorizeException("You must be authenticated user");
        }
        if (!ignoreAuth && !authorizeService.isAdmin(context) && !context.getCurrentUser().equals(ePerson)) {
            throw new AuthorizeException("You must be admin user to delete token for this User ID");
        }
        clarinTokenDAO.deleteTokensForEPerson(context, ePerson);
    }

    @Override
    public void deleteAll(Context context) throws SQLException, AuthorizeException {
        boolean ignoreAuth = context.ignoreAuthorization();
        if (!ignoreAuth && !authorizeService.isAdmin(context)) {
            throw new AuthorizeException("You must be admin user");
        }
        clarinTokenDAO.deleteAll(context);
    }

    @Override
    public EPerson getEPersonFromClarinToken(Context context, String token)
            throws SQLException, ParseException, JOSEException {
        JWEObject jweObj = JWEObject.parse(token);
        String tokenId = jweObj.getHeader().getKeyID();
        if (tokenId != null) {
            ClarinToken clarinToken = find(context, Integer.valueOf(tokenId));
            if (clarinToken != null) {
                jweObj.decrypt(new DirectDecrypter(
                        ClarinTokenUtils.getSecretKeyFromBase64EncodedString(
                                configurationService.getProperty(ClarinToken.PROPERTY_ENCRYPTION_SECRET))));
                SignedJWT signedJWT = jweObj.getPayload().toSignedJWT();
                if (ClarinTokenUtils.isSignedJWTValid(signedJWT, clarinToken)) {
                    return clarinToken.getEPerson();
                }
            }
        }
        return null;
    }
}
