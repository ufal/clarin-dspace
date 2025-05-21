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
import org.dspace.content.Item;
import org.dspace.content.dao.ItemDAO;
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
    ItemDAO itemDAO;

    @Autowired
    AuthorizeService authorizeService;

    @Override
    public MatomoReport find(Context context, int id) throws SQLException, AuthorizeException {
        if (context.getCurrentUser() == null) {
            throw new AuthorizeException("You must be authenticated user");
        }
        MatomoReport matomoReport =  matomoReportDAO.findByID(context, MatomoReport.class, id);

        if (matomoReport != null && !context.getCurrentUser().getID().equals(matomoReport.getEPerson().getID())) {
            throw new AuthorizeException("You must be admin to get the MatomoReport object for this ID");
        }
        return matomoReport;
    }

    @Override
    public List<MatomoReport> findAll(Context context) throws SQLException, AuthorizeException {
        if (context.getCurrentUser() == null) {
            throw new AuthorizeException("You must be authenticated user");
        }
        if (authorizeService.isAdmin(context)) {
            return matomoReportDAO.findAll(context, MatomoReport.class);
        } else {
            return matomoReportDAO.findByEPersonId(context, context.getCurrentUser().getID());
        }
    }

    @Override
    public MatomoReport findByItem(Context context, Item item) throws SQLException, AuthorizeException {
        if (context.getCurrentUser() == null) {
            throw new AuthorizeException("You must be authenticated user");
        }
        return matomoReportDAO.findByItemId(context, item.getID());
    }

    @Override
    public MatomoReport subscribe(Context context, Item item) throws SQLException, AuthorizeException {
        MatomoReport matomoReport = findByItem(context, item);

        if (matomoReport != null) {
            // already subscribed
            return matomoReport;
        } else {
            MatomoReport  mr = new MatomoReport();
            mr.setEPerson(context.getCurrentUser());
            mr.setItem(item);
            return matomoReportDAO.create(context, mr);
        }
    }

    @Override
    public void unsubscribe(Context context, Item item) throws SQLException, AuthorizeException {
        if (context.getCurrentUser() == null) {
            throw new AuthorizeException("You must be authenticated user");
        }
        MatomoReport matomoReport = findByItem(context, item);
        if (matomoReport != null) {
            matomoReportDAO.delete(context, matomoReport);
        }
    }

    @Override
    public boolean isSubscribed(Context context, Item item) throws SQLException, AuthorizeException {
        if (context.getCurrentUser() == null) {
            throw new AuthorizeException("You must be authenticated user");
        }
        return (matomoReportDAO.findByItemId(context, item.getID()) != null);
    }
}
