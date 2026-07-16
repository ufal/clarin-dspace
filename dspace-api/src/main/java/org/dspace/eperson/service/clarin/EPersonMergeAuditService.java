/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.eperson.service.clarin;

import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

import org.dspace.core.Context;
import org.dspace.eperson.clarin.EPersonMergeAudit;

/**
 * Records the audit trail for {@code eperson-merge}.
 *
 * @author Ondrej Kosarko
 */
public interface EPersonMergeAuditService {

    /**
     * Persist one merge audit record.
     *
     * @param detail per-table re-pointed row counts, moved netid/email, tool version
     */
    EPersonMergeAudit record(Context context, UUID fromEPerson, UUID toEPerson, UUID performedBy,
        Map<String, Object> detail) throws SQLException;
}
