/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.content.service;

import java.sql.SQLException;
import java.util.Date;
import java.util.List;

import org.dspace.content.ReportResult;
import org.dspace.core.Context;

/**
 * Service interface for managing ReportResult objects.
 * This interface defines methods for creating, finding, deleting, and updating ReportResult instances.
 *
 * @author Milan Majchrak (milan.majchrak at dataquest.sk)
 */
public interface ReportResultService {

    /**
     * Creates a new ReportResult instance in the given context.
     *
     * @param context the DSpace context
     * @return the created ReportResult instance
     * @throws SQLException if an error occurs during creation
     */
    ReportResult create(Context context) throws SQLException;

    /**
     * Creates a new ReportResult instance with the specified reportResult object in the given context.
     *
     * @param context the DSpace context
     * @param reportResult the ReportResult object to create
     * @return the created ReportResult instance
     * @throws SQLException if an error occurs during creation
     */
    ReportResult create(Context context, ReportResult reportResult) throws SQLException;

    /**
     * Finds a ReportResult instance by its ID in the given context.
     *
     * @param context the DSpace context
     * @param id the ID of the ReportResult to find
     * @return the found ReportResult instance, or null if not found
     * @throws SQLException if an error occurs during the search
     */
    ReportResult find(Context context, int id) throws SQLException;

    /**
     * Find all ReportResult instances.
     *
     * @param context the DSpace context
     * @return list of all ReportResult instances
     * @throws SQLException if a database error occurs
     */
    List<ReportResult> findAll(Context context) throws SQLException;

    /**
     * Find a ReportResult by its last modified date.
     *
     * @param context the DSpace context
     * @param lastModified the exact last modified date to search for
     * @return the ReportResult with the given date, or null if not found
     * @throws SQLException if a database error occurs
     */
    ReportResult findByLastModified(Context context, Date lastModified) throws SQLException;

    /**
     * Find a ReportResult by last modified date and check type.
     *
     * @param context the DSpace context
     * @param lastModified the exact last modified date to search for
     * @param checkType the check type index to filter by
     * @return the matching ReportResult, or null if not found
     * @throws SQLException if a database error occurs
     */
    ReportResult findByLastModifiedAndCheckType(Context context, Date lastModified, int checkType) throws SQLException;

    /**
     * Deletes the specified ReportResult instance in the given context.
     *
     * @param context the DSpace context
     * @param reportResult the ReportResult instance to delete
     * @throws SQLException if an error occurs during deletion
     */
    void delete(Context context, ReportResult reportResult) throws SQLException;

    /**
     * Updates the specified ReportResult instance in the given context.
     *
     * @param context the DSpace context
     * @param reportResult the ReportResult instance to update
     * @throws SQLException if an error occurs during the update
     */
    void update(Context context, ReportResult reportResult) throws SQLException;
}
