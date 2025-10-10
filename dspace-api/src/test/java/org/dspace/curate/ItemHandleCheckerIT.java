/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.curate;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.both;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Random;

import org.dspace.AbstractIntegrationTestWithDatabase;
import org.dspace.authorize.AuthorizeException;
import org.dspace.builder.ItemBuilder;
import org.dspace.content.Collection;
import org.dspace.content.Community;
import org.dspace.content.Item;
import org.dspace.content.MetadataValue;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.CollectionService;
import org.dspace.content.service.CommunityService;
import org.dspace.content.service.ItemService;
import org.dspace.identifier.factory.IdentifierServiceFactory;
import org.dspace.identifier.service.IdentifierService;
import org.dspace.services.ConfigurationService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Test for checkhandles curation task.
 *
 * @author mkuchtiak
 */
public class ItemHandleCheckerIT extends AbstractIntegrationTestWithDatabase {
    private static final String TASK_NAME = "checkhandles";

    private static final String HANDLE_COLLECTION = "123456789/" + randomString();

    private static final String HANDLE_ITEM1 = HANDLE_COLLECTION + "-1";
    private static final String HANDLE_ITEM2 = HANDLE_COLLECTION + "-2";
    private static final String HANDLE_ITEM3 = HANDLE_COLLECTION + "-3";
    private static final String HANDLE_ITEM4 = HANDLE_COLLECTION + "-4";
    private static final String HANDLE_NON_EXISTING = HANDLE_COLLECTION + "-999";
    private static final String HANDLE_URL_REAL = "http://hdl.handle.net/11234/6-CTS";
    private static final String HANDLE_INVALID = HANDLE_URL_REAL + "/..??^^/";
    private static final String HANDLE_IGNORED_1 = "11234/998";
    private static final String HANDLE_IGNORED_2 = "11234/999";
    private static final String HANDLE_URL_IGNORED = "http://hdl.handle.net/" + HANDLE_IGNORED_2;

    protected CommunityService communityService = ContentServiceFactory.getInstance().getCommunityService();
    protected CollectionService collectionService = ContentServiceFactory.getInstance().getCollectionService();
    protected ItemService itemService = ContentServiceFactory.getInstance().getItemService();
    protected IdentifierService identifierService = IdentifierServiceFactory.getInstance().getIdentifierService();
    protected ConfigurationService cfg = kernelImpl.getConfigurationService();

    Community parentCommunity;
    Collection collection;
    Item item1;
    Item item2;
    Item item3;
    Item item4;

    private Curator curator;
    private CuratorReportTest.ListReporter reporter;

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();
        try {
            //we have to create a new community in the database
            context.turnOffAuthorisationSystem();
            cfg.setProperty("handle.canonical.prefix", "http://hdl.handle.net/");
            cfg.setProperty("curate.checklist.ignore", HANDLE_IGNORED_1 + "," + HANDLE_IGNORED_2);

            this.parentCommunity = communityService.create(null, context);
            this.collection = collectionService.create(context, parentCommunity, HANDLE_COLLECTION);
            item1 = ItemBuilder.createItem(context, collection)
                    .withHandle(HANDLE_ITEM1)
                    .build();

            item2 = ItemBuilder.createItem(context, collection)
                    .withHandle(HANDLE_ITEM2)
                    .build();

            item3 = ItemBuilder.createItem(context, collection)
                    .withHandle(HANDLE_ITEM3)
                    .build();

            item4 = ItemBuilder.createItem(context, collection)
                    .withHandle(HANDLE_ITEM4)
                    .build();

            context.restoreAuthSystemState();

            curator = new Curator();
            curator.addTask(TASK_NAME);
            reporter = new CuratorReportTest.ListReporter();
            curator.setReporter(reporter);
            context.setCurrentUser(admin);
        } catch (AuthorizeException ex) {
            fail("Authorization Error in init: " + ex.getMessage());
        } catch (SQLException ex) {
            fail("SQL Error in init: " + ex.getMessage());
        }
    }

    @Test
    public void testItemHandleNotFound() throws IOException {
        curator.curate(context, HANDLE_ITEM1);
        assertEquals("Curation should be failed", Curator.CURATE_FAIL, curator.getStatus(TASK_NAME));
        assertEquals(failResultForItem(item1), curator.getResult(TASK_NAME));
        assertTrue(reporter.getReport().contains(failResultForItem(item1)));
        reporter.getReport().clear();
    }

    @Test
    public void testItemHandleRedirected() throws IOException {
        replaceHandleUrl(item2, HANDLE_URL_REAL);
        curator.curate(context, HANDLE_ITEM2);
        assertEquals("Curation should succeed", Curator.CURATE_SUCCESS, curator.getStatus(TASK_NAME));
        assertTrue(curator.getResult(TASK_NAME).contains(redirectedResultForItem(item2)));
        String report = reporter.getReport().get(0);
        assertTrue(report.contains(redirectedResultForItem(item2)));
        assertTrue(report.endsWith("200 - OK\n"));
        reporter.getReport().clear();
    }

    @Test
    public void testNonExistingHandle() throws IOException {
        curator.curate(context, HANDLE_NON_EXISTING);
        assertEquals("Curation should fail", Curator.CURATE_FAIL, curator.getStatus(TASK_NAME));
        assertTrue(reporter.getReport().isEmpty());
    }

    @Test
    public void testInvalidHandleUrl() throws IOException {
        replaceHandleUrl(item3, HANDLE_INVALID);
        curator.curate(context, HANDLE_ITEM3);
        assertEquals("Curation should fail", Curator.CURATE_FAIL, curator.getStatus(TASK_NAME));
        String singleReport = reporter.getReport().get(0);
        assertTrue(singleReport.contains(HANDLE_INVALID + " = 500 - FAILED\n"));
        assertTrue(singleReport.contains("Error: java.net.URISyntaxException: Illegal character"));
        reporter.getReport().clear();
    }

    @Test
    public void testHandleUrlIgnored() throws IOException {
        replaceHandleUrl(item4, HANDLE_URL_IGNORED);
        curator.curate(context, HANDLE_ITEM4);
        assertEquals("Curation should skip", Curator.CURATE_SKIP, curator.getStatus(TASK_NAME));
        assertEquals("Item: " + HANDLE_ITEM4 + "\n", reporter.getReport().get(0));
        reporter.getReport().clear();
    }

    @Test
    public void testCurateCollection() throws IOException {
        replaceHandleUrl(item2, HANDLE_URL_REAL);
        replaceHandleUrl(item3, HANDLE_INVALID);
        replaceHandleUrl(item4, HANDLE_URL_IGNORED);
        curator.curate(context, HANDLE_COLLECTION);
        assertEquals(Curator.CURATE_SUCCESS, curator.getStatus(TASK_NAME));
        assertEquals(5, reporter.getReport().size());
        assertThat(reporter.getReport(), containsInAnyOrder(
                is(""), // this one is for collection itself
                is(failResultForItem(item1)), // item1
                both(
                     containsString("Item: " + item2.getHandle())).and(containsString(" = 200 - OK\n")
                ), // item2
                containsString(HANDLE_INVALID + " = 500 - FAILED"), // item 3
                is("Item: " + HANDLE_ITEM4 + "\n") // item 4 (ignored)
        ));
    }

    @After
    public void destroy() throws Exception {
        // remove all registered handles properly
        identifierService.delete(context, item1, HANDLE_ITEM1);
        identifierService.delete(context, item2, HANDLE_ITEM2);
        identifierService.delete(context, item3, HANDLE_ITEM3);
        identifierService.delete(context, item4, HANDLE_ITEM4);
        identifierService.delete(context, collection, HANDLE_COLLECTION);
        collectionService.delete(context, collection);
        super.destroy();
    }

    private String successResultForItem(Item item) {
        return "Item: " + item.getHandle() + "\n - " + getIdentifierUri(item) + " = 200 - OK\n";
    }

    private String failResultForItem(Item item) {
        return "Item: " + item.getHandle() + "\n - " + getIdentifierUri(item) + " = 404 - FAILED\n";
    }

    private String redirectedResultForItem(Item item) {
        return "Item: " + item.getHandle() + "\n - " + getIdentifierUri(item) + " = 302 - REDIRECTED\n";
    }

    private void replaceHandleUrl(Item item, String handleUrl) {
        List<MetadataValue> oldValues = getIdentifierUris(item);
        MetadataValue uriValue = oldValues.get(0);
        uriValue.setValue(handleUrl);
    }

    private String getIdentifierUri(Item item) {
        List<MetadataValue> values = getIdentifierUris(item);
        assertEquals(1, values.size());
        return values.get(0).getValue();
    }

    private List<MetadataValue> getIdentifierUris(Item item) {
        return itemService.getMetadata(item, "dc", "identifier", "uri", Item.ANY);
    }

    private static String randomString() {
        Random r = new Random();
        // Generate random integers in range 1000 to 1999
        return String.valueOf(1000 + r.nextInt(1000));
    }
}
