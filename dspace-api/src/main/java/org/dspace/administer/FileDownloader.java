/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.administer;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import org.apache.commons.cli.ParseException;
import org.dspace.authorize.AuthorizeException;
import org.dspace.content.Bitstream;
import org.dspace.content.BitstreamFormat;
import org.dspace.content.Bundle;
import org.dspace.content.Item;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.BitstreamFormatService;
import org.dspace.content.service.BitstreamService;
import org.dspace.content.service.ItemService;
import org.dspace.core.Context;
import org.dspace.eperson.EPerson;
import org.dspace.eperson.factory.EPersonServiceFactory;
import org.dspace.eperson.service.EPersonService;
import org.dspace.scripts.DSpaceRunnable;
import org.dspace.scripts.configuration.ScriptConfiguration;
import org.dspace.utils.DSpace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class FileDownloader extends DSpaceRunnable<FileDownloaderConfiguration> {

    private static final Logger log = LoggerFactory.getLogger(FileDownloader.class);
    private boolean help = false;
    private UUID itemUUID;
    private URI uri;
    private String epersonMail;
    private EPersonService epersonService;
    private ItemService itemService;
    private BitstreamService bitstreamService;
    private BitstreamFormatService bitstreamFormatService;
    private final HttpClient httpClient = HttpClient.newBuilder()
                                              .followRedirects(HttpClient.Redirect.NORMAL)
                                              .build();

    /**
     * This method will return the Configuration that the implementing DSpaceRunnable uses
     *
     * @return The {@link ScriptConfiguration} that this implementing DspaceRunnable uses
     */
    @Override
    public FileDownloaderConfiguration getScriptConfiguration() {
        return new DSpace().getServiceManager().getServiceByName("file-downloader",
                FileDownloaderConfiguration.class);
    }

    /**
     * This method has to be included in every script and handles the setup of the script by parsing the CommandLine
     * and setting the variables
     *
     * @throws ParseException If something goes wrong
     */
    @Override
    public void setup() throws ParseException {
        log.debug("Setting up {}", FileDownloader.class.getName());
        if (commandLine.hasOption("h")) {
            help = true;
            return;
        }

        if (!commandLine.hasOption("u")) {
            throw new ParseException("No URL option has been provided");
        }

        if (!commandLine.hasOption("i")) {
            throw new ParseException("No item option has been provided");
        }

        if (getEpersonIdentifier() == null && !commandLine.hasOption("e")) {
            throw new ParseException("No eperson option has been provided");
        }


        this.epersonService = EPersonServiceFactory.getInstance().getEPersonService();
        this.itemService = ContentServiceFactory.getInstance().getItemService();
        this.bitstreamService = ContentServiceFactory.getInstance().getBitstreamService();
        this.bitstreamFormatService = ContentServiceFactory.getInstance().getBitstreamFormatService();

        try {
            uri = new URI(commandLine.getOptionValue("u"));
        } catch (URISyntaxException e) {
            throw new ParseException("The provided URL is not a valid URL");
        }
        itemUUID = UUID.fromString(commandLine.getOptionValue("i"));

        epersonMail = commandLine.getOptionValue("e");
    }

    /**
     * This method has to be included in every script and this will be the main execution block for the script that'll
     * contain all the logic needed
     *
     * @throws Exception If something goes wrong
     */
    @Override
    public void internalRun() throws Exception {
        log.debug("Running {}", FileDownloader.class.getName());
        if (help) {
            printHelp();
            return;
        }

        Context context = new Context();
        context.setCurrentUser(getEperson(context));

        // Download the file from the given url
        // and save it to the item with the given UUID

        //find the item by the given uuid
        Item item = itemService.find(context, itemUUID);
        if (item == null) {
            throw new IllegalArgumentException("No item found for the given UUID");
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .build();

        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() >= 400) {
            throw new IllegalArgumentException("The provided URL returned a status code of " + response.statusCode());
        }

        try (InputStream is = response.body()) {
            saveFileToItem(context, item, is);
        }

        context.commit();
    }

    private void saveFileToItem(Context context, Item item, InputStream is)
            throws SQLException, AuthorizeException, IOException {
        log.debug("Saving file to item {}", item.getID());
        List<Bundle> originals = item.getBundles("ORIGINAL");
        Bitstream b;
        if (originals.isEmpty()) {
            b = itemService.createSingleBitstream(context, is, item);
        } else {
            Bundle bundle = originals.get(0);
            b = bitstreamService.create(context, bundle, is);
        }
        //now guess format of the bitstream
        BitstreamFormat bf = bitstreamFormatService.guessFormat(context, b);
        b.setFormat(context, bf);
    }

    private EPerson getEperson(Context context) throws SQLException {
        if (getEpersonIdentifier() != null) {
            return epersonService.find(context, getEpersonIdentifier());
        } else {
            return epersonService.findByEmail(context, epersonMail);
        }
    }
}

