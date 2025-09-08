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
     * @param context DSpace context object
     * @param uuid ePerson ID of the searching PersonalAccessToken object
     * @return found PersonalAccessToken object or null
     * @throws SQLException if database error
     */
    PersonalAccessToken find(Context context, UUID uuid) throws SQLException;

    String createToken(Context context, UUID uuid, Date expirationTime) throws SQLException, AuthorizeException;

    void delete(Context context, UUID uuid) throws SQLException, AuthorizeException;

    void deleteAll(Context context) throws SQLException, AuthorizeException;

}
