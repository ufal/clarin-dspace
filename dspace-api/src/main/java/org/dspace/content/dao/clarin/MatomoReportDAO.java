/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.content.dao.clarin;

import java.sql.SQLException;
import java.util.UUID;

import org.dspace.content.clarin.MatomoReport;
import org.dspace.core.Context;
import org.dspace.core.GenericDAO;

/**
 * Database Access Object interface class for the MatomoReport object.
 * The implementation of this class is responsible for all database calls for the MatomoReport object
 * and is autowired by spring This class should only be accessed from a single service and should never be exposed
 * outside the API
 *
 * @author Milan Kuchtiak
 */
public interface MatomoReportDAO extends GenericDAO<MatomoReport> {

    MatomoReport findByItemId(Context context, UUID itemId)  throws SQLException;

    MatomoReport findByEPersonIdAndItemId(Context context, UUID ePersonId, UUID itemId)  throws SQLException;

}
