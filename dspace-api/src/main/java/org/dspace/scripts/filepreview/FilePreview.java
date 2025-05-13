/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.scripts.filepreview;

import java.sql.SQLException;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.naming.AuthenticationException;

import org.apache.commons.cli.ParseException;
import org.apache.commons.lang3.StringUtils;
import org.dspace.authenticate.AuthenticationMethod;
import org.dspace.authenticate.factory.AuthenticateServiceFactory;
import org.dspace.authenticate.service.AuthenticationService;
import org.dspace.content.Bitstream;
import org.dspace.content.Bundle;
import org.dspace.content.Item;
import org.dspace.content.PreviewContent;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.ItemService;
import org.dspace.content.service.PreviewContentService;
import org.dspace.core.Context;
import org.dspace.eperson.EPerson;
import org.dspace.eperson.factory.EPersonServiceFactory;
import org.dspace.eperson.service.EPersonService;
import org.dspace.scripts.DSpaceRunnable;
import org.dspace.util.FileInfo;
import org.dspace.utils.DSpace;

/**
 * This class is used to generate a preview for every file in DSpace that should have a preview.
 * It can be run from the command line with the following options:
 * `-i`: Info, show help information.
 * `-u`: UUID of the Item for which to create a preview of its bitstreams.
 * @author Milan Majchrak at (dspace at dataquest.sk)
 */
public class FilePreview extends DSpaceRunnable<FilePreviewConfiguration> {
    private ItemService itemService = ContentServiceFactory.getInstance().getItemService();
    private PreviewContentService previewContentService =
            ContentServiceFactory.getInstance().getPreviewContentService();
    private EPersonService ePersonService = EPersonServiceFactory.getInstance()
            .getEPersonService();
    private AuthenticationService authenticateService = AuthenticateServiceFactory.getInstance()
            .getAuthenticationService();

    /**
     * `-i`: Info, show help information.
     */
    private boolean info = false;

    /**
     * `-u`: UUID of the Item for which to create a preview of its bitstreams.
     */
    private String specificItemUUID = null;

    private String epersonMail = null;
    private String epersonPassword = null;

    @Override
    public FilePreviewConfiguration getScriptConfiguration() {
        return new DSpace().getServiceManager()
                .getServiceByName("file-preview", FilePreviewConfiguration.class);
    }

    @Override
    public void setup() throws ParseException {
        // `-i`: Info, show help information.
        if (commandLine.hasOption('i')) {
            info = true;
            return;
        }

        // `-u`: UUID of the Item for which to create a preview of its bitstreams.
        if (commandLine.hasOption('u')) {
            specificItemUUID = commandLine.getOptionValue('u');
            // Generate the file previews for the specified item with the given UUID.
            handler.logInfo("\nGenerate the file previews for the specified item with the given UUID: " +
                    specificItemUUID);
        }

        epersonMail = commandLine.getOptionValue('e');
        epersonPassword = commandLine.getOptionValue('p');

        if (getEpersonIdentifier() == null && (epersonMail == null || epersonPassword == null)) {
            throw new ParseException("Provide both -e/--email and -p/--password when no eperson is supplied.");
        }
    }

    @Override
    public void internalRun() throws Exception {
        if (info) {
            printHelp();
            return;
        }

        Context context = new Context();
        try {
            context.setCurrentUser(getAuthenticatedEperson((context)));
            handler.logInfo("Authentication by user: " + context.getCurrentUser().getEmail());
            if (StringUtils.isNotBlank(specificItemUUID)) {
                // Generate the preview only for a specific item
                generateItemFilePreviews(context, UUID.fromString(specificItemUUID));
            } else {
                // Generate the preview for all items
                Iterator<Item> items = itemService.findAll(context);

                int count = 0;
                while (items.hasNext()) {
                    count++;
                    Item item = items.next();
                    try {
                        generateItemFilePreviews(context, item.getID());
                    } catch (Exception e) {
                        handler.logError("Error while generating preview for item with UUID: " + item.getID());
                        handler.logError(e.getMessage());
                    }

                    if (count % 100 == 0) {
                        handler.logInfo("Processed " + count + " items.");
                    }
                }
            }
            context.commit();
            context.complete();
        } finally {
            if (context.isValid()) {
                context.abort();
            }
        }
    }

    private void generateItemFilePreviews(Context context, UUID itemUUID) throws Exception {
        Item item = itemService.find(context, itemUUID);
        if (Objects.isNull(item)) {
            handler.logError("Item with UUID: " + itemUUID + " not found.");
            return;
        }

        List<Bundle> bundles = item.getBundles("ORIGINAL");
        for (Bundle bundle : bundles) {
            List<Bitstream> bitstreams = bundle.getBitstreams();
            for (Bitstream bitstream : bitstreams) {
                boolean canPreview = previewContentService.canPreview(context, bitstream);
                if (!canPreview) {
                    return;
                }
                List<PreviewContent> prContents = previewContentService.hasPreview(context, bitstream);
                // Generate new content if we didn't find any
                if (!prContents.isEmpty()) {
                    return;
                }

                List<FileInfo> fileInfos = previewContentService.getFilePreviewContent(context, bitstream);
                // Do not store HTML content in the database because it could be longer than the limit
                // of the database column
                if (StringUtils.equals("text/html", bitstream.getFormat(context).getMIMEType())) {
                    return;
                }

                for (FileInfo fi : fileInfos) {
                    previewContentService.createPreviewContent(context, bitstream, fi);
                }
            }
        }
    }

    @Override
    public void printHelp() {
        handler.logInfo("\n\nINFORMATION\nThis process generates a preview for every file in DSpace that should " +
                "have a preview.\n" +
                "You can choose from these available options:\n" +
                "  -i, --info            Show help information\n" +
                "  -u, --uuid            The UUID of the ITEM for which to create a preview of its bitstreams\n" +
                "  -e, --email           Email for authentication\n" +
                "  -p, --password        Password for authentication\n");

    }

    /**
     * Retrieves an EPerson object either by its identifier or by performing an email-based lookup.
     * It then authenticates the EPerson using the provided email and password.
     * If the authentication is successful, it returns the EPerson object; otherwise,
     * it throws an AuthenticationException.
     *
     * @param context The Context object used for interacting with the DSpace database and service layer.
     * @return The authenticated EPerson object corresponding to the provided email,
     *         if authentication is successful.
     * @throws SQLException If a database error occurs while retrieving or interacting with the EPerson data.
     * @throws AuthenticationException If no EPerson is found for the provided email
     *         or if the authentication fails.
     */
    private EPerson getAuthenticatedEperson(Context context) throws SQLException, AuthenticationException {
        if (getEpersonIdentifier() != null) {
            return ePersonService.find(context, getEpersonIdentifier());
        }
        String msg;
        EPerson ePerson = ePersonService.findByEmail(context, epersonMail);
        if (ePerson == null) {
            msg = "No EPerson found for this email: " + epersonMail;
            handler.logError(msg);
            throw new AuthenticationException(msg);
        }
        int authenticated = authenticateService.authenticate(context, epersonMail, epersonPassword, null, null);
        if (AuthenticationMethod.SUCCESS != authenticated) {
            msg = "Authentication failed for email: " + epersonMail;
            handler.logError(msg);
            throw new AuthenticationException(msg);
        }
        return ePerson;
    }
}
