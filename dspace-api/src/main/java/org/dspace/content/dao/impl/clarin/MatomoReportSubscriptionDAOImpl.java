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

import org.dspace.content.clarin.MatomoReportSubscription;
import org.dspace.content.dao.clarin.MatomoReportSubscriptionDAO;
import org.dspace.core.AbstractHibernateDAO;
import org.dspace.core.Context;
import org.dspace.eperson.EPerson;

/**
 * Hibernate implementation of the Database Access Object interface class for the MatomoReportSubscription object.
 * This class is responsible for all database calls for the MatomoReportSubscription object and is autowired by spring
 * This class should never be accessed directly.
 *
 * @author Milan Kuchtiak
 */
public class MatomoReportSubscriptionDAOImpl extends AbstractHibernateDAO<MatomoReportSubscription>
        implements MatomoReportSubscriptionDAO {
    protected MatomoReportSubscriptionDAOImpl() {
        super();
    }

    @Override
    public MatomoReportSubscription findByItemIdAndCurrentUser(Context context, UUID itemId)throws SQLException {
        EPerson currentUser = context.getCurrentUser();
        return findByEPersonIdAndItemId(context, currentUser.getID(), itemId);
    }

    @Override
    public MatomoReportSubscription findByEPersonIdAndItemId(Context context, UUID ePersonId, UUID itemId)
            throws SQLException {
        Query query = createQuery(
                context,
                "SELECT m FROM MatomoReportSubscription m WHERE m.ePerson.id = :ePersonId AND m.item.id = :itemId"
        );
        query.setParameter("ePersonId", ePersonId);
        query.setParameter("itemId", itemId);
        query.setHint("org.hibernate.cacheable", Boolean.TRUE);

        return singleResult(query);
    }

    @Override
    public List<MatomoReportSubscription> findByEPersonId(Context context, UUID ePersonId) throws SQLException {
        Query query = createQuery(
                context,
                "SELECT m FROM MatomoReportSubscription m WHERE m.ePerson.id = :ePersonId"
        );
        query.setParameter("ePersonId", ePersonId);
        query.setHint("org.hibernate.cacheable", Boolean.TRUE);

        return list(query);
    }
}
