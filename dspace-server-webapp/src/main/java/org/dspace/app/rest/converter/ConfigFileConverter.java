/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.converter;

import org.dspace.app.rest.model.ConfigFileRest;
import org.dspace.app.rest.model.hateoas.ConfigFileResource;
import org.dspace.app.rest.projection.Projection;
import org.dspace.app.rest.utils.Utils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Converter for ConfigFileRest to ConfigFileResource
 * 
 * @author Milan Majchrak (dspace at dataquest.sk)
 */
@Component
public class ConfigFileConverter implements DSpaceConverter<ConfigFileRest, ConfigFileResource> {

    @Autowired
    private Utils utils;

    @Override
    public ConfigFileResource convert(ConfigFileRest obj, Projection projection) {
        ConfigFileResource configFileResource = new ConfigFileResource(obj, utils);
        return configFileResource;
    }

    @Override
    public Class<ConfigFileRest> getModelClass() {
        return ConfigFileRest.class;
    }
}