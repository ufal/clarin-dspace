/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.curate;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.commons.cli.ParseException;
import org.dspace.AbstractIntegrationTestWithDatabase;
import org.dspace.app.scripts.handler.impl.TestDSpaceRunnableHandler;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.ItemBuilder;
import org.dspace.content.Collection;
import org.dspace.content.Community;
import org.dspace.content.Item;
import org.dspace.core.factory.CoreServiceFactory;
import org.dspace.core.service.PluginService;
import org.dspace.ctask.testing.ReportAndFailTask;
import org.dspace.scripts.DSpaceRunnable;
import org.dspace.scripts.configuration.ScriptConfiguration;
import org.dspace.scripts.factory.ScriptServiceFactory;
import org.dspace.scripts.service.ScriptService;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class CurationIT extends AbstractIntegrationTestWithDatabase {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test(expected = ParseException.class)
    public void curationWithoutEPersonParameterTest() throws Exception {

        context.turnOffAuthorisationSystem();
        Community community = CommunityBuilder.createCommunity(context)
                                              .build();
        Collection collection = CollectionBuilder.createCollection(context, community)
                                                 .build();
        context.restoreAuthSystemState();
        String[] args = new String[] {"curate", "-t", CurationClientOptions.getTaskOptions().get(0),
            "-i", collection.getHandle()};
        TestDSpaceRunnableHandler testDSpaceRunnableHandler = new TestDSpaceRunnableHandler();

        ScriptService scriptService = ScriptServiceFactory.getInstance().getScriptService();
        ScriptConfiguration scriptConfiguration = scriptService.getScriptConfiguration(args[0]);

        DSpaceRunnable script = null;
        if (scriptConfiguration != null) {
            script = scriptService.createDSpaceRunnableForScriptConfiguration(scriptConfiguration);
        }
        if (script != null) {
            if (DSpaceRunnable.StepResult.Continue.equals(script.initialize(args, testDSpaceRunnableHandler, null))) {
                script.run();
            }
        }
    }

    @Test
    public void curationWithEPersonParameterTest() throws Exception {

        context.turnOffAuthorisationSystem();
        Community community = CommunityBuilder.createCommunity(context)
                                              .build();
        Collection collection = CollectionBuilder.createCollection(context, community)
                                                 .build();
        context.restoreAuthSystemState();
        String[] args = new String[] {"curate", "-e", "admin@email.com", "-t",
            CurationClientOptions.getTaskOptions().get(0), "-i", collection.getHandle()};
        TestDSpaceRunnableHandler testDSpaceRunnableHandler = new TestDSpaceRunnableHandler();

        ScriptService scriptService = ScriptServiceFactory.getInstance().getScriptService();
        ScriptConfiguration scriptConfiguration = scriptService.getScriptConfiguration(args[0]);

        DSpaceRunnable script = null;
        if (scriptConfiguration != null) {
            script = scriptService.createDSpaceRunnableForScriptConfiguration(scriptConfiguration);
        }
        if (script != null) {
            if (DSpaceRunnable.StepResult.Continue.equals(script.initialize(args, testDSpaceRunnableHandler, null))) {
                script.run();
            }
        }
    }

    @Test
    public void curationReportIsWrittenToFile() throws Exception {
        Item item = createItem();
        Path reportBase = useNewReportBase();
        Path reportFile = reportBase.resolve("report.txt");

        runDSpaceScript("curate", "-e", admin.getEmail(), "-t", "noop", "-i", item.getHandle(),
            "-r", reportFile.toString());

        assertEquals("No operation performed on " + item.getHandle() + System.lineSeparator(),
            Files.readString(reportFile));
    }

    @Test
    public void curationReportIsPrintedToStdout() throws Exception {
        Item item = createItem();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
        try {
            runDSpaceScript("curate", "-e", admin.getEmail(), "-t", "noop", "-i", item.getHandle(), "-r", "-");
        } finally {
            System.setOut(originalOut);
        }

        assertThat(stdout.toString(StandardCharsets.UTF_8).lines().toList(),
            hasItem("No operation performed on " + item.getHandle()));
    }

    @Test
    public void curationReportOutsideReportBaseIsRefused() throws Exception {
        Item item = createItem();
        useNewReportBase();
        Path outsideFile = tempFolder.newFolder("outside").toPath().toRealPath().resolve("report.txt");

        FileNotFoundException e = assertThrows(FileNotFoundException.class, () -> runDSpaceScript("curate",
            "-e", admin.getEmail(), "-t", "noop", "-i", item.getHandle(), "-r", outsideFile.toString()));
        assertThat(e.getMessage(), containsString("Illegal file path"));
        assertFalse(Files.exists(outsideFile));
    }

    @Test
    public void curationReportIsKeptWhenTheTaskFails() throws Exception {
        Item item = createItem();
        Path reportBase = useNewReportBase();
        Path reportFile = reportBase.resolve("report.txt");
        // -T, because the -t task names are cached when the curate script is first used
        Path taskFile = Files.writeString(reportBase.resolve("tasks.txt"), "reportandfail");
        ConfigurationService configurationService = DSpaceServicesFactory.getInstance().getConfigurationService();
        configurationService.setProperty("curate.taskfile.base", reportBase.toString());
        configurationService.addPropertyValue("plugin.named.org.dspace.curate.CurationTask",
            ReportAndFailTask.class.getName() + " = reportandfail");
        PluginService pluginService = CoreServiceFactory.getInstance().getPluginService();
        pluginService.clearNamedPluginClasses();
        try {
            assertThrows(IOException.class, () -> runDSpaceScript("curate", "-e", admin.getEmail(),
                "-T", taskFile.toString(), "-i", item.getHandle(), "-r", reportFile.toString()));
        } finally {
            pluginService.clearNamedPluginClasses();
        }

        assertEquals("Reported before failing on " + item.getHandle() + System.lineSeparator(),
            Files.readString(reportFile));
    }

    private Item createItem() {
        context.turnOffAuthorisationSystem();
        Community community = CommunityBuilder.createCommunity(context).build();
        Collection collection = CollectionBuilder.createCollection(context, community).build();
        Item item = ItemBuilder.createItem(context, collection).withTitle("Curated item").build();
        context.restoreAuthSystemState();
        return item;
    }

    private Path useNewReportBase() throws Exception {
        Path reportBase = tempFolder.newFolder("reports").toPath().toRealPath();
        DSpaceServicesFactory.getInstance().getConfigurationService()
            .setProperty("curate.reporter.base", reportBase.toString());
        return reportBase;
    }
}
