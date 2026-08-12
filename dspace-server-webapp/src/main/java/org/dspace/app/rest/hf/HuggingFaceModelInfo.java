/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.hf;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Plain Jackson POJO modeling the JSON shape expected by huggingface_hub's
 * {@code model_info} response (as consumed by the {@code huggingface_hub} Python
 * client library). This class is intentionally decoupled from the DSpace REST
 * HAL/RestRepository machinery: it is a simple data holder that is serialized
 * directly to JSON by the (future) HuggingFace-facade controller.
 *
 * @author DSpace at UFAL
 */
public class HuggingFaceModelInfo {

    private String id;

    private String modelId;

    private String author;

    private String sha;

    private String lastModified;

    private String createdAt;

    @JsonProperty("private")
    private boolean isPrivate;

    private boolean gated;

    private boolean disabled;

    private long downloads;

    private long likes;

    private List<String> tags;

    private Map<String, Object> cardData;

    private List<HuggingFaceSibling> siblings;

    /**
     * Default no-arg constructor required for Jackson (de)serialization.
     */
    public HuggingFaceModelInfo() {
    }

    /**
     * Get the repository id (in our case, the Item handle).
     *
     * @return the repository id
     */
    public String getId() {
        return id;
    }

    /**
     * Set the repository id.
     *
     * @param id the repository id
     */
    public void setId(String id) {
        this.id = id;
    }

    /**
     * Get the model id (in our case, the same value as {@link #getId()}).
     *
     * @return the model id
     */
    public String getModelId() {
        return modelId;
    }

    /**
     * Set the model id.
     *
     * @param modelId the model id
     */
    public void setModelId(String modelId) {
        this.modelId = modelId;
    }

    /**
     * Get the author (in our case, the handle prefix).
     *
     * @return the author
     */
    public String getAuthor() {
        return author;
    }

    /**
     * Set the author.
     *
     * @param author the author
     */
    public void setAuthor(String author) {
        this.author = author;
    }

    /**
     * Get the sha (revision) of the repository content.
     *
     * @return the sha
     */
    public String getSha() {
        return sha;
    }

    /**
     * Set the sha (revision) of the repository content.
     *
     * @param sha the sha
     */
    public void setSha(String sha) {
        this.sha = sha;
    }

    /**
     * Get the last modification timestamp, as an ISO-8601 string.
     *
     * @return the last modification timestamp
     */
    public String getLastModified() {
        return lastModified;
    }

    /**
     * Set the last modification timestamp.
     *
     * @param lastModified the last modification timestamp, as an ISO-8601 string
     */
    public void setLastModified(String lastModified) {
        this.lastModified = lastModified;
    }

    /**
     * Get the creation timestamp, as an ISO-8601 string.
     *
     * @return the creation timestamp
     */
    public String getCreatedAt() {
        return createdAt;
    }

    /**
     * Set the creation timestamp.
     *
     * @param createdAt the creation timestamp, as an ISO-8601 string
     */
    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    /**
     * Whether the repository is private. Always {@code false} for items exposed by this facade.
     *
     * @return whether the repository is private
     */
    public boolean isPrivate() {
        return isPrivate;
    }

    /**
     * Set whether the repository is private.
     *
     * @param isPrivate whether the repository is private
     */
    public void setPrivate(boolean isPrivate) {
        this.isPrivate = isPrivate;
    }

    /**
     * Whether the repository is gated. Always {@code false} for items exposed by this facade.
     *
     * @return whether the repository is gated
     */
    public boolean isGated() {
        return gated;
    }

    /**
     * Set whether the repository is gated.
     *
     * @param gated whether the repository is gated
     */
    public void setGated(boolean gated) {
        this.gated = gated;
    }

    /**
     * Whether the repository is disabled. Always {@code false} for items exposed by this facade.
     *
     * @return whether the repository is disabled
     */
    public boolean isDisabled() {
        return disabled;
    }

    /**
     * Set whether the repository is disabled.
     *
     * @param disabled whether the repository is disabled
     */
    public void setDisabled(boolean disabled) {
        this.disabled = disabled;
    }

    /**
     * Get the number of downloads. Always {@code 0} for items exposed by this facade.
     *
     * @return the number of downloads
     */
    public long getDownloads() {
        return downloads;
    }

    /**
     * Set the number of downloads.
     *
     * @param downloads the number of downloads
     */
    public void setDownloads(long downloads) {
        this.downloads = downloads;
    }

    /**
     * Get the number of likes. Always {@code 0} for items exposed by this facade.
     *
     * @return the number of likes
     */
    public long getLikes() {
        return likes;
    }

    /**
     * Set the number of likes.
     *
     * @param likes the number of likes
     */
    public void setLikes(long likes) {
        this.likes = likes;
    }

    /**
     * Get the tags associated with this repository.
     *
     * @return the tags
     */
    public List<String> getTags() {
        return tags;
    }

    /**
     * Set the tags associated with this repository.
     *
     * @param tags the tags
     */
    public void setTags(List<String> tags) {
        this.tags = tags;
    }

    /**
     * Get the card data (metadata block normally found in the model card's YAML front matter).
     *
     * @return the card data
     */
    public Map<String, Object> getCardData() {
        return cardData;
    }

    /**
     * Set the card data.
     *
     * @param cardData the card data
     */
    public void setCardData(Map<String, Object> cardData) {
        this.cardData = cardData;
    }

    /**
     * Get the list of files (siblings) belonging to this repository.
     *
     * @return the siblings
     */
    public List<HuggingFaceSibling> getSiblings() {
        return siblings;
    }

    /**
     * Set the list of files (siblings) belonging to this repository.
     *
     * @param siblings the siblings
     */
    public void setSiblings(List<HuggingFaceSibling> siblings) {
        this.siblings = siblings;
    }
}
