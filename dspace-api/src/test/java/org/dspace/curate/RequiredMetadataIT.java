/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.curate;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.sql.SQLException;

import org.dspace.AbstractIntegrationTestWithDatabase;
import org.dspace.authorize.AuthorizeException;
import org.dspace.builder.ItemBuilder;
import org.dspace.content.Collection;
import org.dspace.content.Community;
import org.dspace.content.Item;
import org.dspace.content.WorkspaceItem;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.CollectionService;
import org.dspace.content.service.CommunityService;
import org.dspace.content.service.ItemService;
import org.dspace.content.service.WorkspaceItemService;
import org.dspace.identifier.factory.IdentifierServiceFactory;
import org.dspace.identifier.service.IdentifierService;
import org.junit.Before;
import org.junit.Test;

/**
 * Test for requiredmetadata curation task.
 *
 * @author mkuchtiak
 */
public class RequiredMetadataIT extends AbstractIntegrationTestWithDatabase {
    private static final String TASK_NAME = "requiredmetadata";

    protected CommunityService communityService = ContentServiceFactory.getInstance().getCommunityService();
    protected CollectionService collectionService = ContentServiceFactory.getInstance().getCollectionService();
    protected ItemService itemService = ContentServiceFactory.getInstance().getItemService();
    protected WorkspaceItemService workspaceItemService = ContentServiceFactory.getInstance().getWorkspaceItemService();
    protected IdentifierService identifierService = IdentifierServiceFactory.getInstance().getIdentifierService();

    Community parentCommunity;
    Collection collection;
    Item item1;
    Item item2;
    WorkspaceItem workspaceItem;

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();
        try {
            //we have to create a new community in the database
            context.turnOffAuthorisationSystem();
            this.parentCommunity = communityService.create(null, context);
            this.collection = collectionService.create(context, parentCommunity, "123456789/113");
            this.workspaceItem = workspaceItemService.create(context, collection, true);
            identifierService.register(context, workspaceItem.getItem());
            item1 = ItemBuilder.createItem(context, collection)
                    .withHandle("123456789/113-1")
                    .withMetadata("dc", "title", null, "Test item")
                    .withMetadata("dc", "date", "issued", "2025-07-23")
                    .build();
            item2 = ItemBuilder.createItem(context, collection)
                    .withHandle("123456789/113-2")
                    .build();
            context.restoreAuthSystemState();
        } catch (AuthorizeException ex) {
            fail("Authorization Error in init: " + ex.getMessage());
        } catch (SQLException ex) {
            fail("SQL Error in init: " + ex.getMessage());
        }
    }

    @Test
    public void testPerform() throws IOException {
        Curator curator = new Curator();
        curator.addTask(TASK_NAME);
        CuratorReportTest.ListReporter reporter = new CuratorReportTest.ListReporter();
        curator.setReporter(reporter);

        context.setCurrentUser(admin);

        // run curateTask for workspaceItem - should fail
        curator.curate(context, workspaceItem.getItem().getHandle());
        assertEquals("Curation should fail", Curator.CURATE_FAIL, curator.getStatus(TASK_NAME));
        assertEquals(failResultForItem(workspaceItem.getItem()), curator.getResult(TASK_NAME));
        assertThat(reporter.getReport(), contains(failResultForItem(workspaceItem.getItem())));
        reporter.getReport().clear();

        // run curateTask for item1 - should pass
        curator.curate(context, item1);
        assertEquals("Curation should succeed", Curator.CURATE_SUCCESS, curator.getStatus(TASK_NAME));
        assertEquals(successResultForItem(item1), curator.getResult(TASK_NAME));
        assertThat(reporter.getReport(), contains(successResultForItem(item1)));
        reporter.getReport().clear();

        // run curateTask for item2 - should fail
        curator.curate(context, item2);
        assertEquals("Curation should fail", Curator.CURATE_FAIL, curator.getStatus(TASK_NAME));
        assertEquals(failResultForItem(item2), curator.getResult(TASK_NAME));
        assertThat(reporter.getReport(), contains(failResultForItem(item2)));
        reporter.getReport().clear();

        // run curateTask for collection
        curator.curate(context, collection);
        assertThat(reporter.getReport(), containsInAnyOrder(successResultForItem(item1), failResultForItem(item2)));
    }

    private static String failResultForItem(Item item) {
        return "Item: " + item.getHandle()
                + " missing required field: dc.title. missing required field: dc.date.issued";
    }

    private static String successResultForItem(Item item) {
        return "Item: " + item.getHandle() + " has all required fields";
    }

}
