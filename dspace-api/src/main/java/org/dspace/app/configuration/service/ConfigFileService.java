/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.configuration.service;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import org.dspace.app.configuration.exception.ConfigFileNotAllowedException;
import org.dspace.app.configuration.exception.ConfigFileNotFoundException;
import org.dspace.app.configuration.exception.ConfigFileUpdateException;

/**
 * Service interface for managing DSpace configuration files
 *
 * This service provides secure access to configuration files with proper
 * validation and backup functionality.
 *
 * @author Milan Majchrak (dspace at dataquest.sk)
 */
public interface ConfigFileService {

    /**
     * Get list of configuration files that are allowed to be managed via API
     *
     * @return List of allowed configuration file names
     */
    List<String> getAllowedConfigFiles();

    /**
     * Validate if a file can be accessed via the API
     *
     * @param fileName Name of the file to validate
     * @throws ConfigFileNotFoundException if file doesn't exist
     * @throws ConfigFileNotAllowedException if file access is not permitted
     */
    void validateFileAccess(String fileName)
            throws ConfigFileNotFoundException, ConfigFileNotAllowedException;

    /**
     * Read the contents of a configuration file
     *
     * @param fileName Name of the configuration file
     * @return File contents as string
     * @throws ConfigFileNotFoundException if file doesn't exist
     * @throws ConfigFileNotAllowedException if file access is not permitted
     * @throws IOException if file cannot be read
     */
    String readConfigFile(String fileName)
            throws ConfigFileNotFoundException, ConfigFileNotAllowedException, IOException;

    /**
     * Write new contents to a configuration file
     *
     * Creates an automatic backup before updating the file
     *
     * @param fileName Name of the configuration file
     * @param content New file content
     * @throws ConfigFileNotFoundException if file doesn't exist
     * @throws ConfigFileNotAllowedException if file access is not permitted
     * @throws ConfigFileUpdateException if update operation fails
     * @throws IOException if file cannot be written
     */
    void writeConfigFile(String fileName, String content)
            throws ConfigFileNotFoundException, ConfigFileNotAllowedException,
                   ConfigFileUpdateException, IOException;

    /**
     * Create a backup copy of a configuration file
     *
     * @param filePath Path to the file to backup
     * @return Path to the backup file, or null if no backup was created
     * @throws IOException if backup cannot be created
     */
    Path createBackup(Path filePath) throws IOException;

    /**
     * Get metadata information about a configuration file
     * 
     * @param fileName Name of the configuration file
     * @return ConfigFileMetadata object with file information
     * @throws ConfigFileNotFoundException if file doesn't exist
     * @throws ConfigFileNotAllowedException if file access is not permitted
     */
    ConfigFileMetadata getFileMetadata(String fileName)
            throws ConfigFileNotFoundException, ConfigFileNotAllowedException;

    /**
     * Configuration file metadata container
     */
    class ConfigFileMetadata {
        private final String name;
        private final Path path;
        private final Long size;
        private final LocalDateTime lastModified;
        private final Boolean readable;
        private final Boolean writable;

        public ConfigFileMetadata(String name, Path path, Long size,
                                LocalDateTime lastModified, Boolean readable, Boolean writable) {
            this.name = name;
            this.path = path;
            this.size = size;
            this.lastModified = lastModified;
            this.readable = readable;
            this.writable = writable;
        }

        public String getName() {
            return name;
        }

        public Path getPath() {
            return path;
        }

        public Long getSize() {
            return size;
        }

        public LocalDateTime getLastModified() {
            return lastModified;
        }

        public Boolean getReadable() {
            return readable;
        }

        public Boolean getWritable() {
            return writable;
        }
    }
}