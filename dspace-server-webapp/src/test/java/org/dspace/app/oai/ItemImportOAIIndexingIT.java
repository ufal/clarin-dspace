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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import org.apache.commons.io.file.PathUtils;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.common.SolrDocumentList;
import org.dspace.app.itemimport.factory.ItemImportServiceFactory;
import org.dspace.app.itemimport.service.ItemImportService;
import org.dspace.app.rest.test.AbstractControllerIntegrationTest;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.content.Collection;
import org.dspace.content.Item;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.CollectionService;
import org.dspace.content.service.ItemService;
import org.dspace.core.Context;
import org.dspace.eperson.factory.EPersonServiceFactory;
import org.dspace.eperson.service.EPersonService;
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
 * Regression test for ufal/clarin-dspace#1416: an item created via the {@code bin/dspace import}
 * CLI command (backed by {@link org.dspace.app.itemimport.ItemImportServiceImpl}) must be
 * discoverable via OAI-PMH right away, without requiring a manual {@code bin/dspace oai import}
 * run. This is provided by {@link org.dspace.xoai.app.OAIConsumer}, which reacts to the standard
 * DSpace event system instead of relying on each call site to update the OAI Solr core itself.
 * <p>
 * This test calls {@link ItemImportService#addItems} directly rather than going through
 * {@code runDSpaceScript(new String[]{"import", ...})}: {@code dspace-server-webapp}'s test
 * environment loads both {@code config/spring/api/scripts.xml} (registering the CLI variant,
 * {@code ItemImportCLIScriptConfiguration}, under the bean id "import") and
 * {@code config/spring/rest/scripts.xml} (registering the REST variant, plain
 * {@code ItemImportScriptConfiguration}, without the {@code -e}/{@code -s} options, under the
 * same bean id) into the same Spring context — a collision that doesn't occur in production,
 * where the CLI and the webapp run as separate processes. {@code addItems} is exactly what
 * {@code ItemImportCLI#process} calls for the "add" command, so this still exercises the same
 * production import code-path used by {@code bin/dspace import -a}.
 * <p>
 * The shared test {@code local.cfg} (used by every module's tests, including {@code dspace-api}'s,
 * which has no dependency on {@code dspace-oai}) intentionally omits {@code oai} from the
 * "default" dispatcher's consumer list, since {@link org.dspace.xoai.app.OAIConsumer} isn't on
 * {@code dspace-api}'s classpath and registering it there would break every {@code dspace-api}
 * test that commits a context via "default". Instead, {@code local.cfg} defines a dedicated
 * {@code oai-test} dispatcher (consumers: versioning, discovery, eperson, oai) that nothing else
 * references, and the import {@link Context} below opts into it explicitly via
 * {@link Context#setDispatcher}.
 *
 * @author Milan Kuchtiak
 */
@TestPropertySource(properties = {"oai.enabled = true"})
public class ItemImportOAIIndexingIT extends AbstractControllerIntegrationTest {

    private final ItemService itemService = ContentServiceFactory.getInstance().getItemService();
    private final CollectionService collectionService = ContentServiceFactory.getInstance().getCollectionService();
    private final EPersonService ePersonService = EPersonServiceFactory.getInstance().getEPersonService();

    // Used to reset the cached DSpaceItemSolrRepository before this test, in case an earlier test
    // (in the same JVM/Surefire fork) left it bound to a different embedded Solr core instance.
    @Autowired(required = false)
    private ItemRepositoryResolver itemRepositoryResolver;

    private MockSolrServer mockOAISolr;

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
    }

    @Test
    public void importedItemAppearsInOaiIndex() throws Exception {
        context.turnOffAuthorisationSystem();
        parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Parent Community")
                .build();
        Collection collection = CollectionBuilder.createCollection(context, parentCommunity)
                .withName("Collection")
                .build();
        context.restoreAuthSystemState();

        Path tempDir = Files.createTempDirectory("oaiConsumerImportTest");
        try {
            Path safDir = Files.createDirectory(tempDir.resolve("saf"));
            Path itemDir = Files.createDirectory(safDir.resolve("item_000"));
            Files.writeString(itemDir.resolve("dublin_core.xml"),
                    "<dublin_core>\n"
                    + "    <dcvalue element=\"title\" qualifier=\"none\">OAI Consumer Import Test Item</dcvalue>\n"
                    + "    <dcvalue element=\"date\" qualifier=\"issued\">2026</dcvalue>\n"
                    + "</dublin_core>");

            // Mirrors ItemImportCLI#internalRun / #process for the "add" command: a fresh
            // batch-edit Context acting as the importing eperson, committed at the end so the
            // resulting Item+Install event is dispatched to (and picked up by) OAIConsumer.
            // Entities are re-resolved by id rather than reused from the outer 'context' field,
            // since they belong to a different Hibernate session.
            Context importContext = new Context(Context.Mode.BATCH_EDIT);
            importContext.setDispatcher("oai-test");
            importContext.setCurrentUser(ePersonService.find(importContext, admin.getID()));
            importContext.turnOffAuthorisationSystem();

            List<Collection> myCollections = Collections.singletonList(
                    collectionService.find(importContext, collection.getID()));
            String sourceDir = safDir.toString();
            String mapFile = tempDir.resolve("mapfile.out").toString();

            ItemImportService itemImportService = ItemImportServiceFactory.getInstance().getItemImportService();
            itemImportService.addItems(importContext, myCollections, sourceDir, mapFile, false);
            importContext.complete();

            Item item = itemService.findByMetadataField(context, "dc", "title", null,
                    "OAI Consumer Import Test Item").next();
            assertNotNull("Imported item should have been assigned a handle", item.getHandle());

            SolrDocumentList results = DSpaceSolrSearch.query(mockOAISolr.getSolrServer(),
                    new SolrQuery("item.handle:" + item.getHandle()));
            assertEquals("Item imported via the batch importer should already be indexed in the OAI "
                    + "Solr core, without running 'bin/dspace oai import'", 1, results.getNumFound());
            assertEquals(item.getHandle(), results.get(0).getFieldValue("item.handle"));

            // Beyond the backing Solr document, also verify the item is genuinely discoverable
            // through a real OAI-PMH request - this additionally exercises the OAI-PMH response
            // cache and the request-serving/authorization-filter layers, none of which the Solr
            // check above touches.
            String oaiIdentifier = DSpaceItem.buildIdentifier(item.getHandle());
            String response = getClient().perform(get("/oai/request")
                            .param("verb", "GetRecord")
                            .param("metadataPrefix", "oai_dc")
                            .param("identifier", oaiIdentifier))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            assertFalse("GetRecord should not report the item as unknown ('idDoesNotExist')",
                    response.contains("idDoesNotExist"));
            assertTrue("GetRecord response should contain the imported item's title",
                    response.contains("OAI Consumer Import Test Item"));
        } finally {
            PathUtils.deleteDirectory(tempDir);
        }
    }
}
