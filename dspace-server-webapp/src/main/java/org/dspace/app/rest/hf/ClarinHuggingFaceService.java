/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.hf;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.dspace.authorize.AuthorizeException;
import org.dspace.authorize.service.AuthorizeService;
import org.dspace.content.Bitstream;
import org.dspace.content.Bundle;
import org.dspace.content.Collection;
import org.dspace.content.DSpaceObject;
import org.dspace.content.Item;
import org.dspace.content.MetadataValue;
import org.dspace.content.service.ItemService;
import org.dspace.core.Constants;
import org.dspace.core.Context;
import org.dspace.handle.service.HandleService;
import org.dspace.services.ConfigurationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Service backing the (future, Milestone 2) HuggingFace-Hub-compatible facade controller.
 *
 * <p>This service is responsible for:</p>
 * <ul>
 *     <li>deciding whether the facade is enabled at all ({@link #isEnabled()});</li>
 *     <li>deciding whether a given Item is allowed to be exposed through the facade
 *     ({@link #isExposed(Context, Item)});</li>
 *     <li>resolving a handle to an exposed Item ({@link #resolveExposedItem(Context, String)});</li>
 *     <li>computing a deterministic "sha" (revision) for an Item's ORIGINAL bitstreams
 *     ({@link #computeSha(String, long, Map)} / {@link #computeShaForItem(Context, Item)});</li>
 *     <li>validating a requested revision against the current sha ({@link #isValidRevision(String, String)});</li>
 *     <li>building the HuggingFace-Hub-shaped JSON model ({@link #toModelInfo(Context, Item, String)}) and
 *     file tree ({@link #toTreeEntries(Context, Item)}) for an Item.</li>
 * </ul>
 *
 * @author DSpace at UFAL
 */
@Component
public class ClarinHuggingFaceService {

    private static final Logger log = LogManager.getLogger(ClarinHuggingFaceService.class);

    private static final String PROP_ENABLED = "hf.api.enabled";

    private static final String PROP_FILTER_METADATA_FIELD = "hf.api.filter.metadata-field";

    private static final String PROP_FILTER_VALUE = "hf.api.filter.value";

    private static final String PROP_EXPOSED_COLLECTIONS = "hf.api.exposed.collections";

    private static final String DEFAULT_FILTER_METADATA_FIELD = "dc.type";

    private static final String MAIN_REVISION = "main";

    private static final int DEFAULT_LIST_LIMIT = 20;

    private static final int MAX_LIST_LIMIT = 100;

    /**
     * Guard so that the "no exposure filter configured" warning is only logged once per JVM lifetime,
     * instead of once per checked Item.
     */
    private static final AtomicBoolean NO_FILTER_CONFIGURED_WARNING_LOGGED = new AtomicBoolean(false);

    @Autowired
    private ConfigurationService configurationService;

    @Autowired
    private ItemService itemService;

    @Autowired
    private HandleService handleService;

    @Autowired
    private AuthorizeService authorizeService;

    /**
     * Master switch for the HuggingFace-Hub-compatible facade.
     *
     * @return {@code true} if the facade is enabled via {@code hf.api.enabled}, {@code false} otherwise
     */
    public boolean isEnabled() {
        return configurationService.getBooleanProperty(PROP_ENABLED, false);
    }

    /**
     * Fail-closed exposure filter deciding whether a given Item may be exposed through the HuggingFace facade.
     *
     * <p>An Item is exposed iff:</p>
     * <ul>
     *     <li>the item is non-null, archived and not withdrawn, AND</li>
     *     <li>either (a) the configured metadata field ({@code hf.api.filter.metadata-field}) has a value on
     *     the item equal to the configured filter value ({@code hf.api.filter.value}) -- only evaluated when
     *     both properties are non-blank -- or (b) the item's owning collection handle is listed in
     *     {@code hf.api.exposed.collections}.</li>
     * </ul>
     *
     * <p>If neither the metadata filter nor the collection allow-list is configured, a warning is logged
     * (once) and the method fails closed, returning {@code false}.</p>
     *
     * @param context the DSpace context
     * @param item    the item to check
     * @return {@code true} if the item may be exposed through the HuggingFace facade
     */
    public boolean isExposed(Context context, Item item) {
        if (item == null || item.isWithdrawn() || !item.isArchived()) {
            return false;
        }

        String filterMetadataField = configurationService.getProperty(PROP_FILTER_METADATA_FIELD,
                DEFAULT_FILTER_METADATA_FIELD);
        String filterValue = configurationService.getProperty(PROP_FILTER_VALUE);
        boolean filterConfigured = StringUtils.isNotBlank(filterMetadataField) && StringUtils.isNotBlank(filterValue);

        String[] exposedCollections = configurationService.getArrayProperty(PROP_EXPOSED_COLLECTIONS);
        boolean collectionsConfigured = exposedCollections != null && exposedCollections.length > 0;

        if (!filterConfigured && !collectionsConfigured) {
            if (NO_FILTER_CONFIGURED_WARNING_LOGGED.compareAndSet(false, true)) {
                log.warn("Neither '{}'/'{}' nor '{}' is configured; the HuggingFace facade will not expose "
                                + "any item.", PROP_FILTER_METADATA_FIELD, PROP_FILTER_VALUE,
                        PROP_EXPOSED_COLLECTIONS);
            }
            return false;
        }

        if (filterConfigured) {
            List<MetadataValue> values = itemService.getMetadataByMetadataString(item, filterMetadataField);
            for (MetadataValue value : values) {
                if (StringUtils.equals(filterValue, value.getValue())) {
                    return true;
                }
            }
        }

        if (collectionsConfigured) {
            Collection owningCollection = item.getOwningCollection();
            String ownerHandle = owningCollection != null ? owningCollection.getHandle() : null;
            if (ownerHandle != null) {
                for (String exposedHandle : exposedCollections) {
                    if (StringUtils.equals(ownerHandle, exposedHandle)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * Resolve a handle to an Item that is allowed to be exposed through the HuggingFace facade.
     *
     * @param context the DSpace context
     * @param handle  the handle to resolve (e.g. {@code "11234/1-5814"})
     * @return the exposed Item, or {@code null} if the handle does not resolve to an Item, or the resolved
     *     Item is not exposed
     * @throws SQLException if a database error occurs while resolving the handle
     */
    public Item resolveExposedItem(Context context, String handle) throws SQLException {
        DSpaceObject dso = handleService.resolveToObject(context, handle);
        if (!(dso instanceof Item)) {
            return null;
        }
        Item item = (Item) dso;
        return isExposed(context, item) ? item : null;
    }

    /**
     * Find items exposed through the HuggingFace facade, optionally restricted to items whose title
     * contains {@code search} (case-insensitively), and capped at {@code limit} results.
     *
     * <p>Candidates are collected -- deliberately without any Solr dependence -- from up to two sources,
     * which are merged and deduplicated by item UUID:</p>
     * <ul>
     *     <li>every item of every collection listed in {@code hf.api.exposed.collections}, resolved via
     *     {@link HandleService} and iterated with {@link ItemService#findByCollection(Context, Collection)};
     *     and</li>
     *     <li>the items whose configured filter metadata field ({@code hf.api.filter.metadata-field}) has
     *     the configured filter value ({@code hf.api.filter.value}), via
     *     {@link ItemService#findArchivedByMetadataField(Context, String, String, String, String)}.</li>
     * </ul>
     *
     * <p>Every candidate is (re-)checked against {@link #isExposed(Context, Item)}, fail-closed, which also
     * filters out withdrawn/non-archived items that may still be reachable through the collection path. Each
     * exposed candidate is additionally checked for {@code READ} authorization for the current context user
     * ({@link AuthorizeService#authorizeActionBoolean(Context, DSpaceObject, int)}) before it counts towards
     * {@code limit}, so that embargoed/private items are filtered out before pagination rather than after,
     * and are never returned to a user who could not otherwise read them.</p>
     *
     * @param context the DSpace context
     * @param search  an optional, case-insensitive substring to match against the item title (or name, if
     *                the item has no title); {@code null} or blank to not filter by title
     * @param limit   the maximum number of items to return; a non-positive value defaults to 20, and any
     *                value is capped at 100
     * @return the list of exposed, READ-authorized items matching {@code search}, capped at the effective
     *     limit; empty if neither exposure mechanism is configured, or no item is exposed, readable and
     *     matches
     * @throws SQLException if a database error occurs
     */
    public List<Item> findExposedItems(Context context, String search, int limit) throws SQLException {
        int effectiveLimit = limit <= 0 ? DEFAULT_LIST_LIMIT : Math.min(limit, MAX_LIST_LIMIT);

        LinkedHashMap<UUID, Item> candidates = new LinkedHashMap<>();

        String[] exposedCollections = configurationService.getArrayProperty(PROP_EXPOSED_COLLECTIONS);
        if (exposedCollections != null) {
            for (String handle : exposedCollections) {
                DSpaceObject dso = handleService.resolveToObject(context, handle);
                if (dso instanceof Collection) {
                    Iterator<Item> items = itemService.findByCollection(context, (Collection) dso);
                    while (items.hasNext()) {
                        Item item = items.next();
                        candidates.putIfAbsent(item.getID(), item);
                    }
                }
            }
        }

        String filterMetadataField = configurationService.getProperty(PROP_FILTER_METADATA_FIELD,
                DEFAULT_FILTER_METADATA_FIELD);
        String filterValue = configurationService.getProperty(PROP_FILTER_VALUE);
        if (StringUtils.isNotBlank(filterMetadataField) && StringUtils.isNotBlank(filterValue)) {
            String[] fieldParts = filterMetadataField.split("\\.");
            String schema = fieldParts.length > 0 ? fieldParts[0] : null;
            String element = fieldParts.length > 1 ? fieldParts[1] : null;
            String qualifier = fieldParts.length > 2 ? fieldParts[2] : null;
            try {
                Iterator<Item> items =
                        itemService.findArchivedByMetadataField(context, schema, element, qualifier, filterValue);
                while (items.hasNext()) {
                    Item item = items.next();
                    candidates.putIfAbsent(item.getID(), item);
                }
            } catch (AuthorizeException e) {
                log.warn("Unexpected AuthorizeException while listing HuggingFace-exposed items by metadata "
                        + "filter '{}' = '{}'.", filterMetadataField, filterValue, e);
            }
        }

        String searchLower = StringUtils.isNotBlank(search) ? search.toLowerCase(Locale.ROOT) : null;

        List<Item> result = new ArrayList<>();
        for (Item item : candidates.values()) {
            if (result.size() >= effectiveLimit) {
                break;
            }
            if (!isExposed(context, item)) {
                continue;
            }
            if (searchLower != null && !matchesSearch(item, searchLower)) {
                continue;
            }
            if (!authorizeService.authorizeActionBoolean(context, item, Constants.READ)) {
                continue;
            }
            result.add(item);
        }

        return result;
    }

    /**
     * Check whether an Item's title (or name, if it has no title) contains the given (already
     * lower-cased) search string.
     *
     * @param item        the item
     * @param searchLower the search string, already lower-cased
     * @return {@code true} if the item's title/name contains {@code searchLower}, case-insensitively
     */
    private boolean matchesSearch(Item item, String searchLower) {
        String title = itemService.getMetadataFirstValue(item, "dc", "title", null, Item.ANY);
        if (StringUtils.isBlank(title)) {
            title = item.getName();
        }
        return title != null && title.toLowerCase(Locale.ROOT).contains(searchLower);
    }

    /**
     * Compute a deterministic sha (revision id) for an Item, given its last-modified timestamp and the
     * filename-to-md5 map of its ORIGINAL bitstreams.
     *
     * <p>The result is a 40-character lowercase hex string (SHA-1), deterministic across JVM runs and
     * insensitive to the iteration order of {@code filenameToMd5}.</p>
     *
     * @param itemUuid           the item's UUID, as a string
     * @param lastModifiedMillis the item's last-modified timestamp, in epoch milliseconds
     * @param filenameToMd5      map of ORIGINAL bitstream filename to its MD5 checksum
     * @return the computed sha, a 40-character lowercase hex string
     */
    public static String computeSha(String itemUuid, long lastModifiedMillis, Map<String, String> filenameToMd5) {
        List<String> pairs = new ArrayList<>();
        for (Map.Entry<String, String> entry : filenameToMd5.entrySet()) {
            pairs.add(entry.getKey() + ":" + entry.getValue());
        }
        Collections.sort(pairs);
        String joined = String.join("\n", pairs);
        String input = itemUuid + "\n" + lastModifiedMillis + "\n" + joined;
        return DigestUtils.sha1Hex(input);
    }

    /**
     * Check whether a requested revision string is valid, given the current sha of an Item.
     *
     * @param revision   the requested revision (path parameter of the facade endpoints)
     * @param currentSha the current sha of the Item, as computed by {@link #computeSha(String, long, Map)}
     * @return {@code true} if {@code revision} equals {@code "main"} or equals {@code currentSha}
     *     (case-insensitively); {@code false} if {@code revision} is null or blank
     */
    public static boolean isValidRevision(String revision, String currentSha) {
        if (StringUtils.isBlank(revision)) {
            return false;
        }
        return MAIN_REVISION.equals(revision) || revision.equalsIgnoreCase(currentSha);
    }

    /**
     * Compute the current sha of an Item, based on its ORIGINAL bitstreams and last-modified timestamp.
     *
     * @param context the DSpace context
     * @param item    the item
     * @return the computed sha, a 40-character lowercase hex string
     */
    public String computeShaForItem(Context context, Item item) {
        Map<String, Bitstream> originalFiles = getOriginalFiles(item);
        Map<String, String> filenameToMd5 = new LinkedHashMap<>();
        for (Map.Entry<String, Bitstream> entry : originalFiles.entrySet()) {
            filenameToMd5.put(entry.getKey(), entry.getValue().getChecksum());
        }
        long lastModifiedMillis = item.getLastModified() != null ? item.getLastModified().getTime() : 0L;
        String itemUuid = item.getID().toString();
        return computeSha(itemUuid, lastModifiedMillis, filenameToMd5);
    }

    /**
     * Collect the filename-to-bitstream map of all bitstreams in the Item's ORIGINAL bundles, iterating
     * bundles then bitstreams in order. If several bitstreams share the same filename, the first one
     * encountered wins.
     *
     * @param item the item
     * @return a filename-to-bitstream map, preserving encounter order; empty if the item has no ORIGINAL
     *     bundle or the bundle(s) are empty
     */
    public LinkedHashMap<String, Bitstream> getOriginalFiles(Item item) {
        LinkedHashMap<String, Bitstream> result = new LinkedHashMap<>();
        List<Bundle> bundles = item.getBundles(Constants.CONTENT_BUNDLE_NAME);
        for (Bundle bundle : bundles) {
            for (Bitstream bitstream : bundle.getBitstreams()) {
                result.putIfAbsent(bitstream.getName(), bitstream);
            }
        }
        return result;
    }

    /**
     * Build the HuggingFace-Hub-shaped model info for an Item.
     *
     * @param context the DSpace context
     * @param item    the item
     * @param sha     the item's current sha, as computed by {@link #computeShaForItem(Context, Item)}
     * @return the model info
     */
    public HuggingFaceModelInfo toModelInfo(Context context, Item item, String sha) {
        HuggingFaceModelInfo modelInfo = new HuggingFaceModelInfo();

        String handle = item.getHandle();
        modelInfo.setId(handle);
        modelInfo.setModelId(handle);
        modelInfo.setAuthor(handleAuthor(handle));
        modelInfo.setSha(sha);

        LinkedHashSet<String> tags = new LinkedHashSet<>();
        addMetadataValues(tags, itemService.getMetadataByMetadataString(item, "dc.subject"));
        List<String> languages = metadataValues(itemService.getMetadataByMetadataString(item, "dc.language.iso"));
        tags.addAll(languages);
        addMetadataValues(tags, itemService.getMetadataByMetadataString(item, "dc.type"));
        modelInfo.setTags(new ArrayList<>(tags));

        Map<String, Object> cardData = new LinkedHashMap<>();
        List<MetadataValue> rights = itemService.getMetadataByMetadataString(item, "dc.rights");
        if (!rights.isEmpty()) {
            cardData.put("license", rights.get(0).getValue());
        }
        if (!languages.isEmpty()) {
            cardData.put("language", languages);
        }
        cardData.put("tags", new ArrayList<>(tags));
        modelInfo.setCardData(cardData);

        long lastModifiedMillis = item.getLastModified() != null ? item.getLastModified().getTime() : 0L;
        String lastModified = Instant.ofEpochMilli(lastModifiedMillis).toString();
        modelInfo.setLastModified(lastModified);
        modelInfo.setCreatedAt(resolveCreatedAt(item, lastModified));

        List<HuggingFaceSibling> siblings = new ArrayList<>();
        for (String filename : getOriginalFiles(item).keySet()) {
            siblings.add(new HuggingFaceSibling(filename));
        }
        modelInfo.setSiblings(siblings);

        modelInfo.setPrivate(false);
        modelInfo.setGated(false);
        modelInfo.setDisabled(false);
        modelInfo.setDownloads(0L);
        modelInfo.setLikes(0L);

        return modelInfo;
    }

    /**
     * Build the HuggingFace-Hub-shaped file tree for an Item, one entry per ORIGINAL bitstream.
     *
     * @param context the DSpace context
     * @param item    the item
     * @return the list of tree entries; empty if the item has no ORIGINAL bitstreams
     */
    public List<HuggingFaceTreeEntry> toTreeEntries(Context context, Item item) {
        List<HuggingFaceTreeEntry> entries = new ArrayList<>();
        for (Map.Entry<String, Bitstream> entry : getOriginalFiles(item).entrySet()) {
            HuggingFaceTreeEntry treeEntry = new HuggingFaceTreeEntry();
            treeEntry.setType("file");
            treeEntry.setPath(entry.getKey());
            treeEntry.setSize(entry.getValue().getSizeBytes());
            treeEntry.setOid(entry.getValue().getChecksum());
            entries.add(treeEntry);
        }
        return entries;
    }

    /**
     * Extract the author (handle prefix) from a handle.
     *
     * @param handle the handle, e.g. {@code "11234/1-5814"}
     * @return the handle prefix, e.g. {@code "11234"}, or the handle itself if it does not contain a slash
     */
    private String handleAuthor(String handle) {
        if (handle == null) {
            return null;
        }
        int slashIndex = handle.indexOf('/');
        return slashIndex >= 0 ? handle.substring(0, slashIndex) : handle;
    }

    /**
     * Resolve the "createdAt" timestamp of an Item from its {@code dc.date.accessioned} metadata, falling
     * back to the given last-modified timestamp if the metadata is missing or unparseable.
     *
     * @param item          the item
     * @param lastModified  the pre-computed, ISO-8601 last-modified timestamp to fall back on
     * @return the ISO-8601 createdAt timestamp
     */
    private String resolveCreatedAt(Item item, String lastModified) {
        List<MetadataValue> accessioned = itemService.getMetadataByMetadataString(item, "dc.date.accessioned");
        if (accessioned.isEmpty()) {
            return lastModified;
        }
        try {
            return Instant.parse(accessioned.get(0).getValue()).toString();
        } catch (RuntimeException e) {
            return lastModified;
        }
    }

    /**
     * Extract the {@link MetadataValue#getValue()} of each entry into a new list.
     *
     * @param values the metadata values
     * @return the extracted string values, in the same order
     */
    private List<String> metadataValues(List<MetadataValue> values) {
        List<String> result = new ArrayList<>();
        addMetadataValues(result, values);
        return result;
    }

    /**
     * Append the {@link MetadataValue#getValue()} of each entry to the given collection.
     *
     * @param target the collection to append to
     * @param values the metadata values
     */
    private void addMetadataValues(java.util.Collection<String> target, List<MetadataValue> values) {
        for (MetadataValue value : values) {
            target.add(value.getValue());
        }
    }
}
