/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.model.step;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonProperty.Access;

/**
 * Java Bean to expose the CLARIN license section during in progress submission.
 *
 * @author Milan Majchrak (milan.majchrak at dataquest.sk)
 */
public class ClarinDataLicense implements SectionData {

    private String name;

    @JsonProperty(access = Access.READ_ONLY)
    private String definition;

    @JsonProperty(access = Access.READ_ONLY)
    private String label;

    private boolean granted = false;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDefinition() {
        return definition;
    }

    public void setDefinition(String definition) {
        this.definition = definition;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public boolean isGranted() {
        return granted;
    }

    public void setGranted(boolean granted) {
        this.granted = granted;
    }
}