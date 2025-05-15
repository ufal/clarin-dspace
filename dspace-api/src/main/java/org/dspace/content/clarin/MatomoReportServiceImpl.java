/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.content.clarin;

import java.sql.SQLException;
import java.util.List;

import org.dspace.authorize.AuthorizeException;
import org.dspace.authorize.service.AuthorizeService;
import org.dspace.content.dao.clarin.MatomoReportDAO;
import org.dspace.content.service.clarin.MatomoReportService;
import org.dspace.core.Context;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Service implementation for MatomoReport object.
 * This class is responsible for all business logic calls for the MatomoReport object and is autowired
 * by spring.
 * This class should never be accessed directly.
 *
 * @author Milan Kuchtiak
 */
public class MatomoReportServiceImpl implements MatomoReportService {

    @Autowired
    MatomoReportDAO matomoReportDAO;

    @Autowired
    AuthorizeService authorizeService;

    @Override
    public MatomoReport create(Context context, MatomoReport matomoReport) throws SQLException,
            AuthorizeException {
        if (!authorizeService.isAdmin(context)) {
            throw new AuthorizeException(
                    "You must be an admin to create a MatomoReport object");
        }

        return matomoReportDAO.create(context, matomoReport);
    }

    @Override
    public MatomoReport find(Context context, int id) throws SQLException {
        return matomoReportDAO.findByID(context, MatomoReport.class, id);
    }

    @Override
    public List<MatomoReport> findAll(Context context) throws SQLException, AuthorizeException {
        return matomoReportDAO.findAll(context, MatomoReport.class);
    }

    @Override
    public void delete(Context context, MatomoReport matomoReport) throws SQLException, AuthorizeException {
        if (!authorizeService.isAdmin(context)) {
            throw new AuthorizeException(
                    "You must be an admin to delete a MatomoReport object");
        }

        matomoReportDAO.delete(context, matomoReport);
    }
}
