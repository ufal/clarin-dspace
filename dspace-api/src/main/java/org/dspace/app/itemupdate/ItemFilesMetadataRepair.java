/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.itemupdate;

import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.dspace.content.Bitstream;
import org.dspace.content.Bundle;
import org.dspace.content.Collection;
import org.dspace.content.Item;
import org.dspace.content.MetadataValue;
import org.dspace.content.factory.ClarinServiceFactory;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.CollectionService;
import org.dspace.content.service.ItemService;
import org.dspace.content.service.clarin.ClarinItemService;
import org.dspace.core.Constants;
import org.dspace.core.Context;
import org.dspace.eperson.EPerson;
import org.dspace.eperson.factory.EPersonServiceFactory;

/**
 * Documentation for this class: https://github.com/ufal/clarin-dspace/pull/1243#issue-3236707035
 */
public class ItemFilesMetadataRepair {

    private static final Logger log = LogManager.getLogger(ItemFilesMetadataRepair.class);

    private static final int BATCH_SIZE = 100;
    private static final String LOCAL_SCHEMA = "local";
    private static final String FILES_ELEMENT = "files";
    private static final String HAS_ELEMENT = "has";
    private static final String COUNT_QUALIFIER = "count";
    private static final String SIZE_QUALIFIER = "size";

    private ItemFilesMetadataRepair() {
    }

    public static void main(String[] args) throws Exception {
        log.info("Fixing item files metadata started.");

        Options options = new Options();
        options.addRequiredOption("e", "email", true, "admin email");
        options.addOption("c", "collection", true, "collection UUID");
        options.addOption("i", "item", true, "item UUID");
        options.addOption("d", "dry-run", false, "dry run - with no repair");
        options.addOption("h", "help", false, "help");
        options.addOption("v", "verbose", false, "verbose output");

        CommandLineParser parser = new DefaultParser();
        try {
            CommandLine line = parser.parse(options, args);
            if (line.hasOption('h') || !line.hasOption('e')) {
                printHelpAndExit(options);
            }
            String adminEmail = line.getOptionValue('e');
            String collectionUuid = line.getOptionValue('c');
            String itemUuid = line.getOptionValue('i');
            boolean verboseOutput = line.hasOption('v');
            boolean dryRun = line.hasOption('d');
            run(adminEmail, collectionUuid, itemUuid, dryRun, verboseOutput);
        } catch (ParseException e) {
            System.err.println("Failed to parse command line options: " + e.getMessage());
            printHelpAndExit(options);
        }

        log.info("Fixing item files metadata finished.");
    }

    private static void run(String adminEmail,
                            String collectionUuid,
                            String itemUuid,
                            boolean dryRun,
                            boolean verboseOutput) throws Exception {

        System.out.println("ItemFilesMetadataRepair Started.\n");

        try (Context context = new Context(Context.Mode.READ_WRITE)) {
            ItemService itemService = ContentServiceFactory.getInstance().getItemService();
            ClarinItemService clarinItemService = ClarinServiceFactory.getInstance().getClarinItemService();

            EPerson eperson = EPersonServiceFactory.getInstance().getEPersonService().findByEmail(context, adminEmail);
            context.turnOffAuthorisationSystem();
            context.setCurrentUser(eperson);
            context.restoreAuthSystemState();

            String messagePrefix = dryRun ? "Found incorrect files metadata in" : "Updated";
            if (itemUuid != null) {
                // fixing only one item
                Item item = itemService.find(context, UUID.fromString(itemUuid));
                if (item == null) {
                    throw new IllegalArgumentException("Item not found with the provided UUID");
                }
                boolean updated = updateItem(item, context, clarinItemService, itemService, dryRun, verboseOutput);
                if (updated) {
                    System.out.println(dryRun ? "Files metadata are incorrect." : "Files metadata were updated.");
                } else {
                    System.out.println("Files metadata are correct.");
                }
            } else if (collectionUuid != null) {
                // fixing items in collection
                CollectionService collectionService = ContentServiceFactory.getInstance().getCollectionService();
                Collection collection = collectionService.find(context, UUID.fromString(collectionUuid));
                if (collection == null) {
                    throw new IllegalArgumentException("Invalid Collection UUID");
                }
                // Read the name now: the collection is detached by the batch uncaching in updateItems.
                String collectionName = collection.getName();
                Iterator<Item> itemIterator = itemService.findAllByCollection(context, collection);
                Results results =
                        updateItems(itemIterator, context,  clarinItemService, itemService, dryRun, verboseOutput);
                System.out.printf("Checked %d items in Collection: \"%s\".\n",
                        results.getItemsCount(), collectionName);
                System.out.printf("%s %d items.\n", messagePrefix, results.getUpdatedItemsCount());
            } else {
                // fixing all items
                Iterator<Item> itemIterator = itemService.findAll(context);
                Results results =
                        updateItems(itemIterator, context,  clarinItemService, itemService, dryRun, verboseOutput);
                System.out.printf("Checked %d items.\n", results.getItemsCount());
                System.out.printf("%s %d items.\n", messagePrefix, results.getUpdatedItemsCount());
            }
            context.complete();
        }

        System.out.println("\nItemFilesMetadataRepair Finished");
    }

    private static Results updateItems(Iterator<Item> itemIterator,
                                       Context context,
                                       ClarinItemService clarinItemService,
                                       ItemService itemService,
                                       boolean dryRun,
                                       boolean verboseOutput) throws Exception {
        int itemsCount = 0;
        int updatedItemsCount = 0;
        while (itemIterator.hasNext()) {
            itemsCount++;
            boolean updated =
                    updateItem(itemIterator.next(), context, clarinItemService, itemService, dryRun, verboseOutput);
            if (updated) {
                updatedItemsCount++;
            }
            if (itemsCount % BATCH_SIZE == 0) {
                // Keep the transaction and the Hibernate session bounded on a full-repository run.
                if (!dryRun) {
                    context.commit();
                }
                context.uncacheEntities();
            }
        }

        return new Results(itemsCount, updatedItemsCount);
    }

    private static boolean updateItem(Item item,
                                      Context context,
                                      ClarinItemService clarinItemService,
                                      ItemService itemService,
                                      boolean dryRun,
                                      boolean verboseOutput) throws Exception {
        List<Bundle> originalBundles = item.getBundles(Constants.CONTENT_BUNDLE_NAME);
        if (CollectionUtils.isEmpty(originalBundles)) {
            return false;
        }

        // Compare against the values `updateItemFilesMetadata` would write, not only against zero:
        // an item can carry a non-zero but stale size (e.g. the last imported file was not counted).
        int expectedCount = 0;
        long expectedSize = 0;
        for (Bundle bundle : originalBundles) {
            for (Bitstream bitstream : bundle.getBitstreams()) {
                expectedCount++;
                expectedSize += bitstream.getSizeBytes();
            }
        }
        String expectedHasFiles = expectedCount > 0 ? "yes" : "no";

        List<String> filesCountValues = getValues(itemService, item, FILES_ELEMENT, COUNT_QUALIFIER);
        List<String> filesSizeValues = getValues(itemService, item, FILES_ELEMENT, SIZE_QUALIFIER);
        List<String> hasFilesValues = getValues(itemService, item, HAS_ELEMENT, FILES_ELEMENT);
        boolean correct = isSingleValue(filesCountValues, String.valueOf(expectedCount))
                && isSingleValue(filesSizeValues, String.valueOf(expectedSize))
                && isSingleValue(hasFilesValues, expectedHasFiles);
        if (correct) {
            return false;
        }

        if (verboseOutput) {
            System.out.printf("Incorrect metadata in item '%s': " +
                            "[files.count: %s, files.size: %s, has.files: %s], " +
                            "expected [files.count: %d, files.size: %d, has.files: %s].%n",
                    item.getHandle(), describe(filesCountValues), describe(filesSizeValues), describe(hasFilesValues),
                    expectedCount, expectedSize, expectedHasFiles);
        }
        if (!dryRun) {
            clarinItemService.updateItemFilesMetadata(context, item, originalBundles.get(0));
            // Fire the metadata-modified event so search and OAI indexes pick up the new values.
            itemService.update(context, item);
        }
        return true;
    }

    private static List<String> getValues(ItemService itemService, Item item, String element, String qualifier) {
        return itemService.getMetadata(item, LOCAL_SCHEMA, element, qualifier, Item.ANY).stream()
                .map(MetadataValue::getValue)
                .collect(Collectors.toList());
    }

    private static boolean isSingleValue(List<String> values, String expected) {
        return values.size() == 1 && expected.equals(values.get(0));
    }

    private static String describe(List<String> values) {
        return values.isEmpty() ? "undefined" : String.join(" | ", values);
    }

    private static void printHelpAndExit(Options options) {
        // print the help message
        HelpFormatter myHelp = new HelpFormatter();
        myHelp.printHelp("dsrun org.dspace.app.itemupdate.ItemFilesMetadataRepair \n", options);
        System.exit(0);
    }

    private static class Results {
        private final int itemsCount;
        private final int updatedItemsCount;

        public Results(int itemsCount, int updatedItemsCount) {
            this.itemsCount = itemsCount;
            this.updatedItemsCount = updatedItemsCount;
        }

        public int getItemsCount() {
            return itemsCount;
        }

        public int getUpdatedItemsCount() {
            return updatedItemsCount;
        }
    }

}
