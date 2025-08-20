/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.dspace.app.rest.RestResourceController;

/**
 * The Epic Handle REST Resource
 *
 * @author Milan Kuchtiak
 */
public class EpicHandleRest extends BaseObjectRest<String> {

    public static final String NAME = "epichandle";
    public static final String CATEGORY = RestAddressableModel.CORE;
    public static final String URI_PREFIX = "/api/" + CATEGORY + "/" + NAME;
    public static final String URI_PREFIX_PLURAL = URI_PREFIX + "s";

    private String url;

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    @Override
    // @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @JsonIgnore
    public String getType() {
        return NAME;
    }

    @Override
    public Class getController() {
        return RestResourceController.class;
    }

    @Override
    public String getCategory() {
        return CATEGORY;
    }
}
