/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.content.dao.impl.clarin;

import java.sql.SQLException;

import org.dspace.content.clarin.PersonalAccessToken;
import org.dspace.content.dao.clarin.PersonalAccessTokenDAO;
import org.dspace.core.AbstractHibernateDAO;
import org.dspace.core.Context;

/**
 * Hibernate implementation of the Database Access Object interface class for the PersonalAccessToken object.
 * This class is responsible for all database calls for thePersonalAccessToken object and is autowired by spring
 * This class should never be accessed directly.
 *
 * @author Milan Kuchtiak
 */
public class PersonalAccessTokenDAOImpl extends AbstractHibernateDAO<PersonalAccessToken>
        implements PersonalAccessTokenDAO {

    @Override
    public void createOrUpdate(Context context, PersonalAccessToken pat) throws SQLException {
        if (findByID(context, PersonalAccessToken.class, pat.getID()) == null) {
            getHibernateSession(context).persist(pat);
        } else {
            getHibernateSession(context).merge(pat);
        }
    }

    @Override
    public void deleteAll(Context context) throws SQLException {
        String stringQuery = "DELETE FROM PersonalAccessToken";
        createQuery(context, stringQuery).executeUpdate();
    }
}
