/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.hf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Plain Jackson POJO representing the {@code GET /api/models/{repo}/refs} response of the
 * HuggingFace Hub API.
 *
 * <p>DSpace Items have no branches, tags or pull requests, so {@code converts} and
 * {@code tags} are always empty and {@code branches} always holds the single synthetic
 * {@code main} branch.</p>
 *
 * @author DSpace at UFAL
 */
public class HuggingFaceRefs {

    private List<HuggingFaceRef> branches = new ArrayList<>();

    private List<HuggingFaceRef> converts = Collections.emptyList();

    private List<HuggingFaceRef> tags = Collections.emptyList();

    /**
     * Default no-arg constructor required for Jackson (de)serialization.
     */
    public HuggingFaceRefs() {
    }

    /**
     * Construct a refs response advertising a single {@code main} branch at the given revision.
     *
     * @param sha the Item's current revision sha; becomes the branch's {@code targetCommit}
     * @return the refs response
     */
    public static HuggingFaceRefs mainOnly(String sha) {
        HuggingFaceRefs refs = new HuggingFaceRefs();
        refs.branches.add(new HuggingFaceRef("main", "refs/heads/main", sha));
        return refs;
    }

    /**
     * Get the branches of this repository.
     *
     * @return the branches
     */
    public List<HuggingFaceRef> getBranches() {
        return branches;
    }

    /**
     * Set the branches of this repository.
     *
     * @param branches the branches
     */
    public void setBranches(List<HuggingFaceRef> branches) {
        this.branches = branches;
    }

    /**
     * Get the conversion refs of this repository; always empty.
     *
     * @return the converts
     */
    public List<HuggingFaceRef> getConverts() {
        return converts;
    }

    /**
     * Set the conversion refs of this repository.
     *
     * @param converts the converts
     */
    public void setConverts(List<HuggingFaceRef> converts) {
        this.converts = converts;
    }

    /**
     * Get the tags of this repository; always empty.
     *
     * @return the tags
     */
    public List<HuggingFaceRef> getTags() {
        return tags;
    }

    /**
     * Set the tags of this repository.
     *
     * @param tags the tags
     */
    public void setTags(List<HuggingFaceRef> tags) {
        this.tags = tags;
    }
}
