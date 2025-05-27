/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.content.service.clarin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.sql.SQLException;
import java.util.List;
import javax.ws.rs.BadRequestException;

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
    public void testSubscribe() throws Exception {
        context.setCurrentUser(admin);
        MatomoReportSubscription expected = new MatomoReportSubscription();
        expected.setItem(item);
        expected.setEPerson(admin);

        MatomoReportSubscription created1 = matomoReportSubscriptionService.subscribe(context, item);
        expected.setId(created1.getID());
        assertEquals(expected, created1);
        assertTrue(matomoReportSubscriptionService.isSubscribed(context, item));

        context.setCurrentUser(eperson);
        expected.setEPerson(eperson);

        MatomoReportSubscription created2 = matomoReportSubscriptionService.subscribe(context, item);
        expected.setId(created2.getID());
        assertEquals(expected, created2);
        assertTrue(matomoReportSubscriptionService.isSubscribed(context, item));
    }

    @Test
    public void testUnSubscribe() throws Exception {
        context.setCurrentUser(admin);
        matomoReportSubscriptionService.subscribe(context, item);

        context.setCurrentUser(eperson);
        matomoReportSubscriptionService.subscribe(context, item);

        context.setCurrentUser(admin);
        assertEquals(2, matomoReportSubscriptionService.findAll(context).size());
        matomoReportSubscriptionService.unsubscribe(context, item);
        assertEquals(1, matomoReportSubscriptionService.findAll(context).size());

        // common user cannot see other subscriptions
        assertThrows(BadRequestException.class, () ->
                matomoReportSubscriptionService.unsubscribe(context, item)
        );

        context.setCurrentUser(eperson);
        assertEquals(1, matomoReportSubscriptionService.findAll(context).size());
        matomoReportSubscriptionService.unsubscribe(context, item);
        assertEquals(0, matomoReportSubscriptionService.findAll(context).size());

        context.setCurrentUser(admin);
        assertEquals(0, matomoReportSubscriptionService.findAll(context).size());
    }

    @Test
    public void testFindAll() throws Exception {
        context.setCurrentUser(admin);
        matomoReportSubscriptionService.subscribe(context, item);

        context.setCurrentUser(eperson);
        matomoReportSubscriptionService.subscribe(context, item);

        context.setCurrentUser(admin);
        List<MatomoReportSubscription> retrievedSubscriptions1 = matomoReportSubscriptionService.findAll(context);
        assertEquals(2, retrievedSubscriptions1.size());

        context.setCurrentUser(eperson);
        List<MatomoReportSubscription> retrievedSubscriptions2 = matomoReportSubscriptionService.findAll(context);
        assertEquals(1, retrievedSubscriptions2.size());
    }

    @Test
    public void testFindOne() throws Exception {
        context.setCurrentUser(admin);
        MatomoReportSubscription createdSubscription1 = matomoReportSubscriptionService.subscribe(context, item);
        MatomoReportSubscription retrievedSubscription1 =
                matomoReportSubscriptionService.find(context, createdSubscription1.getID());
        assertEquals(createdSubscription1, retrievedSubscription1);

        context.setCurrentUser(eperson);
        MatomoReportSubscription createdSubscription2 = matomoReportSubscriptionService.subscribe(context, item);
        MatomoReportSubscription retrievedSubscription2 =
                matomoReportSubscriptionService.find(context, createdSubscription2.getID());
        assertEquals(createdSubscription2, retrievedSubscription2);

        // common user cannot see other subscriptions
        assertThrows(AuthorizeException.class, () ->
                matomoReportSubscriptionService.find(context, createdSubscription1.getID())
        );

        // admin user can see all subscriptions
        context.setCurrentUser(admin);
        MatomoReportSubscription retrievedSubscription3 =
                matomoReportSubscriptionService.find(context, createdSubscription2.getID());

        assertEquals(createdSubscription2, retrievedSubscription3);
    }

    @Test
    public void testFindByItem() throws Exception {
        context.setCurrentUser(admin);
        MatomoReportSubscription createdSubscription = matomoReportSubscriptionService.subscribe(context, item);
        MatomoReportSubscription retrievedSubscription = matomoReportSubscriptionService.findByItem(context, item);
        assertEquals(createdSubscription, retrievedSubscription);

        // common user has no subscriptions
        context.setCurrentUser(eperson);
        assertNull(matomoReportSubscriptionService.findByItem(context, item));
    }
}
