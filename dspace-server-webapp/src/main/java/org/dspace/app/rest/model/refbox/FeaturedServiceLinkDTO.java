/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.model.refbox;

import java.io.Serializable;

/**
 * DTO for links in the featured services of the reference box.
 * This class represents a link with a key and value, typically used to
 * represent a service link in the featured services section.
 * @author Milan Majchrak (dspace at dataquest.sk)
 */
public class FeaturedServiceLinkDTO implements Serializable {
    private String key;
    private String value;

    public FeaturedServiceLinkDTO(String key, String value) {
        this.key = key;
        this.value = value;
    }

    public String getKey() {
        return key;
    }
    public void setKey(String key) {
        this.key = key;
    }

    public String getValue() {
        return value;
    }
    public void setValue(String value) {
        this.value = value;
    }
}
