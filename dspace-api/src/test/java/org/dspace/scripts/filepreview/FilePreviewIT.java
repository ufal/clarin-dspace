/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.scripts.filepreview;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;

import java.io.InputStream;
import java.sql.SQLException;
import java.util.List;

import org.dspace.AbstractIntegrationTestWithDatabase;
import org.dspace.app.launcher.ScriptLauncher;
import org.dspace.app.scripts.handler.impl.TestDSpaceRunnableHandler;
import org.dspace.authorize.AuthorizeException;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.WorkspaceItemBuilder;
import org.dspace.content.Bitstream;
import org.dspace.content.BitstreamFormat;
import org.dspace.content.Bundle;
import org.dspace.content.Collection;
import org.dspace.content.Community;
import org.dspace.content.Item;
import org.dspace.content.WorkspaceItem;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.BitstreamFormatService;
import org.dspace.content.service.BitstreamService;
import org.junit.Before;
import org.junit.Test;

/**
 * Integration test for the FilePreview script
 * @author Milan Majchrak (milan.majchrak at dataquest.sk)
 */
public class FilePreviewIT extends AbstractIntegrationTestWithDatabase {
    BitstreamService bitstreamService = ContentServiceFactory.getInstance().getBitstreamService();

    BitstreamFormatService bitstreamFormatService = ContentServiceFactory.getInstance().getBitstreamFormatService();

    Item item;

    @Before
    public void setup() throws SQLException, AuthorizeException {
        InputStream previewZipIs = getClass().getResourceAsStream("preview-file-test.zip");

        context.turnOffAuthorisationSystem();
        Community community = CommunityBuilder.createCommunity(context).withName("Com").build();
        Collection collection = CollectionBuilder.createCollection(context, community).withName("Col").build();
        WorkspaceItem wItem = WorkspaceItemBuilder.createWorkspaceItem(context, collection)
                .withFulltext("preview-file-test.zip", "/local/path/preview-file-test.zip", previewZipIs)
                .build();
        context.restoreAuthSystemState();

        // Get the item and its bitstream
        item = wItem.getItem();
        List<Bundle> bundles = item.getBundles();
        List<Bitstream> bitstreams = bundles.get(0).getBitstreams();
        Bitstream bitstream = bitstreams.get(0);

        // Set the bitstream format to application/zip
        BitstreamFormat bitstreamFormat = bitstreamFormatService.findByMIMEType(context, "application/zip");
        bitstream.setFormat(context, bitstreamFormat);
        bitstreamService.update(context, bitstream);
        context.commit();
        context.reloadEntity(bitstream);
        context.reloadEntity(item);
    }

    @Test
    public void testWhenNoFilesRun() throws Exception {
        TestDSpaceRunnableHandler testDSpaceRunnableHandler = new TestDSpaceRunnableHandler();

        String[] args = new String[] { "file-preview" };
        ScriptLauncher.handleScript(args, ScriptLauncher.getConfig(kernelImpl), testDSpaceRunnableHandler, kernelImpl);

        checkNoError(testDSpaceRunnableHandler);
    }

    @Test
    public void testForSpecificItem() throws Exception {
        // Run the script
        TestDSpaceRunnableHandler testDSpaceRunnableHandler = new TestDSpaceRunnableHandler();
        String[] args = new String[] { "file-preview", "-u", item.getID().toString() };
        ScriptLauncher.handleScript(args, ScriptLauncher.getConfig(kernelImpl), testDSpaceRunnableHandler, kernelImpl);

        // There should be no errors or warnings
        checkNoError(testDSpaceRunnableHandler);

        // There should be an info message about generating the file previews for the specified item
        List<String> messages = testDSpaceRunnableHandler.getInfoMessages();
        assertThat(messages, hasSize(1));
        assertThat(messages, hasItem(containsString("Generate the file previews for the specified item with " +
                "the given UUID: " + item.getID())));
    }

    @Test
    public void testForAllItem() throws Exception {
        // Run the script
        TestDSpaceRunnableHandler testDSpaceRunnableHandler = new TestDSpaceRunnableHandler();
        String[] args = new String[] { "file-preview" };
        ScriptLauncher.handleScript(args, ScriptLauncher.getConfig(kernelImpl), testDSpaceRunnableHandler, kernelImpl);

        // There should be no errors or warnings
        checkNoError(testDSpaceRunnableHandler);
    }

    private void checkNoError(TestDSpaceRunnableHandler testDSpaceRunnableHandler) {
        assertThat(testDSpaceRunnableHandler.getErrorMessages(), empty());
        assertThat(testDSpaceRunnableHandler.getWarningMessages(), empty());
    }
}
