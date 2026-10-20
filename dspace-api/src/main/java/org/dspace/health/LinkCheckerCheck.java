/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.health;

import java.io.IOException;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.lang3.StringUtils;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Reports the results of the CLARIN link checker for the links in our harvested metadata: number of links per
 * category (Ok, Broken, Restricted_Access, Blocked_By_Robots_txt, ...) and the list of broken links, optionally
 * filtered by a regular expression (e.g. to show only links to our own hosts, which we can fix).
 * <p>
 * Data come from the CLARIN Curation Dashboard (https://curation.clarin.eu/linkchecker). Replaces the link checking
 * part ({@code fastchecklinks} curation task) of the v5 {@code CuratorCheck}.
 */
public class LinkCheckerCheck extends ClarinProviderCheck {

    static final String URL_PROPERTY = "healthcheck.linkchecker.url";
    static final String BROKEN_FILTER_PROPERTY = "healthcheck.linkchecker.broken.url-filter";
    static final String MAX_BROKEN_PROPERTY = "healthcheck.linkchecker.broken.max-to-show";
    private static final String DEFAULT_URL = "https://curation.clarin.eu";
    private static final int DEFAULT_MAX_BROKEN = 50;
    private static final String BROKEN = "Broken";

    @Override
    protected void run(ReportInfo ri, String providerName, StringBuilder sb) throws IOException {
        String url = stripTrailingSlash(getConfigurationService().getProperty(URL_PROPERTY, DEFAULT_URL));
        String filter = getConfigurationService().getProperty(BROKEN_FILTER_PROPERTY);
        int maxBroken = getConfigurationService().getIntProperty(MAX_BROKEN_PROPERTY, DEFAULT_MAX_BROKEN);

        JSONObject root = new JSONObject();
        root.put("configured", true);
        setReportJson(root);

        String reportUrl = url + "/download/linkchecker/AllLinkcheckerReport?format=json";
        JsonNode report = fetchJson(reportUrl).path("allLinkcheckerReport");
        JsonNode collection = null;
        for (JsonNode c : arrayOrSingleton(report.get("collection"), reportUrl)) {
            if (providerName.equals(c.path("name").asText())) {
                collection = c;
                break;
            }
        }
        if (collection == null) {
            sb.append("WARNING: provider [").append(providerName).append("] not found in the link checker report.\n");
            root.put("found", false);
            return;
        }
        root.put("found", true);
        String created = report.path("creationTime").asText();
        root.put("reportCreated", created);
        sb.append(String.format("Link checker report:      %s/linkchecker/%s (created %s)\n", url,
                encode(providerName), created));

        JSONObject categories = new JSONObject();
        long total = 0;
        for (JsonNode statistics : arrayOrSingleton(collection.get("statistics"), reportUrl)) {
            String category = statistics.path("category").asText();
            long count = statistics.path("count").asLong();
            categories.put(category, count);
            total += count;
            sb.append(String.format("  %-24s%8d\n", category + ":", count));
        }
        sb.append(String.format("  %-24s%8d\n", "Total:", total));
        root.put("categories", categories);
        root.put("total", total);

        if (categories.optLong(BROKEN) > 0) {
            try {
                reportBroken(url, providerName, filter, maxBroken, sb, root);
            } catch (IOException e) {
                error(e, "Could not get the list of broken links.");
            }
        }
    }

    private void reportBroken(String url, String providerName, String filter, int maxBroken, StringBuilder sb,
                              JSONObject root) throws IOException {
        String brokenUrl = url + "/download/linkchecker/" + encode(providerName) + "/" + BROKEN + "?format=json";
        JsonNode links = requireArray(fetchJson(brokenUrl).get("link"), brokenUrl);
        Pattern pattern = StringUtils.isBlank(filter) ? null : Pattern.compile(filter);
        StringBuilder lines = new StringBuilder();
        JSONArray shown = new JSONArray();
        int matching = 0;
        for (JsonNode link : links) {
            String linkUrl = link.path("url").asText();
            if (pattern != null && !pattern.matcher(linkUrl).find()) {
                continue;
            }
            matching++;
            if (shown.length() < maxBroken) {
                String status = link.path("statusCode").isNull() ? "-" : link.path("statusCode").asText();
                String message = link.path("message").asText();
                lines.append(String.format("  %-4s %s (%s)\n", status, linkUrl, message));
                shown.put(new JSONObject().put("url", linkUrl).put("status", status).put("message", message));
            }
        }
        sb.append("\n").append(pattern == null ? "Broken links: " : "Broken links matching [" + filter + "]: ")
                .append(matching).append("\n").append(lines);
        if (matching > maxBroken) {
            sb.append("  and more...\n");
        }
        root.put("brokenMatching", matching);
        root.put("brokenShown", shown);
    }

    /**
     * The summary report collapses one-element lists into a single object (e.g. the statistics of a provider
     * whose links all fall into one category), so accept both shapes.
     */
    private JsonNode arrayOrSingleton(JsonNode node, String url) throws IOException {
        if (node != null && node.isObject()) {
            return mapper.createArrayNode().add(node);
        }
        return requireArray(node, url);
    }
}
