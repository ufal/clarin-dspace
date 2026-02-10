/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.content.dao.impl;

import java.sql.SQLException;
import java.util.Date;
import javax.persistence.Query;

import org.dspace.content.ReportResult;
import org.dspace.content.dao.ReportResultDAO;
import org.dspace.core.AbstractHibernateDAO;
import org.dspace.core.Context;

/**
 * Database Access Object implementation class for the ReportResult object.
 * This class is responsible for all database calls for the ReportResult object
 * and is autowired by Spring. It extends AbstractHibernateDAO to provide basic CRUD operations.
 *
 * @author Milan Majchrak (milan.majchrak at dataquest.sk)
 */
public class ReportResultDAOImpl extends AbstractHibernateDAO<ReportResult> implements ReportResultDAO {

    @Override
    public ReportResult findByLastModified(Context context, Date lastModified) throws SQLException {
        Query query = createQuery(context, "SELECT r FROM ReportResult r WHERE r.lastModified = :lastModified");

        query.setParameter("lastModified", lastModified);
        query.setHint("org.hibernate.cacheable", Boolean.TRUE);

        return singleResult(query);
    }

    @Override
    public ReportResult findByLastModifiedAndCheckType(Context context, Date lastModified, int checkType)
            throws SQLException {
        // Use string matching for checkType in args (args contains command line options like "-c: 0")
        Query query = createQuery(context, "SELECT r FROM ReportResult r WHERE r.lastModified = :lastModified " +
                "AND r.args LIKE :argsPattern");

        query.setParameter("lastModified", lastModified);
        query.setParameter("argsPattern", "%-c: " + checkType + "%");
        query.setHint("org.hibernate.cacheable", Boolean.TRUE);

        return singleResult(query);
    }
}
