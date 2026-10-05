/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.xoai.app;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
 * <p>
 * Indexing is done synchronously in {@link #end}, reusing the SAME {@link Context} that fired
 * the events, rather than opening a separate one: events are dispatched BEFORE the triggering
 * context's transaction commits (see {@link Context#commit()}), so a second Context/DB
 * connection could not see the not-yet-committed changes. A later attempt to defer indexing
 * until after the real commit (via a Hibernate transaction synchronization) turned out to be
 * unreliable in general, since {@link Context#dispatchEvents()} is sometimes called well before
 * an eventual commit (e.g. DSpace's own test builders batch many changes under one long-lived
 * Context, committed only once at the very end) - so that commit may never arrive within any
 * useful timeframe.
 * <p>
 * Reusing the triggering Context means inheriting whatever privileged state it happens to be
 * in. OAI-PMH is an anonymous-facing protocol, so item.public/item.deleted must be computed as
 * Anonymous would see them - not as the acting user (e.g. an admin can always read an item,
 * which would otherwise make everything look public), and not with authorization checks
 * disabled entirely (e.g. the CLI batch importer calls
 * {@code context.turnOffAuthorisationSystem()} and never restores it before committing). Both
 * {@code currentUser} and the private {@code ignoreAuth} flag are therefore reset for the
 * duration of the indexing calls below, then restored. {@code ignoreAuth} has no public setter
 * other than the stack-based {@code turnOffAuthorisationSystem()}/{@code restoreAuthSystemState()}
 * pair (unsafe to (mis)use here - see git history for details), so it's reset directly via
 * reflection.
 * <p>
 * Besides direct Item events, this consumer also reacts to {@code Collection+Add/Remove} (an item
 * mapped into or out of a collection fires the event with the Collection as the subject and the
 * Item as the object - see {@link #resolveItemIdToReindex}), since each OAI document stores the
 * item's collection/community membership.
 *
 * @author Milan Kuchtiak
 */
public class OAIConsumer implements Consumer {
    private static final Logger log = LogManager.getLogger(OAIConsumer.class);

    private static final Field IGNORE_AUTH_FIELD;

    static {
        try {
            IGNORE_AUTH_FIELD = Context.class.getDeclaredField("ignoreAuth");
            IGNORE_AUTH_FIELD.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

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
        UUID itemId = resolveItemIdToReindex(event);
        if (itemId == null) {
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
            toIndex.remove(itemId);
            toDelete.add(itemId);
            return;
        }

        toIndex.add(itemId);
    }

    /**
     * Resolve the id of the item that should be reindexed for this event, or {@code null} if the
     * event isn't relevant. Most Item-subject events (Install/Modify/Modify_Metadata/Delete/Remove)
     * directly reference the changed item as the subject. {@code Collection+Add/Remove} events are
     * different: mapping/unmapping an archived item into or out of a collection
     * ({@code CollectionServiceImpl#addItem}/{@code removeItem}) fires the event with the
     * Collection as the subject and the affected Item as the object - without special-casing this
     * (mirroring {@code IndexEventConsumer}, which has the same need for Discovery), mapping an
     * item would never update its OAI "sets" (collection/community membership, stored in each OAI
     * document - see {@link XOAI#index}), leaving them stale until a full reindex.
     */
    private UUID resolveItemIdToReindex(Event event) {
        if (event.getSubjectType() == Constants.ITEM) {
            return event.getSubjectID();
        }
        if (event.getSubjectType() == Constants.COLLECTION
                && (event.getEventType() == Event.ADD || event.getEventType() == Event.REMOVE)
                && event.getObjectType() == Constants.ITEM) {
            return event.getObjectID();
        }
        return null;
    }

    @Override
    public void end(Context ctx) throws Exception {
        try {
            if ((toIndex == null || toIndex.isEmpty()) && (toDelete == null || toDelete.isEmpty())) {
                return;
            }

            XOAI indexer = new XOAI(ctx, false, false);
            applicationContext.getAutowireCapableBeanFactory().autowireBean(indexer);

            EPerson previousUser = ctx.getCurrentUser();
            boolean previousIgnoreAuth = getIgnoreAuth(ctx);
            try {
                ctx.setCurrentUser(null);
                setIgnoreAuth(ctx, false);

                if (toIndex != null && !toIndex.isEmpty()) {
                    List<Item> items = new ArrayList<>();
                    for (UUID id : toIndex) {
                        Item item = itemService.find(ctx, id);
                        if (item == null) {
                            // removed again before this batch of events was processed
                            continue;
                        }
                        items.add(item);
                    }
                    if (!items.isEmpty()) {
                        try {
                            indexer.indexItems(items);
                        } catch (Exception ex) {
                            log.error("Failed to reindex " + items.size() + " item(s) in the OAI Solr core", ex);
                        }
                    }
                }

                if (toDelete != null && !toDelete.isEmpty()) {
                    try {
                        indexer.deleteItems(toDelete);
                    } catch (Exception ex) {
                        log.error("Failed to remove " + toDelete.size() + " item(s) from the OAI Solr core", ex);
                    }
                }
            } finally {
                ctx.setCurrentUser(previousUser);
                setIgnoreAuth(ctx, previousIgnoreAuth);
            }
        } finally {
            toIndex = null;
            toDelete = null;
        }
    }

    private static boolean getIgnoreAuth(Context ctx) {
        try {
            return (boolean) IGNORE_AUTH_FIELD.get(ctx);
        } catch (IllegalAccessException e) {
            log.error("Failed to read Context#ignoreAuth, assuming authorization checks are active", e);
            return false;
        }
    }

    private static void setIgnoreAuth(Context ctx, boolean value) {
        try {
            IGNORE_AUTH_FIELD.set(ctx, value);
        } catch (IllegalAccessException e) {
            log.error("Failed to set Context#ignoreAuth to " + value, e);
        }
    }

    @Override
    public void finish(Context ctx) {
    }
}
