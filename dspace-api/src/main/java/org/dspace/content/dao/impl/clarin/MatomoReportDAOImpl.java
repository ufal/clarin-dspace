/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.content.dao.impl.clarin;

import java.sql.SQLException;
import java.util.UUID;
import javax.persistence.Query;

import org.dspace.content.clarin.MatomoReport;
import org.dspace.content.dao.clarin.MatomoReportDAO;
import org.dspace.core.AbstractHibernateDAO;
import org.dspace.core.Context;
import org.dspace.eperson.EPerson;

/**
 * Hibernate implementation of the Database Access Object interface class for the MatomoReport object.
 * This class is responsible for all database calls for the MatomoReport object and is autowired by spring
 * This class should never be accessed directly.
 *
 * @author Milan Kuchtiak
 */
public class MatomoReportDAOImpl extends AbstractHibernateDAO<MatomoReport>
        implements MatomoReportDAO {
    protected MatomoReportDAOImpl() {
        super();
    }

    @Override
    public MatomoReport findByItemId(Context context, UUID itemId)throws SQLException {
        EPerson currentUser = context.getCurrentUser();
        return findByEPersonIdAndItemId(context, currentUser.getID(), itemId);
    }

    @Override
    public MatomoReport findByEPersonIdAndItemId(Context context, UUID ePersonId, UUID itemId) throws SQLException {
        Query query = createQuery(
                context,
                "SELECT mr FROM MatomoReport mr WHERE mr.ePerson.id = :ePersonId AND mr.item.id = :itemId"
        );
        query.setParameter("ePersonId", ePersonId);
        query.setParameter("itemId", itemId);
        query.setHint("org.hibernate.cacheable", Boolean.TRUE);

        return singleResult(query);
    }


}
