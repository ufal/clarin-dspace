/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.curate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;

import org.dspace.AbstractIntegrationTestWithDatabase;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.ItemBuilder;
import org.dspace.content.Collection;
import org.dspace.content.Community;
import org.dspace.content.Item;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.CollectionService;
import org.dspace.content.service.CommunityService;
import org.dspace.content.service.ItemService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Test for ItemMetadataQAChecker curation task.
 *
 * @author LINDAT/CLARIN
 */
public class ItemMetadataQACheckerIT extends AbstractIntegrationTestWithDatabase {
    private static final String TASK_NAME = "metadataqa";

    protected CommunityService communityService = ContentServiceFactory.getInstance().getCommunityService();
    protected CollectionService collectionService = ContentServiceFactory.getInstance().getCollectionService();
    protected ItemService itemService = ContentServiceFactory.getInstance().getItemService();

    Community parentCommunity;
    Collection collection;
    Item validItem;
    Item itemWithoutDcType;
    Item itemWithInvalidDcType;
    Item itemWithInvalidLanguage;
    Item itemWithIncorrectLanguageName;
    Item itemWithTwoAvailableDates;
    Item itemWithTwoAvailableDatesAndLang;

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();
        try {
            context.turnOffAuthorisationSystem();

            // Create a parent community
            this.parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Test Community")
                .build();

            // Create a collection
            this.collection = CollectionBuilder.createCollection(context, parentCommunity)
                .withName("Test Collection")
                .build();

            // Create a valid item with all required metadata
            validItem = ItemBuilder.createItem(context, collection)
                .withTitle("Valid Test Item")
                .withMetadata("dc", "type", null, "corpus")
                .withMetadata("dc", "language", "iso", "eng")
                .withMetadata("local", "language", "name", "English")
                .withMetadata("dc", "subject", null, "test subject")
                .withMetadata("local", "branding", null, "Test Community")
                .build();

            // Create an item without dc.type
            itemWithoutDcType = ItemBuilder.createItem(context, collection)
                .withTitle("Item Without Type")
                .build();

            // Create an item with invalid dc.type
            itemWithInvalidDcType = ItemBuilder.createItem(context, collection)
                .withTitle("Item With Invalid Type")
                .withMetadata("dc", "type", null, "invalidType")
                .build();

            // Create an item with invalid language code
            itemWithInvalidLanguage = ItemBuilder.createItem(context, collection)
                .withTitle("Item With Invalid Language")
                .withMetadata("dc", "type", null, "corpus")
                .withMetadata("dc", "language", "iso", "xyz")
                .build();

            // Create an item with incorrect local.language.name - deliberately set wrong name
            // Note: We need to create it without triggering automatic language name addition
            itemWithIncorrectLanguageName = ItemBuilder.createItem(context, collection)
                .withTitle("Item With Incorrect Language Name")
                .withMetadata("dc", "type", null, "corpus")
                .withMetadata("dc", "subject", null, "test subject")
                .withMetadata("local", "branding", null, "Test Community")
                .build();
            // Manually add dc.language.iso and wrong local.language.name after creation
            itemService.addMetadata(context, itemWithIncorrectLanguageName, "dc", "language", "iso", null, "eng");
            itemService.addMetadata(context, itemWithIncorrectLanguageName, "local", "language", "name", null,
                "WrongLanguageName");
            itemService.update(context, itemWithIncorrectLanguageName);

            itemWithTwoAvailableDates = ItemBuilder.createItem(context, collection)
                .withTitle("Item With Two Available Dates")
                .withMetadata("dc", "type", null, "corpus")
                .withMetadata("dc", "date", "available", "2020-01-01")
                .withMetadata("dc", "date", "available", "2021-01-01")
                .build();

            itemWithTwoAvailableDatesAndLang = ItemBuilder.createItem(context, collection)
                    .withTitle("Item With Two Available Dates")
                    .withMetadata("dc", "type", null, "corpus")
                    .withMetadata("dc", "date", "available", "2020-01-01")
                    .build();

            itemService.addMetadata(context, itemWithTwoAvailableDatesAndLang,"dc", "date",
                    "available", "en_US", "2021-01-01");

            context.restoreAuthSystemState();
        } catch (Exception ex) {
            fail("Error in init: " + ex.getMessage());
        }
    }

    @Test
    public void testItemWithTwoAvailableDates() throws IOException {
        Curator curator = new Curator();
        curator.addTask(TASK_NAME);
        context.setCurrentUser(admin);

        // Run curator task for item with two dc.date.available - should fail
        curator.curate(context, itemWithTwoAvailableDates.getHandle());
        int status = curator.getStatus(TASK_NAME);
        assertEquals("Curation should fail for item with two dc.date.available", Curator.CURATE_FAIL, status);
        String result = curator.getResult(TASK_NAME);
        assertTrue("Result should mention multiple dc.date.available", result.contains("dc.date.available"));
    }

    @Test
    public void testItemWithTwoAvailableDatesAndLang() throws IOException {
        Curator curator = new Curator();
        curator.addTask(TASK_NAME);
        context.setCurrentUser(admin);

        // Run curator task for item with two dc.date.available with language - should fail
        curator.curate(context, itemWithTwoAvailableDatesAndLang.getHandle());
        int status = curator.getStatus(TASK_NAME);
        assertEquals("Curation should fail for item with two dc.date.available with language",
                Curator.CURATE_FAIL, status);
        String result = curator.getResult(TASK_NAME);
        assertTrue("Result should mention multiple dc.date.available", result.contains("dc.date.available"));
    }

    @Test
    public void testValidItem() throws IOException {
        Curator curator = new Curator();
        curator.addTask(TASK_NAME);
        context.setCurrentUser(admin);

        // Run curator task for valid item - should succeed
        curator.curate(context, validItem.getHandle());
        int status = curator.getStatus(TASK_NAME);
        assertEquals("Curation should succeed for valid item", Curator.CURATE_SUCCESS, status);
    }

    @Test
    public void testItemWithoutDcType() throws IOException {
        Curator curator = new Curator();
        curator.addTask(TASK_NAME);
        context.setCurrentUser(admin);

        // Run curator task for item without dc.type - should fail
        curator.curate(context, itemWithoutDcType.getHandle());
        int status = curator.getStatus(TASK_NAME);
        assertEquals("Curation should fail for item without dc.type", Curator.CURATE_FAIL, status);
        String result = curator.getResult(TASK_NAME);
        assertTrue("Result should mention dc.type metadata", result.contains("dc.type"));
    }

    @Test
    public void testItemWithInvalidDcType() throws IOException {
        Curator curator = new Curator();
        curator.addTask(TASK_NAME);
        context.setCurrentUser(admin);

        // Run curator task for item with invalid dc.type - should fail
        curator.curate(context, itemWithInvalidDcType.getHandle());
        int status = curator.getStatus(TASK_NAME);
        assertEquals("Curation should fail for item with invalid dc.type", Curator.CURATE_FAIL, status);
        String result = curator.getResult(TASK_NAME);
        assertTrue("Result should mention invalid type", result.contains("invalid type"));
    }

    @Test
    public void testItemWithInvalidLanguageCode() throws IOException {
        Curator curator = new Curator();
        curator.addTask(TASK_NAME);
        context.setCurrentUser(admin);

        // Run curator task for item with invalid language code - should fail
        curator.curate(context, itemWithInvalidLanguage.getHandle());
        int status = curator.getStatus(TASK_NAME);
        assertEquals("Curation should fail for item with invalid language code", Curator.CURATE_FAIL, status);
        String result = curator.getResult(TASK_NAME);
        assertTrue("Result should mention invalid language code", result.contains("Invalid language code"));
    }

    @Test
    public void testItemWithIncorrectLanguageName() throws IOException {
        Curator curator = new Curator();
        curator.addTask(TASK_NAME);
        context.setCurrentUser(admin);

        // Run curator task for item with incorrect local.language.name - should fail
        curator.curate(context, itemWithIncorrectLanguageName.getHandle());
        int status = curator.getStatus(TASK_NAME);
        String result = curator.getResult(TASK_NAME);
        System.out.println("Test result: " + result);
        assertEquals("Curation should fail for item with incorrect local.language.name", Curator.CURATE_FAIL, status);
        assertTrue("Result should mention local.language.name mismatch, but was: " + result,
            result.contains("local.language.name") && result.contains("does not match"));
    }

    @After
    public void destroy() throws Exception {
        super.destroy();
    }
}
