/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.content.clarin;

import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.ws.rs.BadRequestException;

import org.dspace.authorize.AuthorizeException;
import org.dspace.authorize.service.AuthorizeService;
import org.dspace.content.Item;
import org.dspace.content.dao.ItemDAO;
import org.dspace.content.dao.clarin.MatomoReportSubscriptionDAO;
import org.dspace.content.service.clarin.MatomoReportSubscriptionService;
import org.dspace.core.Context;
import org.dspace.eperson.EPerson;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Service implementation for MatomoReportSubscription object.
 * This class is responsible for all business logic calls for the MatomoReportSubscription object and is autowired
 * by spring.
 * This class should never be accessed directly.
 *
 * @author Milan Kuchtiak
 */
public class MatomoReportSubscriptionServiceImpl implements MatomoReportSubscriptionService {

    @Autowired
    MatomoReportSubscriptionDAO matomoReportSubscriptionDAO;

    @Autowired
    ItemDAO itemDAO;

    @Autowired
    AuthorizeService authorizeService;

    @Override
    public MatomoReportSubscription find(Context context, int id) throws SQLException, AuthorizeException {
        if (context.getCurrentUser() == null) {
            throw new AuthorizeException("You must be authenticated user");
        }
        MatomoReportSubscription matomoReportSubscription =
                matomoReportSubscriptionDAO.findByID(context, MatomoReportSubscription.class, id);

        if (matomoReportSubscription != null && !authorizeService.isAdmin(context) &&
                !context.getCurrentUser().getID().equals(matomoReportSubscription.getEPerson().getID())) {
            throw new AuthorizeException("You must be admin user to get matomo report subscription for this ID");
        }
        return matomoReportSubscription;
    }

    @Override
    public List<MatomoReportSubscription> findAll(Context context) throws SQLException, AuthorizeException {
        if (context.getCurrentUser() == null) {
            throw new AuthorizeException("You must be authenticated user");
        }
        if (authorizeService.isAdmin(context)) {
            return matomoReportSubscriptionDAO.findAll(context, MatomoReportSubscription.class);
        } else {
            return matomoReportSubscriptionDAO.findByEPersonId(context, context.getCurrentUser().getID());
        }
    }

    @Override
    public MatomoReportSubscription findByItem(Context context, Item item) throws SQLException, AuthorizeException {
        if (context.getCurrentUser() == null) {
            throw new AuthorizeException("You must be authenticated user");
        }
        return matomoReportSubscriptionDAO.findByItemIdAndCurrentUser(context, item.getID());
    }

    @Override
    public MatomoReportSubscription subscribe(Context context, Item item) throws SQLException, AuthorizeException {
        MatomoReportSubscription matomoReportSubscription = findByItem(context, item);

        if (matomoReportSubscription != null) {
            // already subscribed
            return matomoReportSubscription;
        } else {
            MatomoReportSubscription mrs = new MatomoReportSubscription();
            mrs.setEPerson(context.getCurrentUser());
            mrs.setItem(item);
            return matomoReportSubscriptionDAO.create(context, mrs);
        }
    }

    @Override
    public void unsubscribe(Context context, Item item) throws SQLException, AuthorizeException {
        if (context.getCurrentUser() == null) {
            throw new AuthorizeException("You must be authenticated user");
        }
        MatomoReportSubscription matomoReportSubscription = findByItem(context, item);
        if (matomoReportSubscription != null) {
            matomoReportSubscriptionDAO.delete(context, matomoReportSubscription);
        } else {
            throw new BadRequestException("Matomo report subscription for this item doesn't exist for this user");
        }
    }

    @Override
    public boolean isSubscribed(Context context, Item item) throws SQLException, AuthorizeException {
        if (context.getCurrentUser() == null) {
            throw new AuthorizeException("You must be authenticated user");
        }
        return (matomoReportSubscriptionDAO.findByItemIdAndCurrentUser(context, item.getID()) != null);
    }

    @Override
    public int reassignEPerson(Context context, EPerson from, EPerson to) throws SQLException {
        Set<UUID> toItemIds = new HashSet<>();
        for (MatomoReportSubscription subscription : matomoReportSubscriptionDAO.findByEPersonId(context,
                to.getID())) {
            toItemIds.add(subscription.getItem().getID());
        }

        int count = 0;
        for (MatomoReportSubscription subscription : matomoReportSubscriptionDAO.findByEPersonId(context,
                from.getID())) {
            UUID itemId = subscription.getItem().getID();
            if (toItemIds.contains(itemId)) {
                // `to` is already subscribed to this item - drop the duplicate.
                matomoReportSubscriptionDAO.delete(context, subscription);
            } else {
                subscription.setEPerson(to);
                matomoReportSubscriptionDAO.save(context, subscription);
                toItemIds.add(itemId);
                count++;
            }
        }
        return count;
    }
}
