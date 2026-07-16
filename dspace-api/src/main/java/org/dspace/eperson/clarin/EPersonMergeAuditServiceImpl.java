/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.eperson.clarin;

import java.sql.SQLException;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dspace.core.Context;
import org.dspace.eperson.dao.clarin.EPersonMergeAuditDAO;
import org.dspace.eperson.service.clarin.EPersonMergeAuditService;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * @see EPersonMergeAuditService
 *
 * @author Ondrej Kosarko
 */
public class EPersonMergeAuditServiceImpl implements EPersonMergeAuditService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private EPersonMergeAuditDAO ePersonMergeAuditDAO;

    @Override
    public EPersonMergeAudit record(Context context, UUID fromEPerson, UUID toEPerson, UUID performedBy,
            Map<String, Object> detail) throws SQLException {
        EPersonMergeAudit audit = new EPersonMergeAudit();
        audit.setFromEPerson(fromEPerson);
        audit.setToEPerson(toEPerson);
        audit.setPerformedBy(performedBy);
        audit.setPerformedAt(new Date());
        try {
            audit.setDetail(objectMapper.writeValueAsString(detail));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialize merge audit detail", e);
        }
        return ePersonMergeAuditDAO.create(context, audit);
    }
}
