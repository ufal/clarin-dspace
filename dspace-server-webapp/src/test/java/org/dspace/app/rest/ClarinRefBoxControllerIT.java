/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.dspace.app.rest.test.AbstractControllerIntegrationTest;
import org.dspace.app.rest.utils.SolrOAIReindexer;
import org.dspace.app.rest.utils.Utils;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.ItemBuilder;
import org.dspace.content.Collection;
import org.dspace.content.Item;
import org.dspace.services.ConfigurationService;
import org.dspace.solr.MockSolrServer;
import org.dspace.xoai.services.api.cache.XOAICacheService;
import org.dspace.xoai.services.api.solr.SolrServerResolver;
import org.dspace.xoai.services.api.xoai.ItemRepositoryResolver;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The Integration Test class for the ClarinRefBoxController.
 */
@TestPropertySource(properties = {"oai.enabled = true"})
public class ClarinRefBoxControllerIT extends AbstractControllerIntegrationTest {

    @Autowired
    ConfigurationService configurationService;

    @Autowired
    SolrOAIReindexer solrOAIReindexer;

    // Mock OAI cache to disable it during tests (avoids side-effects from cache state)
    @MockBean
    private XOAICacheService xoaiCacheService;

    // Mock the OAI SolrServerResolver — overridden to return an embedded Solr client in setUp()
    @MockBean
    private SolrServerResolver solrServerResolver;

    // Used to reset the cached DSpaceItemSolrRepository before each test
    @Autowired(required = false)
    private ItemRepositoryResolver itemRepositoryResolver;

    private MockSolrServer mockOAISolr;

    // FS = featuredService
    private Item itemWithFS;
    private Item item;
    private Collection collection;

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();

        // Skip all tests if the OAI module is not on the classpath
        try {
            Class.forName("org.dspace.app.configuration.OAIWebConfig");
        } catch (ClassNotFoundException ce) {
            Assume.assumeNoException(ce);
        }

        // Initialise embedded Solr for the OAI core
        mockOAISolr = new MockSolrServer("oai");
        when(solrServerResolver.getServer()).thenReturn(mockOAISolr.getSolrServer());

        // Disable OAI caching so tests see live Solr state
        when(xoaiCacheService.isActive()).thenReturn(false);
        when(xoaiCacheService.hasCache(anyString())).thenReturn(false);

        // Reset the cached ItemRepository so it is re-created with the embedded client
        if (itemRepositoryResolver != null) {
            ReflectionTestUtils.setField(itemRepositoryResolver, "itemRepository", null);
        }

        // Ensure the reindexer also uses the embedded Solr client
        ReflectionTestUtils.setField(solrOAIReindexer, "solrServerResolver", solrServerResolver);

        context.turnOffAuthorisationSystem();
        parentCommunity = CommunityBuilder.createCommunity(context).withName("test").build();
        collection = CollectionBuilder.createCollection(context, parentCommunity).withName("Collection 1").build();
        item = ItemBuilder.createItem(context, collection)
                .withTitle("Public item 2")
                .withIssueDate("2016-02-13")
                .withAuthor("Test author 2")
                .withSubject("TestingForMore 2")
                .build();
        itemWithFS = ItemBuilder.createItem(context, collection)
                .withTitle("Public item 1")
                .withIssueDate("2016-02-13")
                .withAuthor("Test author")
                .withSubject("TestingForMore")
                .withMetadata("local","featuredService","kontext", "Slovak|URLSlovak")
                .withMetadata("local","featuredService","kontext", "Czech|URLCzech")
                .withMetadata("local","featuredService","pmltq", "Arabic|URLArabic")
                .build();
        context.restoreAuthSystemState();

        // Index the test item in the xoai Solr core so the citations endpoint can find it.
        solrOAIReindexer.reindexItem(item);
    }

    @After
    public void tearDownOAI() throws Exception {
        if (mockOAISolr != null) {
            mockOAISolr.destroy();
            mockOAISolr = null;
        }
    }

    @Test
    public void returnFeaturedServiceWithoutLinks() throws Exception {
        String token = getAuthToken(admin.getEmail(), password);
        getClient(token).perform(get("/api/core/refbox/services?id=" + item.getID()))
                .andExpect(status().isOk());
    }

    @Test
    public void returnFeaturedServiceWithLinks() throws Exception {
        String token = getAuthToken(admin.getEmail(), password);
        getClient(token).perform(get("/api/core/refbox/services?id=" + itemWithFS.getID()))
                .andExpect(status().isOk());
    }

    @Test
    public void testReturnAllRefboxInfoForItemWithFeaturedService() throws Exception {
        String token = getAuthToken(admin.getEmail(), password);
        String handle = itemWithFS.getHandle();
        String baseUrl = configurationService.getProperty("dspace.server.url") +
                "/api/core/refbox/citations?handle=/" + Utils.getCanonicalHandleUrlNoProtocol(itemWithFS);
        String bibtexUrl = baseUrl + "&type=bibtex";
        String cmdiUrl = baseUrl + "&type=cmdi";

        getClient(token).perform(get("/api/core/refbox?handle=" + handle))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayText").value(org.hamcrest.Matchers.containsString("Test author")))
                .andExpect(jsonPath("$.title").value("Public item 1"))
                // For exportFormats
                .andExpect(jsonPath("$.exportFormats.exportFormat[*].name", hasItem("bibtex")))
                .andExpect(jsonPath("$.exportFormats.exportFormat[*].name", hasItem("cmdi")))
                .andExpect(jsonPath("$.exportFormats.exportFormat[?(@.name=='bibtex')].url").value(hasItem(bibtexUrl)))
                .andExpect(jsonPath("$.exportFormats.exportFormat[?(@.name=='cmdi')].url").value(hasItem(cmdiUrl)))
                // For featuredServices
                .andExpect(jsonPath("$.featuredServices.featuredService[*].name", hasItem("KonText")))
                .andExpect(jsonPath("$.featuredServices.featuredService[*].name", hasItem("PML-TQ")))
                .andExpect(jsonPath("$.featuredServices.featuredService[?(@.name=='KonText')].links.entry[*].key"
                        , hasItem("Slovak")))
                .andExpect(jsonPath("$.featuredServices.featuredService[?(@.name=='KonText')].links.entry[*].value"
                        , hasItem("URLSlovak")))
                .andExpect(jsonPath("$.featuredServices.featuredService[?(@.name=='KonText')].links.entry[*].key"
                        , hasItem("Czech")))
                .andExpect(jsonPath("$.featuredServices.featuredService[?(@.name=='KonText')].links.entry[*].value"
                        , hasItem("URLCzech")))
                .andExpect(jsonPath("$.featuredServices.featuredService[?(@.name=='PML-TQ')].links.entry[*].key"
                        , hasItem("Arabic")))
                .andExpect(jsonPath("$.featuredServices.featuredService[?(@.name=='PML-TQ')].links.entry[*].value"
                        , hasItem("URLArabic")));
    }

    @Test
    public void testReturnAllRefboxInfoForItemWithEmptyFeaturedService() throws Exception {
        String token = getAuthToken(admin.getEmail(), password);
        String handle = item.getHandle();
        String baseUrl = configurationService.getProperty("dspace.server.url") +
                "/api/core/refbox/citations?handle=/" + Utils.getCanonicalHandleUrlNoProtocol(item);
        String bibtexUrl = baseUrl + "&type=bibtex";
        String cmdiUrl = baseUrl + "&type=cmdi";

        getClient(token).perform(get("/api/core/refbox?handle=" + handle))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayText")
                        .value(org.hamcrest.Matchers.containsString("Test author 2")))
                .andExpect(jsonPath("$.displayText")
                        .value(org.hamcrest.Matchers.containsString(item.getHandle())))
                .andExpect(jsonPath("$.title").value("Public item 2"))
                .andExpect(jsonPath("$.exportFormats.exportFormat[*].name", hasItem("bibtex")))
                .andExpect(jsonPath("$.exportFormats.exportFormat[*].name", hasItem("cmdi")))
                .andExpect(jsonPath("$.exportFormats.exportFormat[?(@.name=='bibtex')].url")
                        .value(hasItem(bibtexUrl)))
                .andExpect(jsonPath("$.exportFormats.exportFormat[?(@.name=='cmdi')].url")
                        .value(hasItem(cmdiUrl)))
                .andExpect(jsonPath("$.exportFormats.exportFormat[?(@.name=='bibtex')].extract")
                        .value(hasItem("")))
                .andExpect(jsonPath("$.exportFormats.exportFormat[?(@.name=='cmdi')].extract")
                        .value(hasItem("")))
                .andExpect(jsonPath("$.exportFormats.exportFormat[?(@.name=='bibtex')].dataType")
                        .value(hasItem("json")))
                .andExpect(jsonPath("$.exportFormats.exportFormat[?(@.name=='cmdi')].dataType")
                        .value(hasItem("json")))
                .andExpect(jsonPath("$.featuredServices.featuredService").isEmpty());
    }

    @Test
    public void testRefboxInfoWithNullHandleParam() throws Exception {
        String token = getAuthToken(admin.getEmail(), password);
        getClient(token).perform(get("/api/core/refbox"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    public void testRefboxInfoWithMalformedHandle() throws Exception {
        String token = getAuthToken(admin.getEmail(), password);
        getClient(token).perform(get("/api/core/refbox?handle=notAHandle"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    public void testRefboxInfoWithOnlyPublisher() throws Exception {
        context.turnOffAuthorisationSystem();
        Item itemPublisher = ItemBuilder.createItem(context, collection)
                .withMetadata("dc", "publisher", null, "Test Publisher")
                .build();
        context.restoreAuthSystemState();

        String token = getAuthToken(admin.getEmail(), password);
        getClient(token).perform(get("/api/core/refbox?handle=" + itemPublisher.getHandle()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayText").value(org.hamcrest.Matchers.containsString("Test Publisher")));
    }

    @Test
    public void testRefboxInfoWithOnlyYear() throws Exception {
        context.turnOffAuthorisationSystem();
        Item itemYear = ItemBuilder.createItem(context, collection)
                .withIssueDate("2022")
                .build();
        context.restoreAuthSystemState();

        String token = getAuthToken(admin.getEmail(), password);
        getClient(token).perform(get("/api/core/refbox?handle=" + itemYear.getHandle()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayText").value(org.hamcrest.Matchers.containsString("2022")));
    }

    @Test
    public void testRefboxInfoWithOnlyTitle() throws Exception {
        context.turnOffAuthorisationSystem();
        Item itemTitle = ItemBuilder.createItem(context, collection)
                .withTitle("Title Only")
                .build();
        context.restoreAuthSystemState();

        String token = getAuthToken(admin.getEmail(), password);
        getClient(token).perform(get("/api/core/refbox?handle=" + itemTitle.getHandle()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayText").value(org.hamcrest.Matchers.containsString("Title Only")));
    }

    @Test
    public void testRefboxInfoWithDOIAndHandle() throws Exception {
        context.turnOffAuthorisationSystem();
        Item itemDOI = ItemBuilder.createItem(context, collection)
                .withTitle("DOI Item")
                .withDoiIdentifier("10.1234/abcd")
                .build();
        context.restoreAuthSystemState();

        String token = getAuthToken(admin.getEmail(), password);
        getClient(token).perform(get("/api/core/refbox?handle=" + itemDOI.getHandle()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayText").value(org.hamcrest.Matchers.containsString("10.1234/abcd")));
    }

    @Test
    public void testRefboxInfoWithWhitespaceMetadata() throws Exception {
        context.turnOffAuthorisationSystem();
        Item itemWhitespace = ItemBuilder.createItem(context, collection)
                .withTitle("   ")
                .withAuthor(" ")
                .build();
        context.restoreAuthSystemState();

        String token = getAuthToken(admin.getEmail(), password);
        getClient(token).perform(get("/api/core/refbox?handle=" + itemWhitespace.getHandle()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayText").exists());
    }

    @Test
    public void testFeaturedServiceWithDuplicateEntries() throws Exception {
        context.turnOffAuthorisationSystem();
        Item itemDupFS = ItemBuilder.createItem(context, collection)
                .withMetadata("local", "featuredService", "kontext", "Key1|Value1")
                .withMetadata("local", "featuredService", "kontext", "Key1|Value1")
                .build();
        context.restoreAuthSystemState();

        String token = getAuthToken(admin.getEmail(), password);
        getClient(token).perform(get("/api/core/refbox?handle=" + itemDupFS.getHandle()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.featuredServices.featuredService[0].links.entry.length()").value(2));
    }

    @Test
    public void testFeaturedServiceWithMalformedLink() throws Exception {
        context.turnOffAuthorisationSystem();
        Item itemMalformed = ItemBuilder.createItem(context, collection)
                .withMetadata("local", "featuredService", "kontext", "NoPipeDelimiter")
                .build();
        context.restoreAuthSystemState();

        String token = getAuthToken(admin.getEmail(), password);
        getClient(token).perform(get("/api/core/refbox?handle=" + itemMalformed.getHandle()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.featuredServices.featuredService").isEmpty());
    }

    @Test
    public void testDisplayTextWithOneAuthor() throws Exception {
        context.turnOffAuthorisationSystem();
        Item itemOneAuthor = ItemBuilder.createItem(context, collection)
                .withAuthor("Single Author")
                .build();
        context.restoreAuthSystemState();

        String token = getAuthToken(admin.getEmail(), password);
        getClient(token).perform(get("/api/core/refbox?handle=" + itemOneAuthor.getHandle()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayText").value(org.hamcrest.Matchers.containsString("Single Author")));
    }

    @Test
    public void testDisplayTextWithTwoAuthors() throws Exception {
        context.turnOffAuthorisationSystem();
        Item itemTwoAuthors = ItemBuilder.createItem(context, collection)
                .withAuthor("Author One")
                .withAuthor("Author Two")
                .build();
        context.restoreAuthSystemState();

        String token = getAuthToken(admin.getEmail(), password);
        getClient(token).perform(get("/api/core/refbox?handle=" + itemTwoAuthors.getHandle()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayText").value(org.hamcrest.Matchers.containsString(
                        "Author One and Author Two")));
    }

    @Test
    public void testDisplayTextWithFiveAuthors() throws Exception {
        context.turnOffAuthorisationSystem();
        Item itemFiveAuthors = ItemBuilder.createItem(context, collection)
                .withAuthor("A1")
                .withAuthor("A2")
                .withAuthor("A3")
                .withAuthor("A4")
                .withAuthor("A5")
                .build();
        context.restoreAuthSystemState();

        String token = getAuthToken(admin.getEmail(), password);
        getClient(token).perform(get("/api/core/refbox?handle=" + itemFiveAuthors.getHandle()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayText").value(org.hamcrest.Matchers.containsString(
                        "A1; A2; A3; A4 and A5")));
    }

    @Test
    public void testDisplayTextWithMoreThanFiveAuthors() throws Exception {
        context.turnOffAuthorisationSystem();
        Item itemManyAuthors = ItemBuilder.createItem(context, collection)
                .withAuthor("First Author")
                .withAuthor("Second Author")
                .withAuthor("Third Author")
                .withAuthor("Fourth Author")
                .withAuthor("Fifth Author")
                .withAuthor("Sixth Author")
                .build();
        context.restoreAuthSystemState();

        String token = getAuthToken(admin.getEmail(), password);
        getClient(token).perform(get("/api/core/refbox?handle=" + itemManyAuthors.getHandle()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayText").value(org.hamcrest.Matchers.containsString(
                        "First Author; et al.")));
    }

    // --- Citations endpoint tests (#1366) ---

    @Test
    public void testCitationsEndpointReturnsBibtex() throws Exception {
        // Verifies the /citations endpoint does not return a 500 for a valid handle + bibtex
        // type (regression test for #1366) and that the response is a valid OaiMetadataWrapper.
        // Content-Type must be application/json (catches reintroduction of the XML preset bug).
        // $.metadata must contain "@misc{" which is the BibTeX entry marker produced by the XSLT.
        getClient().perform(get("/api/core/refbox/citations")
                        .param("type", "bibtex")
                        .param("handle", item.getHandle()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.metadata", containsString("@misc{")));
    }

    @Test
    public void testCitationsEndpointReturnsCmdi() throws Exception {
        // Verifies the /citations endpoint does not return a 500 for a valid handle + cmdi type
        // (regression test for #1366) and that the response is a valid OaiMetadataWrapper.
        // Content-Type must be application/json (catches reintroduction of the XML preset bug).
        // $.metadata must be non-empty (the CMDI crosswalk always produces XML content).
        getClient().perform(get("/api/core/refbox/citations")
                        .param("type", "cmdi")
                        .param("handle", item.getHandle()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.metadata", not(emptyOrNullString())));
    }

    @Test
    public void testCitationsEndpointWithUrlBuiltHandle() throws Exception {
        // Reproduces the exact URL format that buildExportFormats() produces, where
        // the handle is the canonical URL path (e.g. "/hdl.handle.net/123456789/xxx").
        // The endpoint must not return 500 for this input.
        String handle = "/" + Utils.getCanonicalHandleUrlNoProtocol(item);
        getClient().perform(get("/api/core/refbox/citations")
                        .param("type", "bibtex")
                        .param("handle", handle))
                .andExpect(status().isOk());
    }

    @Test
    public void testCitationsEndpointWithMissingTypeParam() throws Exception {
        getClient().perform(get("/api/core/refbox/citations")
                        .param("handle", item.getHandle()))
                .andExpect(status().is4xxClientError());
    }

    @Test
    public void testCitationsEndpointWithMissingHandleParam() throws Exception {
        getClient().perform(get("/api/core/refbox/citations")
                        .param("type", "bibtex"))
                .andExpect(status().is4xxClientError());
    }
}
