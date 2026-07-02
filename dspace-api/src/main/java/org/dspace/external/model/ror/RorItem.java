/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.external.model.ror;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * ROR item model representing the single item from ROR API response.
 *
 * @author Milan Kuchtiak
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RorItem {

    private final String id;
    private final List<Name> names;
    private final String status;
    private final String[] types;
    private final List<Location> locations;

    @JsonCreator()
    public RorItem(@JsonProperty("id") String id,
                   @JsonProperty("names") List<Name> names,
                   @JsonProperty("locations") List<Location> locations,
                   @JsonProperty("status") String status,
                   @JsonProperty("types") String[] types) {
        this.id = id;
        this.names = names;
        this.locations = locations;
        this.status = status;
        this.types = types;
    }

    public String getId() {
        return id;
    }

    public List<Name> getNames() {
        return names;
    }

    public List<Location> getLocations() {
        return locations;
    }

    public String getStatus() {
        return status;
    }

    public String[] getTypes() {
        return types;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Name {
        private String lang;
        private List<String> types;
        private String value;

        @JsonCreator()
        public Name(@JsonProperty("lang") String lang,
                    @JsonProperty("types") List<String> types,
                    @JsonProperty("value") String value) {
            this.lang = lang;
            this.types = types;
            this.value = value;
        }

        public String getLang() {
            return lang;
        }

        public List<String> getTypes() {
            return types;
        }

        public String getValue() {
            return value;
        }
    }

}
