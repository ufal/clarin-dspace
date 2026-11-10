/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.hf;

/**
 * Plain Jackson POJO representing a single entry of the {@code siblings} array
 * returned by the HuggingFace Hub {@code model_info} API. Each sibling corresponds
 * to a single file that belongs to the "model repository" (in our case, an ORIGINAL
 * bitstream of a DSpace Item).
 *
 * @author DSpace at UFAL
 */
public class HuggingFaceSibling {

    private String rfilename;

    /**
     * Default no-arg constructor required for Jackson (de)serialization.
     */
    public HuggingFaceSibling() {
    }

    /**
     * Construct a sibling entry for the given filename.
     *
     * @param rfilename the repository-relative filename
     */
    public HuggingFaceSibling(String rfilename) {
        this.rfilename = rfilename;
    }

    /**
     * Get the repository-relative filename.
     *
     * @return the filename
     */
    public String getRfilename() {
        return rfilename;
    }

    /**
     * Set the repository-relative filename.
     *
     * @param rfilename the filename
     */
    public void setRfilename(String rfilename) {
        this.rfilename = rfilename;
    }
}
