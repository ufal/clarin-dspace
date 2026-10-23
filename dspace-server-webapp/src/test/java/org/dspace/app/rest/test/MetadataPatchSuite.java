/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.ws.rs.core.MediaType;
import org.junit.Assert;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Utility class for performing metadata patch tests sourced from a common json file (see constructor).
 */
public class MetadataPatchSuite {
    static String PROVENANCE = "dc.description.provenance";

    private final ObjectMapper mapper;

    private final JsonNode suite;

    /**
     * Initializes the suite by parsing the json file of tests.
     *
     * @param mapper the initialized ObjectMapper (e.g. from Spring Boot)
     * @throws Exception if there is an error reading the file.
     */
    public MetadataPatchSuite(ObjectMapper mapper) throws Exception {
        this.mapper = mapper;
        suite = mapper.readTree(getClass().getResourceAsStream("metadata-patch-suite.json"));
    }

    /**
     * Initializes the suite by parsing a specific json file of tests (e.g. the item variant, whose
     * expectations include the CLARIN provenance values written on every item metadata patch).
     *
     * @param mapper the initialized ObjectMapper (e.g. from Spring Boot)
     * @param name the classpath-relative json resource with the tests.
     * @throws Exception if there is an error reading the file.
     */
    public MetadataPatchSuite(ObjectMapper mapper, String name) throws Exception {
        this.mapper = mapper;
        suite = mapper.readTree(getClass().getResourceAsStream(name));
    }

    /**
     * Runs all tests in the file using the given client and url, expecting the given status.
     *
     * @param client the client to use.
     * @param url the url to issue the patch against.
     * @param expectedStatus the expected http status code. If this does not match the actual code, the test fails.
     */
    public void runWith(MockMvc client, String url, int expectedStatus) {
        for (JsonNode testNode: suite.get("tests")) {
            String requestBody = testNode.get("patch").toString();
            String expectedMetadata = testNode.get("expect").toString();
            try {
                System.out.println("Running patch test: " + testNode.get("name") + "\nRequest: " + requestBody);
                checkResponse("PATCH", client, patch(url).content(requestBody), expectedMetadata, expectedStatus);
                if (expectedStatus >= 200 && expectedStatus < 300) {
                    checkResponse("GET", client, get(url), expectedMetadata, expectedStatus);
                }
            } catch (Throwable t) {
                Assert.fail("Metadata patch test '" + testNode.get("name") + "' failed.\n" + "Request body: "
                        + requestBody + "\n" + "Error: " + (t instanceof AssertionError ? "" : t.getClass().getName())
                        + t.getMessage());
            }
        }
    }

    /**
     * Issues a PATCH or GET request and checks that the body and response code match what is expected.
     *
     * @param verb the http verb (PATCH or GET).
     * @param client the client to use.
     * @param requestBuilder the request builder that has been pre-seeded with the request url and request body.
     * @param expectedMetadata the expected metadata as a minimal (no extra spaces) json string. Note: This will
     *                         only be checked if the expectedStatus is in the 200 range.
     * @param expectedStatus the expected http response status.
     * @throws Exception if any checked error occurs, signifying test failure.
     */
    private void checkResponse(String verb, MockMvc client, MockHttpServletRequestBuilder requestBuilder,
                               String expectedMetadata, int expectedStatus) throws Exception {
        ResultActions resultActions = client.perform(requestBuilder
                .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                .andExpect(status().is(expectedStatus));
        if (expectedStatus >= 200 && expectedStatus < 300) {
          String responseBody = resultActions.andReturn().getResponse().getContentAsString();
          JsonNode responseJson =  mapper.readTree(responseBody);
          JsonNode responseMetadataJson = responseJson.get("metadata");
          if (responseMetadataJson.get(PROVENANCE) != null) {
              // The provenance value embeds the action timestamp; strip it before comparing.
              String rspProvenance = responseMetadataJson.get(PROVENANCE).toString();
              String datePattern = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z";
              Matcher matcher = Pattern.compile(datePattern).matcher(rspProvenance);
              String rspModifiedProvenance = rspProvenance;
              while (matcher.find()) {
                  rspModifiedProvenance = rspModifiedProvenance.replaceAll(matcher.group(0), "");
              }
              ((ObjectNode) responseMetadataJson).set(PROVENANCE, mapper.readTree(rspModifiedProvenance));
          }
          String responseMetadata = responseMetadataJson.toString();
          if (!responseMetadata.equals(expectedMetadata)) {
              Assert.fail("Expected metadata in " + verb + " response: " + expectedMetadata
                      + "\nGot metadata in " + verb + " response: " + responseMetadata);
          }
        }
    }
}
