/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.content.dao;

import java.sql.SQLException;
import java.util.Date;

import org.dspace.content.ReportResult;
import org.dspace.core.Context;
import org.dspace.core.GenericDAO;

/**
 * Database Access Object interface class for the ReportResult object.
 * The implementation of this class is responsible for all database calls for the ReportResult object
 *
 * @author Milan Majchrak (milan.majchrak at dataquest.sk)
 */
public interface ReportResultDAO extends GenericDAO<ReportResult> {
    /**
      * Find a ReportResult by its last modified date.
      *
      * @param context the DSpace context
      * @param lastModified the exact last modified date to search for
      * @return the ReportResult with the given last modified date, or null if not found
      * @throws SQLException if a database error occurs
      */
    ReportResult findByLastModified(Context context, Date lastModified) throws SQLException;

    /**
     * Find a ReportResult by its last modified date and check type.
     *
     * @param context the DSpace context
     * @param lastModified the exact last modified date to search for
     * @param checkType the check type index to filter by (searches within args field)
     * @return the ReportResult matching both criteria, or null if not found
     * @throws SQLException if a database error occurs
     */
    ReportResult findByLastModifiedAndCheckType(Context context, Date lastModified, int checkType) throws SQLException;
}

