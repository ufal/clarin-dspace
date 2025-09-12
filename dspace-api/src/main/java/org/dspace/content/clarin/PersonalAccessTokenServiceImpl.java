/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.content.clarin;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import javax.crypto.KeyGenerator;
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
import com.nimbusds.jose.crypto.DirectEncrypter;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.dspace.authorize.AuthorizeException;
import org.dspace.authorize.service.AuthorizeService;
import org.dspace.content.dao.clarin.PersonalAccessTokenDAO;
import org.dspace.content.service.clarin.PersonalAccessTokenService;
import org.dspace.core.Context;
import org.dspace.eperson.EPerson;
import org.dspace.eperson.dao.EPersonDAO;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Service implementation for PersonalAccessToken object.
 * This class is responsible for all business logic calls for the PersonalAccessToken object and is autowired
 * by spring.
 * This class should never be accessed directly.
 *
 * @author Milan Kuchtiak
 */
public class PersonalAccessTokenServiceImpl implements PersonalAccessTokenService {

    @Autowired
    PersonalAccessTokenDAO personalAccessTokenDAO;

    @Autowired
    EPersonDAO ePersonDAO;

    @Autowired
    AuthorizeService authorizeService;

    @Override
    public PersonalAccessToken find(Context context, Integer id) throws SQLException {
        return personalAccessTokenDAO.findByID(context, PersonalAccessToken.class, id);
    }

    @Override
    public PersonalAccessToken findByEPersonID(Context context, UUID ePersonID)
            throws SQLException, AuthorizeException {
        boolean ignoreAuth = context.ignoreAuthorization();

        if (!ignoreAuth && context.getCurrentUser() == null) {
            throw new AuthorizeException("You must be authenticated user");
        }

        if (!ignoreAuth && !authorizeService.isAdmin(context) && !context.getCurrentUser().getID().equals(ePersonID)) {
            throw new AuthorizeException("You must be admin user to create personal access token for this User ID");
        }

        return personalAccessTokenDAO.findByEPersonUUID(context, ePersonID);
    }

    @Override
    public String createToken(Context context, UUID ePersonID, Date expirationTime)
            throws SQLException, AuthorizeException {
        boolean ignoreAuth = context.ignoreAuthorization();

        if (!ignoreAuth && context.getCurrentUser() == null) {
            throw new AuthorizeException("You must be authenticated user");
        }

        if (!ignoreAuth && !authorizeService.isAdmin(context) && !context.getCurrentUser().getID().equals(ePersonID)) {
            throw new AuthorizeException("You must be admin user to create personal access token for this User ID");
        }

        SecureRandom random = new SecureRandom();
        byte[] sharedSecretArray = new byte[32];
        random.nextBytes(sharedSecretArray);

        String macSecret = Base64.getEncoder().encodeToString(sharedSecretArray);

        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .issuer(PersonalAccessToken.TOKEN_ISSUER)
                .claim(PersonalAccessToken.E_PERSON_ID, ePersonID.toString())
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
            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(EncryptionMethod.A256GCM.cekBitLength());
            SecretKey aesKey = keyGen.generateKey();

            String encodedAesKey = Base64.getEncoder().encodeToString(aesKey.getEncoded());

            PersonalAccessToken pat = new PersonalAccessToken();
            pat.setEPersonID(ePersonID);
            pat.setMacSecret(macSecret);
            pat.setAesKey(encodedAesKey);

            pat = this.createToken(context, pat);

            JWEHeader header = new JWEHeader.Builder(JWEAlgorithm.DIR, EncryptionMethod.A256GCM)
                    .keyID(String.valueOf(pat.getID()))
                    .type(PersonalAccessToken.JWE_TOKEN_CLARIN_TYPE)
                    .build();
            jweObject = new JWEObject(header, new Payload(signedJWT));
            jweObject.encrypt(new DirectEncrypter(aesKey));

        } catch (JOSEException | NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }

        return jweObject.serialize();
    }

    @Override
    public void delete(Context context, UUID uuid) throws SQLException, AuthorizeException {
        boolean ignoreAuth = context.ignoreAuthorization();
        if (!ignoreAuth && !authorizeService.isAdmin(context)) {
            throw new AuthorizeException("You must be admin user");
        }
        PersonalAccessToken personalAccessToken = findByEPersonID(context, uuid);
        if (personalAccessToken != null) {
            personalAccessTokenDAO.delete(context, personalAccessToken);
        } else {
            throw new BadRequestException("PersonalAccessToken for user with ID: " + uuid + " doesn't exist");
        }
    }

    @Override
    public void deleteAll(Context context) throws SQLException, AuthorizeException {
        boolean ignoreAuth = context.ignoreAuthorization();
        if (!ignoreAuth && !authorizeService.isAdmin(context)) {
            throw new AuthorizeException("You must be admin user");
        }
        personalAccessTokenDAO.deleteAll(context);
    }

    private PersonalAccessToken createToken(Context context, PersonalAccessToken pat) throws SQLException {
        EPerson ePerson = ePersonDAO.findByID(context, EPerson.class, pat.getEPersonID());

        if (ePerson == null) {
            throw new BadRequestException("EPerson with this ID doesn't exist");
        }

        return personalAccessTokenDAO.createOrUpdate(context, pat);
    }

}
