/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.hf;

/**
 * Plain Jackson POJO representing a single entry returned by the HuggingFace Hub
 * "tree" endpoint (e.g. {@code GET /api/models/{repo_id}/tree/{revision}}). Each entry
 * describes one file present in the repository (in our case, an ORIGINAL bitstream of
 * a DSpace Item).
 *
 * @author DSpace at UFAL
 */
public class HuggingFaceTreeEntry {

    private String type;

    private String path;

    private long size;

    private String oid;

    /**
     * Default no-arg constructor required for Jackson (de)serialization.
     */
    public HuggingFaceTreeEntry() {
    }

    /**
     * Get the entry type, e.g. {@code "file"}.
     *
     * @return the entry type
     */
    public String getType() {
        return type;
    }

    /**
     * Set the entry type.
     *
     * @param type the entry type
     */
    public void setType(String type) {
        this.type = type;
    }

    /**
     * Get the repository-relative path of the file.
     *
     * @return the file path
     */
    public String getPath() {
        return path;
    }

    /**
     * Set the repository-relative path of the file.
     *
     * @param path the file path
     */
    public void setPath(String path) {
        this.path = path;
    }

    /**
     * Get the file size in bytes.
     *
     * @return the file size in bytes
     */
    public long getSize() {
        return size;
    }

    /**
     * Set the file size in bytes.
     *
     * @param size the file size in bytes
     */
    public void setSize(long size) {
        this.size = size;
    }

    /**
     * Get the object id (checksum) of the file, as reported by the git/LFS-like tree API.
     *
     * @return the object id
     */
    public String getOid() {
        return oid;
    }

    /**
     * Set the object id (checksum) of the file.
     *
     * @param oid the object id
     */
    public void setOid(String oid) {
        this.oid = oid;
    }
}
