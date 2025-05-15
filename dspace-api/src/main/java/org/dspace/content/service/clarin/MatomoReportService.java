/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.content.service.clarin;

import java.sql.SQLException;
import java.util.List;

import org.dspace.authorize.AuthorizeException;
import org.dspace.content.clarin.MatomoReport;
import org.dspace.core.Context;

/**
 * Service interface class for the MatomoReport object.
 * The implementation of this class is responsible for all business logic calls for the MatomoReport object
 * and is autowired by spring
 *
 * @author Milan Kuchtiak
 */
public interface MatomoReportService {

    /**
     * Create a new MatomoReport object. Authorization is done inside this method.
     * @param context DSpace context object
     * @param matomoReport new MatomoReport object data
     * @return the newly created MatomoReport object
     * @throws SQLException if database error
     * @throws AuthorizeException the user in not admin
     */
    MatomoReport create(Context context, MatomoReport matomoReport) throws SQLException,
            AuthorizeException;

    /**
     * Find the MatomoReport object by id
     * @param context DSpace context object
     * @param id id of the searching MatomoReport object
     * @return found MatomoReport object or null
     * @throws SQLException if database error
     */
    MatomoReport find(Context context, int id) throws SQLException;

    /**
     * Find all MatomoReport objects
     * @param context DSpace context object
     * @return list of all MatomoReport objects
     * @throws SQLException if database error
     * @throws AuthorizeException the user in not admin
     */
    List<MatomoReport> findAll(Context context) throws SQLException, AuthorizeException;

    /**
     * Delete the MatomoReport by id. The id is retrieved from passed MatomoReport object.
     * @param context DSpace context object
     * @param matomoReport object to delete
     * @throws SQLException if database error
     * @throws AuthorizeException the user in not admin
     */
    void delete(Context context, MatomoReport matomoReport) throws SQLException, AuthorizeException;

}
