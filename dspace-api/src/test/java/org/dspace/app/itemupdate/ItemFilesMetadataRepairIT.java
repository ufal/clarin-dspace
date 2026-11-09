/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.itemupdate;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.dspace.AbstractIntegrationTestWithDatabase;
import org.dspace.builder.BitstreamBuilder;
import org.dspace.builder.BundleBuilder;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.ItemBuilder;
import org.dspace.content.Bitstream;
import org.dspace.content.Collection;
import org.dspace.content.Item;
import org.dspace.content.MetadataValue;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.ItemService;
import org.dspace.core.Constants;
import org.dspace.core.Context;
import org.junit.Before;
import org.junit.Test;

/**
 * Integration tests for {@link ItemFilesMetadataRepair}.
 */
public class ItemFilesMetadataRepairIT extends AbstractIntegrationTestWithDatabase {

    private final ItemService itemService = ContentServiceFactory.getInstance().getItemService();

    private Collection collection;
    private Item item;
    private long firstFileSize;
    private long totalFilesSize;

    @Before
    public void setUpItemWithStaleFilesSize() throws Exception {
        context.turnOffAuthorisationSystem();
        parentCommunity = CommunityBuilder.createCommunity(context).build();
        collection = CollectionBuilder.createCollection(context, parentCommunity).build();
        item = ItemBuilder.createItem(context, collection).withTitle("Item with two files").build();
        Bitstream first = createBitstream("first file");
        Bitstream second = createBitstream("second, longer file");
        firstFileSize = first.getSizeBytes();
        totalFilesSize = firstFileSize + second.getSizeBytes();

        // Simulate the state left by the CLARIN import: the size of the last file is not counted.
        itemService.clearMetadata(context, item, "local", "files", "size", Item.ANY);
        itemService.addMetadata(context, item, "local", "files", "size", null, String.valueOf(firstFileSize));
        itemService.update(context, item);
        context.restoreAuthSystemState();
        context.commit();
    }

    @Test
    public void dryRunReportsStaleFilesSizeWithoutChangingIt() throws Exception {
        Date lastModified = reloadLastModified(item);

        String output = runRepair("-i", item.getID().toString(), "-d");

        assertThat(output, containsString("Files metadata are incorrect."));
        assertFilesMetadata(item, "2", String.valueOf(firstFileSize), "yes");
        assertEquals(lastModified, reloadLastModified(item));
    }

    @Test
    public void repairRecalculatesNonZeroStaleFilesSize() throws Exception {
        Date lastModified = reloadLastModified(item);

        String output = runRepair("-i", item.getID().toString());

        assertThat(output, containsString("Files metadata were updated."));
        assertFilesMetadata(item, "2", String.valueOf(totalFilesSize), "yes");
        // itemService.update ran: the change is announced to search/OAI with a new modification date
        assertTrue(reloadLastModified(item).after(lastModified));
    }

    @Test
    public void repairLeavesCorrectFilesMetadataUntouched() throws Exception {
        runRepair("-i", item.getID().toString());
        Date lastModified = reloadLastModified(item);

        String output = runRepair("-i", item.getID().toString());

        assertThat(output, containsString("Files metadata are correct."));
        assertEquals(lastModified, reloadLastModified(item));
    }

    @Test
    public void repairResetsFilesMetadataOfEmptyOriginalBundle() throws Exception {
        context.turnOffAuthorisationSystem();
        Item emptyItem = ItemBuilder.createItem(context, collection)
                .withTitle("Item whose files were removed")
                .withMetadata("local", "has", "files", "yes")
                .withMetadata("local", "files", "count", "2")
                .withMetadata("local", "files", "size", "29")
                .build();
        BundleBuilder.createBundle(context, emptyItem).withName(Constants.CONTENT_BUNDLE_NAME).build();
        context.restoreAuthSystemState();
        context.commit();

        runRepair("-i", emptyItem.getID().toString());

        assertFilesMetadata(emptyItem, "0", "0", "no");
    }

    @Test
    public void collectionRepairFixesItemsBeyondOneBatch() throws Exception {
        // One more than the batch size, so the run commits and clears the session mid-iteration.
        context.turnOffAuthorisationSystem();
        List<Item> emptyItems = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            emptyItems.add(createItemWithEmptyOriginalBundle());
        }
        context.restoreAuthSystemState();
        context.commit();

        String output = runRepair("-c", collection.getID().toString());

        assertThat(output, containsString("Checked 101 items"));
        assertThat(output, containsString("Updated 101 items."));
        assertFilesMetadata(item, "2", String.valueOf(totalFilesSize), "yes");
        for (Item emptyItem : emptyItems) {
            assertFilesMetadata(emptyItem, "0", "0", "no");
        }
    }

    private Bitstream createBitstream(String content) throws Exception {
        try (InputStream is = IOUtils.toInputStream(content, StandardCharsets.UTF_8)) {
            return BitstreamBuilder.createBitstream(context, item, is).build();
        }
    }

    private Item createItemWithEmptyOriginalBundle() throws Exception {
        Item emptyItem = ItemBuilder.createItem(context, collection).withTitle("Item without files").build();
        BundleBuilder.createBundle(context, emptyItem).withName(Constants.CONTENT_BUNDLE_NAME).build();
        return emptyItem;
    }

    private String runRepair(String... args) throws Exception {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
        try {
            ItemFilesMetadataRepair.main(ArrayUtils.addAll(new String[] {"-e", admin.getEmail()}, args));
        } finally {
            System.setOut(originalOut);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    private Date reloadLastModified(Item target) throws Exception {
        try (Context verifyContext = new Context()) {
            return itemService.find(verifyContext, target.getID()).getLastModified();
        }
    }

    private void assertFilesMetadata(Item target, String count, String size, String hasFiles) throws Exception {
        try (Context verifyContext = new Context()) {
            Item reloaded = itemService.find(verifyContext, target.getID());
            assertEquals(List.of(count), values(reloaded, "files", "count"));
            assertEquals(List.of(size), values(reloaded, "files", "size"));
            assertEquals(List.of(hasFiles), values(reloaded, "has", "files"));
        }
    }

    private List<String> values(Item reloaded, String element, String qualifier) {
        return itemService.getMetadata(reloaded, "local", element, qualifier, Item.ANY).stream()
                .map(MetadataValue::getValue)
                .collect(Collectors.toList());
    }
}
