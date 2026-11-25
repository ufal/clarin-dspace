/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.external.model.ror;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Location model representing the single location element from ROR API response.
 *
 * @author Milan Kuchtiak
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Location {
    private final int geonamesId;
    private final GeonamesDetails geonamesDetails;

    public Location(@JsonProperty("geonames_id") int id,
                    @JsonProperty("geonames_details") GeonamesDetails geonamesDetails) {
        this.geonamesId = id;
        this.geonamesDetails = geonamesDetails;
    }

    public int getGeonamesId() {
        return geonamesId;
    }

    public GeonamesDetails getGeonamesDetails() {
        return geonamesDetails;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GeonamesDetails {

        private final String name;
        private final String countrySubdivisionName;
        private final String countryCode;
        private final String countryName;
        private final String continentName;

        public GeonamesDetails(@JsonProperty("name") String name,
                               @JsonProperty("country_subdivision_name") String countrySubdivisionName,
                               @JsonProperty("country_code") String countryCode,
                               @JsonProperty("country_name") String countryName,
                               @JsonProperty("continent_name") String continentName) {
            this.name = name;
            this.countrySubdivisionName = countrySubdivisionName;
            this.countryCode = countryCode;
            this.countryName = countryName;
            this.continentName = continentName;
        }

        public String getName() {
            return name;
        }

        public String getCountrySubdivisionName() {
            return countrySubdivisionName;
        }

        public String getCountryCode() {
            return countryCode;
        }

        public String getCountryName() {
            return countryName;
        }

        public String getContinentName() {
            return continentName;
        }
    }

}
