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
import org.dspace.content.DSpaceObject;
import org.dspace.content.Item;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.ItemService;
import org.dspace.core.Context;
import org.dspace.eperson.EPerson;
import org.dspace.eperson.factory.EPersonServiceFactory;
import org.dspace.eperson.service.EPersonService;
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
    String firstItemId;
    String secondItemId;
    private VersioningService versioningService;
    private VersionHistoryService versionHistoryService;
    private ItemService itemService;
    private EPersonService ePersonService;
    private IdentifierService identifierService;

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
        if (commandLine.hasOption("h") || (!commandLine.hasOption("f") || !commandLine.hasOption("s"))) {
            help = true;
            return;
        }

        firstItemId = commandLine.getOptionValue("f");
        secondItemId = commandLine.getOptionValue("s");

        versioningService = VersionServiceFactory.getInstance().getVersionService();
        versionHistoryService = VersionServiceFactory.getInstance().getVersionHistoryService();
        itemService = ContentServiceFactory.getInstance().getItemService();
        ePersonService = EPersonServiceFactory.getInstance().getEPersonService();
        identifierService = IdentifierServiceFactory.getInstance().getIdentifierService();
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
        if (help || firstItemId == null || secondItemId == null) {
            printHelp();
            return;
        }

        Context context = new Context();
        EPerson ePerson = getEperson(context);
        if (ePerson == null) {
            throw new RuntimeException("Only authenticated user can run the script");
        }
        context.setCurrentUser(ePerson);

        Item firstItem = findItem(context, firstItemId);
        Item secondItem = findItem(context, secondItemId);



        if (firstItem == null) {
            throw new IllegalArgumentException(String.format("First item '%s' not found.", firstItemId));
        }

        if (secondItem == null) {
            throw new IllegalArgumentException(String.format("Second item '%s' not found.", secondItemId));
        }

        if (itemService.isInProgressSubmission(context, firstItem) ||
                itemService.isInProgressSubmission(context, secondItem)) {
            throw new IllegalArgumentException("Both items must be archived to create versioning relationship");
        }

        Version firstVersion = versioningService.getVersion(context, firstItem);

        if (firstVersion != null && !isLatestVersion(context, firstVersion)) {
            handler.logError("First item is already part of another versioning history.");
            return;
        }

        Version secondVersion = versioningService.getVersion(context, secondItem);
        if (secondVersion != null) {
            handler.logError("Second item is already part of another versioning history.");
            return;
        }

        if (firstVersion != null) {
            // create new version of item in existing history
            VersionHistory history = firstVersion.getVersionHistory();
            versioningService.createNewVersion(context, history, secondItem, "Linked as next version", new Date(),
                    firstVersion.getVersionNumber() + 1);
        } else {
            // create new version history
            VersionHistory history = versionHistoryService.create(context);
            versioningService.createNewVersion(context, history, firstItem, "Initial version", new Date(), 1);
            versioningService.createNewVersion(context, history, secondItem, "Linked as next version", new Date(), 2);
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
        return versionHistoryService
                .getLatestVersion(context, version.getVersionHistory()).getID().equals(version.getID());
    }

}

