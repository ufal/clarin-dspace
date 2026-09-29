/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.xoai.app;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.dspace.content.Item;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.ItemService;
import org.dspace.core.Constants;
import org.dspace.core.Context;
import org.dspace.eperson.EPerson;
import org.dspace.event.Consumer;
import org.dspace.event.Event;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * Keeps the OAI-PMH Solr index in sync with items that are installed, modified or deleted,
 * regardless of which code path made the change (REST API submission, batch/CLI item importer
 * via <code>bin/dspace import</code>, AIP/packager restore, etc.), by reacting to the standard
 * DSpace event system instead of requiring each call site to update the index itself. This
 * ensures e.g. a freshly batch-imported item is immediately resolvable via OAI-PMH (and
 * features built on top of it, such as <code>/api/core/refbox/citations</code>) without
 * requiring a manual <code>bin/dspace oai import</code> run.
 */
public class OAIConsumer implements Consumer {
    private static final Logger log = LogManager.getLogger(OAIConsumer.class);

    private ItemService itemService;
    private AnnotationConfigApplicationContext applicationContext;

    private Set<UUID> toIndex;
    private Set<UUID> toDelete;

    @Override
    public void initialize() throws Exception {
        itemService = ContentServiceFactory.getInstance().getItemService();
        applicationContext = new AnnotationConfigApplicationContext(BasicConfiguration.class);
    }

    @Override
    public void consume(Context ctx, Event event) throws Exception {

        if (event.getEventType() == Event.INSTALL) {
            System.out.println("Event type is INSTALL");
        }

        if (event.getSubjectType() != Constants.ITEM) {
            return;
        }
        if (toIndex == null) {
            toIndex = new LinkedHashSet<>();
        }
        if (toDelete == null) {
            toDelete = new LinkedHashSet<>();
        }

        if (event.getEventType() == Event.DELETE) {
            // the item is already (or about to be) removed from the database, so it can no
            // longer be looked up by id; the subject id is still available on the event though
            toIndex.remove(event.getSubjectID());
            toDelete.add(event.getSubjectID());
            return;
        }

        toIndex.add(event.getSubjectID());
        System.out.println("Added item with ID: " + event.getSubjectID() + " to toIndex set.");
        System.out.println("Current toIndex set: " + toIndex);
    }

    @Override
    public void end(Context ctx) throws Exception {
        try {
            if ((toIndex == null || toIndex.isEmpty()) && (toDelete == null || toDelete.isEmpty())) {
                return;
            }

            XOAI indexer = new XOAI(ctx, false, false);
            applicationContext.getAutowireCapableBeanFactory().autowireBean(indexer);

            // OAI-PMH is an anonymous-facing protocol, so item.public/item.deleted must reflect
            // what an anonymous harvester can see, not what the user who triggered this change
            // can see (e.g. an admin performing a REST update can always read an item, so
            // authorizeActionBoolean() would otherwise report every item as public regardless of
            // whether Anonymous actually has READ access). Temporarily switch the SAME context to
            // no current user for the indexing calls, then restore it. We deliberately reuse ctx
            // rather than opening a separate Context/DB connection here: events are dispatched
            // BEFORE the triggering context's transaction is committed (see Context#commit), so a
            // second connection could not see the not-yet-committed changes anyway.
            EPerson previousUser = ctx.getCurrentUser();
            try {
                ctx.setCurrentUser(null);

                if (toIndex != null) {
                    for (UUID id : toIndex) {
                        try {
                            Item item = itemService.find(ctx, id);
                            if (item == null) {
                                // removed again before this batch of events was processed
                                continue;
                            }
                            indexer.indexItem(item);
                        } catch (Exception ex) {
                            log.error("Failed to reindex item " + id + " in the OAI Solr core", ex);
                        }
                    }
                }

                if (toDelete != null) {
                    for (UUID id : toDelete) {
                        try {
                            indexer.deleteItem(id);
                        } catch (Exception ex) {
                            log.error("Failed to remove item " + id + " from the OAI Solr core", ex);
                        }
                    }
                }
            } finally {
                ctx.setCurrentUser(previousUser);
            }
        } finally {
            toIndex = null;
            toDelete = null;
        }
    }

    @Override
    public void finish(Context ctx) {
    }
}
