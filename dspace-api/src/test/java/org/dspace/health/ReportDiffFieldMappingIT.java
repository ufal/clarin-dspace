/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.health;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dspace.AbstractIntegrationTestWithDatabase;
import org.dspace.app.launcher.ScriptLauncher;
import org.dspace.app.scripts.handler.impl.TestDSpaceRunnableHandler;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.ItemBuilder;
import org.dspace.content.Collection;
import org.dspace.content.Community;
import org.dspace.content.ReportResult;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.ReportResultService;
import org.dspace.core.factory.CoreServiceFactory;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.dspace.storage.bitstore.DSBitStoreService;
import org.dspace.utils.DSpace;
import org.junit.Before;
import org.junit.Test;

/**
 * Guards the contract between {@code report-diff-fields.json} and the health checks that are supposed to
 * fill it.
 * <P>
 * {@code report-diff} does not read the checks; it reads the JSON a health-report run stored, addressing
 * every value by a path such as
 * {@code /checks/[name=Item summary]/report/collectionsSizesInfo/totalSize}. Nothing links that path back
 * to the class that is meant to emit it, so a check can stop emitting - or never start - and the only
 * symptom is a column that is quietly always empty. That is exactly what the v9 upgrade did: it took
 * {@code InfoCheck}, {@code ItemCheck} and {@code UserCheck} wholesale from vanilla, which has no
 * {@code setReportJson} at all, and 23 of the 26 mappings lost their emitter without a single test
 * turning red.
 * <P>
 * These tests close that gap from both ends: every mapped path must resolve in a real health report, every
 * mapped check name must resolve to a {@link Check} on the classpath, and every mapping must also appear in
 * {@code fieldOrder} - {@code ReportDiff} iterates {@code fieldOrder}, so a mapping missing from it is
 * never printed either.
 */
public class ReportDiffFieldMappingIT extends AbstractIntegrationTestWithDatabase {

    /** The mapping file, read from the classpath exactly as {@code ReportDiff} reads it. */
    private static final String MAPPINGS_RESOURCE = "/report-diff-fields.json";

    private static final String FIELD_MAPPINGS = "fieldMappings";
    private static final String FIELD_ORDER = "fieldOrder";

    /** {@code /checks/[name=<check>]/report/<path inside the check's JSON>} */
    private static final Pattern MAPPING_PATH =
            Pattern.compile("^/?checks/\\[name=([^]]+)]/report/(.+)$");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Before
    public void setUpFixture() throws Exception {
        context.turnOffAuthorisationSystem();
        Community community = CommunityBuilder.createCommunity(context)
                .withName("Report diff mapping community")
                .build();
        Collection collection = CollectionBuilder.createCollection(context, community)
                .withName("Report diff mapping collection")
                .build();
        // Deliberately no bitstream: a full health report runs the Checksum check, which records a
        // most_recent_checksum row for every bitstream it sees, and the foreign key from that row then
        // blocks the builder teardown. Nothing this test asserts needs one.
        ItemBuilder.createItem(context, collection)
                .withTitle("Report diff mapping item")
                .withIssueDate("2026-09-10")
                .build();
        context.restoreAuthSystemState();
        context.commit();

        // General Information reports one entry per directory and report-diff addresses them by position
        // (directoryStats/0 = assetstore, directoryStats/1 = log dir). A directory that does not exist
        // yields no size, so make sure both are there before the report runs.
        ConfigurationService configurationService = DSpaceServicesFactory.getInstance().getConfigurationService();
        DSBitStoreService localStore = new DSpace().getServiceManager()
                .getServicesByType(DSBitStoreService.class).get(0);
        ensureDirectory(localStore.getBaseDir().toString());
        ensureDirectory(configurationService.getProperty("log.report.dir"));
    }

    private void ensureDirectory(String path) {
        if (path == null) {
            return;
        }
        File dir = new File(path);
        if (!dir.exists()) {
            assertTrue("Could not create the directory the health report reads: " + path, dir.mkdirs());
        }
    }

    /**
     * The point of this class: run a real health report and resolve every mapped path in what it produced.
     * A check that emits no JSON, or emits it under a different key, fails here with the path named.
     */
    @Test
    public void everyMappedFieldIsEmittedByItsCheck() throws Exception {
        JsonNode mappings = loadMappingFile().get(FIELD_MAPPINGS);
        assertNotNull(MAPPINGS_RESOURCE + " has no " + FIELD_MAPPINGS, mappings);
        assertTrue(MAPPINGS_RESOURCE + " maps no fields at all", mappings.size() > 0);

        JsonNode report = runHealthReport();

        List<String> unresolved = new ArrayList<>();
        mappings.fieldNames().forEachRemaining(path -> {
            if (resolve(report, path) == null) {
                unresolved.add(path + "   (displayed as \"" + mappings.get(path).asText() + "\")");
            }
        });

        assertThat("report-diff-fields.json maps " + mappings.size() + " fields, but the health report does"
                        + " not contain " + unresolved.size() + " of them - the check that should emit them"
                        + " either does not call setReportJson or uses a different key:\n  "
                        + String.join("\n  ", unresolved),
                unresolved, empty());
    }

    /**
     * The other end of the same contract: every {@code [name=...]} in the mapping file must be a check that
     * actually exists. A renamed or dropped check class leaves the mapping addressing nothing.
     */
    @Test
    public void everyMappedCheckNameResolvesToACheckOnTheClasspath() throws Exception {
        JsonNode mappings = loadMappingFile().get(FIELD_MAPPINGS);

        Set<String> checkNames = new LinkedHashSet<>();
        mappings.fieldNames().forEachRemaining(path -> {
            Matcher matcher = MAPPING_PATH.matcher(path);
            assertTrue("Mapped path is not addressable by report-diff: " + path, matcher.matches());
            checkNames.add(matcher.group(1));
        });
        assertTrue("No check names found in " + MAPPINGS_RESOURCE, checkNames.size() > 0);

        List<String> missing = new ArrayList<>();
        for (String checkName : checkNames) {
            Object plugin = CoreServiceFactory.getInstance().getPluginService()
                    .getNamedPlugin(Check.class, checkName);
            if (!(plugin instanceof Check)) {
                missing.add(checkName);
            }
        }

        assertThat("report-diff-fields.json addresses checks that no Check class is registered for in"
                        + " config/modules/healthcheck.cfg: " + missing,
                missing, empty());
    }

    /**
     * {@code ReportDiff} iterates {@code fieldOrder} and only looks the display name up in
     * {@code fieldMappings}, so a field present in one and absent from the other is silently dropped.
     */
    @Test
    public void fieldMappingsAndFieldOrderDescribeTheSameFields() throws Exception {
        JsonNode root = loadMappingFile();

        Set<String> mapped = new LinkedHashSet<>();
        root.get(FIELD_MAPPINGS).fieldNames().forEachRemaining(mapped::add);

        Set<String> ordered = new LinkedHashSet<>();
        root.get(FIELD_ORDER).forEach(node -> ordered.add(node.asText()));

        List<String> mappedNotOrdered = new ArrayList<>(mapped);
        mappedNotOrdered.removeAll(ordered);
        List<String> orderedNotMapped = new ArrayList<>(ordered);
        orderedNotMapped.removeAll(mapped);

        assertThat("Mapped but never printed, because report-diff iterates fieldOrder: " + mappedNotOrdered,
                mappedNotOrdered, empty());
        assertThat("Ordered but unmapped, so report-diff prints the raw path as the column name: "
                        + orderedNotMapped, orderedNotMapped, empty());
        assertEquals("fieldMappings and fieldOrder must describe the same fields",
                mapped.size(), ordered.size());
    }

    private JsonNode loadMappingFile() throws Exception {
        try (InputStream is = ReportDiffFieldMappingIT.class.getResourceAsStream(MAPPINGS_RESOURCE)) {
            assertNotNull(MAPPINGS_RESOURCE + " is not on the classpath", is);
            return MAPPER.readTree(is);
        }
    }

    /**
     * Runs the health-report script and returns the JSON it stored, i.e. the very document report-diff
     * later reads.
     */
    private JsonNode runHealthReport() throws Exception {
        TestDSpaceRunnableHandler handler = new TestDSpaceRunnableHandler();
        String[] args = new String[] { "health-report" };
        ScriptLauncher.handleScript(args, ScriptLauncher.getConfig(kernelImpl), handler, kernelImpl);
        assertThat("The health report itself failed, so nothing can be said about the mappings",
                handler.getErrorMessages(), empty());

        ReportResultService reportResultService = ContentServiceFactory.getInstance().getReportResultService();
        context.reloadEntity(eperson);
        List<ReportResult> allReports = reportResultService.findAll(context);
        assertTrue("The health-report run stored no ReportResult", !allReports.isEmpty());
        // findAll() does not guarantee ordering; sort by lastModified so the newest report is last.
        allReports.sort(Comparator.comparing(ReportResult::getLastModified));
        String stored = allReports.get(allReports.size() - 1).getValue();
        assertNotNull("The stored health report is empty", stored);
        return MAPPER.readTree(stored);
    }

    /**
     * Resolves one report-diff field path against a health report, the way
     * {@code ReportDiff} does: {@code /} separates segments, {@code [name=X]} selects the element of an
     * array whose {@code name} is {@code X}, and a numeric segment is an array index.
     *
     * @param report the stored health report
     * @param path a key of {@code fieldMappings}
     * @return the addressed node, or {@code null} if any segment does not resolve
     */
    private JsonNode resolve(JsonNode report, String path) {
        JsonNode current = report;
        for (String segment : path.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            if (current == null) {
                return null;
            }
            if (segment.startsWith("[name=") && segment.endsWith("]")) {
                String wanted = segment.substring("[name=".length(), segment.length() - 1);
                JsonNode found = null;
                for (JsonNode candidate : current) {
                    JsonNode name = candidate.get("name");
                    if (name != null && wanted.equals(name.asText())) {
                        found = candidate;
                        break;
                    }
                }
                current = found;
            } else if (current.isArray() && segment.matches("\\d+")) {
                current = current.get(Integer.parseInt(segment));
            } else {
                current = current.get(segment);
            }
        }
        return current;
    }
}
