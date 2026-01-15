/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.administer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.UUID;

import org.dspace.AbstractIntegrationTestWithDatabase;
import org.dspace.app.launcher.ScriptLauncher;
import org.dspace.app.scripts.handler.impl.TestDSpaceRunnableHandler;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.ItemBuilder;
import org.dspace.content.Collection;
import org.dspace.content.Community;
import org.dspace.content.Item;
import org.dspace.content.MetadataValue;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.ItemService;
import org.dspace.eperson.EPerson;
import org.junit.Before;
import org.junit.Test;

public class ItemVersionLinkerIT extends AbstractIntegrationTestWithDatabase {

    private TestDSpaceRunnableHandler testDSpaceRunnableHandler;
    private Item item1;
    private Item item2;
    private Item item3;

    private ItemService itemService;

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();
        context.setCurrentUser(admin);
        Community community = CommunityBuilder.createCommunity(context).build();
        Collection collection = CollectionBuilder.createCollection(context, community)
                .withSubmitterGroup(eperson)
                .build();
        item1 = ItemBuilder.createItem(context, collection).withTitle("Item 1").build();
        item2 = ItemBuilder.createItem(context, collection).withTitle("Item 2").build();
        item3 = ItemBuilder.createItem(context, collection).withTitle("Item 3").build();
        itemService = ContentServiceFactory.getInstance().getItemService();
    }

    @Test()
    public void testLink() throws Exception {
        testDSpaceRunnableHandler = createTestHandler();
        runScript(getLinkOptions(item1, item2, admin));
        assertLinkMessages(item1, item2, 2);

        // linking item1 with item3 should fail since item1 is not the last version anymore
        testDSpaceRunnableHandler = createTestHandler();
        runScript(getLinkOptions(item1, item3, admin));
        assertEquals(1, testDSpaceRunnableHandler.getErrorMessages().size());
        assertEquals(String.format("Previous item '%s' is already part of existing versioning history, " +
                "and its version is not the latest version in that history.", item1.getID()), getErrorMessage());

        // linking item3 with item2 should fail since item2 is already part of other versioning history
        testDSpaceRunnableHandler = createTestHandler();
        runScript(getLinkOptions(item3, item2, admin));
        assertEquals(1, testDSpaceRunnableHandler.getErrorMessages().size());
        assertEquals(getLinkErrorMessagePartOfOtherVersionHistory(item2), getErrorMessage());

        // linking item3 with item1 should fail (same as above)
        testDSpaceRunnableHandler = createTestHandler();
        runScript(getLinkOptions(item3, item1, admin));
        assertEquals(1, testDSpaceRunnableHandler.getErrorMessages().size());
        assertEquals(getLinkErrorMessagePartOfOtherVersionHistory(item1), getErrorMessage());

        // linking item2 with item1 should fail also (cyclic linking)
        testDSpaceRunnableHandler = createTestHandler();
        runScript(getLinkOptions(item2, item1, admin));
        assertEquals(1, testDSpaceRunnableHandler.getErrorMessages().size());
        assertEquals(getLinkErrorMessagePartOfOtherVersionHistory(item1), getErrorMessage());

        testDSpaceRunnableHandler = createTestHandler();
        runScript(getLinkOptions(item2, item3, admin));
        assertLinkMessages(item2, item3, 3);
    }

    @Test()
    public void testLinkErrors() throws Exception {
        // non-admin user trying to link items
        testDSpaceRunnableHandler = createTestHandler();
        runScript(getLinkOptions(item1, item2, eperson));
        assertEquals(1, testDSpaceRunnableHandler.getErrorMessages().size());
        assertEquals("Only admin user can run the script.", getErrorMessage());

        // trying to link an item to itself
        testDSpaceRunnableHandler = createTestHandler();
        runScript(getLinkOptions(item1, item1, admin));
        assertEquals(1, testDSpaceRunnableHandler.getErrorMessages().size());
        assertEquals("Cannot create versioning relationship between the same item.", getErrorMessage());

        // invalid previous item uuid
        testDSpaceRunnableHandler = createTestHandler();
        runScript(new String[] { "item-version-linker", "-l", "-p", "invalid-uuid",
                "-i", item2.getID().toString(), "-e", admin.getEmail() });
        assertNotNull(testDSpaceRunnableHandler.getException());
        assertEquals("Unable to resolve 'invalid-uuid' identifier.", getExceptionMessage());

        // invalid item uuid
        testDSpaceRunnableHandler = createTestHandler();
        runScript(new String[] { "item-version-linker", "-l", "-p", item1.getHandle(),
                "-i", "invalid-uuid", "-e", admin.getEmail() });
        assertNotNull(testDSpaceRunnableHandler.getException());
        assertEquals("Unable to resolve 'invalid-uuid' identifier.", getExceptionMessage());

        // item not found
        testDSpaceRunnableHandler = createTestHandler();
        UUID randomUUID = UUID.randomUUID();
        runScript(new String[] { "item-version-linker", "-l", "-p", item1.getHandle(),
                "-i", randomUUID.toString(), "-e", admin.getEmail() });
        assertNotNull(testDSpaceRunnableHandler.getException());
        assertEquals(String.format("Item '%s' not found.", randomUUID), getExceptionMessage());
    }

    @Test()
    public void testUnlink() throws Exception {
        testDSpaceRunnableHandler = createTestHandler();
        // linking item1 -> item2 -> item3
        runScript(getLinkOptions(item1, item2, admin));
        runScript(getLinkOptions(item2, item3, admin));

        testDSpaceRunnableHandler = createTestHandler();
        runScript(getUnlinkOptions(item2, admin));
        assertEquals("Can unlink only the item whose version is the latest version in the versioning history.",
                getErrorMessage());

        // unlinking item3
        testDSpaceRunnableHandler = createTestHandler();
        runScript(getUnlinkOptions(item3, admin));
        assertUnlinkMessages(item2, item3);

        // unlinking  item3 again should fail as item3 is not linked anymore
        testDSpaceRunnableHandler = createTestHandler();
        runScript(getUnlinkOptions(item3, admin));
        assertEquals(getUnlinkErrorMessageNotPartOfVersionHistory(item3), getErrorMessage());

        // unlinking item1 should fail as item1 is not the latest version
        testDSpaceRunnableHandler = createTestHandler();
        runScript(getUnlinkOptions(item1, admin));
        assertEquals(("Can unlink only the item whose version is the latest version in the versioning history."),
                getErrorMessage());

        // unlinking item2 (will unlink both item1 and item2 since item1 was the first version)
        testDSpaceRunnableHandler = createTestHandler();
        runScript(getUnlinkOptions(item2, admin));
        assertUnlinkMessagesLastItems(item1, item2);

        // unlinking item2 again should fail
        testDSpaceRunnableHandler = createTestHandler();
        runScript(getUnlinkOptions(item2, admin));
        assertEquals(getUnlinkErrorMessageNotPartOfVersionHistory(item2), getErrorMessage());

        // unlinking item1 should also fail since both items item1 and item2 were unlinked
        // because item1 was the first item in the versioning history
        testDSpaceRunnableHandler = createTestHandler();
        runScript(getUnlinkOptions(item1, admin));
        assertEquals(getUnlinkErrorMessageNotPartOfVersionHistory(item1), getErrorMessage());
    }

    private void assertLinkMessages(Item item1, Item item2, int version) {
        assertEquals(0, testDSpaceRunnableHandler.getErrorMessages().size());
        List<String> infoMessages = testDSpaceRunnableHandler.getInfoMessages();
        assertEquals(2, infoMessages.size());

        assertEquals(String.format("Creating versioning relationship between '%s' and '%s' items.",
                item1.getID(), item2.getID()), infoMessages.get(0));
        assertEquals(String.format("Item '%s' has become a new version (version %d) of item '%s'.",
                item2.getID(), version, item1.getID()), infoMessages.get(1));

        // check dc.relation metadata added
        List<MetadataValue> isReplacedBy = itemService.getMetadata(item1, "dc", "relation", "isreplacedby", null);
        assertEquals(1, isReplacedBy.size());
        assertTrue(isReplacedBy.get(0).getValue().endsWith(item2.getHandle()));

        List<MetadataValue> replaces = itemService.getMetadata(item2, "dc", "relation", "replaces", null);
        assertEquals(1, replaces.size());
        assertTrue(replaces.get(0).getValue().endsWith(item1.getHandle()));
    }

    private void assertUnlinkMessages(Item item1, Item item2) {
        assertEquals(0, testDSpaceRunnableHandler.getErrorMessages().size());
        List<String> infoMessages = testDSpaceRunnableHandler.getInfoMessages();
        assertTrue(infoMessages.size() >= 2);

        assertEquals(getUnlinkStartMessage(item2), infoMessages.get(0));
        assertEquals(getUnlinkSuccessMessage(item2), infoMessages.get(1));

        // check dc.relation metadata removed
        List<MetadataValue> isReplacedBy = itemService.getMetadata(item1, "dc", "relation", "isreplacedby", null);
        assertEquals(0, isReplacedBy.size());

        List<MetadataValue> replaces = itemService.getMetadata(item2, "dc", "relation", "replaces", null);
        assertEquals(0, replaces.size());
    }

    private void assertUnlinkMessagesLastItems(Item item1, Item item2) {
        assertUnlinkMessages(item1, item2);
        assertEquals(String.format("The previous item '%s' was the first version of the '%s' item, " +
                                "so the full versioning history associated with the items was removed as well.",
                        item1.getID(), item2.getID()),
                testDSpaceRunnableHandler.getInfoMessages().get(2));
    }

    private static String getUnlinkStartMessage(Item item) {
        return String.format("Going to unlink item '%s' from the versioning history.", item.getID());
    }

    private static String getUnlinkSuccessMessage(Item item) {
        return String.format("Item '%s' unlinked successfully.", item.getID());
    }

    private static String getLinkErrorMessagePartOfOtherVersionHistory(Item item) {
        return String.format("The item '%s' is already part of other versioning history.", item.getID());
    }

    private static String getUnlinkErrorMessageNotPartOfVersionHistory(Item item) {
        return String.format("The item '%s', to be unlinked, is not part of any versioning history.", item.getID());
    }

    private static String[] getLinkOptions(Item item1, Item item2, EPerson eperson) {
        return new String[] { "item-version-linker",
                "-l", "-p", item1.getID().toString(), "-i", item2.getID().toString(), "-e", eperson.getEmail() };
    }

    private static String[] getUnlinkOptions(Item item, EPerson eperson) {
        return new String[] { "item-version-linker", "-u", "-i", item.getID().toString(), "-e", eperson.getEmail() };
    }

    private void runScript(String[] args) throws Exception {
        ScriptLauncher.handleScript(args, ScriptLauncher.getConfig(kernelImpl), testDSpaceRunnableHandler, kernelImpl);
    }

    private TestDSpaceRunnableHandler createTestHandler() {
        return new TestDSpaceRunnableHandler();
    }

    private String getErrorMessage() {
        return testDSpaceRunnableHandler.getErrorMessages().get(0);
    }

    private String getExceptionMessage() {
        return testDSpaceRunnableHandler.getException().getMessage();
    }

}