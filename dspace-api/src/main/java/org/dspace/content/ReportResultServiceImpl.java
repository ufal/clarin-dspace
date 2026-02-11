/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.content;

import java.sql.SQLException;
import java.util.Date;
import java.util.List;

import org.dspace.content.dao.ReportResultDAO;
import org.dspace.content.service.ReportResultService;
import org.dspace.core.Context;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Service implementation for managing ReportResult objects.
 * This class provides methods for creating, finding, deleting, and updating ReportResult instances.
 * @see ReportResultService
 *
 * @author Milan Majchrak (milan.majchrak at dataquest.sk)
 */
public class ReportResultServiceImpl implements ReportResultService {
    @Autowired
    private ReportResultDAO reportResultDAO;

    @Override
    public ReportResult create(Context context) throws SQLException {
        return reportResultDAO.create(context, new ReportResult());
    }

    @Override
    public ReportResult create(Context context, ReportResult reportResult) throws SQLException {
        return reportResultDAO.create(context, reportResult);
    }

    @Override
    public ReportResult find(Context context, int id) throws SQLException {
        return reportResultDAO.findByID(context, ReportResult.class, id);
    }

    @Override
    public List<ReportResult> findAll(Context context) throws SQLException {
        return reportResultDAO.findAll(context, ReportResult.class);
    }

    @Override
    public ReportResult findByLastModified(Context context, Date lastModified) throws SQLException {
        return reportResultDAO.findByLastModified(context, lastModified);
    }

    @Override
    public ReportResult findByLastModifiedAndCheckType(Context context, Date lastModified, int checkType)
            throws SQLException {
        return reportResultDAO.findByLastModifiedAndCheckType(context, lastModified, checkType);
    }

    @Override
    public void delete(Context context, ReportResult reportResult) throws SQLException {
        reportResultDAO.delete(context, reportResult);
    }

    @Override
    public void update(Context context, ReportResult reportResult) throws SQLException {
        // The @UpdateTimestamp annotation on the lastModified field of ReportResult
        // automatically updates lastModified when the entity is saved.
        reportResultDAO.save(context, reportResult);
    }
}
