/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.eperson.dao.impl.clarin;

import java.sql.SQLException;
import java.util.List;
import javax.persistence.Query;

import org.dspace.core.AbstractHibernateDAO;
import org.dspace.core.Context;
import org.dspace.eperson.EPerson;
import org.dspace.eperson.clarin.EPersonNetidAlias;
import org.dspace.eperson.dao.clarin.EPersonNetidAliasDAO;

/**
 * Database Access Object implementation class for the EPersonNetidAlias object.
 *
 * @author Ondrej Kosarko
 */
public class EPersonNetidAliasDAOImpl extends AbstractHibernateDAO<EPersonNetidAlias>
        implements EPersonNetidAliasDAO {

    private static final char LIKE_ESCAPE = '\\';

    @Override
    public EPersonNetidAlias findByNetid(Context context, String netid) throws SQLException {
        Query query = createQuery(context, "SELECT a FROM EPersonNetidAlias a WHERE a.netid = :netid");
        query.setParameter("netid", netid);
        query.setHint("org.hibernate.cacheable", Boolean.TRUE);
        return singleResult(query);
    }

    @Override
    public List<EPersonNetidAlias> findByValuePrefix(Context context, String valuePrefix) throws SQLException {
        Query query = createQuery(context,
                "SELECT a FROM EPersonNetidAlias a WHERE a.netid LIKE :pattern ESCAPE '\\'");
        query.setParameter("pattern", escapeLike(valuePrefix) + "[%");
        return findMany(context, query);
    }

    @Override
    public List<EPersonNetidAlias> findByEPerson(Context context, EPerson ePerson) throws SQLException {
        Query query = createQuery(context, "SELECT a FROM EPersonNetidAlias a WHERE a.ePerson = :eperson");
        query.setParameter("eperson", ePerson);
        return findMany(context, query);
    }

    /**
     * Escape LIKE wildcards ('%', '_') in a value that is used as a literal
     * prefix, so an IdP-asserted identifier cannot widen the match.
     */
    private static String escapeLike(String value) {
        return value
                .replace(String.valueOf(LIKE_ESCAPE), LIKE_ESCAPE + "" + LIKE_ESCAPE)
                .replace("%", LIKE_ESCAPE + "%")
                .replace("_", LIKE_ESCAPE + "_");
    }
}
