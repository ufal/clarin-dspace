/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.health;

import java.io.IOException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.lang3.StringUtils;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Reports how the CLARIN OAI harvester (which feeds the VLO) sees this repository: the last harvest of our endpoint,
 * the number of harvested records and its change since the previous harvest, whether the endpoint was part of the
 * latest harvest run and warnings/errors from the endpoint's harvest log.
 * <p>
 * Data come from the public API of the OAI harvest viewer (https://vlo.clarin.eu/oai-harvest-viewer/) and the
 * harvest logs it links to. Replaces the v5 {@code VLOCheck}.
 */
public class VloHarvestCheck extends ClarinProviderCheck {

    static final String API_URL_PROPERTY = "healthcheck.vlo.api.url";
    static final String DATA_URL_PROPERTY = "healthcheck.vlo.data.url";
    static final String MAX_AGE_PROPERTY = "healthcheck.vlo.max-harvest-age-days";
    private static final String DEFAULT_API_URL = "https://vlo.clarin.eu/oai-harvest-viewer/api";
    private static final String DEFAULT_DATA_URL = "https://vlo.clarin.eu/data";
    private static final int DEFAULT_MAX_AGE_DAYS = 7;
    private static final int MAX_LOG_LINES_TO_SHOW = 20;

    // e.g. "2026-10-19T18:01:36,148 WARN [provider] TransformAction - ..."
    private static final Pattern LOG_LINE = Pattern.compile("^(\\S+) (WARN|ERROR) (.*)$");

    @Override
    protected String run(ReportInfo ri, String providerName) throws IOException {
        String apiUrl = stripTrailingSlash(getConfigurationService().getProperty(API_URL_PROPERTY, DEFAULT_API_URL));
        String dataUrl = stripTrailingSlash(getConfigurationService().getProperty(DATA_URL_PROPERTY,
                DEFAULT_DATA_URL));
        int maxAgeDays = getConfigurationService().getIntProperty(MAX_AGE_PROPERTY, DEFAULT_MAX_AGE_DAYS);

        StringBuilder sb = new StringBuilder();
        JSONObject root = new JSONObject();
        root.put("configured", true);
        setReportJson(root);

        // the two most recent harvests of our endpoint
        JsonNode runs = fetchJson(apiUrl + "/mv_endpoint_info?name=eq." + encode(providerName)
                + "&order=when.desc&limit=2");
        if (!runs.isArray() || runs.size() == 0) {
            sb.append("WARNING: endpoint [").append(providerName).append("] not found in the VLO harvester.\n");
            root.put("found", false);
            return sb.toString();
        }
        root.put("found", true);
        JsonNode last = runs.get(0);
        String type = last.path("type").asText();
        long harvestId = last.path("harvest_id").asLong();
        String finished = last.path("when").asText();
        long records = last.path("records").asLong();
        long ageDays = Duration.between(OffsetDateTime.parse(finished).toInstant(), now()).toDays();

        sb.append(String.format("Endpoint:                 %s (id %d, type %s)\n", providerName,
                last.path("endpoint_id").asLong(), type));
        sb.append(String.format("Last harvest run:         %d, finished %s (%d days ago)\n", harvestId, finished,
                ageDays));
        root.put("harvestId", harvestId);
        root.put("harvestFinished", finished);
        root.put("harvestAgeDays", ageDays);
        root.put("records", records);

        if (runs.size() > 1) {
            long previousRecords = runs.get(1).path("records").asLong();
            sb.append(String.format("Records harvested:        %d (previous harvest run %d: %d, change: %+d)\n",
                    records, runs.get(1).path("harvest_id").asLong(), previousRecords, records - previousRecords));
            root.put("previousRecords", previousRecords);
            root.put("recordsChange", records - previousRecords);
        } else {
            sb.append(String.format("Records harvested:        %d\n", records));
        }

        if (ageDays > maxAgeDays) {
            sb.append(String.format("WARNING: the last harvest is older than %d days.\n", maxAgeDays));
        }

        // was our endpoint part of the latest harvest run of its type?
        JsonNode latestRuns = fetchJson(apiUrl + "/mv_harvest_info?type=eq." + encode(type)
                + "&order=when.desc&limit=1");
        if (latestRuns.isArray() && latestRuns.size() > 0) {
            JsonNode latest = latestRuns.get(0);
            boolean inLatest = latest.path("harvest_id").asLong() == harvestId;
            root.put("inLatestHarvest", inLatest);
            if (!inLatest) {
                sb.append(String.format("WARNING: the endpoint was not part of the latest %s harvest run %d "
                        + "(finished %s).\n", type, latest.path("harvest_id").asLong(),
                        latest.path("when").asText()));
            }
        }

        // the harvest log of the last harvest; only the latest one is published
        String logUrl = dataUrl + "/" + encode(type) + "/log/" + encode(providerName) + ".log";
        sb.append("Harvest log:              ").append(logUrl).append("\n");
        try {
            reportLog(fetch(logUrl), sb, root);
        } catch (IOException e) {
            error(e, "Could not get the harvest log.");
        }
        return sb.toString();
    }

    private void reportLog(String log, StringBuilder sb, JSONObject root) {
        String[] lines = log.split("\\R");
        String started = lines.length > 0 ? StringUtils.substringBefore(lines[0], " ") : "";
        int warnings = 0;
        int errors = 0;
        List<String> problems = new ArrayList<>();
        for (String line : lines) {
            Matcher m = LOG_LINE.matcher(line);
            if (!m.matches()) {
                continue;
            }
            if ("WARN".equals(m.group(2))) {
                warnings++;
            } else {
                errors++;
            }
            problems.add(line);
        }
        sb.append(String.format("  started:                %s\n", started));
        sb.append(String.format("  warnings / errors:      %d / %d\n", warnings, errors));
        JSONArray shown = new JSONArray();
        for (String line : problems.subList(0, Math.min(problems.size(), MAX_LOG_LINES_TO_SHOW))) {
            sb.append("  ").append(line).append("\n");
            shown.put(line);
        }
        if (problems.size() > MAX_LOG_LINES_TO_SHOW) {
            sb.append("  and more...\n");
        }
        root.put("logStarted", started);
        root.put("logWarnings", warnings);
        root.put("logErrors", errors);
        root.put("logProblems", shown);
    }
}
