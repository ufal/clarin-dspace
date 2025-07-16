
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
            if (line.hasOption('h') || !line.hasOption('e') || (!line.hasOption('c') && !line.hasOption('i'))) {
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
                Item item = itemService.find(context, UUID.fromString(itemUuid));
                if (item == null) {
                    throw new IllegalArgumentException("InvalidItem UUID");
                }
                boolean updated = updateItem(context, clarinItemService, itemService, item, verboseOutput);

                System.out.println("Updated " + (updated ? "one item" : "zero items"));

            } else if (collectionUuid != null) {
                CollectionService collectionService = ContentServiceFactory.getInstance().getCollectionService();
                Collection collection = collectionService.find(context, UUID.fromString(collectionUuid));
                if (collection == null) {
                    throw new IllegalArgumentException("Invalid Collection UUID");
                }
                Iterator<Item> itemIterator = itemService.findAllByCollection(context, collection);
                int itemsCount = 0;
                int updatedItems = 0;
                while (itemIterator.hasNext()) {
                    itemsCount++;
                    boolean updated =
                            updateItem(context, clarinItemService, itemService, itemIterator.next(), verboseOutput);
                    if (updated) {
                        updatedItems++;
                    }
                }
                System.out.println("Checked " + itemsCount + " items in Collection:\" " + collection.getName() + "\"");
                System.out.println("Updated " + updatedItems + " items");
            }
            context.complete();
        }

        System.out.println("ItemFilesMetadataRepair Finished");
    }

    private static boolean updateItem(Context context,
                                      ClarinItemService clarinItemService,
                                      ItemService itemService,
                                      Item item,
                                      boolean verboseOutput) throws Exception {
        boolean updated = false;
        List<Bundle> originalBundles = item.getBundles("ORIGINAL");
        if (!originalBundles.isEmpty()) {
            Bundle bundle = originalBundles.get(0);
            if (!bundle.getBitstreams().isEmpty()) {
                List<MetadataValue>  filesCountValues =
                        itemService.getMetadata(item, "local", "files", "count", Item.ANY);
                List<MetadataValue>  filesSizeValues =
                        itemService.getMetadata(item, "local", "files", "size", Item.ANY);
                List<MetadataValue>  hasFilesValues =
                        itemService.getMetadata(item, "local", "has", "files", Item.ANY);

                int filesCount = 0;
                String filesCountValue = "0";
                if (!filesCountValues.isEmpty()) {
                    filesCountValue = filesCountValues.get(0).getValue();
                    try {
                        filesCount = Integer.parseInt(filesCountValue);
                    } catch (NumberFormatException ex) {
                        // filesCount = 0
                    }
                }
                long filesSize = 0;
                String filesSizeValue = "0";
                if (!filesSizeValues.isEmpty()) {
                    filesSizeValue = filesSizeValues.get(0).getValue();
                    try {
                        filesSize = Long.parseLong(filesSizeValue);
                    } catch (NumberFormatException ex) {
                        // filesSize = 0
                    }
                }
                String hasFiles = hasFilesValues.isEmpty() ? "no" : hasFilesValues.get(0).getValue();

                if (filesCount == 0 || filesSize == 0 || !"yes".equals(hasFiles)) {
                    if (verboseOutput) {
                        System.out.println("Found incorrect metadata for item " + item.getHandle() + " [" +
                                "files.count:" + filesCountValue +
                                ", files.size:" + filesSizeValue +
                                ", has.files:" + hasFiles +
                                "]");
                    }
                    clarinItemService.updateItemFilesMetadata(context, item, bundle);
                    updated = true;
                }
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
}
