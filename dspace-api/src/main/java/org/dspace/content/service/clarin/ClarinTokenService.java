/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.content.service.clarin;

import java.sql.SQLException;
import java.text.ParseException;
import java.util.Date;

import com.nimbusds.jose.JOSEException;
import org.dspace.authorize.AuthorizeException;
import org.dspace.content.clarin.ClarinToken;
import org.dspace.core.Context;
import org.dspace.eperson.EPerson;

/**
 * Service interface class for the ClarinTokenService object.
 * The implementation of this class is responsible for all business logic calls for the ClarinTokenService object
 * and is autowired by spring
 *
 * @author Milan Kuchtiak
 */
public interface ClarinTokenService {

    /**
     * Find the ClarinToken object by id.
     * Any user can get ClarinToken object for given ID, to allow JWT authentication service
     * to get the userID + sign key for given token, at the moment when nobody is signed to DSpace yet.
     *
     * @param context DSpace context object
     * @param id ID of the searching larinToken object
     * @return found ClarinToken object or null
     * @throws SQLException if database error
     */
    ClarinToken find(Context context, Integer id) throws SQLException;

    /**
     * Create new token for ePerson, with given ID, and create ClarinToken object containing shared secret string
     * used to verify token.
     *
     * @param context DSpace context object
     * @param ePerson EPerson
     * @param expirationTime expiration time when token becomes expired
     * @return token string
     * @throws SQLException if database error
     * @throws AuthorizeException when user is not allowed to create token
     */
    String createToken(Context context, EPerson ePerson, Date expirationTime) throws SQLException, AuthorizeException;

    /**
     *  Delete/Invalidate all clarin tokens for ePerson, with given ID.
     *
     * @param context DSpace context object
     * @param ePerson EPerson
     * @throws SQLException if database error
     * @throws AuthorizeException when user is not allowed to delete token
     */
    void delete(Context context, EPerson ePerson) throws SQLException, AuthorizeException;

    /**
     *  Delete/Invalidate token.
     *
     * @param context DSpace context object
     * @param token token string
     * @throws SQLException if database error
     * @throws AuthorizeException when user is not allowed to delete token
     */
    void delete(Context context, String token) throws SQLException, AuthorizeException;

    /**
     * Delete/Invalidate all clarin tokens.
     *
     * @param context DSpace context object
     * @throws SQLException if database error
     * @throws AuthorizeException when user is not admin user
     */
    void deleteAll(Context context) throws SQLException, AuthorizeException;

    /**
     * Get EPerson object from clarin token.
     *
     * @param context DSpace context object
     * @param token clarin token string
     * @return EPerson object
     * @throws SQLException if database error occurs
     * @throws ParseException if token parse error occurs
     * @throws JOSEException if other JOSE error occurs
     */
    EPerson getEPersonFromClarinToken(Context context, String token) throws SQLException, ParseException, JOSEException;

}
