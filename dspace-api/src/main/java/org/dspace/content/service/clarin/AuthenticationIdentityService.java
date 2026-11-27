/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.content.service.clarin;

import java.sql.SQLException;
import java.util.List;

import org.dspace.authorize.AuthorizeException;
import org.dspace.content.clarin.AuthenticationIdentity;
import org.dspace.core.Context;
import org.dspace.eperson.EPerson;

/**
 * Service interface class for the AuthenticationIdentity object.
 * The implementation of this class is responsible for all business logic calls for the AuthenticationIdentity
 * object and is autowired by spring
 *
 * @author Milan Kuchtiak
 */
public interface AuthenticationIdentityService {

    /**
     * Find the AuthenticationIdentity object by id.
     *
     * @param context DSpace context object
     * @param id ID of the searching AuthenticationIdentity object
     * @return found AuthenticationIdentity object or null
     * @throws SQLException if database error
     */
    AuthenticationIdentity find(Context context, Integer id) throws SQLException;

    /**
     * Find the identity by its unique key. Used during login to find the EPerson linked to the external identity.
     *
     * @param context DSpace context object
     * @param authMethod authentication method, e.g. "oidc"
     * @param provider identity provider label, e.g. "einfra"
     * @param userId user identifier assigned by the provider, e.g. OIDC "sub"
     * @return found AuthenticationIdentity object or null
     * @throws SQLException if database error
     */
    AuthenticationIdentity find(Context context, String authMethod, String provider, String userId)
            throws SQLException;

    /**
     * Find all identities linked to the ePerson.
     *
     * @param context DSpace context object
     * @param ePerson EPerson
     * @return list of identities, empty if none found
     * @throws SQLException if database error
     */
    List<AuthenticationIdentity> findByEPerson(Context context, EPerson ePerson) throws SQLException;

    /**
     * Find identities of given authentication method linked to the ePerson.
     *
     * @param context DSpace context object
     * @param ePerson EPerson
     * @param authMethod authentication method, e.g. "oidc"
     * @return list of identities, empty if none found
     * @throws SQLException if database error
     */
    List<AuthenticationIdentity> findByEPersonAndAuthMethod(Context context,
                                                            EPerson ePerson,
                                                            String authMethod) throws SQLException;

    /**
     * Create new identity linked to the ePerson. Both created and lastLogin dates are set to current time.
     * Only admin can create identity, unless the authorization system is turned off (e.g. during login).
     *
     * @param context DSpace context object
     * @param ePerson EPerson to link the identity to
     * @param authMethod authentication method, e.g. "oidc"
     * @param provider identity provider label, e.g. "einfra"
     * @param userId user identifier assigned by the provider, e.g. OIDC "sub"
     * @return created AuthenticationIdentity object
     * @throws SQLException if database error
     * @throws AuthorizeException when user is not allowed to create identity
     */
    AuthenticationIdentity create(Context context,
                                  EPerson ePerson,
                                  String authMethod,
                                  String provider,
                                  String userId) throws SQLException, AuthorizeException;

    /**
     * Set lastLogin date of the identity to current time.
     * Only admin can update identity, unless the authorization system is turned off (e.g. during login).
     *
     * @param context DSpace context object
     * @param identity identity to update
     * @throws SQLException if database error
     * @throws AuthorizeException when user is not allowed to update identity
     */
    void updateLastLogin(Context context, AuthenticationIdentity identity)
            throws SQLException, AuthorizeException;

    /**
     * Delete (unlink) the identity. Allowed for admin or the owner of the identity.
     *
     * @param context DSpace context object
     * @param identity identity to delete
     * @throws SQLException if database error
     * @throws AuthorizeException when user is not allowed to delete identity
     */
    void delete(Context context, AuthenticationIdentity identity) throws SQLException, AuthorizeException;

    /**
     * Delete (unlink) all identities of the ePerson. Allowed for admin or the ePerson itself.
     *
     * @param context DSpace context object
     * @param ePerson EPerson
     * @throws SQLException if database error
     * @throws AuthorizeException when user is not allowed to delete identities
     */
    void delete(Context context, EPerson ePerson) throws SQLException, AuthorizeException;
}
