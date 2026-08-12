/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.hf;

/**
 * Plain Jackson POJO representing a single git ref (branch, tag or convert) of the
 * {@code GET /api/models/{repo}/refs} response of the HuggingFace Hub API.
 *
 * <p>A DSpace Item has no git history, so the facade advertises exactly one branch,
 * {@code main}, whose {@code targetCommit} is the Item's synthetic revision sha. Clients
 * feed that value straight back as the revision of subsequent {@code tree} and
 * {@code resolve} calls, so it must be a revision the facade accepts.</p>
 *
 * @author DSpace at UFAL
 */
public class HuggingFaceRef {

    private String name;

    private String ref;

    private String targetCommit;

    /**
     * Default no-arg constructor required for Jackson (de)serialization.
     */
    public HuggingFaceRef() {
    }

    /**
     * Construct a ref.
     *
     * @param name         the short ref name (e.g. {@code "main"})
     * @param ref          the fully qualified ref (e.g. {@code "refs/heads/main"})
     * @param targetCommit the 40-character hex commit the ref points at
     */
    public HuggingFaceRef(String name, String ref, String targetCommit) {
        this.name = name;
        this.ref = ref;
        this.targetCommit = targetCommit;
    }

    /**
     * Get the short ref name.
     *
     * @return the ref name
     */
    public String getName() {
        return name;
    }

    /**
     * Set the short ref name.
     *
     * @param name the ref name
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * Get the fully qualified ref.
     *
     * @return the fully qualified ref
     */
    public String getRef() {
        return ref;
    }

    /**
     * Set the fully qualified ref.
     *
     * @param ref the fully qualified ref
     */
    public void setRef(String ref) {
        this.ref = ref;
    }

    /**
     * Get the commit this ref points at.
     *
     * @return the 40-character hex commit
     */
    public String getTargetCommit() {
        return targetCommit;
    }

    /**
     * Set the commit this ref points at.
     *
     * @param targetCommit the 40-character hex commit
     */
    public void setTargetCommit(String targetCommit) {
        this.targetCommit = targetCommit;
    }
}
