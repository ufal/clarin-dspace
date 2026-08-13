/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.InputStream;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.codec.CharEncoding;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.IOUtils;
import org.dspace.app.rest.test.AbstractControllerIntegrationTest;
import org.dspace.authorize.service.AuthorizeService;
import org.dspace.builder.BitstreamBuilder;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.ItemBuilder;
import org.dspace.builder.ResourcePolicyBuilder;
import org.dspace.content.Bitstream;
import org.dspace.content.Collection;
import org.dspace.content.Item;
import org.dspace.content.service.ItemService;
import org.dspace.core.Constants;
import org.dspace.services.ConfigurationService;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Integration tests for {@link ClarinHuggingFaceController}.
 */
public class ClarinHuggingFaceControllerIT extends AbstractControllerIntegrationTest {

    private static final String ENDPOINT_BASE = "/api/hf/api/models";

    private static final String ZERO_REVISION = "0000000000000000000000000000000000000000";

    private static final String MODEL_BIN_CONTENT = "hello model";

    private static final String CONFIG_JSON_CONTENT = "config content";

    @Autowired
    private ConfigurationService configurationService;

    @Autowired
    private AuthorizeService authorizeService;

    @Autowired
    private ItemService itemService;

    private Item exposedItem;

    private Item nonExposedItem;

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();

        configurationService.setProperty("hf.api.enabled", true);
        configurationService.setProperty("hf.api.filter.metadata-field", "dc.type");
        configurationService.setProperty("hf.api.filter.value", "machineLearningModel");

        context.turnOffAuthorisationSystem();

        parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Parent Community")
                .build();
        Collection collection = CollectionBuilder.createCollection(context, parentCommunity)
                .withName("Collection")
                .build();

        exposedItem = ItemBuilder.createItem(context, collection)
                .withTitle("A Machine Learning Model")
                .withType("machineLearningModel")
                .withSubject("nlp")
                .withSubject("classification")
                .build();
        try (InputStream is = IOUtils.toInputStream(MODEL_BIN_CONTENT, CharEncoding.UTF_8)) {
            BitstreamBuilder.createBitstream(context, exposedItem, is)
                    .withName("model.bin")
                    .withMimeType("application/octet-stream")
                    .build();
        }
        try (InputStream is = IOUtils.toInputStream(CONFIG_JSON_CONTENT, CharEncoding.UTF_8)) {
            BitstreamBuilder.createBitstream(context, exposedItem, is)
                    .withName("config.json")
                    .withMimeType("application/json")
                    .build();
        }

        nonExposedItem = ItemBuilder.createItem(context, collection)
                .withTitle("A Plain Item")
                .build();

        context.restoreAuthSystemState();
    }

    @Test
    public void modelInfoReturnsExpectedShape() throws Exception {
        String[] handleParts = exposedItem.getHandle().split("/");

        getClient().perform(get(ENDPOINT_BASE + "/" + handleParts[0] + "/" + handleParts[1]))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(exposedItem.getHandle())))
                .andExpect(jsonPath("$.sha", matchesPattern("^[0-9a-f]{40}$")))
                .andExpect(jsonPath("$.private", is(false)))
                .andExpect(jsonPath("$.gated", is(false)))
                .andExpect(jsonPath("$.downloads", is(0)))
                .andExpect(jsonPath("$.siblings", hasSize(2)))
                .andExpect(jsonPath("$.siblings[*].rfilename", hasItem("model.bin")))
                .andExpect(jsonPath("$.siblings[*].rfilename", hasItem("config.json")))
                .andExpect(jsonPath("$.tags", hasItem("nlp")))
                .andExpect(jsonPath("$.tags", hasItem("classification")))
                .andExpect(jsonPath("$.tags", hasItem("machineLearningModel")));
    }

    @Test
    public void revisionMainIsValidAndZeroRevisionIsNotFound() throws Exception {
        String[] handleParts = exposedItem.getHandle().split("/");
        String base = ENDPOINT_BASE + "/" + handleParts[0] + "/" + handleParts[1];

        getClient().perform(get(base + "/revision/main"))
                .andExpect(status().isOk());

        getClient().perform(get(base + "/revision/" + ZERO_REVISION))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Error-Code", "RevisionNotFound"));
    }

    @Test
    public void treeMainReturnsFileEntriesWithCorrectSizeAndOid() throws Exception {
        String[] handleParts = exposedItem.getHandle().split("/");

        MvcResult result = getClient()
                .perform(get(ENDPOINT_BASE + "/" + handleParts[0] + "/" + handleParts[1] + "/tree/main"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].type", everyItem(is("file"))))
                .andExpect(jsonPath("$[*].oid", everyItem(matchesPattern("^[0-9a-f]{40}$"))))
                .andReturn();

        JsonNode entries = new ObjectMapper().readTree(result.getResponse().getContentAsString());
        Map<String, JsonNode> byPath = new HashMap<>();
        for (JsonNode entry : entries) {
            byPath.put(entry.get("path").asText(), entry);
        }

        assertEquals(MODEL_BIN_CONTENT.length(), byPath.get("model.bin").get("size").asLong());
        assertEquals(DigestUtils.sha1Hex("model.bin" + "\n" + DigestUtils.md5Hex(MODEL_BIN_CONTENT)),
                byPath.get("model.bin").get("oid").asText());
        assertEquals(CONFIG_JSON_CONTENT.length(), byPath.get("config.json").get("size").asLong());
        assertEquals(DigestUtils.sha1Hex("config.json" + "\n" + DigestUtils.md5Hex(CONFIG_JSON_CONTENT)),
                byPath.get("config.json").get("oid").asText());
    }

    @Test
    public void treeAcceptsCurrentSha() throws Exception {
        String[] handleParts = exposedItem.getHandle().split("/");
        String base = ENDPOINT_BASE + "/" + handleParts[0] + "/" + handleParts[1];

        MvcResult result = getClient().perform(get(base))
                .andExpect(status().isOk())
                .andReturn();
        String content = result.getResponse().getContentAsString();
        JsonNode json = new ObjectMapper().readTree(content);
        String sha = json.get("sha").asText();

        getClient().perform(get(base + "/tree/" + sha))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    public void refsReturnsMainBranchAtCurrentSha() throws Exception {
        String handle = exposedItem.getHandle();
        String[] handleParts = handle.split("/");
        String sha = fetchModelSha(handle);

        getClient().perform(get(ENDPOINT_BASE + "/" + handleParts[0] + "/" + handleParts[1] + "/refs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.branches", hasSize(1)))
                .andExpect(jsonPath("$.branches[0].name", is("main")))
                .andExpect(jsonPath("$.branches[0].ref", is("refs/heads/main")))
                .andExpect(jsonPath("$.branches[0].targetCommit", is(sha)))
                .andExpect(jsonPath("$.converts", hasSize(0)))
                .andExpect(jsonPath("$.tags", hasSize(0)));
    }

    @Test
    public void refsTargetCommitIsAcceptedAsRevision() throws Exception {
        String[] handleParts = exposedItem.getHandle().split("/");
        String base = ENDPOINT_BASE + "/" + handleParts[0] + "/" + handleParts[1];

        MvcResult result = getClient().perform(get(base + "/refs"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = new ObjectMapper().readTree(result.getResponse().getContentAsString());
        String targetCommit = json.get("branches").get(0).get("targetCommit").asText();

        // Real clients feed targetCommit straight back as the revision of the next tree call.
        getClient().perform(get(base + "/tree/" + targetCommit))
                .andExpect(status().isOk());
    }

    @Test
    public void refsNonExposedItemIsNotFound() throws Exception {
        String[] handleParts = nonExposedItem.getHandle().split("/");

        getClient().perform(get(ENDPOINT_BASE + "/" + handleParts[0] + "/" + handleParts[1] + "/refs"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Error-Code", "RepoNotFound"));
    }

    @Test
    public void refsDisabledFacadeReturnsNotFound() throws Exception {
        configurationService.setProperty("hf.api.enabled", false);
        String[] handleParts = exposedItem.getHandle().split("/");

        getClient().perform(get(ENDPOINT_BASE + "/" + handleParts[0] + "/" + handleParts[1] + "/refs"))
                .andExpect(status().isNotFound());
    }

    @Test
    public void nonExposedItemIsNotFound() throws Exception {
        String[] handleParts = nonExposedItem.getHandle().split("/");

        getClient().perform(get(ENDPOINT_BASE + "/" + handleParts[0] + "/" + handleParts[1]))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Error-Code", "RepoNotFound"));
    }

    @Test
    public void listModelsReturnsOnlyExposedItems() throws Exception {
        context.turnOffAuthorisationSystem();
        Collection collection = exposedItem.getOwningCollection();
        Item secondExposedItem = ItemBuilder.createItem(context, collection)
                .withTitle("Another Machine Learning Model")
                .withType("machineLearningModel")
                .build();
        context.restoreAuthSystemState();

        getClient().perform(get(ENDPOINT_BASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(exposedItem.getHandle())))
                .andExpect(jsonPath("$[*].id", hasItem(secondExposedItem.getHandle())))
                .andExpect(jsonPath("$[*].id", not(hasItem(nonExposedItem.getHandle()))));
    }

    @Test
    public void listModelsExcludesItemsWithoutReadAccess() throws Exception {
        context.turnOffAuthorisationSystem();
        Collection collection = exposedItem.getOwningCollection();
        Item restrictedExposedItem = ItemBuilder.createItem(context, collection)
                .withTitle("Restricted Machine Learning Model")
                .withType("machineLearningModel")
                .build();
        // Remove all read policies from the item and grant READ to admin only, so the item is still
        // "exposed" (passes the HuggingFace filter) but not readable by an anonymous user.
        authorizeService.removeAllPolicies(context, restrictedExposedItem);
        ResourcePolicyBuilder.createResourcePolicy(context, admin, null)
                .withDspaceObject(restrictedExposedItem)
                .withAction(Constants.READ)
                .build();
        context.restoreAuthSystemState();

        // Anonymous user cannot read the restricted item, so it must not appear in the listing.
        getClient().perform(get(ENDPOINT_BASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(exposedItem.getHandle())))
                .andExpect(jsonPath("$[*].id", not(hasItem(restrictedExposedItem.getHandle()))));

        // The admin can read it, so it must appear in the listing.
        String adminToken = getAuthToken(admin.getEmail(), password);
        getClient(adminToken).perform(get(ENDPOINT_BASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(restrictedExposedItem.getHandle())));
    }

    @Test
    public void listModelsSupportsSearchAndLimit() throws Exception {
        context.turnOffAuthorisationSystem();
        Collection collection = exposedItem.getOwningCollection();
        ItemBuilder.createItem(context, collection)
                .withTitle("Second Model")
                .withType("machineLearningModel")
                .build();
        context.restoreAuthSystemState();

        getClient().perform(get(ENDPOINT_BASE).param("search", "Machine Learning"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id", is(exposedItem.getHandle())));

        getClient().perform(get(ENDPOINT_BASE).param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    public void listModelsDisabledFacadeReturnsNotFound() throws Exception {
        configurationService.setProperty("hf.api.enabled", false);

        getClient().perform(get(ENDPOINT_BASE))
                .andExpect(status().isNotFound());
    }

    @Test
    public void disabledFacadeReturnsNotFound() throws Exception {
        configurationService.setProperty("hf.api.enabled", false);
        String[] handleParts = exposedItem.getHandle().split("/");

        getClient().perform(get(ENDPOINT_BASE + "/" + handleParts[0] + "/" + handleParts[1]))
                .andExpect(status().isNotFound());
    }

    @Test
    public void withdrawnItemIsNotFound() throws Exception {
        String[] handleParts = exposedItem.getHandle().split("/");

        context.turnOffAuthorisationSystem();
        itemService.withdraw(context, exposedItem);
        context.restoreAuthSystemState();

        getClient().perform(get(ENDPOINT_BASE + "/" + handleParts[0] + "/" + handleParts[1]))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Error-Code", "RepoNotFound"));
    }

    @Test
    public void whoAmIAlwaysReturnsUnauthorized() throws Exception {
        getClient().perform(get("/api/hf/api/whoami-v2"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void resolveHeadReturnsMetadataHeaders() throws Exception {
        String handle = exposedItem.getHandle();
        String sha = fetchModelSha(handle);

        getClient().perform(head("/api/hf/" + handle + "/resolve/main/model.bin"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Repo-Commit", sha))
                .andExpect(header().string("ETag", "\"" + DigestUtils.md5Hex(MODEL_BIN_CONTENT) + "\""))
                .andExpect(header().longValue("Content-Length", MODEL_BIN_CONTENT.length()))
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andExpect(header().exists("X-Linked-Etag"));
    }

    @Test
    public void resolveGetStreamsFileContent() throws Exception {
        String handle = exposedItem.getHandle();

        getClient().perform(get("/api/hf/" + handle + "/resolve/main/model.bin"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Repo-Commit"))
                .andExpect(content().bytes(MODEL_BIN_CONTENT.getBytes()));
    }

    @Test
    public void resolveGetSupportsRangeRequests() throws Exception {
        String handle = exposedItem.getHandle();

        getClient().perform(get("/api/hf/" + handle + "/resolve/main/model.bin")
                        .header("Range", "bytes=1-3"))
                .andExpect(status().is(206))
                // The Content-Length must match the requested range
                .andExpect(header().longValue("Content-Length", 3))
                // The server should indicate we support Range requests
                .andExpect(header().string("Accept-Ranges", "bytes"))
                // The ETag has to be based on the checksum
                .andExpect(header().string("ETag", "\"" + DigestUtils.md5Hex(MODEL_BIN_CONTENT) + "\""))
                // The response should give us details about the range
                .andExpect(header().string("Content-Range", "bytes 1-3/" + MODEL_BIN_CONTENT.length()))
                // We only expect the bytes 1, 2 and 3
                .andExpect(content().bytes(MODEL_BIN_CONTENT.substring(1, 4).getBytes()));
    }

    @Test
    public void resolveGetSupportsZeroLengthRangeProbe() throws Exception {
        // The @huggingface/hub JS client probes with Range: bytes=0-0 and derives the file size purely
        // from Content-Range, so this exact single-byte probe is a real client contract.
        String handle = exposedItem.getHandle();

        getClient().perform(get("/api/hf/" + handle + "/resolve/main/model.bin")
                        .header("Range", "bytes=0-0"))
                .andExpect(status().is(206))
                .andExpect(header().string("Content-Range", "bytes 0-0/" + MODEL_BIN_CONTENT.length()))
                .andExpect(header().longValue("Content-Length", 1))
                .andExpect(header().string("ETag", "\"" + DigestUtils.md5Hex(MODEL_BIN_CONTENT) + "\""));
    }

    @Test
    public void resolveAcceptsCurrentShaAndRejectsUnknownRevision() throws Exception {
        String handle = exposedItem.getHandle();
        String sha = fetchModelSha(handle);

        getClient().perform(get("/api/hf/" + handle + "/resolve/" + sha + "/model.bin"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(MODEL_BIN_CONTENT.getBytes()));

        getClient().perform(get("/api/hf/" + handle + "/resolve/" + ZERO_REVISION + "/model.bin"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Error-Code", "RevisionNotFound"));
    }

    @Test
    public void resolveRestrictedBitstreamReturnsUnauthorizedOrForbidden() throws Exception {
        String handle = exposedItem.getHandle();

        context.turnOffAuthorisationSystem();
        Bitstream restricted;
        try (InputStream is = IOUtils.toInputStream("secret content", CharEncoding.UTF_8)) {
            restricted = BitstreamBuilder.createBitstream(context, exposedItem, is)
                    .withName("secret.bin")
                    .withMimeType("application/octet-stream")
                    .build();
        }
        // Remove all read policies from the bitstream and add a read policy only for admin
        authorizeService.removeAllPolicies(context, restricted);
        ResourcePolicyBuilder.createResourcePolicy(context, admin, null)
                .withDspaceObject(restricted)
                .withAction(Constants.READ)
                .build();
        context.restoreAuthSystemState();

        // Anonymous user should get 401
        getClient().perform(get("/api/hf/" + handle + "/resolve/main/secret.bin"))
                .andExpect(status().isUnauthorized());

        // Authenticated non-admin user should get 403
        String token = getAuthToken(eperson.getEmail(), password);
        getClient(token).perform(get("/api/hf/" + handle + "/resolve/main/secret.bin"))
                .andExpect(status().isForbidden());
    }

    @Test
    public void resolveMissingFileReturnsEntryNotFound() throws Exception {
        String handle = exposedItem.getHandle();

        getClient().perform(get("/api/hf/" + handle + "/resolve/main/nope.bin"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Error-Code", "EntryNotFound"));
    }

    @Test
    public void resolveSupportsUrlEncodedFilenames() throws Exception {
        String handle = exposedItem.getHandle();
        String encodedFileContent = "encoded filename content";

        context.turnOffAuthorisationSystem();
        try (InputStream is = IOUtils.toInputStream(encodedFileContent, CharEncoding.UTF_8)) {
            BitstreamBuilder.createBitstream(context, exposedItem, is)
                    .withName("my model (v2).bin")
                    .withMimeType("application/octet-stream")
                    .build();
        }
        context.restoreAuthSystemState();

        getClient().perform(get(URI.create("/api/hf/" + handle + "/resolve/main/my%20model%20%28v2%29.bin")))
                .andExpect(status().isOk())
                .andExpect(content().bytes(encodedFileContent.getBytes()));
    }

    /**
     * Fetch the current sha of a model repository from its model-info JSON.
     *
     * @param handle the item handle
     * @return the sha reported by the model-info endpoint
     */
    private String fetchModelSha(String handle) throws Exception {
        MvcResult result = getClient().perform(get(ENDPOINT_BASE + "/" + handle))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = new ObjectMapper().readTree(result.getResponse().getContentAsString());
        return json.get("sha").asText();
    }
}
