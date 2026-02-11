/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */

package org.dspace.app.rest.utils;

import static com.lyncode.xoai.dataprovider.core.Granularity.Second;
import static org.dspace.xoai.util.ItemUtils.retrieveMetadata;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import javax.xml.stream.XMLStreamException;

import com.lyncode.xoai.dataprovider.exceptions.WritingXmlException;
import com.lyncode.xoai.dataprovider.xml.XmlOutputContext;
import com.lyncode.xoai.dataprovider.xml.xoai.Metadata;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;
import org.dspace.authorize.ResourcePolicy;
import org.dspace.authorize.factory.AuthorizeServiceFactory;
import org.dspace.authorize.service.AuthorizeService;
import org.dspace.content.Bitstream;
import org.dspace.content.Bundle;
import org.dspace.content.Collection;
import org.dspace.content.Community;
import org.dspace.content.DSpaceObject;
import org.dspace.content.Item;
import org.dspace.content.MetadataField;
import org.dspace.content.MetadataValue;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.ItemService;
import org.dspace.core.Constants;
import org.dspace.core.Context;
import org.dspace.event.Event;
import org.dspace.util.SolrUtils;
import org.dspace.utils.DSpace;
import org.dspace.xoai.app.BasicConfiguration;
import org.dspace.xoai.app.XOAIExtensionItemCompilePlugin;
import org.dspace.xoai.services.api.CollectionsService;
import org.dspace.xoai.services.api.cache.XOAICacheService;
import org.dspace.xoai.services.api.cache.XOAIItemCacheService;
import org.dspace.xoai.services.api.solr.SolrServerResolver;
import org.dspace.xoai.solr.DSpaceSolrSearch;
import org.dspace.xoai.solr.exceptions.DSpaceSolrException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.stereotype.Component;


/**
 * Serves to reindex solr oai core after item has been added, modified or deleted.
 * Solr document creation (method index(Item item)) taken from XOAI.java.
 *
 * @author Marian Berger (marian.berger at dataquest.sk)
 */
@Component
public class SolrOAIReindexer {

    private static final Logger log = LogManager.getLogger(SolrOAIReindexer.class);

    private final XOAICacheService cacheService;
    private final XOAIItemCacheService itemCacheService;


    @Autowired
    private SolrServerResolver solrServerResolver;

    @Autowired
    private CollectionsService collectionsService;

    {
        AnnotationConfigApplicationContext applicationContext =
                new AnnotationConfigApplicationContext(BasicConfiguration.class);
        cacheService = applicationContext.getBean(XOAICacheService.class);
        itemCacheService = applicationContext.getBean(XOAIItemCacheService.class);

    }

    private final Context context = new Context(Context.Mode.READ_ONLY);
    private final ItemService itemService = ContentServiceFactory.getInstance().getItemService();
    private final AuthorizeService authorizeService = AuthorizeServiceFactory.getInstance()
            .getAuthorizeService();
    private final List<XOAIExtensionItemCompilePlugin> extensionPlugins = new DSpace().getServiceManager()
            .getServicesByType(XOAIExtensionItemCompilePlugin.class);

    private boolean isPublic(Item item) throws SQLException {
        // Check if READ access allowed on this Item
        return authorizeService.authorizeActionBoolean(context, item, Constants.READ);
    }


    private boolean checkIfVisibleInOAI(Item item) throws IOException {
        SolrQuery params = new SolrQuery("item.id:" + item.getID().toString()).addField("item.public");
        try {
            SolrDocumentList documents = DSpaceSolrSearch.query(solrServerResolver.getServer(), params);
            if (documents.getNumFound() == 1) {
                return (boolean) documents.get(0).getFieldValue("item.public");
            } else {
                return false;
            }
        } catch (DSpaceSolrException | SolrServerException e) {
            return false;
        }
    }

    private boolean checkIfIndexed(Item item) throws IOException {
        SolrQuery params = new SolrQuery("item.id:" + item.getID().toString()).addField("item.id");
        try {
            SolrDocumentList documents = DSpaceSolrSearch.query(solrServerResolver.getServer(), params);
            return documents.getNumFound() == 1;
        } catch (DSpaceSolrException | SolrServerException e) {
            return false;
        }
    }

    private boolean willChangeStatus(Item item) throws SQLException {
        List<ResourcePolicy> policies = authorizeService.getPoliciesActionFilter(context, item, Constants.READ);
        for (ResourcePolicy policy : policies) {
            if ((policy.getGroup() != null) && (policy.getGroup().getName().equals("Anonymous"))) {
                if (policy.getStartDate() != null && policy.getStartDate().after(new Date())) {
                    return true;
                }
                if (policy.getEndDate() != null && policy.getEndDate().after(new Date())) {
                    return true;
                }
            }
            context.uncacheEntity(policy);
        }
        return false;
    }

    /**
     * Taken from package org.dspace.xoai.app.XOAI.java together with associated methods.
     */
    private SolrInputDocument index(Item item) throws SQLException, IOException, XMLStreamException,
            WritingXmlException {
        SolrInputDocument doc = new SolrInputDocument();
        doc.addField("item.id", item.getID().toString());

        String handle = item.getHandle();
        doc.addField("item.handle", handle);

        boolean isEmbargoed = !isPublic(item);
        boolean isCurrentlyVisible = checkIfVisibleInOAI(item);
        boolean isIndexed = checkIfIndexed(item);

        /*
         * If the item is not under embargo, it should be visible. If it is,
         * make it invisible if this is the first time it is indexed. For
         * subsequent index runs, keep the current status, so that if the item
         * is embargoed again, it is flagged as deleted instead and does not
         * just disappear, or if it is still under embargo, it won't become
         * visible and be known to harvesters as deleted before it gets
         * disseminated for the first time. The item has to be indexed directly
         * after publication even if it is still embargoed, because its
         * lastModified date will not change when the embargo end date (or start
         * date) is reached. To circumvent this, an item which will change its
         * status in the future will be marked as such.
         */

        boolean isPublic = !isEmbargoed || (isIndexed && isCurrentlyVisible);
        doc.addField("item.public", isPublic);

        // if the visibility of the item will change in the future due to an
        // embargo, mark it as such.

        doc.addField("item.willChangeStatus", willChangeStatus(item));

        /*
         * Mark an item as deleted not only if it is withdrawn, but also if it
         * is made private, because items should not simply disappear from OAI
         * with a transient deletion policy. Do not set the flag for still
         * invisible embargoed items, because this will override the item.public
         * flag.
         */
        boolean discoverable = item.isDiscoverable();
        // The Item is not deleted when it has local metadata `local.hidden = hidden`.
        // Without this, the item is not discoverable and harvestable; however, it should be harvestable via OAI-PMH.
        if (!discoverable && item.isHidden()) {
            discoverable = true;
        }
        boolean isDeleted = item.isWithdrawn() || (!discoverable) || (isEmbargoed && isPublic);
        doc.addField("item.deleted", isDeleted);

        /*
         * An item that is embargoed will potentially not be harvested by
         * incremental harvesters if the from and until params do not encompass
         * both the standard lastModified date and the anonymous-READ resource
         * policy start date. The same is true for the end date, where
         * harvesters might not get a tombstone record. Therefore, consider all
         * relevant policy dates and the standard lastModified date and take the
         * most recent of those which have already passed.
         */
        doc.addField("item.lastmodified", SolrUtils.getDateFormatter()
                .format(getMostRecentModificationDate(item)));

        if (item.getSubmitter() != null) {
            doc.addField("item.submitter", item.getSubmitter().getEmail());
        }

        for (Collection col : item.getCollections()) {
            doc.addField("item.collections", "col_" + col.getHandle().replace("/", "_"));
        }
        for (Community com : collectionsService.flatParentCommunities(context, item)) {
            doc.addField("item.communities", "com_" + com.getHandle().replace("/", "_"));
        }

        List<MetadataValue> allData = itemService.getMetadata(item, Item.ANY, Item.ANY, Item.ANY, Item.ANY);
        for (MetadataValue dc : allData) {
            MetadataField field = dc.getMetadataField();
            String key = "metadata." + field.getMetadataSchema().getName() + "." + field.getElement();
            if (field.getQualifier() != null) {
                key += "." + field.getQualifier();
            }
            doc.addField(key, dc.getValue());
            if (dc.getAuthority() != null) {
                doc.addField(key + ".authority", dc.getAuthority());
                doc.addField(key + ".confidence", dc.getConfidence() + "");
            }
        }

        for (String f : getFileFormats(item)) {
            doc.addField("metadata.dc.format.mimetype", f);
        }


        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XmlOutputContext xmlContext = XmlOutputContext.emptyContext(out, Second);
        Metadata metadata = retrieveMetadata(context, item);

        // Do any additional metadata element, depends on the plugins
        for (XOAIExtensionItemCompilePlugin plugin : extensionPlugins) {
            metadata = plugin.additionalMetadata(context, metadata, item);
        }

        metadata.write(xmlContext);
        xmlContext.getWriter().flush();
        xmlContext.getWriter().close();
        doc.addField("item.compile", out.toString());

        return doc;
    }

    private List<String> getFileFormats(Item item) throws SQLException {
        List<String> formats = new ArrayList<>();

        for (Bundle b : itemService.getBundles(item, "ORIGINAL")) {
            for (Bitstream bs : b.getBitstreams()) {
                if (!formats.contains(bs.getFormat(context).getMIMEType())) {
                    formats.add(bs.getFormat(context).getMIMEType());
                }
            }
        }

        return formats;
    }

    private Date getMostRecentModificationDate(Item item) throws SQLException {
        List<Date> dates = new LinkedList<>();
        List<ResourcePolicy> policies = authorizeService.getPoliciesActionFilter(context, item, Constants.READ);
        for (ResourcePolicy policy : policies) {
            if ((policy.getGroup() != null) && (policy.getGroup().getName().equals("Anonymous"))) {
                if (policy.getStartDate() != null) {
                    dates.add(policy.getStartDate());
                }
                if (policy.getEndDate() != null) {
                    dates.add(policy.getEndDate());
                }
            }
            context.uncacheEntity(policy);
        }
        dates.add(item.getLastModified());
        Collections.sort(dates);
        Date now = new Date();
        Date lastChange = null;
        for (Date d : dates) {
            if (d.before(now)) {
                lastChange = d;
            }
        }
        return lastChange;
    }

    public void reindexItem(DSpaceObject item) {
        if (!(item instanceof Item)) {
            return;
        }
        reindexItem((Item) item);
    }

    public void reindexItem(Item item) {
        if (Objects.isNull(item.getHandle())) {
//            we cannot put such item into solr
            return;
        }
        try {
            // Before reindexing delete item
            deleteItemByQuery(item);
            SolrClient server = solrServerResolver.getServer();
            SolrInputDocument solrInput = index(item);
            server.add(solrInput);
            server.commit();

            // Try to clear caches safely, but don't fail if it doesn't work
            safeClearCaches(item);
            log.info("Successfully reindexed item ID: " + item.getID());
        } catch (IOException | XMLStreamException | SQLException | WritingXmlException | SolrServerException e) {
            log.warn("Direct reindexing failed for item ID: " + item.getID() + " because: " + e.getMessage() +
                    ". Attempting fallback reindexing via event.");

            boolean fallbackSuccessful = triggerReindexingViaEvent(item);

            if (fallbackSuccessful) {
                log.info("Fallback reindexing event triggered successfully for item ID: " + item.getID());
                // Still try to clear caches even after fallback
                safeClearCaches(item);
            } else {
                // Fallback also failed
                handleFinalFailure("Cannot reindex the item with ID: " + item.getID() + " because: " + e.getMessage() +
                        ". Fallback reindexing via event also failed.");
            }
        }
    }

    /**
     * Triggers reindexing through the event system as a fallback mechanism.
     * This will fire a MODIFY event which should be picked up by consumers that handle
     * indexing operations.
     *
     * @param item The item to trigger reindexing for
     * @return true if the event was successfully fired, false otherwise
     */
    protected boolean triggerReindexingViaEvent(Item item) {
        try (Context eventContext = new Context()) {
            eventContext.addEvent(new Event(Event.MODIFY, Constants.ITEM, item.getID(), null));
            eventContext.complete();
            log.info("Triggered fallback reindexing event for item ID: " + item.getID());
            return true;
        } catch (Exception e) {
            log.warn("Failed to trigger fallback reindexing event for item ID: " + item.getID() +
                    " - " + e.getMessage());
            return false;
        }
    }

    /**
     * Triggers deletion through the event system as a fallback mechanism.
     * This will fire a DELETE event which should be picked up by consumers that handle
     * indexing operations.
     *
     * @param item The item to trigger deletion for
     * @return true if the event was successfully fired, false otherwise
     */
    protected boolean triggerDeletionViaEvent(Item item) {
        try (Context eventContext = new Context()) {
            eventContext.addEvent(new Event(Event.DELETE, Constants.ITEM, item.getID(), null));
            eventContext.complete();
            log.info("Triggered fallback deletion event for item ID: " + item.getID());
            return true;
        } catch (Exception e) {
            log.warn("Failed to trigger fallback deletion event for item ID: " + item.getID() +
                    " - " + e.getMessage());
            return false;
        }
    }

    /**
     * Attempts to clear OAI caches safely. If cache clearing fails due to concurrent access,
     * it logs a warning but doesn't throw an exception to avoid breaking the workflow.
     *
     * @param item The item that was reindexed
     */
    private void safeClearCaches(Item item) {
        try {
            // Try to clear item-specific cache first (less likely to cause conflicts)
            if (itemCacheService != null) {
                itemCacheService.delete(item);
                log.debug("Cleared item-specific cache for item ID: " + item.getID());
            }
        } catch (Exception e) {
            log.warn("Failed to clear item-specific cache for item ID: " + item.getID() +
                     "(will refresh naturally): " + e.getMessage());
        }

        try {
            // Try to clear general OAI request cache (more likely to cause conflicts)
            if (cacheService != null && cacheService.isActive()) {
                cacheService.deleteAll();
                log.debug("Cleared OAI request cache after reindexing item ID: " + item.getID());
            }
        } catch (Exception e) {
            // This is expected to sometimes fail with "Device or resource busy"
            log.warn("Could not clear OAI request cache for item ID: " + item.getID() +
                    " (harvesters will get fresh data on cache miss): " + e.getMessage());
        }
    }

    public void deleteItem(Item item) {
        try {
            deleteItemByQuery(item);
            // Try to clear caches safely, but don't fail if it doesn't work
            safeClearCaches(item);

            log.info("Successfully deleted item from Solr index: " + item.getID());
        } catch (SolrServerException | IOException e) {
            // Before logging error and throwing exception, attempt fallback deletion via event
            log.warn("Direct deletion from Solr failed for item ID: " + item.getID() + " because: " + e.getMessage() +
                    ". Attempting fallback deletion via event.");

            boolean fallbackSuccessful = triggerDeletionViaEvent(item);

            if (fallbackSuccessful) {
                log.info("Fallback deletion event triggered successfully for item ID: " + item.getID());
                // Still try to clear caches even after fallback
                safeClearCaches(item);
            } else {
                // Fallback also failed
                handleFinalFailure("Cannot reindex the Solr after deleting the item with ID: " + item.getID() +
                        " because: " + e.getMessage() + ". Fallback deletion via event also failed.");
            }
        }
    }

    /**
     * Handles final failure when both direct Solr operation and event fallback fail.
     * This method is public to allow mocking in tests to avoid RuntimeExceptions during testing.
     *
     * @param message The error message describing the failure
     */
    public void handleFinalFailure(String message) {
        log.error(message);
        throw new RuntimeException(message);
    }

    /**
     * Delete the item from Solr by the ID of the item
     */
    private void deleteItemByQuery(Item item) throws SolrServerException, IOException {
        SolrClient solrClient = solrServerResolver.getServer();
        solrClient.deleteByQuery("item.id:" + item.getID().toString());
        solrClient.commit();
    }
}
