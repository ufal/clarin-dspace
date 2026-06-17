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
 * ROR items model representing the ROR API response.
 *
 * @author Milan Kuchtiak
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RorItems {

    private final int noOfResults;
    private final int timeTaken;
    private final List<RorItem> items;

    @JsonCreator()
    public RorItems(@JsonProperty("number_of_results") int noOfResults,
                    @JsonProperty("time_taken") int timeTaken,
                    @JsonProperty("items") List<RorItem> items
    ) {
        this.noOfResults = noOfResults;
        this.timeTaken = timeTaken;
        this.items = items;
    }

    public int getNoOfResults() {
        return noOfResults;
    }

    public int getTimeTaken() {
        return timeTaken;
    }

    public List<RorItem> getItems() {
        return items;
    }
}
