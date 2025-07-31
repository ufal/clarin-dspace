/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.dspace.app.rest.RestResourceController;

/**
 * The MatomoReportSubscription REST Resource
 *
 * @author Milan Kuchtiak
 */
public class MatomoReportSubscriptionRest extends BaseObjectRest<Integer> {

    public static final String NAME = "matomoreportsubscription";
    public static final String CATEGORY = RestAddressableModel.CORE;
    private static final String URI_PREFIX = "/api/" + CATEGORY + "/" + NAME;
    public static final String URI_SINGLE = URI_PREFIX + "/item/{itemId}";
    public static final String URI_PLURAL = URI_PREFIX + "s/item/{itemId}";

    private String epersonId;
    private String itemId;

    public String getEpersonId() {
        return epersonId;
    }

    public void setEpersonId(String epersonId) {
        this.epersonId = epersonId;
    }

    public String getItemId() {
        return itemId;
    }

    public void setItemId(String itemId) {
        this.itemId = itemId;
    }

    @Override
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
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
