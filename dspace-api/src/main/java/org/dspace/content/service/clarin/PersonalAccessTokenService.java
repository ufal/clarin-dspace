/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.content.service.clarin;

import java.sql.SQLException;
import java.util.Date;
import java.util.UUID;

import org.dspace.authorize.AuthorizeException;
import org.dspace.content.clarin.PersonalAccessToken;
import org.dspace.core.Context;

/**
 * Service interface class for the PersonalAccessToken object.
 * The implementation of this class is responsible for all business logic calls for the PersonalAccessToken object
 * and is autowired by spring
 *
 * @author Milan Kuchtiak
 */
public interface PersonalAccessTokenService {

    /**
     * Find the PersonalAccessToken object by id
     *
     * @param context DSpace context object
     * @param uuid ePerson ID of the searching PersonalAccessToken object
     * @return found PersonalAccessToken object or null
     * @throws SQLException if database error
     */
    PersonalAccessToken find(Context context, UUID uuid) throws SQLException;

    /**
     * Create token for ePerson, with given ID, and create PersonalAccessToken object containing shared secret string
     * used to verify token.
     *
     * @param context DSpace context object
     * @param ePersonID ePerson ID
     * @param expirationTime expiration time when token becomes expired
     * @return token string
     * @throws SQLException if database error
     * @throws AuthorizeException when user is not allowed to create token
     */
    String createToken(Context context, UUID ePersonID, Date expirationTime) throws SQLException, AuthorizeException;

    /**
     *  Delete PersonalAccessToken object for ePerson, with given ID.
     *
     * @param context DSpace context object
     * @param ePersonID ePerson ID
     * @throws SQLException if database error
     * @throws AuthorizeException when user is not admin user
     */
    void delete(Context context, UUID ePersonID) throws SQLException, AuthorizeException;

    /**
     * Delete all PersonalAccessToken objects.
     *
     * @param context DSpace context object
     * @throws SQLException if database error
     * @throws AuthorizeException when user is not admin user
     */
    void deleteAll(Context context) throws SQLException, AuthorizeException;

}
