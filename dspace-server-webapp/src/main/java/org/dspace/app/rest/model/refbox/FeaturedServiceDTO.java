/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.model.refbox;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * DTO for featured services in the reference box.
 * This class represents the featured service details including its name, URL, description, and links.
 * @author Milan Majchrak (dspace at dataquest.sk)
 */
public class FeaturedServiceDTO implements Serializable {
    private String name;
    private String url;
    private String description;
    private Map<String, List<FeaturedServiceLinkDTO>> links;

    public FeaturedServiceDTO(String name, String url, String description, Map<String,
            List<FeaturedServiceLinkDTO>> links) {
        this.name = name;
        this.url = url;
        this.description = description;
        this.links = links;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Map<String, List<FeaturedServiceLinkDTO>> getLinks() {
        return links;
    }

    public void setLinks(Map<String, List<FeaturedServiceLinkDTO>> links) {
        this.links = links;
    }
}
