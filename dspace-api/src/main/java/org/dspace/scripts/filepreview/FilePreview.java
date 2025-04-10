/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.scripts.filepreview;

import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.apache.commons.cli.ParseException;
import org.apache.commons.lang3.StringUtils;
import org.dspace.content.Bitstream;
import org.dspace.content.Bundle;
import org.dspace.content.Item;
import org.dspace.content.PreviewContent;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.ItemService;
import org.dspace.content.service.PreviewContentService;
import org.dspace.core.Context;
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

    /**
     * `-i`: Info, show help information.
     */
    private boolean info = false;

    /**
     * `-u`: UUID of the Item for which to create a preview of its bitstreams.
     */
    private String specificItemUUID = null;

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
    }

    @Override
    public void internalRun() throws Exception {
        if (info) {
            printHelp();
            return;
        }

        Context context = new Context();
        context.turnOffAuthorisationSystem();
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
        context.restoreAuthSystemState();
        context.commit();
        context.complete();
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
                "  -u, --uuid            The UUID of the ITEM for which to create a preview of its bitstreams\n");
    }
}
