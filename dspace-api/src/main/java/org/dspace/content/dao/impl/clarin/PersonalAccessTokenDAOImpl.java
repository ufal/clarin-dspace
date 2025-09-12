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
import java.util.UUID;
import javax.persistence.Query;

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
    public PersonalAccessToken createOrUpdate(Context context, PersonalAccessToken pat) throws SQLException {
        PersonalAccessToken existingPat = findByEPersonUUID(context, pat.getEPersonID());
        if (existingPat != null) {
            pat.setId(existingPat.getID());
            getHibernateSession(context).merge(pat);
            return pat;
        } else {
            return create(context, pat);
        }
    }

    @Override
    public PersonalAccessToken findByEPersonUUID(Context context, UUID epersonUUID) throws SQLException {
        Query query = createQuery(context, "SELECT pat FROM PersonalAccessToken as pat " +
                "WHERE pat.ePersonID = :epersonUUID");
        query.setParameter("epersonUUID", epersonUUID);
        List<PersonalAccessToken> resultList = list(query);
        return resultList.isEmpty() ? null : resultList.get(0);
    }

    @Override
    public void deleteAll(Context context) throws SQLException {
        String stringQuery = "DELETE FROM PersonalAccessToken";
        createQuery(context, stringQuery).executeUpdate();
        context.commit();
    }
}
