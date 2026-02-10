/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.model;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.dspace.app.rest.ConfigFileRestController;

/**
 * The Configuration File REST Resource
 *
 * @author Milan Majchrak (dspace at dataquest.sk)
 */
public class ConfigFileRest extends BaseObjectRest<String> {
    public static final String NAME = "configfile";
    public static final String PLURAL_NAME = "configfiles";
    public static final String CATEGORY = RestModel.CONFIGURATION;

    @JsonProperty("name")
    private String name;

    @JsonProperty("path")
    private String path;

    @JsonProperty("size")
    private Long size;

    @JsonProperty("lastModified")
    private LocalDateTime lastModified;

    @JsonProperty("readable")
    private Boolean readable;

    @JsonProperty("writable")
    private Boolean writable;

    @Override
    public String getCategory() {
        return CATEGORY;
    }

    @Override
    public Class getController() {
        return ConfigFileRestController.class;
    }

    @Override
    public String getType() {
        return NAME;
    }

    @Override
    public String getTypePlural() {
        return PLURAL_NAME;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public Long getSize() {
        return size;
    }

    public void setSize(Long size) {
        this.size = size;
    }

    public LocalDateTime getLastModified() {
        return lastModified;
    }

    public void setLastModified(LocalDateTime lastModified) {
        this.lastModified = lastModified;
    }

    public Boolean getReadable() {
        return readable;
    }

    public void setReadable(Boolean readable) {
        this.readable = readable;
    }

    public Boolean getWritable() {
        return writable;
    }

    public void setWritable(Boolean writable) {
        this.writable = writable;
    }
}