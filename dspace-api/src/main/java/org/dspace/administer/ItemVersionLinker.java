/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.administer;

import java.sql.SQLException;
import java.util.Date;
import java.util.UUID;

import org.apache.commons.cli.ParseException;
import org.dspace.authorize.AuthorizeException;
import org.dspace.content.DSpaceObject;
import org.dspace.content.Item;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.ItemService;
import org.dspace.core.Context;
import org.dspace.eperson.EPerson;
import org.dspace.eperson.factory.EPersonServiceFactory;
import org.dspace.eperson.service.EPersonService;
import org.dspace.handle.factory.HandleServiceFactory;
import org.dspace.handle.service.HandleService;
import org.dspace.identifier.IdentifierNotFoundException;
import org.dspace.identifier.IdentifierNotResolvableException;
import org.dspace.identifier.factory.IdentifierServiceFactory;
import org.dspace.identifier.service.IdentifierService;
import org.dspace.scripts.DSpaceRunnable;
import org.dspace.scripts.configuration.ScriptConfiguration;
import org.dspace.utils.DSpace;
import org.dspace.versioning.Version;
import org.dspace.versioning.VersionHistory;
import org.dspace.versioning.factory.VersionServiceFactory;
import org.dspace.versioning.service.VersionHistoryService;
import org.dspace.versioning.service.VersioningService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This script allows to link two items into the versioning relationship,
 * where the second item becomes the next version of the first item.
 *
 *  * @author Milan Kuchtiak
 *
 */
public class ItemVersionLinker extends DSpaceRunnable<ItemVersionLinkerConfiguration> {

    private static final Logger log = LoggerFactory.getLogger(ItemVersionLinker.class);
    private boolean help = false;
    boolean link = false;
    boolean unlink = false;
    String previousItemID;
    String itemID;
    private VersioningService versioningService;
    private VersionHistoryService versionHistoryService;
    private ItemService itemService;
    private EPersonService ePersonService;
    private IdentifierService identifierService;
    private HandleService handleService;

    /**
     * This method will return the Configuration that the implementing DSpaceRunnable uses
     *
     * @return The {@link ScriptConfiguration} that this implementing DspaceRunnable uses
     */
    @Override
    public ItemVersionLinkerConfiguration getScriptConfiguration() {
        return new DSpace().getServiceManager().getServiceByName("item-version-linker",
                ItemVersionLinkerConfiguration.class);
    }

    /**
     * This method has to be included in every script and handles the setup of the script by parsing the CommandLine
     * and setting the variables
     *
     * @throws ParseException If something goes wrong
     */
    @Override
    public void setup() throws ParseException {
        log.debug("Setting up {}", ItemVersionLinker.class.getName());

        link = commandLine.hasOption("l");
        unlink = commandLine.hasOption("u");

        if (commandLine.hasOption("h") || (link && unlink) || (!link && !unlink)) {
            help = true;
            return;
        }

        if (!commandLine.hasOption("i")) {
            help = true;
            return;
        }

        if (link && !commandLine.hasOption("p")) {
            help = true;
            return;
        }

        versioningService = VersionServiceFactory.getInstance().getVersionService();
        versionHistoryService = VersionServiceFactory.getInstance().getVersionHistoryService();
        itemService = ContentServiceFactory.getInstance().getItemService();
        ePersonService = EPersonServiceFactory.getInstance().getEPersonService();
        identifierService = IdentifierServiceFactory.getInstance().getIdentifierService();
        handleService = HandleServiceFactory.getInstance().getHandleService();
    }

    /**
     * This method has to be included in every script and this will be the main execution block for the script that'll
     * contain all the logic needed
     *
     * @throws Exception If something goes wrong
     */
    @Override
    public void internalRun() throws Exception {
        log.debug("Running {}", ItemVersionLinker.class.getName());
        if (help) {
            printHelp();
            return;
        }

        Context context = new Context();
        EPerson ePerson = getEperson(context);
        if (ePerson == null) {
            throw new RuntimeException("Only authenticated user can run the script");
        }
        context.setCurrentUser(ePerson);

        itemID = commandLine.getOptionValue("i");
        Item item = findItem(context, itemID);

        if (item == null) {
            throw new IllegalArgumentException(String.format("Item '%s' not found.", itemID));
        }

        if (link) {
            previousItemID = commandLine.getOptionValue("p");
            Item previousItem = findItem(context, previousItemID);
            if (previousItem == null) {
                throw new IllegalArgumentException(String.format("Previous item '%s' not found.", previousItemID));
            }
            linkItems(context, previousItem, item);
        } else {
            unlinkLastItem(context, item);
        }
    }

    /**
     *  Link item with the previous item into the versioning relationship.
     *
     * @param context
     * @param previousItem
     * @param item
     * @throws SQLException
     * @throws AuthorizeException
     */
    private void linkItems(Context context, Item previousItem, Item item) throws SQLException, AuthorizeException {

        if (previousItem.getID().equals(item.getID())) {
            handler.logError("Cannot create versioning relationship between the same item.");
            return;
        }

        if (itemService.isInProgressSubmission(context, previousItem) ||
                itemService.isInProgressSubmission(context, item)) {
            handler.logError("Both items must be archived to create versioning relationship.");
            return;
        }

        Version previousVersion = versioningService.getVersion(context, previousItem);

        if (previousVersion != null && !isLatestVersion(context, previousVersion)) {
            handler.logError(String.format("Previous item '%s' is already part of existing versioning history, " +
                            "and its version is not the latest version in that history.",
                    previousItemID));
            return;
        }

        Version secondVersion = versioningService.getVersion(context, item);
        if (secondVersion != null) {
            handler.logError(String.format("The item '%s' cannot be part of existing versioning history.", itemID));
            return;
        }

        handler.logInfo(String.format("Creating versioning relationship between '%s' and '%s' items.",
                previousItemID, itemID));

        String previousItemName = previousItem.getName();
        String previousItemHandleRef = handleService.getCanonicalForm(previousItem.getHandle());
        String secondItemHandleRef = handleService.getCanonicalForm(item.getHandle());

        int newVersionNumber;
        if (previousVersion != null) {
            // create new version of item in existingversioning history
            VersionHistory history = previousVersion.getVersionHistory();
            int firstVersionNumber = previousVersion.getVersionNumber();
            newVersionNumber = firstVersionNumber + 1;
            Version version = versioningService.createNewVersion(context, history, item,
                    "Linked as the next version of " + previousItemName, new Date(), newVersionNumber);
        } else {
            // create new versioning history for the items
            VersionHistory history = versionHistoryService.create(context);
            versioningService.createNewVersion(context, history, previousItem,
                    "The first version of " + previousItemName, new Date(), 1);
            versioningService.createNewVersion(context, history, item,
                    "Linked as the next version of " + previousItemName, new Date(), 2);
            newVersionNumber = 2;
        }

        itemService.addMetadata(context, previousItem, "dc", "relation", "isreplacedby", null,
                secondItemHandleRef);

        itemService.addMetadata(context, item, "dc", "relation", "replaces", null,
                previousItemHandleRef);

        handler.logInfo(String.format("Item '%s' has become a new version (version %d) of item '%s'.",
                itemID, newVersionNumber, previousItemID));
    }

    private void unlinkLastItem(Context context, Item item) throws SQLException, AuthorizeException {

        Version version = versioningService.getVersion(context, item);
        if (version == null) {
            handler.logError(String.format("The item '%s', to be unlinked, is not part of any versioning history.",
                    itemID));
            return;
        }

        if (!isLatestVersion(context, version)) {
            handler.logError("Can unlink only the item whose version is the latest version in the versioning history.");
            return;
        }

        handler.logInfo(String.format("Going to unlink item '%s' from the versioning history",
                itemID));

        // remove the dc.relation.replaces metadata (if ever exists)
        itemService.clearMetadata(context, item, "dc", "relation", "replaces", null);

        VersionHistory versionHistory = version.getVersionHistory();
        Version previousVersion = versionHistoryService.getPrevious(context, version.getVersionHistory(), version);

        // remove the version relationship
        versioningService.deleteVersion(context, version);
        handler.logInfo(String.format("Item '%s' unlinked successfully", itemID));

        if (previousVersion != null) {
            // remove the dc.relation.isreplacedby metadata (if ever exists)
            itemService.clearMetadata(context, previousVersion.getItem(), "dc", "relation", "isreplacedby", null);

            if (isFirstVersion(context, versionHistory, previousVersion)) {
                // if the previous version is the first version, we need to remove the version
                // and the full versioning history as well
                versioningService.deleteVersion(context, previousVersion);
                versionHistoryService.delete(context, versionHistory);
                handler.logInfo(String.format("The previous item '%s' was the first version of the '%s' item, " +
                        "so the full versioning history associated with the items was removed as well.",
                        previousVersion.getItem().getHandle(), itemID));
            }
        } else {
            versionHistoryService.delete(context, versionHistory);
            handler.logInfo(String.format("The item '%s' had no previous version in the versioning history, " +
                    "so the full versioning history associated with the item was removed as well.", itemID));
        }

    }

    private Item findItem(Context context, String itemId) throws SQLException {
        try {
            return itemService.find(context, UUID.fromString(itemId));
        } catch (IllegalArgumentException ex) {
            try {
                DSpaceObject dso = identifierService.resolve(context, itemId);
                if (dso instanceof Item) {
                    return (Item) dso;
                } else {
                    throw new IllegalArgumentException(String.format("Unable to resolve '%s' identifier.", itemId));
                }
            } catch (IdentifierNotFoundException | IdentifierNotResolvableException iex) {
                throw new IllegalArgumentException(iex);
            }
        }
    }

    private EPerson getEperson(Context context) throws SQLException {
        UUID ePersonIdentifier = getEpersonIdentifier();
        return ePersonIdentifier == null ? null : ePersonService.find(context, ePersonIdentifier);
    }

    private boolean isLatestVersion(Context context, Version version) throws SQLException {
        return versionHistoryService.isLastVersion(context, version.getVersionHistory(), version);
    }

    private boolean isFirstVersion(Context context, VersionHistory versionHistory, Version version)
            throws SQLException {
        return versionHistoryService.isFirstVersion (context, version.getVersionHistory(), version);
    }

}

