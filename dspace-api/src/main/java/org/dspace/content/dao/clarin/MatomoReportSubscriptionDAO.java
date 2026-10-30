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
import java.util.UUID;

import org.dspace.content.clarin.MatomoReportSubscription;
import org.dspace.core.Context;
import org.dspace.core.GenericDAO;

/**
 * Database Access Object interface class for the MatomoReportSubscription object.
 * The implementation of this class is responsible for all database calls for the MatomoReportSubscription object
 * and is autowired by spring This class should only be accessed from a single service and should never be exposed
 * outside the API
 *
 * @author Milan Kuchtiak
 */
public interface MatomoReportSubscriptionDAO extends GenericDAO<MatomoReportSubscription> {

    MatomoReportSubscription findByItemIdAndCurrentUser(Context context, UUID itemId) throws SQLException;

    MatomoReportSubscription findByEPersonIdAndItemId(Context context, UUID ePersonId, UUID itemId) throws SQLException;

    List<MatomoReportSubscription> findByEPersonId(Context context, UUID ePersonId)  throws SQLException;
}
