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
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import javax.ws.rs.BadRequestException;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
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
 * Service implementation for MatomoReportSubscription object.
 * This class is responsible for all business logic calls for the MatomoReportSubscription object and is autowired
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
    public PersonalAccessToken find(Context context, UUID uuid) throws SQLException {
        return personalAccessTokenDAO.findByID(context, PersonalAccessToken.class, uuid);
    }

    @Override
    public String createToken(Context context, UUID uuid, Date expirationTime) throws SQLException, AuthorizeException {

        if (context.getCurrentUser() == null) {
            throw new AuthorizeException("You must be authenticated user");
        }

        if (!authorizeService.isAdmin(context) && !context.getCurrentUser().getID().equals(uuid)) {
            throw new AuthorizeException("You must be admin user to create personal access token for this User ID");
        }

        SecureRandom random = new SecureRandom();
        byte[] sharedSecretArray = new byte[32];
        random.nextBytes(sharedSecretArray);

        String sharedSecret = Base64.getEncoder().encodeToString(sharedSecretArray);

        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .claim("authenticationMethod", "personal_access_token")
                .claim(PersonalAccessToken.E_PERSON_ID, uuid.toString())
                .expirationTime(expirationTime)
                .build();

        SignedJWT signedJWT = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claimsSet);

        try {
            JWSSigner signer = new MACSigner(sharedSecret);
            signedJWT.sign(signer);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }

        PersonalAccessToken pat = new PersonalAccessToken();
        pat.setId(uuid);
        pat.setSharedSecret(sharedSecret);

        this.createToken(context, pat);

        return PersonalAccessToken.PREFIX + signedJWT.serialize();
    }

    @Override
    public void delete(Context context, UUID uuid) throws SQLException, AuthorizeException {
        if (!authorizeService.isAdmin(context)) {
            throw new AuthorizeException("You must be admin user");
        }
        PersonalAccessToken personalAccessToken = find(context, uuid);
        if (personalAccessToken != null) {
            personalAccessTokenDAO.delete(context, personalAccessToken);
        } else {
            throw new BadRequestException("PersonalAccessToken doesn't exist");
        }
    }

    @Override
    public void deleteAll(Context context) throws SQLException, AuthorizeException {
        if (!authorizeService.isAdmin(context)) {
            throw new AuthorizeException("You must be admin user");
        }
        personalAccessTokenDAO.deleteAll(context);
    }

    private void createToken(Context context, PersonalAccessToken pat)
            throws SQLException {

        EPerson ePerson = ePersonDAO.findByID(context, EPerson.class, pat.getID());

        if (ePerson == null) {
            throw new BadRequestException("EPerson with this ID doesn't exist");
        }

        personalAccessTokenDAO.createOrUpdate(context, pat);
    }

}
