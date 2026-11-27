/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.health;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.dspace.app.client.DSpaceHttpClientFactory;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.json.JSONObject;

/**
 * Base for checks that report what the CLARIN infrastructure knows about this repository as a metadata
 * provider (VLO harvester, link checker). Both services identify the repository by the same name, configured in
 * {@code healthcheck.clarin.provider.name}. When the name is not configured, the check makes no network calls and
 * only reports that it is not configured.
 */
public abstract class ClarinProviderCheck extends Check {

    static final String PROVIDER_NAME_PROPERTY = "healthcheck.clarin.provider.name";
    static final String TIMEOUT_PROPERTY = "healthcheck.clarin.http.timeout";
    private static final int DEFAULT_TIMEOUT_MS = 30_000;

    protected final ObjectMapper mapper = new ObjectMapper();

    @Override
    protected String run(ReportInfo ri) {
        String providerName = getConfigurationService().getProperty(PROVIDER_NAME_PROPERTY);
        if (StringUtils.isBlank(providerName)) {
            JSONObject root = new JSONObject();
            root.put("configured", false);
            setReportJson(root);
            return "Not configured (" + PROVIDER_NAME_PROPERTY + " is empty).\n";
        }
        StringBuilder sb = new StringBuilder();
        try {
            run(ri, providerName.trim(), sb);
        } catch (Exception e) {
            // a failing remote service must not fail the whole health report; keep what was collected so far
            error(e, "Could not get data from the CLARIN service.");
        }
        return sb.toString();
    }

    /**
     * Run the check for a configured provider.
     *
     * @param ri           report info
     * @param providerName name of this repository as a CLARIN metadata provider
     * @param sb           the textual report; what was appended is kept even when an exception is thrown
     * @throws Exception on any failure; it is reported as the check's error
     */
    protected abstract void run(ReportInfo ri, String providerName, StringBuilder sb) throws Exception;

    /**
     * Fetch the body of the given URL as a string, failing on any non-200 response.
     *
     * @param url the URL to fetch
     * @return the response body
     * @throws IOException on network error or non-200 response
     */
    protected String fetch(String url) throws IOException {
        int timeout = getConfigurationService().getIntProperty(TIMEOUT_PROPERTY, DEFAULT_TIMEOUT_MS);
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(timeout)
                .setConnectionRequestTimeout(timeout)
                .setSocketTimeout(timeout)
                .build();
        try (CloseableHttpClient client = DSpaceHttpClientFactory.getInstance().buildWithRequestConfig(requestConfig);
             CloseableHttpResponse response = client.execute(new HttpGet(url))) {
            int status = response.getStatusLine().getStatusCode();
            HttpEntity entity = response.getEntity();
            String body = entity == null ? "" : EntityUtils.toString(entity, StandardCharsets.UTF_8);
            if (status != 200) {
                throw new IOException("GET " + url + " returned HTTP " + status);
            }
            return body;
        }
    }

    protected JsonNode fetchJson(String url) throws IOException {
        return mapper.readTree(fetch(url));
    }

    protected JsonNode fetchJsonArray(String url) throws IOException {
        return requireArray(fetchJson(url), url);
    }

    /**
     * Fail on an unexpected response instead of silently treating it as an empty result.
     *
     * @param node the node expected to be a JSON array
     * @param url  the URL the node came from, for the error message
     * @return the node
     * @throws IOException if the node is not an array
     */
    protected static JsonNode requireArray(JsonNode node, String url) throws IOException {
        if (node == null || !node.isArray()) {
            throw new IOException("Unexpected response from " + url + ": expected a JSON array");
        }
        return node;
    }

    protected Instant now() {
        return Instant.now();
    }

    protected ConfigurationService getConfigurationService() {
        return DSpaceServicesFactory.getInstance().getConfigurationService();
    }

    protected static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    protected static String stripTrailingSlash(String url) {
        return StringUtils.removeEnd(url.trim(), "/");
    }
}
