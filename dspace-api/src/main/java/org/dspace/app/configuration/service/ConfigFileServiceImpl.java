/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.configuration.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.dspace.app.configuration.exception.ConfigFileNotAllowedException;
import org.dspace.app.configuration.exception.ConfigFileNotFoundException;
import org.dspace.app.configuration.exception.ConfigFileUpdateException;
import org.dspace.services.ConfigurationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Implementation of ConfigFileService for managing DSpace configuration files
 *
 * This service provides secure access to configuration files with:
 * - File access validation based on configuration
 * - Automatic backup creation before updates
 * - Path traversal attack prevention
 * - Proper error handling and logging
 *
 * @author Milan Majchrak (dspace at dataquest.sk)
 */
@Service
public class ConfigFileServiceImpl implements ConfigFileService {

    private static final Logger log = LogManager.getLogger(ConfigFileServiceImpl.class);

    @Autowired
    private ConfigurationService configurationService;

    /**
     * Get list of configuration files that are allowed to be managed via API.
     * Files are defined in the 'config.admin.updateable.files' configuration property.
     */
    @Override
    public List<String> getAllowedConfigFiles() {
        String[] allowedFiles = configurationService.getArrayProperty("config.admin.updateable.files");

        if (allowedFiles == null || allowedFiles.length == 0) {
            log.warn("No configuration files are allowed for API access. " +
                    "Configure 'config.admin.updateable.files' property to enable file management.");
            return List.of();
        }

        return Arrays.stream(allowedFiles)
                    .map(String::trim)
                    .filter(StringUtils::isNotBlank)
                    .collect(Collectors.toList());
    }

    /**
     * Check if a given file is allowed to be accessed via the API
     */
    protected boolean isFileAllowed(String fileName) {
        List<String> allowedFiles = getAllowedConfigFiles();
        boolean isAllowed = allowedFiles.contains(fileName);

        if (!isAllowed) {
            log.debug("File '{}' is not in the list of allowed files: {}", fileName, allowedFiles);
        }

        return isAllowed;
    }

    /**
     * Get the full path to the config directory
     */
    protected Path getConfigDirectory() {
        String dspaceDir = configurationService.getProperty("dspace.dir");
        if (StringUtils.isBlank(dspaceDir)) {
            throw new IllegalStateException("DSpace directory not configured (dspace.dir property is missing)");
        }

        Path configDir = Paths.get(dspaceDir, "config");
        log.debug("Using config directory: {}", configDir);

        return configDir;
    }

    /**
     * Get the full path to a configuration file
     */
    protected Path getConfigFilePath(String fileName) {
        return getConfigDirectory().resolve(fileName);
    }

    @Override
    public void validateFileAccess(String fileName)
            throws ConfigFileNotFoundException, ConfigFileNotAllowedException {

        // Check for null or empty filename
        if (StringUtils.isBlank(fileName)) {
            throw new ConfigFileNotAllowedException("File name cannot be null or empty");
        }

        // Check if file is in allowed list
        if (!isFileAllowed(fileName)) {
            log.warn("Attempt to access non-allowed configuration file: {}", fileName);
            throw new ConfigFileNotAllowedException(
                "File '" + fileName + "' is not allowed for API access. " +
                "Configure 'config.admin.updateable.files' property to allow this file.");
        }

        Path filePath = getConfigFilePath(fileName);

        // Check if file exists
        if (!Files.exists(filePath)) {
            log.warn("Configuration file not found: {}", filePath);
            throw new ConfigFileNotFoundException("Configuration file '" + fileName + "' not found at: " + filePath);
        }

        // Security check: ensure the resolved path is still within the config directory
        Path configDir = getConfigDirectory().normalize();
        Path normalizedFilePath = filePath.normalize();

        if (!normalizedFilePath.startsWith(configDir)) {
            log.error("Path traversal attack detected! Requested file '{}' resolves to '{}' " +
                      "which is outside config directory '{}'", fileName, normalizedFilePath, configDir);
            throw new ConfigFileNotAllowedException("Invalid file path detected - path traversal attack prevented");
        }

        log.debug("File access validation passed for: {}", fileName);
    }

    @Override
    public String readConfigFile(String fileName)
            throws ConfigFileNotFoundException, ConfigFileNotAllowedException, IOException {

        validateFileAccess(fileName);
        Path filePath = getConfigFilePath(fileName);

        try {
            String content = Files.readString(filePath);
            log.debug("Successfully read configuration file '{}' ({} characters)", fileName, content.length());
            return content;
        } catch (IOException e) {
            log.error("Failed to read configuration file '{}': {}", fileName, e.getMessage(), e);
            throw new IOException("Failed to read configuration file '" + fileName + "': " + e.getMessage(), e);
        }
    }

    @Override
    public void writeConfigFile(String fileName, String content)
            throws ConfigFileNotFoundException, ConfigFileNotAllowedException,
                   ConfigFileUpdateException, IOException {

        validateFileAccess(fileName);
        Path filePath = getConfigFilePath(fileName);

        if (content == null) {
            throw new IllegalArgumentException("Content cannot be null");
        }

        try {
            // Create backup before updating (if file exists)
            Path backupPath = null;
            if (Files.exists(filePath)) {
                backupPath = createBackup(filePath);
                log.info("Created backup of '{}' at: {}", fileName, backupPath);
            }

            // Write the new content
            Files.writeString(filePath, content);

            log.info("Successfully updated configuration file '{}' ({} characters written)",
                    fileName, content.length());

        } catch (IOException e) {
            log.error("Failed to update configuration file '{}': {}", fileName, e.getMessage(), e);
            throw new ConfigFileUpdateException(
                "Failed to update configuration file '" + fileName + "': " + e.getMessage(), e);
        }
    }

    @Override
    public Path createBackup(Path filePath) throws IOException {
        if (!Files.exists(filePath)) {
            log.debug("No backup created - file does not exist: {}", filePath);
            return null;
        }

        // Create timestamped backup filename
        String timestamp = String.valueOf(System.currentTimeMillis());
        String fileName = filePath.getFileName().toString();
        String backupName = fileName + ".backup." + timestamp;
        Path backupPath = filePath.getParent().resolve(backupName);

        try {
            Files.copy(filePath, backupPath, StandardCopyOption.REPLACE_EXISTING);
            log.info("Configuration backup created: {} -> {}", filePath.getFileName(), backupPath.getFileName());
            return backupPath;

        } catch (IOException e) {
            log.error("Failed to create backup of '{}': {}", filePath, e.getMessage(), e);
            throw new IOException("Failed to create backup of '" + filePath + "': " + e.getMessage(), e);
        }
    }

    @Override
    public ConfigFileMetadata getFileMetadata(String fileName)
            throws ConfigFileNotFoundException, ConfigFileNotAllowedException {

        validateFileAccess(fileName);
        Path filePath = getConfigFilePath(fileName);

        try {
            Long size = Files.size(filePath);
            LocalDateTime lastModified = LocalDateTime.ofInstant(
                Files.getLastModifiedTime(filePath).toInstant(),
                ZoneId.systemDefault());
            Boolean readable = Files.isReadable(filePath);
            Boolean writable = Files.isWritable(filePath);

            ConfigFileMetadata metadata = new ConfigFileMetadata(
                fileName, filePath, size, lastModified, readable, writable);

            log.debug("Retrieved metadata for '{}': size={}, readable={}, writable={}",
                     fileName, size, readable, writable);

            return metadata;

        } catch (IOException e) {
            log.error("Error reading metadata for file '{}': {}", fileName, e.getMessage(), e);
            throw new ConfigFileNotFoundException(
                "Error reading metadata for file '" + fileName + "': " + e.getMessage(), e);
        }
    }
}
