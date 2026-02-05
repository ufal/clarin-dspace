/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.curate;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.nio.file.Files;

import org.dspace.AbstractDSpaceTest;
import org.dspace.content.Item;
import org.dspace.core.factory.CoreServiceFactory;
import org.dspace.ctask.general.NoOpCurationTask;
import org.dspace.curate.reporters.DoNothingReporter;
import org.dspace.curate.reporters.FilePrinterReporter;
import org.dspace.curate.reporters.SystemOutReporter;
import org.dspace.services.ConfigurationService;
import org.junit.Before;
import org.junit.Test;

/**
 * Test different Reporter implementations with Curator.
 *
 * @author Milan Kuchtiak
 */
public class CuratorReporterTest extends AbstractDSpaceTest {
    private static final String TASK_NAME = "noop";
    private static final String TEST_HANDLE = "testHandle";
    private static final String NO_OP = "No operation performed on " + TEST_HANDLE;

    private Curator curator;

    @Before
    public void setup() {
        CoreServiceFactory.getInstance().getPluginService().clearNamedPluginClasses();

        // Configure the noop task to be run.
        ConfigurationService cfg = kernelImpl.getConfigurationService();
        cfg.setProperty("plugin.named.org.dspace.curate.CurationTask",
                NoOpCurationTask.class.getName() + " = " + TASK_NAME);

        // Get and configure a Curator.
        curator = new Curator();
    }

    @Test
    public void testCurateWithDoNothingReporter() throws Exception {
        try (Reporter reporter = new DoNothingReporter()) {
            runCuratorWithReporter(reporter);
        }
    }

    @Test
    public void testCurateWithSystemOutReporter() throws Exception {
        try (Reporter reporter = new SystemOutReporter()) {
            runCuratorWithReporter(reporter);
        }
    }

    @Test
    public void testCurateWithFilePrinterReporter() throws Exception {
        File tempFile = File.createTempFile("curator-test-report", "txt");
        try (Reporter reporter = new FilePrinterReporter(tempFile.getAbsolutePath())) {
            runCuratorWithReporter(reporter);
        }
        // check if the file contains expected line with one line separator
        String fileOutput = Files.readString(tempFile.toPath());
        assertEquals(NO_OP + System.lineSeparator(), fileOutput);
    }

    @Test
    public void testCurateWithStringBuilder() throws Exception {
        StringBuilder stringBuilder = new StringBuilder();
        runCuratorWithReporter(stringBuilder);
        assertEquals(NO_OP, stringBuilder.toString());
    }

    private void runCuratorWithReporter(Appendable reporter) throws Exception {
        curator.setReporter(reporter);
        curator.addTask(TASK_NAME);
        Item item = mock(Item.class);
        when(item.getType()).thenReturn(2);
        when(item.getHandle()).thenReturn(TEST_HANDLE);
        curator.curate(item);

        assertEquals(Curator.CURATE_SUCCESS, curator.getStatus(TASK_NAME));
        assertEquals(NO_OP, curator.getResult(TASK_NAME));
    }

}
