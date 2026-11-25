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
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.namespace.NamespaceContext;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

import org.apache.commons.io.IOUtils;
import org.dspace.app.rest.test.AbstractControllerIntegrationTest;
import org.dspace.app.rest.utils.SolrOAIReindexer;
import org.dspace.builder.BitstreamBuilder;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.ItemBuilder;
import org.dspace.content.Collection;
import org.dspace.content.Item;
import org.dspace.core.Constants;
import org.dspace.services.ConfigurationService;
import org.dspace.solr.MockSolrServer;
import org.dspace.xoai.data.DSpaceItem;
import org.dspace.xoai.services.api.cache.XOAICacheService;
import org.dspace.xoai.services.api.solr.SolrServerResolver;
import org.dspace.xoai.services.api.xoai.ItemRepositoryResolver;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * End-to-end regression test for ufal/clarin-dspace#1383: the CMDI OAI crosswalk
 * ({@code dspace/config/crosswalks/oai/metadataFormats/lindat_cmdi.xsl}, template {@code ProcessBitstreams})
 * must emit machine-actionable {@code cmd:ResourceRef} download links of the form
 * {@code ${dspace.server.url}/api/core/bitstreams/handle/<handle>/<url-encoded-filename>} (no
 * {@code ?sequence=} query parameter), listing only bitstreams from the item's ORIGINAL bundle.
 * <P>
 * This test harvests a CMDI record via OAI-PMH GetRecord, extracts the resulting ResourceRef URL(s)
 * and then performs an actual download against that URL, verifying the returned content matches the
 * bitstream that was uploaded.
 */
@TestPropertySource(properties = {"oai.enabled = true"})
public class CmdiDownloadLinkIT extends AbstractControllerIntegrationTest {

    private static final String BITSTREAM_CONTENT = "CMDI download link e2e test content";

    @Autowired
    private ConfigurationService configurationService;

    @Autowired
    private SolrOAIReindexer solrOAIReindexer;

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
    }

    @After
    public void tearDownOAI() throws Exception {
        if (mockOAISolr != null) {
            mockOAISolr.destroy();
            mockOAISolr = null;
        }
    }

    @Test
    public void cmdiResourceRefIsMachineActionableDownloadLink() throws Exception {
        context.turnOffAuthorisationSystem();
        parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Parent Community")
                .build();
        Collection collection = CollectionBuilder.createCollection(context, parentCommunity)
                .withName("Collection")
                .build();
        Item item = ItemBuilder.createItem(context, collection)
                .withTitle("CMDI Download Link Test Item")
                .withIssueDate("2023-01-01")
                .withAuthor("Test Author")
                .build();

        // Bitstream in the ORIGINAL bundle — the filename has a space, to exercise URL-encoding end-to-end
        try (InputStream is = IOUtils.toInputStream(BITSTREAM_CONTENT, StandardCharsets.UTF_8)) {
            BitstreamBuilder.createBitstream(context, item, is)
                    .withName("test file.txt")
                    .withMimeType("text/plain")
                    .build();
        }
        // Bitstream in the LICENSE bundle — must NOT show up in the CMDI ResourceProxyList
        try (InputStream is = IOUtils.toInputStream("License text", StandardCharsets.UTF_8)) {
            BitstreamBuilder.createBitstream(context, item, is, Constants.LICENSE_BUNDLE_NAME)
                    .withName(Constants.LICENSE_BITSTREAM_NAME)
                    .withMimeType("text/plain")
                    .build();
        }
        context.restoreAuthSystemState();

        // Index the item in the xoai Solr core so it can be retrieved via OAI-PMH
        solrOAIReindexer.reindexItem(item);

        // Build the OAI identifier the same way production code does (see DSpaceItem#buildIdentifier)
        String oaiIdentifier = DSpaceItem.buildIdentifier(item.getHandle());

        String response = getClient().perform(get("/oai/request")
                        .param("verb", "GetRecord")
                        .param("metadataPrefix", "cmdi")
                        .param("identifier", oaiIdentifier))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Legacy download link forms must not reappear
        assertFalse("Response must not contain legacy /bitstream/handle/ links",
                response.contains("/bitstream/handle/"));
        assertFalse("Response must not contain a ?sequence= query parameter",
                response.contains("?sequence="));
        assertFalse("The LICENSE bundle bitstream must not be listed as a CMDI resource",
                response.contains(Constants.LICENSE_BITSTREAM_NAME));

        List<String> resourceRefs = extractResourceProxyRefs(response);
        assertEquals("Exactly one bitstream ResourceRef is expected (ORIGINAL bundle only)",
                1, resourceRefs.size());

        String serverUrl = configurationService.getProperty("dspace.server.url");
        String expectedRef = serverUrl + "/api/core/bitstreams/handle/" + item.getHandle() + "/test%20file.txt";
        assertEquals(expectedRef, resourceRefs.get(0));

        // Strip the server URL prefix to obtain the server-relative download path
        String relativePath = resourceRefs.get(0).substring(serverUrl.length());
        assertTrue("The download link must point at the by-handle bitstream endpoint",
                relativePath.startsWith("/api/core/bitstreams/handle/"));

        // Use URI.create so MockMvc does not re-encode the already-encoded %20
        getClient().perform(get(URI.create(relativePath)))
                .andExpect(status().isOk())
                .andExpect(content().string(BITSTREAM_CONTENT));
    }

    /**
     * Parses the OAI-PMH GetRecord response and returns the text content of every {@code cmd:ResourceRef}
     * element whose parent {@code cmd:ResourceProxy} is a bitstream proxy: {@code cmd:ResourceType} of
     * "Resource" and an id starting with "_" (bitstream proxy ids are "_&lt;uuid&gt;", which excludes the
     * LandingPage ("lp_...") and any source-URI proxies ("uri_...") emitted by the CMDI crosswalk).
     *
     * @param xml the raw OAI-PMH response body
     * @return the list of matching ResourceRef text values, in document order
     * @throws Exception if the response cannot be parsed as XML or the XPath expression fails
     */
    private List<String> extractResourceProxyRefs(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        // Harden the parser: no DOCTYPE/external entities are expected in an OAI-PMH response
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document document = builder.parse(new InputSource(new StringReader(xml)));

        XPath xPath = XPathFactory.newInstance().newXPath();
        xPath.setNamespaceContext(new NamespaceContext() {
            @Override
            public String getNamespaceURI(String prefix) {
                if ("cmd".equals(prefix)) {
                    return "http://www.clarin.eu/cmd/";
                }
                return XMLConstants.NULL_NS_URI;
            }

            @Override
            public String getPrefix(String namespaceURI) {
                return null;
            }

            @Override
            public Iterator<String> getPrefixes(String namespaceURI) {
                return Collections.emptyIterator();
            }
        });

        NodeList refNodes = (NodeList) xPath.evaluate(
                "//cmd:ResourceProxy[cmd:ResourceType='Resource' and starts-with(@id, '_')]/cmd:ResourceRef",
                document, XPathConstants.NODESET);

        List<String> refs = new ArrayList<>();
        for (int i = 0; i < refNodes.getLength(); i++) {
            refs.add(refNodes.item(i).getTextContent().trim());
        }
        return refs;
    }
}
