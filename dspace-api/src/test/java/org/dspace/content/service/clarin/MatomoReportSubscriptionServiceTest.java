/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.content.service.clarin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.sql.SQLException;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.dspace.AbstractIntegrationTestWithDatabase;
import org.dspace.authorize.AuthorizeException;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.content.Collection;
import org.dspace.content.Community;
import org.dspace.content.Item;
import org.dspace.content.WorkspaceItem;
import org.dspace.content.clarin.MatomoReportSubscription;
import org.dspace.content.factory.ClarinServiceFactory;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.InstallItemService;
import org.dspace.content.service.WorkspaceItemService;
import org.junit.Before;
import org.junit.Test;

public class MatomoReportSubscriptionServiceTest extends AbstractIntegrationTestWithDatabase {
    private static final Logger log = LogManager.getLogger(MatomoReportSubscriptionServiceTest.class);

    protected InstallItemService installItemService = ContentServiceFactory.getInstance().getInstallItemService();
    protected WorkspaceItemService workspaceItemService = ContentServiceFactory.getInstance().getWorkspaceItemService();
    private final MatomoReportSubscriptionService matomoReportSubscriptionService =
            ClarinServiceFactory.getInstance().getMatomoReportService();

    Community community;
    Collection collection1;

    Item item;

    /**
     * This method will be run before every test as per @Before. It will
     * initialize resources required for the tests.
     */
    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();
        try {
            context.turnOffAuthorisationSystem();

            community = CommunityBuilder.createCommunity(context)
                .build();

            collection1 = CollectionBuilder.createCollection(context, community)
                .withEntityType("Publication")
                .build();

            WorkspaceItem is = workspaceItemService.create(context, collection1, false);

            item = installItemService.installItem(context, is);

            context.restoreAuthSystemState();
        } catch (AuthorizeException ex) {
            log.error("Authorization Error in init", ex);
            fail("Authorization Error in init: " + ex.getMessage());
        } catch (SQLException ex) {
            log.error("SQL Error in init", ex);
            fail("SQL Error in init: " + ex.getMessage());
        }
    }

    @Test
    public void testMatomoReportSubscriptionService() throws Exception {
        MatomoReportSubscription mrs = new MatomoReportSubscription();
        mrs.setItem(item);
        MatomoReportSubscription expected = new MatomoReportSubscription();
        expected.setItem(item);
        expected.setEPerson(admin);

        context.setCurrentUser(admin);
        MatomoReportSubscription created = matomoReportSubscriptionService.subscribe(context, item);
        expected.setId(created.getID());
        assertEquals(expected, created);

        assertTrue(matomoReportSubscriptionService.isSubscribed(context, item));

        List<MatomoReportSubscription> retrievedSubscriptions = matomoReportSubscriptionService.findAll(context);

        assertEquals(1, retrievedSubscriptions.size());
        assertEquals(expected, retrievedSubscriptions.get(0));

        MatomoReportSubscription retrievedSubscription =
                matomoReportSubscriptionService.findByItem(context, retrievedSubscriptions.get(0).getItem());
        assertEquals(expected, retrievedSubscription);

        matomoReportSubscriptionService.unsubscribe(context, created.getItem());
        assertEquals(0, matomoReportSubscriptionService.findAll(context).size());

        context.setCurrentUser(eperson);
        MatomoReportSubscription mrs1 = new MatomoReportSubscription();
        mrs1.setItem(item);
        MatomoReportSubscription anotherCreated = matomoReportSubscriptionService.subscribe(context, item);
        assertTrue(anotherCreated.getID() > 1);

        MatomoReportSubscription retrievedSubscription1 =
                matomoReportSubscriptionService.find(context, anotherCreated.getID());
        assertEquals(anotherCreated, retrievedSubscription1);

        assertTrue(matomoReportSubscriptionService.isSubscribed(context, item));

        List<MatomoReportSubscription> retrievedSubscriptions1 = matomoReportSubscriptionService.findAll(context);
        assertEquals(1, retrievedSubscriptions1.size());
    }
}
