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
import java.util.stream.Stream;

import org.apache.commons.cli.ParseException;
import org.dspace.authorize.AuthorizeException;
import org.dspace.content.Bitstream;
import org.dspace.content.BitstreamFormat;
import org.dspace.content.Bundle;
import org.dspace.content.DSpaceObject;
import org.dspace.content.Item;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.BitstreamFormatService;
import org.dspace.content.service.BitstreamService;
import org.dspace.content.service.ItemService;
import org.dspace.content.service.WorkspaceItemService;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class FileDownloader extends DSpaceRunnable<FileDownloaderConfiguration> {

    private static final Logger log = LoggerFactory.getLogger(FileDownloader.class);
    private boolean help = false;
    private UUID itemUUID;
    private int workspaceID;
    private String pid;
    private URI uri;
    private String epersonMail;
    private String bitstreamName;
    private EPersonService epersonService;
    private ItemService itemService;
    private  WorkspaceItemService workspaceItemService;
    private IdentifierService identifierService;
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

        if (!commandLine.hasOption("i") && !commandLine.hasOption("w") && !commandLine.hasOption("p")) {
            throw new ParseException("No item id option has been provided");
        }

        if (getEpersonIdentifier() == null && !commandLine.hasOption("e")) {
            throw new ParseException("No eperson option has been provided");
        }


        this.epersonService = EPersonServiceFactory.getInstance().getEPersonService();
        this.itemService = ContentServiceFactory.getInstance().getItemService();
        this.workspaceItemService = ContentServiceFactory.getInstance().getWorkspaceItemService();
        this.bitstreamService = ContentServiceFactory.getInstance().getBitstreamService();
        this.bitstreamFormatService = ContentServiceFactory.getInstance().getBitstreamFormatService();
        this.identifierService = IdentifierServiceFactory.getInstance().getIdentifierService();

        try {
            uri = new URI(commandLine.getOptionValue("u"));
        } catch (URISyntaxException e) {
            throw new ParseException("The provided URL is not a valid URL");
        }

        if (commandLine.hasOption("i")) {
            itemUUID = UUID.fromString(commandLine.getOptionValue("i"));
        } else if (commandLine.hasOption("w")) {
            workspaceID = Integer.parseInt(commandLine.getOptionValue("w"));
        } else if (commandLine.hasOption("p")) {
            pid = commandLine.getOptionValue("p");
        }

        epersonMail = commandLine.getOptionValue("e");

        if (commandLine.hasOption("n")) {
            bitstreamName = commandLine.getOptionValue("n");
        }
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

        //find the item by the given id
        Item item = findItem(context);
        if (item == null) {
            throw new IllegalArgumentException("No item found for the given ID");
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .build();

        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() >= 400) {
            throw new IllegalArgumentException("The provided URL returned a status code of " + response.statusCode());
        }

        //use the provided value, the content-disposition header, the last part of the uri
        if (bitstreamName == null) {
            bitstreamName = response.headers().firstValue("Content-Disposition")
                    .filter(value -> value.contains("filename=")).flatMap(value -> Stream.of(value.split(";"))
                            .filter(v -> v.contains("filename="))
                            .findFirst()
                            .map(fvalue -> fvalue.replaceFirst("filename=", "").replaceAll("\"", "")))
                    .orElse(uri.getPath().substring(uri.getPath().lastIndexOf('/') + 1));
        }

        try (InputStream is = response.body()) {
            saveFileToItem(context, item, is, bitstreamName);
        }

        context.commit();
    }

    private Item findItem(Context context) throws SQLException {
        if (itemUUID != null) {
            return itemService.find(context, itemUUID);
        } else if (workspaceID != 0) {
            return workspaceItemService.find(context, workspaceID).getItem();
        } else {
            try {
                DSpaceObject dso = identifierService.resolve(context, pid);
                if (dso instanceof Item) {
                    return (Item) dso;
                } else {
                    throw new IllegalArgumentException("The provided identifier does not resolve to an item");
                }
            } catch (IdentifierNotFoundException | IdentifierNotResolvableException e) {
                throw new IllegalArgumentException(e);
            }
        }
    }

    private void saveFileToItem(Context context, Item item, InputStream is, String name)
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
        b.setName(context, name);
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

