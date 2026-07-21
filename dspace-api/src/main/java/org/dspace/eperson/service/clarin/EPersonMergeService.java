/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.eperson.service.clarin;

import java.sql.SQLException;

import org.dspace.authorize.AuthorizeException;
import org.dspace.core.Context;
import org.dspace.eperson.EPerson;
import org.dspace.eperson.clarin.EPersonMergeAudit;

/**
 * Merges two EPerson accounts that turned out to be duplicates of the same
 * human. Re-points foreign keys, unions/dedupes memberships, tombstones the
 * {@code from} account (never deletes it) and writes an audit trail.
 * Deliberately does NOT rewrite item metadata/provenance - historical
 * {@code dc.description.provenance} keeps the old submitter identity, since
 * it records what was true at the time.
 *
 * @author Ondrej Kosarko
 */
public interface EPersonMergeService {

    /**
     * Merge {@code from} into {@code to}.
     *
     * @param performedBy the admin EPerson running the merge (may be null for a system/CLI run
     *      with no logged-in admin)
     * @return the audit record describing what was moved
     */
    EPersonMergeAudit merge(Context context, EPerson from, EPerson to, EPerson performedBy)
        throws SQLException, AuthorizeException;
}
