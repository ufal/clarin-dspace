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
import java.sql.SQLException;
import java.time.Instant;

import org.dspace.AbstractIntegrationTestWithDatabase;
import org.dspace.authorize.AuthorizeException;
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
import org.dspace.core.factory.CoreServiceFactory;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.dspace.versioning.VersionHistory;
import org.dspace.versioning.factory.VersionServiceFactory;
import org.dspace.versioning.service.VersionHistoryService;
import org.dspace.versioning.service.VersioningService;
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
    protected VersionHistoryService versionHistoryService =
            VersionServiceFactory.getInstance().getVersionHistoryService();
    protected VersioningService versioningService = VersionServiceFactory.getInstance().getVersionService();
    protected ConfigurationService configurationService = DSpaceServicesFactory.getInstance().getConfigurationService();

    Community parentCommunity;
    Collection collection;
    Item validItem;
    Item itemWithoutDcType;
    Item itemWithInvalidDcType;
    Item itemWithInvalidLanguage;
    Item itemWithIncorrectLanguageName;
    Item itemWithTwoAvailableDates;
    Item itemWithTwoAvailableDatesAndLang;
    Item itemWithTwoDois;
    Item itemVersion1;
    Item itemVersion2;
    Item itemVersion3;
    Item itemVersion4;
    Item itemVersion5;
    private String handlePrefix;

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();
        CoreServiceFactory.getInstance().getPluginService().clearNamedPluginClasses();
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

            itemWithTwoDois = ItemBuilder.createItem(context, collection)
                    .withTitle("Item With Two DOIs")
                    .withMetadata("dc", "type", null, "corpus")
                    .withMetadata("dc", "identifier", "doi", "https://doi.org/10.5072/test-1")
                    .withMetadata("dc", "identifier", "doi", "https://doi.org/10.5072/test-2")
                    .build();

            itemVersion1 = ItemBuilder.createItem(context, collection)
                    .withTitle("Item Version 1")
                    .withMetadata("dc", "type", null, "corpus")
                    .withMetadata("dc", "subject", null, "test subject")
                    .withMetadata("local", "branding", null, "Test Community")
                    .build();

            itemVersion2 = ItemBuilder.createItem(context, collection)
                    .withTitle("Item Version 2")
                    .withMetadata("dc", "type", null, "corpus")
                    .withMetadata("dc", "subject", null, "test subject")
                    .withMetadata("local", "branding", null, "Test Community")
                    .build();

            itemVersion3 = ItemBuilder.createItem(context, collection)
                    .withTitle("Item Version 3")
                    .withMetadata("dc", "type", null, "corpus")
                    .withMetadata("dc", "subject", null, "test subject")
                    .withMetadata("local", "branding", null, "Test Community")
                    .build();

            itemVersion4 = ItemBuilder.createItem(context, collection)
                    .withTitle("Item Version 4")
                    .withMetadata("dc", "type", null, "corpus")
                    .withMetadata("dc", "subject", null, "test subject")
                    .withMetadata("local", "branding", null, "Test Community")
                    .build();

            itemVersion5 = ItemBuilder.createItem(context, collection)
                    .withTitle("Item Version 5")
                    .withMetadata("dc", "type", null, "corpus")
                    .withMetadata("dc", "subject", null, "test subject")
                    .withMetadata("local", "branding", null, "Test Community")
                    .build();

            String ref1 = itemService.getMetadataFirstValue(itemVersion1, "dc", "identifier", "uri", Item.ANY);
            String ref2 = itemService.getMetadataFirstValue(itemVersion2, "dc", "identifier", "uri", Item.ANY);

            itemService.addMetadata(context, itemVersion1, "dc", "relation", "isreplacedby", null, ref2);
            itemService.addMetadata(context, itemVersion2, "dc", "relation", "replaces", null, ref1);
            itemService.addMetadata(context, itemVersion3, "dc", "relation", "replaces", null, ref2);
            itemService.update(context, itemVersion1);
            itemService.update(context, itemVersion2);
            itemService.update(context, itemVersion3);

            VersionHistory versionHistory = versionHistoryService.create(context);
            versioningService.createNewVersion(context, versionHistory, itemVersion1, "Version 1", Instant.now(), 1);
            versioningService.createNewVersion(context, versionHistory, itemVersion2, "Version 2", Instant.now(), 2);
            versioningService.createNewVersion(context, versionHistory, itemVersion3, "Version 3", Instant.now(), 3);

            context.restoreAuthSystemState();
            handlePrefix = configurationService.getProperty("handle.canonical.prefix");

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
    public void testItemWithTwoDois() throws IOException {
        Curator curator = new Curator();
        curator.addTask(TASK_NAME);
        context.setCurrentUser(admin);

        // Run curator task for item with two dc.identifier.doi - should fail
        curator.curate(context, itemWithTwoDois.getHandle());
        int status = curator.getStatus(TASK_NAME);
        assertEquals("Curation should fail for item with two dc.identifier.doi", Curator.CURATE_FAIL, status);
        String result = curator.getResult(TASK_NAME);
        assertTrue("Result should mention multiple dc.identifier.doi", result.contains("dc.identifier.doi"));
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
        assertEquals("Curation should fail for item with incorrect local.language.name", Curator.CURATE_FAIL, status);
        assertTrue("Result should mention local.language.name mismatch, but was: " + result,
            result.contains("local.language.name") && result.contains("does not match"));
    }

    @Test
    public void testItemVersion1() throws IOException {
        testItemWithCorrectRelationship(itemVersion1, "meets relation requirements");
    }

    @Test
    public void testItemVersion2() throws IOException {
        testItemWithCorrectRelationship(itemVersion2, "meets relation requirements");
    }

    @Test
    public void testItemWithBadRelationship1() throws IOException, SQLException, AuthorizeException {
        // itemVersion2 has 'dc.relation.isreplacedby that points to itemVersion4
        // but itemVersion4 doesn't contain 'dc.relation.replaces' metadata
        String ref4 = itemService.getMetadataFirstValue(itemVersion4, "dc", "identifier", "uri", Item.ANY);

        context.turnOffAuthorisationSystem();
        itemService.addMetadata(context, itemVersion2, "dc", "relation", "isreplacedby", null, ref4);
        itemService.update(context, itemVersion2);
        context.restoreAuthSystemState();

        testItemWithRelationError(
                itemVersion2,
                "the referenced item [[%s]] does not refer back via %s",
                ref4,
                "dc.relation.replaces");
    }

    @Test
    public void testItemWithBadRelationship2() throws IOException {
        String ref2 = itemService.getMetadataFirstValue(itemVersion2, "dc", "identifier", "uri", Item.ANY);
        // itemVersion3 has 'dc.relation.replaces' that points back to itemVersion2
        // but itemVersion2 doesn't have 'dc.relation.isreplacedby' that points forward to itemVersion3
        testItemWithRelationError(
                itemVersion3,
                "the referenced item [[%s]] does not refer back via %s",
                ref2,
                "dc.relation.isreplacedby");
    }
    @Test
    public void testItemWithBadRelationship3() throws IOException, SQLException, AuthorizeException {

        context.turnOffAuthorisationSystem();
        String ref = "https://example.org/this-doesnt-resolve";
        itemService.addMetadata(context, itemVersion5, "dc", "relation", "replaces", null, ref);
        itemService.update(context, itemVersion5);
        context.restoreAuthSystemState();

        testItemWithRelationError(
                itemVersion5,
                "contains '%s' but the referenced object [[%s]] is not an item or doesn't exist",
                "dc.relation.replaces",
                ref);
    }

    @Test
    public void testItemWithMissingVersionHistory() throws SQLException, IOException, AuthorizeException {
        String ref2 = itemService.getMetadataFirstValue(itemVersion2, "dc", "identifier", "uri", Item.ANY);
        String ref4 = itemService.getMetadataFirstValue(itemVersion4, "dc", "identifier", "uri", Item.ANY);

        context.turnOffAuthorisationSystem();
        itemService.addMetadata(context, itemVersion2, "dc", "relation", "isreplacedby", null, ref4);
        itemService.addMetadata(context, itemVersion4, "dc", "relation", "replaces", null, ref2);
        itemService.update(context, itemVersion2);
        itemService.update(context, itemVersion4);
        context.restoreAuthSystemState();

        testItemWithRelationError(itemVersion4,
                "contains '%s' but it's not part of any version history", "dc.relation.replaces");
    }

    @Test
    public void testItemWithMissingVersionHistoryForReferencedItem()
            throws SQLException, IOException, AuthorizeException {
        String ref2 = itemService.getMetadataFirstValue(itemVersion2, "dc", "identifier", "uri", Item.ANY);
        String ref4 = itemService.getMetadataFirstValue(itemVersion4, "dc", "identifier", "uri", Item.ANY);

        context.turnOffAuthorisationSystem();
        itemService.addMetadata(context, itemVersion2, "dc", "relation", "isreplacedby", null, ref4);
        itemService.addMetadata(context, itemVersion4, "dc", "relation", "replaces", null, ref2);
        itemService.update(context, itemVersion2);
        itemService.update(context, itemVersion4);
        context.restoreAuthSystemState();

        testItemWithRelationError(itemVersion2,
                "contains '%s' but the referenced item [[%s]] is not part of any version history",
                "dc.relation.isreplacedby", ref4);
    }

    @Test
    public void testItemWithNotMatchingVersionHistory() throws SQLException, IOException, AuthorizeException {
        String ref2 = itemService.getMetadataFirstValue(itemVersion2, "dc", "identifier", "uri", Item.ANY);
        String ref4 = itemService.getMetadataFirstValue(itemVersion4, "dc", "identifier", "uri", Item.ANY);

        context.turnOffAuthorisationSystem();
        itemService.addMetadata(context, itemVersion2,"dc", "relation", "isreplacedby", null, ref4);
        itemService.addMetadata(context, itemVersion4,"dc", "relation", "replaces", null, ref2);
        itemService.update(context, itemVersion2);
        itemService.update(context, itemVersion4);
        context.restoreAuthSystemState();

        VersionHistory versionHistory = versionHistoryService.create(context);
        versioningService.createNewVersion(context, versionHistory, itemVersion4,
                "Another Version History - Version 1", Instant.now(), 1);

        testItemWithRelationError(itemVersion4,
                "contains '%s' but the referenced item [[%s]] is not in the same version history",
                "dc.relation.replaces", ref2);
    }

    @Test
    public void testItemWithNoRelationMetadata() throws SQLException, IOException {
        testItemWithCorrectRelationship(itemVersion4, null);
    }

    private void testItemWithCorrectRelationship(Item item, String successMessage) throws IOException {
        Curator curator = runCuratorForItem(item);

        int status = curator.getStatus(TASK_NAME);
        String result = curator.getResult(TASK_NAME);
        assertEquals("Curation should succeed for valid item with relation", Curator.CURATE_SUCCESS, status);
        if (successMessage == null) {
            assertTrue("Result must be empty, but was " + result, result.isEmpty());
        } else {
            assertTrue("Result must contain success message, but was " + result,
                    result.contains(successMessage) && result.contains(item.getHandle()));
        }
    }

    private void testItemWithRelationError(Item item, String errorMessage, Object... args) throws IOException {
        Curator curator = runCuratorForItem(item);

        int status = curator.getStatus(TASK_NAME);
        String result = curator.getResult(TASK_NAME);
        assertEquals("Curation should fail for incorrect relationship", Curator.CURATE_FAIL, status);
        String failMessage = String.format(errorMessage, args);
        assertTrue(String.format("Result: %s\n must contain fail message \n %s ", result, failMessage),
                result.contains(failMessage)
        );
    }

    private Curator runCuratorForItem(Item item) throws IOException {
        Curator curator = new Curator();
        curator.addTask(TASK_NAME);
        context.setCurrentUser(admin);
        curator.curate(context, item.getHandle());
        return curator;
    }

    @After
    public void destroy() throws Exception {
        super.destroy();
    }
}
