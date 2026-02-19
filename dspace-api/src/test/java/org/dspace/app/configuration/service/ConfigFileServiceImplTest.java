/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.configuration.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;

import org.dspace.app.configuration.exception.ConfigFileNotAllowedException;
import org.dspace.app.configuration.exception.ConfigFileNotFoundException;
import org.dspace.services.ConfigurationService;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

/**
 * Unit tests for ConfigFileServiceImpl
 *
 * These tests verify the core functionality of the configuration file service
 * including file validation, content operations, backup creation, and security measures.
 *
 * @author Milan Majchrak (dspace at dataquest.sk)
 */
@RunWith(MockitoJUnitRunner.class)
public class ConfigFileServiceImplTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Mock
    private ConfigurationService configurationService;

    @InjectMocks
    private ConfigFileServiceImpl configFileService;

    private Path testConfigDir;
    private Path testConfigFile;
    private String testFileName = "test-config.cfg";
    private String testContent = "# Test Configuration\ntest.property=test.value\n";

    @Before
    public void setUp() throws IOException {
        // Create temporary test directories and files
        testConfigDir = tempFolder.newFolder("config").toPath();
        testConfigFile = testConfigDir.resolve(testFileName);

        // Write test content to file
        Files.writeString(testConfigFile, testContent);

        // Mock configuration service
        when(configurationService.getProperty("dspace.dir"))
            .thenReturn(tempFolder.getRoot().getAbsolutePath());
        when(configurationService.getArrayProperty("config.admin.updateable.files"))
            .thenReturn(new String[]{testFileName, "dspace.cfg", "local.cfg"});
    }

    @After
    public void tearDown() {
        // TemporaryFolder rule handles cleanup
    }

    @Test
    public void testGetAllowedConfigFiles_ReturnsConfiguredFiles() {
        List<String> allowedFiles = configFileService.getAllowedConfigFiles();

        assertNotNull("Allowed files should not be null", allowedFiles);
        assertEquals("Should have 3 allowed files", 3, allowedFiles.size());
        assertTrue("Should contain test config file", allowedFiles.contains(testFileName));
        assertTrue("Should contain dspace.cfg", allowedFiles.contains("dspace.cfg"));
        assertTrue("Should contain local.cfg", allowedFiles.contains("local.cfg"));
    }

    @Test
    public void testGetAllowedConfigFiles_NoConfiguration_ReturnsEmpty() {
        when(configurationService.getArrayProperty("config.admin.updateable.files"))
            .thenReturn(null);

        List<String> allowedFiles = configFileService.getAllowedConfigFiles();

        assertNotNull("Allowed files should not be null", allowedFiles);
        assertTrue("Should be empty when no configuration", allowedFiles.isEmpty());
    }

    @Test
    public void testValidateFileAccess_AllowedFile_Succeeds() throws Exception {
        // Should not throw any exception
        configFileService.validateFileAccess(testFileName);
    }

    @Test(expected = ConfigFileNotAllowedException.class)
    public void testValidateFileAccess_NotAllowedFile_ThrowsException() throws Exception {
        configFileService.validateFileAccess("not-allowed.cfg");
    }

    @Test(expected = ConfigFileNotFoundException.class)
    public void testValidateFileAccess_NonExistentFile_ThrowsException() throws Exception {
        when(configurationService.getArrayProperty("config.admin.updateable.files"))
            .thenReturn(new String[]{"nonexistent.cfg"});

        configFileService.validateFileAccess("nonexistent.cfg");
    }

    @Test(expected = ConfigFileNotAllowedException.class)
    public void testValidateFileAccess_EmptyFileName_ThrowsException() throws Exception {
        configFileService.validateFileAccess("");
    }

    @Test(expected = ConfigFileNotAllowedException.class)
    public void testValidateFileAccess_NullFileName_ThrowsException() throws Exception {
        configFileService.validateFileAccess(null);
    }

    @Test
    public void testReadConfigFile_ValidFile_ReturnsContent() throws Exception {
        String content = configFileService.readConfigFile(testFileName);

        assertEquals("Content should match", testContent, content);
    }

    @Test(expected = ConfigFileNotAllowedException.class)
    public void testReadConfigFile_NotAllowedFile_ThrowsException() throws Exception {
        configFileService.readConfigFile("not-allowed.cfg");
    }

    @Test
    public void testWriteConfigFile_ValidFile_UpdatesContent() throws Exception {
        String newContent = "# Updated Configuration\nupdated.property=new.value\n";

        configFileService.writeConfigFile(testFileName, newContent);

        String readContent = Files.readString(testConfigFile);
        assertEquals("Content should be updated", newContent, readContent);
    }

    @Test
    public void testWriteConfigFile_CreatesBackup() throws Exception {
        String newContent = "# Updated Configuration\n";

        // Verify no backup files exist initially
        assertEquals("Should have no backup files initially", 0,
                    countBackupFiles(testConfigFile.getFileName().toString()));

        configFileService.writeConfigFile(testFileName, newContent);

        // Verify backup was created
        assertEquals("Should have created one backup file", 1,
                    countBackupFiles(testConfigFile.getFileName().toString()));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testWriteConfigFile_NullContent_ThrowsException() throws Exception {
        configFileService.writeConfigFile(testFileName, null);
    }

    @Test
    public void testWriteConfigFile_EmptyContent_Succeeds() throws Exception {
        configFileService.writeConfigFile(testFileName, "");

        String content = Files.readString(testConfigFile);
        assertEquals("Content should be empty", "", content);
    }

    @Test
    public void testCreateBackup_ExistingFile_CreatesBackup() throws Exception {
        Path backupPath = configFileService.createBackup(testConfigFile);

        assertNotNull("Backup path should not be null", backupPath);
        assertTrue("Backup file should exist", Files.exists(backupPath));
        assertTrue("Backup filename should contain 'backup'",
                  backupPath.getFileName().toString().contains("backup"));

        String backupContent = Files.readString(backupPath);
        assertEquals("Backup content should match original", testContent, backupContent);
    }

    @Test
    public void testCreateBackup_NonExistentFile_ReturnsNull() throws Exception {
        Path nonExistentFile = testConfigDir.resolve("nonexistent.cfg");

        Path backupPath = configFileService.createBackup(nonExistentFile);

        assertNull("Backup path should be null for non-existent file", backupPath);
    }

    @Test
    public void testGetFileMetadata_ValidFile_ReturnsMetadata() throws Exception {
        ConfigFileService.ConfigFileMetadata metadata =
            configFileService.getFileMetadata(testFileName);

        assertNotNull("Metadata should not be null", metadata);
        assertEquals("Name should match", testFileName, metadata.getName());
        assertEquals("Size should match file size",
                    (Long) Files.size(testConfigFile), metadata.getSize());
        assertTrue("File should be readable", metadata.getReadable());
        assertTrue("File should be writable", metadata.getWritable());
        assertNotNull("Last modified should be set", metadata.getLastModified());
        assertNotNull("Path should be set", metadata.getPath());
    }

    @Test(expected = ConfigFileNotAllowedException.class)
    public void testGetFileMetadata_NotAllowedFile_ThrowsException() throws Exception {
        configFileService.getFileMetadata("not-allowed.cfg");
    }

    @Test
    public void testConfigFileMetadata_GettersWork() {
        ConfigFileService.ConfigFileMetadata metadata =
            new ConfigFileService.ConfigFileMetadata(
                "test.cfg", Paths.get("/test/test.cfg"), 100L,
                null, true, false);

        assertEquals("test.cfg", metadata.getName());
        assertEquals(Paths.get("/test/test.cfg"), metadata.getPath());
        assertEquals((Long) 100L, metadata.getSize());
        assertNull(metadata.getLastModified());
        assertTrue(metadata.getReadable());
        assertFalse(metadata.getWritable());
    }

    @Test
    public void testServiceHandlesDSpaceDirNotSet() {
        when(configurationService.getProperty("dspace.dir")).thenReturn(null);

        try {
            configFileService.validateFileAccess(testFileName);
            fail("Should throw IllegalStateException when dspace.dir not set");
        } catch (IllegalStateException e) {
            assertTrue("Error message should mention dspace.dir",
                      e.getMessage().contains("dspace.dir"));
        } catch (Exception e) {
            fail("Should throw IllegalStateException, not " + e.getClass().getSimpleName());
        }
    }

    @Test
    public void testServiceHandlesIOErrors()
            throws IOException, ConfigFileNotFoundException, ConfigFileNotAllowedException {
        // Create a file and then make directory unreadable to trigger IO errors
        Path readOnlyFile = testConfigDir.resolve("readonly.cfg");
        Files.writeString(readOnlyFile, "test", StandardOpenOption.CREATE);

        when(configurationService.getArrayProperty("config.admin.updateable.files"))
            .thenReturn(new String[]{"readonly.cfg"});

        // Make file unreadable by changing parent directory permissions
        // Note: This test might be platform-specific
        testConfigDir.toFile().setReadable(false);

        try {
            configFileService.readConfigFile("readonly.cfg");
            // If we get here, either the platform doesn't support the permission change
            // or the operation succeeded despite our attempt to make it fail
            // In either case, we can't test the IO error scenario
        } catch (IOException e) {
            // Expected - IO error occurred as intended
            assertTrue("Error message should mention file name",
                      e.getMessage().contains("readonly.cfg"));
        } finally {
            // Restore permissions for cleanup
            testConfigDir.toFile().setReadable(true);
        }
    }

    /**
     * Helper method to count backup files for a given config file
     */
    private long countBackupFiles(String configFileName) throws IOException {
        return Files.list(testConfigDir)
                   .filter(p -> p.getFileName().toString().startsWith(configFileName + ".backup."))
                   .count();
    }
}
