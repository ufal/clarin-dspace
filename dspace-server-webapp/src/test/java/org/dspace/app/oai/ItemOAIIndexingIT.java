/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.oai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import javax.ws.rs.core.MediaType;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.common.SolrDocumentList;
import org.dspace.app.rest.model.ResourcePolicyRest;
import org.dspace.app.rest.model.patch.Operation;
import org.dspace.app.rest.model.patch.ReplaceOperation;
import org.dspace.app.rest.test.AbstractControllerIntegrationTest;
import org.dspace.authorize.ResourcePolicy;
import org.dspace.authorize.factory.AuthorizeServiceFactory;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.ItemBuilder;
import org.dspace.builder.ResourcePolicyBuilder;
import org.dspace.builder.WorkspaceItemBuilder;
import org.dspace.content.Collection;
import org.dspace.content.Item;
import org.dspace.content.WorkspaceItem;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.CollectionService;
import org.dspace.content.service.ItemService;
import org.dspace.core.Constants;
import org.dspace.eperson.Group;
import org.dspace.eperson.factory.EPersonServiceFactory;
import org.dspace.event.factory.EventServiceFactory;
import org.dspace.event.service.EventService;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.dspace.solr.MockSolrServer;
import org.dspace.xoai.data.DSpaceItem;
import org.dspace.xoai.services.api.xoai.ItemRepositoryResolver;
import org.dspace.xoai.services.impl.solr.DSpaceSolrServerResolver;
import org.dspace.xoai.solr.DSpaceSolrSearch;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Regression test for ufal/clarin-dspace#1416: an item archived through the normal REST
 * submission/workflow process (i.e. {@code WorkflowItemRestRepository#createAndReturn}) must be
 * discoverable via OAI-PMH right away.
 * <p>
 * {@code WorkflowItemRestRepository} previously called {@code SolrOAIReindexer#reindexItem}
 * directly after archiving, but that call was a pure duplicate: {@code InstallItemServiceImpl}
 * already fires {@code Event.INSTALL} for every archived item, and that event is now picked up by
 * {@link org.dspace.xoai.app.OAIConsumer} regardless of which code path triggered the installation. This
 * test proves that removing the manual call didn't regress the normal submission path, mirroring
 * {@link ItemImportOAIIndexingIT}, which proves the same thing for the CLI batch importer.
 * <p>
 * Unlike {@link ItemImportOAIIndexingIT}, this test can't opt a {@code Context} into a dedicated
 * dispatcher via {@code Context#setDispatcher}: the {@code Context} used by
 * {@code WorkflowItemRestRepository} is created by the servlet filter for the current HTTP request,
 * not by this test, and always resolves to the hardcoded {@code "default"} dispatcher. Since the
 * shared test {@code local.cfg} (used by every module's tests, including {@code dspace-api}'s, which
 * has no dependency on {@code dspace-oai}) can't safely add {@code oai} to {@code "default"} either,
 * this test instead activates it by mutating the DSpace kernel's in-memory
 * {@link ConfigurationService} directly and forcing {@link EventService} to rebuild its dispatcher
 * pool — an override strictly scoped to this JVM/test run and reverted in {@link #tearDownOAI()}.
 *
 * @author Milan Kuchtiak
 */
@TestPropertySource(properties = {"oai.enabled = true"})
public class ItemOAIIndexingIT extends AbstractControllerIntegrationTest {

    private final ItemService itemService = ContentServiceFactory.getInstance().getItemService();
    private final CollectionService collectionService = ContentServiceFactory.getInstance().getCollectionService();
    private final ConfigurationService configurationService =
            DSpaceServicesFactory.getInstance().getConfigurationService();
    private final EventService eventService = EventServiceFactory.getInstance().getEventService();

    // Used to reset the cached DSpaceItemSolrRepository before each test, in case an earlier test
    // left it bound to a different embedded Solr core instance.
    @Autowired(required = false)
    private ItemRepositoryResolver itemRepositoryResolver;

    private MockSolrServer mockOAISolr;
    private String[] originalConsumers;

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();

        // Skip if the OAI module is not on the classpath
        try {
            Class.forName("org.dspace.app.configuration.OAIWebConfig");
        } catch (ClassNotFoundException ce) {
            Assume.assumeNoException(ce);
        }

        // OAIConsumer builds its own Spring context (see BasicConfiguration) rather than using
        // beans from the webapp's ApplicationContext, so @MockBean cannot intercept its Solr
        // client. Redirect DSpaceSolrServerResolver's cached client (a static field, shared by
        // every instance) to an embedded "oai" core instead.
        mockOAISolr = new MockSolrServer("oai");
        ReflectionTestUtils.setField(DSpaceSolrServerResolver.class, "server", mockOAISolr.getSolrServer());

        // Reset the cached ItemRepository so it is re-created bound to the embedded client above
        if (itemRepositoryResolver != null) {
            ReflectionTestUtils.setField(itemRepositoryResolver, "itemRepository", null);
        }

        // Activate "oai" on the "default" dispatcher for the lifetime of this test only (see class
        // javadoc for why). Nulling the pool forces EventServiceImpl to rebuild it, re-reading the
        // consumer list on the next commit instead of reusing an already-pooled "default" dispatcher.
        originalConsumers = configurationService.getArrayProperty("event.dispatcher.default.consumers");
        configurationService.setProperty("event.dispatcher.default.consumers",
                String.join(", ", originalConsumers) + ", oai");
        ReflectionTestUtils.setField(eventService, "dispatcherPool", null);
    }

    @After
    public void tearDownOAI() throws Exception {
        ReflectionTestUtils.setField(DSpaceSolrServerResolver.class, "server", null);
        if (itemRepositoryResolver != null) {
            ReflectionTestUtils.setField(itemRepositoryResolver, "itemRepository", null);
        }
        if (mockOAISolr != null) {
            mockOAISolr.destroy();
            mockOAISolr = null;
        }
        if (originalConsumers != null) {
            configurationService.setProperty("event.dispatcher.default.consumers",
                    String.join(", ", originalConsumers));
            ReflectionTestUtils.setField(eventService, "dispatcherPool", null);
        }
    }

    @Test
    public void archivedItemAppearsInOai() throws Exception {
        context.turnOffAuthorisationSystem();
        context.setCurrentUser(admin);
        parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Parent Community")
                .build();
        Collection collection = CollectionBuilder.createCollection(context, parentCommunity)
                .withName("Collection")
                .build();

        WorkspaceItem wsitem = WorkspaceItemBuilder.createWorkspaceItem(context, collection)
                .withTitle("OAI Workflow Test Item")
                .withIssueDate("2026-01-01")
                .grantLicense()
                .build();
        context.restoreAuthSystemState();

        String token = getAuthToken(admin.getEmail(), password);

        // No workflow is configured on the collection, so this archives the item immediately
        // (see WorkflowItemRestRepository#createAndReturn).
        getClient(token).perform(post(BASE_REST_SERVER_URL + "/api/workflow/workflowitems")
                        .content("/api/submission/workspaceitems/" + wsitem.getID())
                        .contentType(textUriContentType))
                .andExpect(status().isCreated());

        Item item = itemService.findByMetadataField(context, "dc", "title", null,
                "OAI Workflow Test Item").next();
        assertTrue("Item should have been archived immediately (no workflow configured)",
                item.isArchived());
        assertNotNull("Archived item should have been assigned a handle", item.getHandle());

        SolrDocumentList results = DSpaceSolrSearch.query(mockOAISolr.getSolrServer(),
                new SolrQuery("item.handle:" + item.getHandle()));
        assertEquals("Item archived via the REST workflow submission should already be indexed "
                + "in the OAI Solr core", 1, results.getNumFound());
        assertEquals(item.getHandle(), results.get(0).getFieldValue("item.handle"));

        // Beyond the backing Solr document, also verify the item is genuinely discoverable
        // through a real OAI-PMH request - this additionally exercises the response cache and
        // the request-serving/authorization-filter layers, none of which the Solr check touches.
        String oaiIdentifier = DSpaceItem.buildIdentifier(item.getHandle());
        String response = getClient().perform(get("/oai/request")
                        .param("verb", "GetRecord")
                        .param("metadataPrefix", "oai_dc")
                        .param("identifier", oaiIdentifier))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertFalse("GetRecord should not report the item as unknown ('idDoesNotExist')",
                response.contains("idDoesNotExist"));
        assertTrue("GetRecord response should contain the archived item's title",
                response.contains("OAI Workflow Test Item"));
    }

    @Test
    public void modifiedItemUpdatesOai() throws Exception {
        context.turnOffAuthorisationSystem();
        parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Parent Community")
                .build();
        Collection collection = CollectionBuilder.createCollection(context, parentCommunity)
                .withName("Collection")
                .build();
        Item item = ItemBuilder.createItem(context, collection)
                .withTitle("Original Title")
                .withIssueDate("2026-01-01")
                .build();
        context.restoreAuthSystemState();

        // sanity check: the item was already indexed on archival, with its original title
        SolrDocumentList initialResults = DSpaceSolrSearch.query(mockOAISolr.getSolrServer(),
                new SolrQuery("item.handle:" + item.getHandle()));
        assertEquals(1, initialResults.getNumFound());
        assertTrue("OAI record should initially contain the original title",
                initialResults.get(0).getFieldValue("item.compile").toString().contains("Original Title"));

        // Warm the OAI-PMH response cache with the item's ORIGINAL title, before the patch below.
        // This is what actually lets the assertions after the patch prove the response cache gets
        // invalidated, rather than just proving the backing Solr document was updated.
        String oaiIdentifier = DSpaceItem.buildIdentifier(item.getHandle());
        String initialResponse = getClient().perform(get("/oai/request")
                        .param("verb", "GetRecord")
                        .param("metadataPrefix", "oai_dc")
                        .param("identifier", oaiIdentifier))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue("GetRecord response should initially contain the original title",
                initialResponse.contains("Original Title"));

        String token = getAuthToken(admin.getEmail(), password);

        List<Operation> ops = new ArrayList<>();
        ops.add(new ReplaceOperation("/metadata/dc.title/0", "Updated Title"));
        getClient(token).perform(patch(BASE_REST_SERVER_URL + "/api/core/items/" + item.getID())
                        .content(getPatchContent(ops))
                        .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                .andExpect(status().isOk());

        SolrDocumentList updatedResults = DSpaceSolrSearch.query(mockOAISolr.getSolrServer(),
                new SolrQuery("item.handle:" + item.getHandle()));
        assertEquals("Item should still have exactly one OAI record after being modified",
                1, updatedResults.getNumFound());
        assertTrue("OAI record should reflect the updated title after the metadata patch",
                updatedResults.get(0).getFieldValue("item.compile").toString().contains("Updated Title"));

        String updatedResponse = getClient().perform(get("/oai/request")
                        .param("verb", "GetRecord")
                        .param("metadataPrefix", "oai_dc")
                        .param("identifier", oaiIdentifier))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertFalse("GetRecord should not keep serving a cached response with the stale, "
                + "original title", updatedResponse.contains("Original Title"));
        assertTrue("GetRecord response should reflect the updated title, not a stale cached "
                + "response", updatedResponse.contains("Updated Title"));
    }

    @Test
    public void deletedItemIsRemovedFromOai() throws Exception {
        context.turnOffAuthorisationSystem();
        parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Parent Community")
                .build();
        Collection collection = CollectionBuilder.createCollection(context, parentCommunity)
                .withName("Collection")
                .build();
        Item item = ItemBuilder.createItem(context, collection)
                .withTitle("Item To Delete")
                .withIssueDate("2026-01-01")
                .build();
        context.restoreAuthSystemState();

        String handle = item.getHandle();

        // sanity check: the item was indexed on archival
        SolrDocumentList initialResults = DSpaceSolrSearch.query(mockOAISolr.getSolrServer(),
                new SolrQuery("item.handle:" + handle));
        assertEquals(1, initialResults.getNumFound());

        // Warm the OAI-PMH response cache with a successful GetRecord response, before the item
        // is deleted below - this is what lets the post-delete assertion prove the response cache
        // gets invalidated, rather than just proving the backing Solr document was removed.
        String oaiIdentifier = DSpaceItem.buildIdentifier(handle);
        String initialResponse = getClient().perform(get("/oai/request")
                        .param("verb", "GetRecord")
                        .param("metadataPrefix", "oai_dc")
                        .param("identifier", oaiIdentifier))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertFalse("GetRecord should initially find the item",
                initialResponse.contains("idDoesNotExist"));

        String token = getAuthToken(admin.getEmail(), password);

        getClient(token).perform(delete(BASE_REST_SERVER_URL + "/api/core/items/" + item.getID()))
                .andExpect(status().isNoContent());

        SolrDocumentList resultsAfterDelete = DSpaceSolrSearch.query(mockOAISolr.getSolrServer(),
                new SolrQuery("item.handle:" + handle));
        assertEquals("Item deleted via REST should have been removed from the OAI Solr core",
                0, resultsAfterDelete.getNumFound());

        String responseAfterDelete = getClient().perform(get("/oai/request")
                        .param("verb", "GetRecord")
                        .param("metadataPrefix", "oai_dc")
                        .param("identifier", oaiIdentifier))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue("GetRecord should report the item as unknown after deletion, not keep serving "
                + "a cached response from before the delete",
                responseAfterDelete.contains("idDoesNotExist"));
    }

    @Test
    public void resourcePolicyUpdateReindexOai() throws Exception {
        context.turnOffAuthorisationSystem();
        parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Parent Community")
                .build();
        Collection collection = CollectionBuilder.createCollection(context, parentCommunity)
                .withName("Collection")
                .build();
        Item item = ItemBuilder.createItem(context, collection)
                .withTitle("Item With Policy")
                .withIssueDate("2026-01-01")
                .build();

        // ItemBuilder/installItemService inherits the collection's own default READ policies onto
        // the item, so it may already have an Anonymous READ policy besides the one we add below.
        // Clear all READ policies first so the item's public/non-public state is fully controlled
        // by the single policy this test manages, rather than depending on any leftover default.
        AuthorizeServiceFactory.getInstance().getResourcePolicyService()
                .removePolicies(context, item, Constants.READ);

        Group anonymousGroup = EPersonServiceFactory.getInstance().getGroupService()
                .findByName(context, Group.ANONYMOUS);
        ResourcePolicy resourcePolicy = ResourcePolicyBuilder.createResourcePolicy(context, null, anonymousGroup)
                .withAction(Constants.READ)
                .withDspaceObject(item)
                .withPolicyType(ResourcePolicy.TYPE_CUSTOM)
                .withName("Anonymous Read Policy")
                .build();
        context.restoreAuthSystemState();

        // sanity check: the item is indexed and currently public (Anonymous has an active READ
        // policy), so it's not yet flagged as an OAI-PMH tombstone.
        SolrDocumentList initialResults = DSpaceSolrSearch.query(mockOAISolr.getSolrServer(),
                new SolrQuery("item.handle:" + item.getHandle()));
        assertEquals(1, initialResults.getNumFound());
        assertEquals("Item should initially be public (Anonymous has an active READ policy)",
                true, initialResults.get(0).getFieldValue("item.public"));
        assertEquals("Item should not initially be flagged deleted in the OAI Solr core",
                false, initialResults.get(0).getFieldValue("item.deleted"));

        // Warm the OAI-PMH response cache with the item's full, public record, before the
        // resource policy is restricted below - this is what lets the post-change assertions
        // prove the response cache gets invalidated, rather than just proving the backing Solr
        // document was updated.
        String oaiIdentifier = DSpaceItem.buildIdentifier(item.getHandle());
        String initialResponse = getClient().perform(get("/oai/request")
                        .param("verb", "GetRecord")
                        .param("metadataPrefix", "oai_dc")
                        .param("identifier", oaiIdentifier))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertFalse("GetRecord should initially return the full record, not a deleted/tombstoned "
                + "status", initialResponse.contains("status=\"deleted\""));
        assertTrue("GetRecord response should initially contain the item's title",
                initialResponse.contains("Item With Policy"));

        String token = getAuthToken(admin.getEmail(), password);

        // Changing WHO a policy applies to (Group -> EPerson) isn't supported by PATCH, nor by the
        // PUT .../eperson|group link-replace endpoints (both reject cross-type reassignment with
        // 422 - see ResourcePolicyEPersonReplaceRestController/ResourcePolicyGroupReplaceRestController).
        // So restricting READ to admin-only means deleting the Anonymous policy and creating a new
        // admin-only one, exactly as an administrator would have to do via the UI.
        getClient(token).perform(delete(BASE_REST_SERVER_URL + "/api/authz/resourcepolicies/"
                        + resourcePolicy.getID()))
                .andExpect(status().isNoContent());

        ResourcePolicyRest resourcePolicyRest = new ResourcePolicyRest();
        resourcePolicyRest.setPolicyType(ResourcePolicy.TYPE_CUSTOM);
        resourcePolicyRest.setAction(Constants.actionText[Constants.READ]);
        ObjectMapper mapper = new ObjectMapper();
        getClient(token).perform(post(BASE_REST_SERVER_URL + "/api/authz/resourcepolicies")
                        .content(mapper.writeValueAsBytes(resourcePolicyRest))
                        .param("resource", item.getID().toString())
                        .param("eperson", admin.getID().toString())
                        .contentType(contentType))
                .andExpect(status().isCreated());

        // OAIConsumer reindexes with the context's current user temporarily nulled out (see
        // OAIConsumer#end), so "is this item public" is evaluated as Anonymous would see it, not
        // as the admin who made the REST calls. With Anonymous no longer having READ access, the
        // item should now be flagged as an OAI-PMH tombstone (item.deleted:true).
        SolrDocumentList updatedResults = DSpaceSolrSearch.query(mockOAISolr.getSolrServer(),
                new SolrQuery("item.handle:" + item.getHandle()));
        assertEquals("Item should still have exactly one OAI record after its READ policy was "
                + "restricted to admin-only", 1, updatedResults.getNumFound());
        assertEquals("Item should be flagged as deleted (OAI-PMH tombstone) in the OAI Solr core, "
                + "since Anonymous no longer has READ access",
                true, updatedResults.get(0).getFieldValue("item.deleted"));

        String updatedResponse = getClient().perform(get("/oai/request")
                        .param("verb", "GetRecord")
                        .param("metadataPrefix", "oai_dc")
                        .param("identifier", oaiIdentifier))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue("GetRecord should now report the record as deleted (OAI-PMH tombstone), not "
                + "keep serving the stale, previously-cached full record",
                updatedResponse.contains("status=\"deleted\""));
        assertFalse("GetRecord response should no longer contain the item's metadata once "
                + "tombstoned", updatedResponse.contains("Item With Policy"));
    }

    @Test
    public void collectionMappingReindexOai() throws Exception {
        context.turnOffAuthorisationSystem();
        parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Parent Community")
                .build();
        Collection originalCollection = CollectionBuilder.createCollection(context, parentCommunity)
                .withName("Original Collection")
                .build();
        Collection mappedCollection = CollectionBuilder.createCollection(context, parentCommunity)
                .withName("Mapped Collection")
                .build();
        Item item = ItemBuilder.createItem(context, originalCollection)
                .withTitle("Mapped Item")
                .withIssueDate("2026-01-01")
                .build();
        context.restoreAuthSystemState();

        String originalCollectionSet = "col_" + originalCollection.getHandle().replace("/", "_");
        String mappedCollectionSet = "col_" + mappedCollection.getHandle().replace("/", "_");
        String oaiIdentifier = DSpaceItem.buildIdentifier(item.getHandle());

        // sanity check: the item was indexed on archival, listing only its owning collection
        SolrDocumentList initialResults = DSpaceSolrSearch.query(mockOAISolr.getSolrServer(),
                new SolrQuery("item.handle:" + item.getHandle()));
        assertEquals(1, initialResults.getNumFound());
        assertTrue("OAI record should initially list the owning collection",
                initialResults.get(0).getFieldValues("item.collections").contains(originalCollectionSet));
        assertFalse("OAI record should not yet list the not-yet-mapped collection",
                initialResults.get(0).getFieldValues("item.collections").contains(mappedCollectionSet));

        // Map the item into a second collection directly through CollectionService (the same call
        // MappedCollectionRestController#createCollectionToItemRelation makes), WITHOUT also
        // touching the item's own metadata. This isolates the Collection+Add event path
        // (OAIConsumer#resolveItemIdToReindex) from the accompanying Item+Modify_Metadata event
        // that the REST mapping endpoint additionally fires via ProvenanceService#mappedItem -
        // that second event would independently trigger a reindex and could mask a regression in
        // the Collection+Add handling specifically.
        context.turnOffAuthorisationSystem();
        collectionService.addItem(context, mappedCollection, item);
        context.restoreAuthSystemState();
        context.dispatchEvents();

        SolrDocumentList afterAddResults = DSpaceSolrSearch.query(mockOAISolr.getSolrServer(),
                new SolrQuery("item.handle:" + item.getHandle()));
        assertEquals("Item should still have exactly one OAI record after being mapped into a "
                + "second collection", 1, afterAddResults.getNumFound());
        assertTrue("OAI record should still list the original owning collection",
                afterAddResults.get(0).getFieldValues("item.collections").contains(originalCollectionSet));
        assertTrue("OAI record should now also list the newly mapped collection, without requiring "
                + "a manual reindex (Collection+Add fires with the Collection as subject and the "
                + "Item as object)",
                afterAddResults.get(0).getFieldValues("item.collections").contains(mappedCollectionSet));

        // Also verify the new collection membership is reflected in a real OAI-PMH GetRecord
        // response's <setSpec> list (DSpaceSolrItem#getSets reads the same item.collections field).
        String responseAfterAdd = getClient().perform(get("/oai/request")
                        .param("verb", "GetRecord")
                        .param("metadataPrefix", "oai_dc")
                        .param("identifier", oaiIdentifier))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue("GetRecord response should list the newly mapped collection as one of the "
                + "item's sets", responseAfterAdd.contains(mappedCollectionSet));

        // Now unmap the item from the second collection, the same way, isolating the
        // Collection+Remove event path. It is not orphaned by this (and so not deleted), since it
        // is still owned by the original collection.
        //
        // Re-fetch the item first: the GetRecord call above went through a separate,
        // request-scoped Context/Hibernate session, which can leave this test's own 'item'
        // reference detached from its session. Mutating a detached entity would silently be lost
        // (never flushed), so operate on a freshly-attached instance instead.
        context.turnOffAuthorisationSystem();
        item = itemService.find(context, item.getID());
        collectionService.removeItem(context, mappedCollection, item);
        context.restoreAuthSystemState();
        context.dispatchEvents();

        SolrDocumentList afterRemoveResults = DSpaceSolrSearch.query(mockOAISolr.getSolrServer(),
                new SolrQuery("item.handle:" + item.getHandle()));
        assertEquals("Item should still have exactly one OAI record after being unmapped from the "
                + "second collection", 1, afterRemoveResults.getNumFound());
        assertTrue("OAI record should still list the original owning collection",
                afterRemoveResults.get(0).getFieldValues("item.collections").contains(originalCollectionSet));
        assertFalse("OAI record should no longer list the unmapped collection, without requiring a "
                + "manual reindex (Collection+Remove fires with the Collection as subject and the "
                + "Item as object)",
                afterRemoveResults.get(0).getFieldValues("item.collections").contains(mappedCollectionSet));

        String responseAfterRemove = getClient().perform(get("/oai/request")
                        .param("verb", "GetRecord")
                        .param("metadataPrefix", "oai_dc")
                        .param("identifier", oaiIdentifier))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertFalse("GetRecord response should no longer list the unmapped collection as one of "
                + "the item's sets", responseAfterRemove.contains(mappedCollectionSet));
    }
}
