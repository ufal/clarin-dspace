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
import org.dspace.content.Item;
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
     * Subscribe current user to get Matomo report for the item.
     * @param context DSpace context object
     * @param item Item to be included in Matomo report
     * @return the newly created MatomoReport object
     * @throws SQLException if database error
     * @throws AuthorizeException the user in not admin
     */
    MatomoReport subscribe(Context context, Item item) throws SQLException, AuthorizeException;

    /**
     * Unsubscribe current user from getting Matomo report for the item.
     * @param context DSpace context object
     * @param item Item to be excluded from Matomo report
     * @throws SQLException if database error
     * @throws AuthorizeException the user in not admin
     */
    void unsubscribe(Context context, Item item) throws SQLException, AuthorizeException;

    /**
     * Check if current user is subscribed to get Matomo report for the item.
     * @param context DSpace context object
     * @param item Item to be checked if included in Matomo report
     * @throws SQLException if database error
     * @throws AuthorizeException the user in not admin
     */
    boolean isSubscribed(Context context, Item item) throws SQLException, AuthorizeException;

    /**
     * Find the MatomoReport object by id
     * @param context DSpace context object
     * @param id id of the searching MatomoReport object
     * @return found MatomoReport object or null
     * @throws SQLException if database error
     */
    MatomoReport find(Context context, int id) throws SQLException, AuthorizeException;

    /**
     * Find the MatomoReport object for the item, for current user .
     * @param context DSpace context object
     * @param item id of the searching MatomoReport object
     * @return found MatomoReport object or null
     * @throws SQLException if database error
     * @throws AuthorizeException the user in not
     */
    MatomoReport findByItem(Context context, Item item) throws SQLException, AuthorizeException;

    /**
     * Find all MatomoReport objects, only available for admin user.
     * @param context DSpace context object
     * @return list of all MatomoReport objects
     * @throws SQLException if database error
     * @throws AuthorizeException the user in not admin
     */
    List<MatomoReport> findAll(Context context) throws SQLException, AuthorizeException;

}
