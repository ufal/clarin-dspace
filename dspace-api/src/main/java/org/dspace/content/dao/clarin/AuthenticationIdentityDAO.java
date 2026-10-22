/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.content.dao.clarin;

import java.sql.SQLException;
import java.util.List;

import org.dspace.content.clarin.AuthenticationIdentity;
import org.dspace.core.Context;
import org.dspace.core.GenericDAO;
import org.dspace.eperson.EPerson;

/**
 * Database Access Object interface class for the AuthenticationIdentity object.
 * The implementation of this class is responsible for all database calls for the AuthenticationIdentity object
 * and is autowired by spring This class should only be accessed from a single service and should never be exposed
 * outside the API
 *
 * @author Milan Kuchtiak
 */
public interface AuthenticationIdentityDAO extends GenericDAO<AuthenticationIdentity> {

    /**
     * Find the identity by its unique key.
     *
     * @param context    DSpace context
     * @param authMethod authentication method, e.g. "oidc"
     * @param provider   identity provider label, e.g. "einfra"
     * @param userId     user identifier assigned by the provider, e.g. OIDC "sub"
     * @return the identity or null if not found
     * @throws SQLException if database error
     */
    AuthenticationIdentity findByAuthMethodAndProviderAndUserId(Context context,
                                                                String authMethod,
                                                                String provider,
                                                                String userId)
            throws SQLException;

    /**
     * Find all identities linked to the given EPerson.
     *
     * @param context DSpace context
     * @param ePerson EPerson
     * @return list of identities, empty if none found
     * @throws SQLException if database error
     */
    List<AuthenticationIdentity> findByEPerson(Context context, EPerson ePerson) throws SQLException;

    /**
     * Find identities of the given authentication method linked to the given EPerson.
     *
     * @param context    DSpace context
     * @param ePerson    EPerson
     * @param authMethod authentication method, e.g. "oidc"
     * @return list of identities, empty if none found
     * @throws SQLException if database error
     */
    List<AuthenticationIdentity> findByEPersonAndAuthMethod(Context context, EPerson ePerson, String authMethod)
            throws SQLException;

    void deleteByEPerson(Context context, EPerson ePerson) throws SQLException;

}

