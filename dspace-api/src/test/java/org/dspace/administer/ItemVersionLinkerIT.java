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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.sql.SQLException;
import java.time.Instant;
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
import org.dspace.handle.factory.HandleServiceFactory;
import org.dspace.handle.service.HandleService;
import org.dspace.versioning.Version;
import org.dspace.versioning.VersionHistory;
import org.dspace.versioning.factory.VersionServiceFactory;
import org.dspace.versioning.service.VersionHistoryService;
import org.dspace.versioning.service.VersioningService;
import org.junit.Before;
import org.junit.Test;

public class ItemVersionLinkerIT extends AbstractIntegrationTestWithDatabase {

    private TestDSpaceRunnableHandler testDSpaceRunnableHandler;
    private Collection collection;
    private Item item1;
    private Item item2;
    private Item item3;

    private ItemService itemService;
    private VersioningService versioningService;
    private VersionHistoryService versionHistoryService;
    private HandleService handleService;

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();
        context.setCurrentUser(admin);
        Community community = CommunityBuilder.createCommunity(context).build();
        collection = CollectionBuilder.createCollection(context, community)
                .withSubmitterGroup(eperson)
                .build();
        item1 = ItemBuilder.createItem(context, collection).withTitle("Item 1").build();
        item2 = ItemBuilder.createItem(context, collection).withTitle("Item 2").build();
        item3 = ItemBuilder.createItem(context, collection).withTitle("Item 3").build();
        itemService = ContentServiceFactory.getInstance().getItemService();
        versioningService = VersionServiceFactory.getInstance().getVersionService();
        versionHistoryService = VersionServiceFactory.getInstance().getVersionHistoryService();
        handleService = HandleServiceFactory.getInstance().getHandleService();
        testDSpaceRunnableHandler = createTestHandler();
    }

    @Test()
    public void testLink() throws Exception {
        // link item1 with item2 should pass
        runScript(getLinkOptions(item1, item2, admin));
        assertLinkMessages(item1, item2, 2);
        testDSpaceRunnableHandler.getInfoMessages().clear();

        // linking item1 with item3 should fail since item1 is not the last version anymore
        runScript(getLinkOptions(item1, item3, admin));
        assertEquals(1, testDSpaceRunnableHandler.getErrorMessages().size());
        assertEquals(String.format("Previous item '%s' is already part of existing versioning history, " +
                "and its version is not the latest version in that history.", item1.getID()), getErrorMessage());
        testDSpaceRunnableHandler.getErrorMessages().clear();

        // there is a limitation that an item that is going to be connected with previous item
        // cannot be part of any versioning history (we don't support one item being part of two versioning histories)
        // in this case, item2 is already in version history with item1
        runScript(getLinkOptions(item3, item2, admin));
        assertEquals(1, testDSpaceRunnableHandler.getErrorMessages().size());
        assertEquals(getLinkErrorMessagePartOfOtherVersionHistory(item2), getErrorMessage());
        testDSpaceRunnableHandler.getErrorMessages().clear();

        // linking item3 with item1 should fail (same as above)
        runScript(getLinkOptions(item3, item1, admin));
        assertEquals(1, testDSpaceRunnableHandler.getErrorMessages().size());
        assertEquals(getLinkErrorMessagePartOfOtherVersionHistory(item1), getErrorMessage());
        testDSpaceRunnableHandler.getErrorMessages().clear();

        // linking item2 with item1 should fail also (cyclic linking)
        runScript(getLinkOptions(item2, item1, admin));
        assertEquals(1, testDSpaceRunnableHandler.getErrorMessages().size());
        assertEquals(getLinkErrorMessagePartOfOtherVersionHistory(item1), getErrorMessage());
    }

    @Test()
    public void testLink3Items() throws Exception {
        // create version history with item1 and item2
        VersionHistory versionHistory = versionHistoryService.create(context);
        createNewVersion(versionHistory, item1, 1);
        createNewVersion(versionHistory, item2, 2);

        // link item2 with item3 should pass
        runScript(getLinkOptions(item2, item3, admin));
        assertLinkMessages(item2, item3, 3);

        Version v3 = versioningService.getVersion(context, item3);
        assertEquals(v3.getVersionHistory(), versionHistory);
    }

    @Test()
    public void testLinkErrorNotAdmin() throws Exception {
        runScript(getLinkOptions(item1, item2, eperson));
        assertEquals(1, testDSpaceRunnableHandler.getErrorMessages().size());
        assertEquals("Only admin user can run the script.", getErrorMessage());
    }

    @Test()
    public void testLinkErrorItemToItself() throws Exception {
        runScript(getLinkOptions(item1, item1, admin));
        assertEquals(1, testDSpaceRunnableHandler.getErrorMessages().size());
        assertEquals("Cannot create versioning relationship between the same item.", getErrorMessage());
    }

    @Test()
    public void testLinkItemNoHandle() throws Exception {
        Item item4 = ItemBuilder.createItem(context, collection).withTitle("Item 4").build();
        itemService.clearMetadata(context, item4, "dc", "identifier", "uri", Item.ANY);

        // linking item1 with item4 should fail since item4 has no handle
        runScript(getLinkOptions(item1, item4, admin));
        assertEquals(1, testDSpaceRunnableHandler.getErrorMessages().size());
        assertEquals(getNoHandleMessage(item4.getID()), getErrorMessage());
        testDSpaceRunnableHandler.getErrorMessages().clear();

        // linking item4 with item1 should also fail since item4 has no handle
        runScript(getLinkOptions(item4, item1, admin));
        assertEquals(1, testDSpaceRunnableHandler.getErrorMessages().size());
        assertEquals(getNoHandleMessage(item4.getID()), getErrorMessage());
    }

    @Test()
    public void testLinkErrorInvalidUuid() throws Exception {
        runScript(new String[]{"item-version-linker", "-l", "-p", item1.getHandle(),
                "-i", "invalid-uuid", "-e", admin.getEmail()});
        assertNotNull(testDSpaceRunnableHandler.getException());
        assertEquals("Unable to resolve 'invalid-uuid' identifier.", getExceptionMessage());
    }

    @Test()
    public void testLinkErrorItemNotFound() throws Exception {
        UUID randomUUID = UUID.randomUUID();
        runScript(new String[] { "item-version-linker", "-l", "-p", item1.getHandle(),
                "-i", randomUUID.toString(), "-e", admin.getEmail() });
        assertNotNull(testDSpaceRunnableHandler.getException());
        assertEquals(String.format("Item '%s' not found.", randomUUID), getExceptionMessage());
    }

    @Test()
    public void testUnlink() throws Exception {
        // create version history with item1, item2 and item3
        VersionHistory versionHistory = versionHistoryService.create(context);
        createNewVersion(versionHistory, item1, 1);
        createNewVersion(versionHistory, item2, 2);
        createNewVersion(versionHistory, item3, 3);

        // unlinking item3
        runScript(getUnlinkOptions(item3, admin));
        assertUnlinkMessages(item2, item3, item3.getID());
        testDSpaceRunnableHandler.getInfoMessages().clear();

        // unlinking  item3 again should fail as item3 is not linked anymore
        runScript(getUnlinkOptions(item3, admin));
        assertEquals(getUnlinkErrorMessageNotPartOfVersionHistory(item3), getErrorMessage());
        testDSpaceRunnableHandler.getErrorMessages().clear();

        // unlinking item1 should fail as item1 is not the latest version
        runScript(getUnlinkOptions(item1, admin));
        assertEquals(getUnlinkErrorMessageNotLastItem(), getErrorMessage());
        testDSpaceRunnableHandler.getErrorMessages().clear();
    }

    @Test()
    public void testUnlinkLastItems() throws Exception {
        // create version history with item1 and item2
        VersionHistory versionHistory = versionHistoryService.create(context);
        createNewVersion(versionHistory, item1, 1);
        createNewVersion(versionHistory, item2, 2);

        // unlinking item2 (will unlink both item1 and item2 since item1 was the first version)
        runScript(getUnlinkOptions(item2, admin));
        assertUnlinkMessagesLastItems(item1, item2, item1.getID(), item2.getID());
        testDSpaceRunnableHandler.getInfoMessages().clear();

        // unlinking item2 again should fail
        runScript(getUnlinkOptions(item2, admin));
        assertEquals(getUnlinkErrorMessageNotPartOfVersionHistory(item2), getErrorMessage());
        testDSpaceRunnableHandler.getErrorMessages().clear();

        // unlinking item1 should also fail since both items item1 and item2 were unlinked
        // because item1 was the first item in the versioning history
        runScript(getUnlinkOptions(item1, admin));
        assertEquals(getUnlinkErrorMessageNotPartOfVersionHistory(item1), getErrorMessage());

        // check if version history was removed
        assertNull(versionHistoryService.find(context, versionHistory.getID()));
    }

    @Test()
    public void testUnlinkLastItemsWithHandles() throws Exception {
        // create version history with item1 and item2
        VersionHistory versionHistory = versionHistoryService.create(context);
        createNewVersion(versionHistory, item1, 1);
        createNewVersion(versionHistory, item2, 2);

        // unlinking item2 (will unlink both item1 and item2 since item1 was the first version)
        runScript(new String[] { "item-version-linker", "-u", "-i", item2.getHandle(), "-e", admin.getEmail() });
        assertUnlinkMessagesLastItems(item1, item2, item1.getHandle(), item2.getHandle());

        // check if version history was removed
        assertNull(versionHistoryService.find(context, versionHistory.getID()));
    }

    @Test()
    public void testUnlinkItemNoHandle() throws Exception {
        VersionHistory versionHistory = versionHistoryService.create(context);
        createNewVersion(versionHistory, item1, 1);
        createNewVersion(versionHistory, item2, 2);

        itemService.clearMetadata(context, item2, "dc", "identifier", "uri", Item.ANY);

        // unlinking item2 should fail since item2 has no handle
        runScript(getUnlinkOptions(item2, admin));
        assertEquals(1, testDSpaceRunnableHandler.getErrorMessages().size());
        assertEquals(getNoHandleMessage(item2.getID()), getErrorMessage());
        testDSpaceRunnableHandler.getErrorMessages().clear();
    }

    @Test()
    public void testUnlinkSingleItemInHistory() throws Exception {
        // create version history with one item only
        VersionHistory versionHistory = versionHistoryService.create(context);
        createNewVersion(versionHistory, item1, 1);

        // unlinking item1 should remove also the version history since item1 is the only item in that history
        runScript(getUnlinkOptions(item1, admin));
        assertEquals(0, testDSpaceRunnableHandler.getErrorMessages().size());
        List<String> infoMessages = testDSpaceRunnableHandler.getInfoMessages();
        assertEquals(3, infoMessages.size());
        assertEquals(getUnlinkStartMessage(item1.getID()), infoMessages.get(0));
        assertEquals(getUnlinkSuccessMessage(item1.getID()), infoMessages.get(1));
        assertEquals(String.format("The item '%s' had no previous version in the versioning history, " +
                "so the full versioning history associated with the item was removed as well.", item1.getID()),
                infoMessages.get(2));
        testDSpaceRunnableHandler.getInfoMessages().clear();

        // check if version history was removed
        assertNull(versionHistoryService.find(context, versionHistory.getID()));

        // unlinking item1 again should fail
        runScript(getUnlinkOptions(item1, admin));
        assertEquals(getUnlinkErrorMessageNotPartOfVersionHistory(item1), getErrorMessage());
        testDSpaceRunnableHandler.getErrorMessages().clear();
    }

    private void assertLinkMessages(Item item1, Item item2, int version) throws SQLException {
        assertEquals(0, testDSpaceRunnableHandler.getErrorMessages().size());
        List<String> infoMessages = testDSpaceRunnableHandler.getInfoMessages();
        assertEquals(2, infoMessages.size());

        assertEquals(String.format("Creating versioning relationship between '%s' and '%s' items.",
                item1.getID(), item2.getID()), infoMessages.get(0));
        assertEquals(String.format("Item '%s' has become a new version (version %d) of item '%s'.",
                item2.getID(), version, item1.getID()), infoMessages.get(1));

        Version v1 = versioningService.getVersion(context, item1);
        Version v2 = versioningService.getVersion(context, item2);
        assertEquals(v1.getVersionHistory(), v2.getVersionHistory());
        assertTrue(v1.getVersionNumber() < v2.getVersionNumber());

        // check dc.relation metadata added
        List<MetadataValue> isReplacedBy = itemService.getMetadata(item1, "dc", "relation", "isreplacedby", null);
        assertEquals(1, isReplacedBy.size());
        assertTrue(isReplacedBy.get(0).getValue().endsWith(item2.getHandle()));

        List<MetadataValue> replaces = itemService.getMetadata(item2, "dc", "relation", "replaces", null);
        assertEquals(1, replaces.size());
        assertTrue(replaces.get(0).getValue().endsWith(item1.getHandle()));
    }

    private void assertUnlinkMessages(Item item1, Item item2, Object item2ID) {
        assertEquals(0, testDSpaceRunnableHandler.getErrorMessages().size());
        List<String> infoMessages = testDSpaceRunnableHandler.getInfoMessages();
        assertTrue(infoMessages.size() >= 2);

        assertEquals(getUnlinkStartMessage(item2ID), infoMessages.get(0));
        assertEquals(getUnlinkSuccessMessage(item2ID), infoMessages.get(1));

        // check dc.relation metadata removed
        List<MetadataValue> isReplacedBy = itemService.getMetadata(item1, "dc", "relation", "isreplacedby", null);
        assertEquals(0, isReplacedBy.size());

        List<MetadataValue> replaces = itemService.getMetadata(item2, "dc", "relation", "replaces", null);
        assertEquals(0, replaces.size());
    }

    private void assertUnlinkMessagesLastItems(Item item1, Item item2, Object item1ID, Object item2ID) {
        assertUnlinkMessages(item1, item2, item2ID);
        assertEquals(String.format("The previous item '%s' was the first version of the '%s' item, " +
                                "so the full versioning history associated with the items was removed as well.",
                        item1ID, item2ID),
                testDSpaceRunnableHandler.getInfoMessages().get(2));
    }

    private static String getUnlinkStartMessage(Object itemID) {
        return String.format("Going to unlink item '%s' from the versioning history.", itemID);
    }

    private static String getUnlinkSuccessMessage(Object itemID) {
        return String.format("Item '%s' unlinked successfully.", itemID);
    }

    private static String getNoHandleMessage(Object itemID) {
        return String.format("Item '%s' has no handle assigned.", itemID);
    }

    private static String getLinkErrorMessagePartOfOtherVersionHistory(Item item) {
        return String.format("The item '%s' is already part of other versioning history.", item.getID());
    }

    private static String getUnlinkErrorMessageNotPartOfVersionHistory(Item item) {
        return String.format("The item '%s', to be unlinked, is not part of any versioning history.", item.getID());
    }

    private static String getUnlinkErrorMessageNotLastItem() {
        return "Can unlink only the item whose version is the latest version in the versioning history.";
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

    private void createNewVersion(VersionHistory versionHistory, Item item, int versionNumber) throws SQLException {
        Version version = versioningService.createNewVersion(context, versionHistory, item,
                "version " + versionNumber, Instant.now(), versionNumber);
        if (!versionHistoryService.isFirstVersion(context, versionHistory, version)) {
            Version previous = versionHistoryService.getPrevious(context, versionHistory, version);
            Item previousItem = previous.getItem();

            String previousItemHandleRef = handleService.getCanonicalForm(previousItem.getHandle());
            String secondItemHandleRef = handleService.getCanonicalForm(item.getHandle());

            itemService.addMetadata(context, previousItem, "dc", "relation", "isreplacedby", null,
                    secondItemHandleRef);

            itemService.addMetadata(context, item, "dc", "relation", "replaces", null,
                    previousItemHandleRef);
        }
    }

}