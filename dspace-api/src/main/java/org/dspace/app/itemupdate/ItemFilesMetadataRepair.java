
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

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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

public class ItemFilesMetadataRepair {

    private static final Logger log = LogManager.getLogger(ItemFilesMetadataRepair.class);

    private ItemFilesMetadataRepair() {
    }

    public static void main(String[] args) throws Exception {
        log.info("Fixing item files metadata started.");

        Options options = new Options();
        options.addRequiredOption("e", "email", true, "admin email");
        options.addOption("c", "collection", true, "collection UUID");
        options.addOption("i", "item", true, "item UUID");
        options.addOption("h", "help", false, "help");
        options.addOption("v", "verbose", false, "Verbose output");

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
            run(adminEmail, collectionUuid, itemUuid, verboseOutput);
        } catch (ParseException e) {
            System.err.println("Cannot read command options");
            printHelpAndExit(options);
        }

        log.info("Fixing item files metadata finished.");
    }

    private static void run(String adminEmail, String collectionUuid, String itemUuid, boolean verboseOutput)
            throws Exception {

        System.out.println("ItemFilesMetadataRepair Started");

        try (Context context = new Context(Context.Mode.READ_WRITE)) {
            ItemService itemService = ContentServiceFactory.getInstance().getItemService();
            ClarinItemService clarinItemService = ClarinServiceFactory.getInstance().getClarinItemService();

            EPerson eperson = EPersonServiceFactory.getInstance().getEPersonService().findByEmail(context, adminEmail);
            context.turnOffAuthorisationSystem();
            context.setCurrentUser(eperson);
            context.restoreAuthSystemState();

            if (itemUuid != null) {
                // fixing only one item
                Item item = itemService.find(context, UUID.fromString(itemUuid));
                if (item == null) {
                    throw new IllegalArgumentException("InvalidItem UUID");
                }
                boolean updated = updateItem(item, context, clarinItemService, itemService, verboseOutput);
                System.out.printf("Updated %s\n", (updated ? "one item" : "0 items"));

            } else if (collectionUuid != null) {
                // fixing items in collection
                CollectionService collectionService = ContentServiceFactory.getInstance().getCollectionService();
                Collection collection = collectionService.find(context, UUID.fromString(collectionUuid));
                if (collection == null) {
                    throw new IllegalArgumentException("Invalid Collection UUID");
                }
                Iterator<Item> itemIterator = itemService.findAllByCollection(context, collection);
                Results results = updateItems(itemIterator, context,  clarinItemService, itemService, verboseOutput);
                System.out.printf("Checked %d items in Collection: \"%s\"\n",
                        results.getItemsCount(), collection.getName());
                System.out.printf("Updated %d items\n", results.getUpdatedItemsCount());
            } else {
                // fixing all items
                Iterator<Item> itemIterator = itemService.findAll(context);
                Results results = updateItems(itemIterator, context,  clarinItemService, itemService, verboseOutput);
                System.out.printf("Checked %d items\n", results.getItemsCount());
                System.out.printf("Updated %d items\n", results.getUpdatedItemsCount());
            }
            context.complete();
        }

        System.out.println("ItemFilesMetadataRepair Finished");
    }

    private static Results updateItems(Iterator<Item> itemIterator,
                                       Context context,
                                       ClarinItemService clarinItemService,
                                       ItemService itemService,
                                       boolean verboseOutput) throws Exception {
        int itemsCount = 0;
        int updatedItemsCount = 0;
        while (itemIterator.hasNext()) {
            itemsCount++;
            boolean updated = updateItem(itemIterator.next(), context, clarinItemService, itemService, verboseOutput);
            if (updated) {
                updatedItemsCount++;
            }
        }

        return new Results(itemsCount, updatedItemsCount);
    }

    private static boolean updateItem(Item item,
                                      Context context,
                                      ClarinItemService clarinItemService,
                                      ItemService itemService,
                                      boolean verboseOutput) throws Exception {
        boolean updated = false;

        List<MetadataValue>  filesCountValues =
                itemService.getMetadata(item, "local", "files", "count", Item.ANY);
        List<MetadataValue>  filesSizeValues =
                itemService.getMetadata(item, "local", "files", "size", Item.ANY);
        List<MetadataValue>  hasFilesValues =
                itemService.getMetadata(item, "local", "has", "files", Item.ANY);

        int filesCount = 0;
        String filesCountValue = "undefined";
        if (!filesCountValues.isEmpty()) {
            filesCountValue = filesCountValues.get(0).getValue();
            try {
                filesCount = Integer.parseInt(filesCountValue);
            } catch (NumberFormatException ex) {
                // filesCount = 0
            }
        }
        long filesSize = 0;
        String filesSizeValue = "undefined";
        if (!filesSizeValues.isEmpty()) {
            filesSizeValue = filesSizeValues.get(0).getValue();
            try {
                filesSize = Long.parseLong(filesSizeValue);
            } catch (NumberFormatException ex) {
                // filesSize = 0
            }
        }
        String hasFiles = hasFilesValues.isEmpty() ? "no" : hasFilesValues.get(0).getValue();

        List<Bundle> originalBundles = item.getBundles(Constants.CONTENT_BUNDLE_NAME);
        if (!CollectionUtils.isEmpty(originalBundles)) {
            Bundle bundle = originalBundles.get(0);
            boolean hasBitstreams = !CollectionUtils.isEmpty(bundle.getBitstreams());
            if (hasBitstreams && (filesCount == 0 || filesSize == 0 || !"yes".equals(hasFiles))) {
                if (verboseOutput) {
                    String message = "Item %s with file(s) has incorrect metadata: " +
                            "[files.count: %s, files.size: %s, has.files: %s]";
                    System.out.printf((message) + "%n", item.getHandle(), filesCountValue, filesSizeValue, hasFiles);
                }
                clarinItemService.updateItemFilesMetadata(context, item, bundle);
                updated = true;
            } else if (!hasBitstreams && (filesCount > 0 || filesSize > 0 || "yes".equals(hasFiles))) {
                if (verboseOutput) {
                    String message = "Item %s with missing files has incorrect metadata: " +
                            "[files.count: %s, files.size: %s, has.files: %s]";
                    System.out.printf((message) + "%n", item.getHandle(), filesCountValue, filesSizeValue, hasFiles);
                }
                itemService.clearMetadata(context, item, "local", "has", "files", Item.ANY);
                itemService.clearMetadata(context, item, "local", "files", "count", Item.ANY);
                itemService.clearMetadata(context, item, "local", "files", "size", Item.ANY);
                itemService.addMetadata(
                        context, item,"local", "has", "files", Item.ANY, "no");
                itemService.addMetadata(
                        context, item,"local", "files", "count", Item.ANY, "" + 0);
                itemService.addMetadata(
                        context, item,"local", "files", "size", Item.ANY, "" + 0L);
                updated = true;
            }
        }
        return updated;
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
