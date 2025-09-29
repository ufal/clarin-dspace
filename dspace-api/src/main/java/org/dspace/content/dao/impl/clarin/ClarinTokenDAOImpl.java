/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.content.dao.impl.clarin;

import java.sql.SQLException;
import javax.persistence.Query;

import org.dspace.content.clarin.ClarinToken;
import org.dspace.content.dao.clarin.ClarinTokenDAO;
import org.dspace.core.AbstractHibernateDAO;
import org.dspace.core.Context;
import org.dspace.eperson.EPerson;

/**
 * Hibernate implementation of the Database Access Object interface class for the ClarinToken object.
 * This class is responsible for all database calls for theClarinToken object and is autowired by spring
 * This class should never be accessed directly.
 *
 * @author Milan Kuchtiak
 */
public class ClarinTokenDAOImpl extends AbstractHibernateDAO<ClarinToken>
        implements ClarinTokenDAO {

    @Override
    public void deleteTokensForEPerson(Context context, EPerson ePerson) throws SQLException {
        Query query = createQuery(context, "DELETE FROM ClarinToken " +
                "WHERE ePerson = :ePerson");
        query.setParameter("ePerson", ePerson);
        query.executeUpdate();
        context.commit();
    }

    @Override
    public void deleteAll(Context context) throws SQLException {
        String stringQuery = "DELETE FROM ClarinToken";
        createQuery(context, stringQuery).executeUpdate();
        context.commit();
    }
}
