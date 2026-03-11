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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.sql.SQLException;
import java.util.List;

import org.dspace.AbstractIntegrationTestWithDatabase;
import org.dspace.app.launcher.ScriptLauncher;
import org.dspace.app.scripts.handler.impl.TestDSpaceRunnableHandler;
import org.dspace.authorize.AuthorizeException;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.EPersonBuilder;
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
import org.dspace.content.service.PreviewContentService;
import org.dspace.eperson.EPerson;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.dspace.storage.bitstore.SyncBitstreamStorageServiceImpl;
import org.junit.Before;
import org.junit.Test;

/**
 * Integration test for the FilePreview script
 * @author Milan Majchrak (milan.majchrak at dataquest.sk)
 */
public class FilePreviewIT extends AbstractIntegrationTestWithDatabase {
    private static final int SYNC_STORE_NUMBER = SyncBitstreamStorageServiceImpl.SYNCHRONIZED_STORES_NUMBER;

    BitstreamService bitstreamService = ContentServiceFactory.getInstance().getBitstreamService();
    BitstreamFormatService bitstreamFormatService = ContentServiceFactory.getInstance().getBitstreamFormatService();
    PreviewContentService previewContentService = ContentServiceFactory.getInstance().getPreviewContentService();
    ConfigurationService configurationService = DSpaceServicesFactory.getInstance().getConfigurationService();

    Collection collection;
    Item item;
    // avoid using eperson created in superclass
    EPerson ePerson;
    String PASSWORD = "test";

    @Before
    public void setup() throws SQLException, AuthorizeException {
        InputStream previewZipIs = getClass().getResourceAsStream("preview-file-test.zip");

        context.turnOffAuthorisationSystem();
        ePerson = EPersonBuilder.createEPerson(context)
                .withEmail("test@test.edu").withPassword(PASSWORD).build();
        Community community = CommunityBuilder.createCommunity(context).withName("Com").build();
        collection = CollectionBuilder.createCollection(context, community).withName("Col").build();
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
    public void testUnauthorizedEmail() throws Exception {
        // Run the script
        TestDSpaceRunnableHandler testDSpaceRunnableHandler = new TestDSpaceRunnableHandler();
        String[] args = new String[] { "file-preview"};
        int run = ScriptLauncher.handleScript(args, ScriptLauncher.getConfig(kernelImpl),
                testDSpaceRunnableHandler, kernelImpl);
        assertEquals(1, run); // Since a ParseException was caught, expect return code 1
    }

    @Test
    public void testUnauthorizedPassword() throws Exception {
        // Run the script
        TestDSpaceRunnableHandler testDSpaceRunnableHandler = new TestDSpaceRunnableHandler();
        String[] args = new String[] { "file-preview", "-e", ePerson.getEmail()};
        int run = ScriptLauncher.handleScript(args, ScriptLauncher.getConfig(kernelImpl),
                testDSpaceRunnableHandler, kernelImpl);
        assertEquals(1, run); // Since a ParseException was caught, expect return code 1
    }

    @Test
    public void testWhenNoFilesRun() throws Exception {
        TestDSpaceRunnableHandler testDSpaceRunnableHandler = new TestDSpaceRunnableHandler();

        String[] args = new String[] { "file-preview", "-e", ePerson.getEmail(), "-p",  PASSWORD };
        int run = ScriptLauncher.handleScript(args, ScriptLauncher.getConfig(kernelImpl),
                testDSpaceRunnableHandler, kernelImpl);
        assertEquals(0, run);
        checkNoError(testDSpaceRunnableHandler);
    }

    @Test
    public void testForSpecificItem() throws Exception {
        Item item2 = createOtherWorkspaceItemWithBitstream(ePerson, 0);
        // Run the script
        runScriptForItemWithBitstreams(item2, ePerson, PASSWORD);

        Bitstream b = bitstreamService.findAll(context).stream()
                .filter(bitstream -> bitstream.getName().equals("logos.tgz"))
                .findFirst().orElse(null);

        assertNotNull(b);
        assertEquals("logos.tgz", b.getName());

        // the preview content was created since the item was created by the same user as the script was run
        assertTrue("Expects preview content created.", previewContentService.hasPreview(context, b));
        assertEquals(2, previewContentService.getPreview(context, b).size());
    }

    @Test
    public void testWhenScriptCannotCreateFilePreview() throws Exception {
        Item item2 = createOtherWorkspaceItemWithBitstream(eperson, 0);
        // Run the script as another user, without admin rights
        runScriptForItemWithBitstreams(item2, ePerson, PASSWORD);

        Bitstream b = bitstreamService.findAll(context).stream()
                .filter(bitstream -> bitstream.getName().equals("logos.tgz"))
                .findFirst().orElse(null);

        assertNotNull(b);
        assertEquals("logos.tgz", b.getName());

        // the preview content cannot be created since the item was created by another user (eperson)
        // than the user (ePerson) who runs the script
        assertFalse("Expects preview content not created.", previewContentService.hasPreview(context, b));

        // Run the script as admin user
        runScriptForItemWithBitstreams(item2, admin, password);

        // now the preview content was created since the script was run by admin user
        assertTrue("Expects preview content created.", previewContentService.hasPreview(context, b));
        assertEquals(2, previewContentService.getPreview(context, b).size());
    }

    @Test
    public void testPreviewWithSyncStorage() throws Exception {
        configurationService.setProperty("sync.storage.service.enabled", true);
        Item item2 = createOtherWorkspaceItemWithBitstream(ePerson, SYNC_STORE_NUMBER);
        // Run the script
        runScriptForItemWithBitstreams(item2, ePerson, PASSWORD);

        Bitstream b = bitstreamService.findAll(context).stream()
                .filter(bitstream -> bitstream.getStoreNumber() == SYNC_STORE_NUMBER)
                .findFirst().orElse(null);

        assertNotNull(b);
        assertEquals("logos.tgz", b.getName());
        assertTrue("Expects preview content created and stored.", previewContentService.hasPreview(context, b));
    }

    @Test
    public void testForAllItem() throws Exception {
        // Run the script
        TestDSpaceRunnableHandler testDSpaceRunnableHandler = new TestDSpaceRunnableHandler();
        String[] args = new String[] { "file-preview", "-e", ePerson.getEmail(), "-p",  PASSWORD};
        int run = ScriptLauncher.handleScript(args, ScriptLauncher.getConfig(kernelImpl),
                testDSpaceRunnableHandler, kernelImpl);
        assertEquals(0, run);
        // There should be no errors or warnings
        checkNoError(testDSpaceRunnableHandler);
    }

    private void checkNoError(TestDSpaceRunnableHandler testDSpaceRunnableHandler) {
        assertThat(testDSpaceRunnableHandler.getErrorMessages(), empty());
        assertThat(testDSpaceRunnableHandler.getWarningMessages(), empty());
    }

    private void runScriptForItemWithBitstreams(Item item, EPerson user, String password) throws Exception {
        // Run the script
        TestDSpaceRunnableHandler testDSpaceRunnableHandler = new TestDSpaceRunnableHandler();
        String[] args = new String[] { "file-preview", "-u", item.getID().toString(),
                "-e", user.getEmail(), "-p",  password};
        int run = ScriptLauncher.handleScript(args, ScriptLauncher.getConfig(kernelImpl),
                testDSpaceRunnableHandler, kernelImpl);
        assertEquals(0, run);
        // There should be no errors or warnings
        checkNoError(testDSpaceRunnableHandler);

        // There should be an info message about generating the file previews for the specified item
        List<String> messages = testDSpaceRunnableHandler.getInfoMessages();
        assertThat(messages, hasSize(2));
        assertThat(messages, hasItem(containsString("Generate the file previews for the specified item with " +
                "the given UUID: " + item.getID())));
        assertThat(messages,
                hasItem(containsString("Authentication by user: " + user.getEmail())));
    }

    private Item createOtherWorkspaceItemWithBitstream(EPerson user, int storageNumber) throws Exception {
        context.turnOffAuthorisationSystem();
        context.setCurrentUser(user);
        WorkspaceItem wItem2;
        try (InputStream tgzFile = getClass().getResourceAsStream("logos.tgz")) {
            wItem2 = WorkspaceItemBuilder.createWorkspaceItem(context, collection)
                    .withBitstream("logos.tgz", "/local/path/logos.tgz", tgzFile, storageNumber)
                    .build();
        }
        context.restoreAuthSystemState();

        Item item2 = wItem2.getItem();
        List<Bundle> bundles = item2.getBundles();
        Bitstream bitstream2 = bundles.get(0).getBitstreams().get(0);

        // Set the bitstream format to application/zip
        BitstreamFormat bitstreamFormat = bitstreamFormatService.findByMIMEType(context, "application/x-gtar");
        bitstream2.setFormat(context, bitstreamFormat);
        bitstreamService.update(context, bitstream2);
        context.commit();
        context.reloadEntity(bitstream2);
        context.reloadEntity(item2);

        return item2;
    }
}
