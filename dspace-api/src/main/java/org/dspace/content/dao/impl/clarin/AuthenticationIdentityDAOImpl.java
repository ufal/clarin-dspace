/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.content.dao.impl.clarin;

import java.sql.SQLException;
import java.util.List;
import javax.persistence.Query;

import org.dspace.content.clarin.AuthenticationIdentity;
import org.dspace.content.dao.clarin.AuthenticationIdentityDAO;
import org.dspace.core.AbstractHibernateDAO;
import org.dspace.core.Context;
import org.dspace.eperson.EPerson;

/**
 * Hibernate implementation of the Database Access Object interface class for the AuthenticationIdentity
 * object. This class is responsible for all database calls for the AuthenticationIdentity object and is
 * autowired by spring. This class should never be accessed directly.
 *
 * @author Milan Kuchtiak
 */
public class AuthenticationIdentityDAOImpl extends AbstractHibernateDAO<AuthenticationIdentity>
        implements AuthenticationIdentityDAO {

    @Override
    public AuthenticationIdentity findByAuthMethodAndProviderAndUserId(Context context,
                                                                       String authMethod,
                                                                       String provider,
                                                                       String userId) throws SQLException {
        Query query = createQuery(context, "SELECT ai FROM AuthenticationIdentity ai " +
                "WHERE ai.authMethod = :authMethod AND ai.provider = :provider AND ai.userId = :userId");
        query.setParameter("authMethod", authMethod);
        query.setParameter("provider", provider);
        query.setParameter("userId", userId);
        return singleResult(query);
    }

    @Override
    public List<AuthenticationIdentity> findByEPerson(Context context, EPerson ePerson) throws SQLException {
        Query query = createQuery(context, "SELECT ai FROM AuthenticationIdentity ai " +
                "WHERE ai.ePerson = :ePerson");
        query.setParameter("ePerson", ePerson);
        return list(query);
    }

    @Override
    public List<AuthenticationIdentity> findByEPersonAndAuthMethod(Context context,
                                                                   EPerson ePerson,
                                                                   String authMethod) throws SQLException {
        Query query = createQuery(context, "SELECT ai FROM AuthenticationIdentity ai " +
                "WHERE ai.ePerson = :ePerson AND ai.authMethod = :authMethod");
        query.setParameter("ePerson", ePerson);
        query.setParameter("authMethod", authMethod);
        return list(query);
    }

    @Override
    public void deleteByEPerson(Context context, EPerson ePerson) throws SQLException {
        Query query = createQuery(context, "DELETE FROM AuthenticationIdentity " +
                "WHERE ePerson = :ePerson");
        query.setParameter("ePerson", ePerson);
        query.executeUpdate();
    }

}

