/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest;

import java.util.List;
import java.util.stream.Collectors;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.dspace.app.configuration.exception.ConfigFileNotAllowedException;
import org.dspace.app.configuration.exception.ConfigFileNotFoundException;
import org.dspace.app.configuration.service.ConfigFileService;
import org.dspace.app.rest.converter.ConverterService;
import org.dspace.app.rest.model.ConfigFileRest;
import org.dspace.app.rest.model.hateoas.ConfigFileResource;
import org.dspace.app.rest.utils.Utils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.rest.webmvc.ResourceNotFoundException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST Controller for managing DSpace configuration files
 *
 * Only administrators can access these endpoints
 *
 * @author Milan Majchrak (dspace at dataquest.sk)
 */
@RestController
@RequestMapping("/api/admin/configfiles")
@PreAuthorize("hasAuthority('ADMIN')")
public class ConfigFileRestController {

    private static final Logger log = LogManager.getLogger(ConfigFileRestController.class);

    @Autowired
    private ConfigFileService configFileService;

    @Autowired
    private Utils utils;

    @Autowired
    private ConverterService converter;

    /**
     * Get list of available configuration files
     *
     * Example:
     * <pre>
     * {@code
     * curl -X GET http://localhost:8080/server/api/admin/configfiles \
     *      -H "Authorization: Bearer [user-token]"
     * }
     * </pre>
     *
     * @param request HTTP request
     * @param response HTTP response
     * @return List of available configuration files
     */
    @RequestMapping(method = RequestMethod.GET)
    @PreAuthorize("hasAuthority('ADMIN')")
    public ResponseEntity<List<ConfigFileResource>> getConfigFiles(HttpServletRequest request,
                                                                   HttpServletResponse response) {
        try {
            List<String> allowedFiles = configFileService.getAllowedConfigFiles();
            List<ConfigFileResource> configFileResources = allowedFiles.stream()
                .map(fileName -> {
                    try {
                        ConfigFileService.ConfigFileMetadata metadata = configFileService.getFileMetadata(fileName);
                        ConfigFileRest configFileRest = convertToRest(metadata);
                        return converter.<ConfigFileResource>toResource(configFileRest);
                    } catch (Exception e) {
                        log.warn("Error getting metadata for file: {}", fileName, e);
                        ConfigFileRest basicRest = createBasicConfigFileRest(fileName);
                        return converter.<ConfigFileResource>toResource(basicRest);
                    }
                })
                .collect(Collectors.toList());

            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(configFileResources);

        } catch (Exception e) {
            log.error("Error retrieving configuration files list", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Get specific configuration file metadata
     *
     * Example:
     * <pre>
     * {@code
     * curl -X GET http://localhost:8080/server/api/admin/configfiles/dspace.cfg \
     *      -H "Authorization: Bearer [user-token]"
     * }
     * </pre>
     *
     * @param fileName Name of the configuration file
     * @param request HTTP request
     * @param response HTTP response
     * @return Configuration file metadata
     */
    @RequestMapping(method = RequestMethod.GET, value = "/{fileName:.+}")
    @PreAuthorize("hasAuthority('ADMIN')")
    public ResponseEntity<ConfigFileResource> getConfigFile(@PathVariable String fileName,
                                                          HttpServletRequest request,
                                                          HttpServletResponse response) {
        try {
            ConfigFileService.ConfigFileMetadata metadata = configFileService.getFileMetadata(fileName);
            ConfigFileRest configFileRest = convertToRest(metadata);
            ConfigFileResource resource = converter.toResource(configFileRest);

            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(resource);

        } catch (ConfigFileNotFoundException e) {
            throw new ResourceNotFoundException("Configuration file not found: " + fileName);
        } catch (ConfigFileNotAllowedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        } catch (Exception e) {
            log.error("Error retrieving configuration file: {}", fileName, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Get configuration file content as plain text
     *
     * Example:
     * <pre>
     * {@code
     * curl -X GET http://localhost:8080/server/api/admin/configfiles/dspace.cfg/content \
     *      -H "Authorization: Bearer [user-token]"
     * }
     * </pre>
     *
     * @param fileName Name of the configuration file
     * @param request HTTP request
     * @param response HTTP response
     * @return Configuration file content as plain text
     */
    @RequestMapping(method = RequestMethod.GET, value = "/{fileName:.+}/content")
    @PreAuthorize("hasAuthority('ADMIN')")
    public ResponseEntity<String> getConfigFileContent(@PathVariable String fileName,
                                                     HttpServletRequest request,
                                                     HttpServletResponse response) {
        try {
            String content = configFileService.readConfigFile(fileName);

            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN_VALUE)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + fileName + "\"")
                .body(content);

        } catch (ConfigFileNotFoundException e) {
            throw new ResourceNotFoundException("Configuration file not found: " + fileName);
        } catch (ConfigFileNotAllowedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        } catch (Exception e) {
            log.error("Error reading configuration file: {}", fileName, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Update configuration file content
     *
     * Creates a backup before updating the file
     *
     * Example:
     * <pre>
     * {@code
     * curl -X PUT http://localhost:8080/server/api/admin/configfiles/local.cfg/content \
     *      -H "Authorization: Bearer [user-token]" \
     *      -H "Content-Type: text/plain" \
     *      -d "# Updated configuration
     *          dspace.name = My Updated DSpace Instance"
     * }
     * </pre>
     *
     * @param fileName Name of the configuration file
     * @param content New file content
     * @param request HTTP request
     * @param response HTTP response
     * @return Success response
     */
    @RequestMapping(method = RequestMethod.PUT, value = "/{fileName:.+}/content")
    @PreAuthorize("hasAuthority('ADMIN')")
    public ResponseEntity<String> updateConfigFileContent(@PathVariable String fileName,
                                                        @RequestBody String content,
                                                        HttpServletRequest request,
                                                        HttpServletResponse response) {
        try {
            configFileService.writeConfigFile(fileName, content);

            log.info("Configuration file '{}' updated by user", fileName);

            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body("{\"message\":\"Configuration file updated successfully\",\"file\":\"" + fileName + "\"}");

        } catch (ConfigFileNotFoundException e) {
            throw new ResourceNotFoundException("Configuration file not found: " + fileName);
        } catch (ConfigFileNotAllowedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body("{\"error\":\"Configuration file access not allowed\",\"file\":\"" + fileName + "\"}");
        } catch (Exception e) {
            log.error("Error updating configuration file: {}", fileName, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("{\"error\":\"Failed to update configuration file\",\"file\":\"" + fileName + "\"}");
        }
    }

    /**
     * Convert ConfigFileMetadata to ConfigFileRest
     */
    private ConfigFileRest convertToRest(ConfigFileService.ConfigFileMetadata metadata) {
        ConfigFileRest rest = new ConfigFileRest();
        rest.setId(metadata.getName());
        rest.setName(metadata.getName());
        rest.setPath(metadata.getPath().toString());
        rest.setSize(metadata.getSize());
        rest.setLastModified(metadata.getLastModified());
        rest.setReadable(metadata.getReadable());
        rest.setWritable(metadata.getWritable());
        return rest;
    }

    /**
     * Create a basic ConfigFileRest object (for error cases)
     */
    private ConfigFileRest createBasicConfigFileRest(String fileName) {
        ConfigFileRest rest = new ConfigFileRest();
        rest.setId(fileName);
        rest.setName(fileName);
        rest.setReadable(false);
        rest.setWritable(false);
        return rest;
    }
}