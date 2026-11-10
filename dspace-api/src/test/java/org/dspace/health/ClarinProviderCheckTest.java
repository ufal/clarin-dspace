/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.health;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.dspace.AbstractDSpaceTest;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests of {@link VloHarvestCheck} and {@link LinkCheckerCheck} against recorded responses of the CLARIN services.
 */
public class ClarinProviderCheckTest extends AbstractDSpaceTest {

    private static final String PROVIDER =
            "LINDAT_CLARIAH_CZ_digital_library_at_the_Institute_of_Formal_and_Applied_Linguistics_UFAL_";
    // the harvest run in vlo-endpoint-info.json finished 2026-10-20T03:28:18Z
    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");

    private ConfigurationService configurationService;

    @Before
    public void setUp() {
        configurationService = DSpaceServicesFactory.getInstance().getConfigurationService();
        configurationService.setProperty(ClarinProviderCheck.PROVIDER_NAME_PROPERTY, PROVIDER);
    }

    @After
    public void tearDown() {
        configurationService.setProperty(ClarinProviderCheck.PROVIDER_NAME_PROPERTY, null);
        configurationService.setProperty(VloHarvestCheck.MAX_AGE_PROPERTY, null);
        configurationService.setProperty(LinkCheckerCheck.BROKEN_FILTER_PROPERTY, null);
        configurationService.setProperty(LinkCheckerCheck.MAX_BROKEN_PROPERTY, null);
    }

    @Test
    public void notConfiguredMakesNoRequests() {
        configurationService.setProperty(ClarinProviderCheck.PROVIDER_NAME_PROPERTY, "");
        for (ClarinProviderCheck check : List.of(new FakeVlo(Map.of()), new FakeLinkChecker(Map.of()))) {
            check.report(new ReportInfo(7));
            assertThat(check.getReport(), containsString("Not configured"));
            assertThat(check.getReportJson().getBoolean("configured"), is(false));
        }
    }

    @Test
    public void vloHarvest() {
        FakeVlo check = new FakeVlo(vloResponses());
        check.report(new ReportInfo(7));

        String report = check.getReport();
        assertThat(check.getErrors(), is(""));
        assertThat(report, containsString("Last harvest run:         18, finished 2026-10-20T03:28:18.710722+00:00"
                + " (1 days ago)"));
        assertThat(report, containsString("Records harvested:        1448 (previous harvest run 16: 1440,"
                + " change: +8)"));
        assertThat(report, containsString("started:                2026-10-19T18:01:35,045"));
        assertThat(report, containsString("warnings / errors:      5 / 0"));
        assertThat(report, containsString("http://hdl.handle.net/11234/5-NAMETAG@format=cmdi"));
        assertThat(report, not(containsString("WARNING")));
        assertThat(check.requested.get(0), containsString("/mv_endpoint_info?name=eq." + PROVIDER));
        assertThat(check.requested.get(2), is("https://vlo.clarin.eu/data/clarin/log/" + PROVIDER + ".log"));

        JSONObject json = check.getReportJson();
        assertThat(json.getLong("records"), is(1448L));
        assertThat(json.getLong("recordsChange"), is(8L));
        assertThat(json.getBoolean("inLatestHarvest"), is(true));
        assertThat(json.getInt("logWarnings"), is(5));
        assertThat(json.getInt("logErrors"), is(0));
    }

    @Test
    public void vloHarvestWarnsWhenStaleOrNotInLatestRun() {
        configurationService.setProperty(VloHarvestCheck.MAX_AGE_PROPERTY, 0);
        Map<String, String> responses = vloResponses();
        responses.put("/mv_harvest_info", "[{\"harvest_id\":19,\"when\":\"2026-10-21T03:00:00+00:00\","
                + "\"type\":\"clarin\"}]");
        FakeVlo check = new FakeVlo(responses);
        check.report(new ReportInfo(7));

        assertThat(check.getReport(), containsString("WARNING: the last harvest is older than 0 days."));
        assertThat(check.getReport(), containsString("WARNING: the endpoint was not part of the latest clarin "
                + "harvest run 19"));
        assertThat(check.getReportJson().getBoolean("inLatestHarvest"), is(false));
    }

    @Test
    public void vloHarvestStaleComparesFullDuration() {
        // the harvest is 1 day 8.5 hours old: older than 1 day although toDays() would say 1
        configurationService.setProperty(VloHarvestCheck.MAX_AGE_PROPERTY, 1);
        FakeVlo check = new FakeVlo(vloResponses());
        check.report(new ReportInfo(7));

        assertThat(check.getReport(), containsString("WARNING: the last harvest is older than 1 days."));
    }

    @Test
    public void vloHarvestKeepsResultsWhenLatestRunRequestFails() {
        Map<String, String> responses = vloResponses();
        responses.remove("/mv_harvest_info");
        FakeVlo check = new FakeVlo(responses);
        check.report(new ReportInfo(7));

        assertThat(check.getErrors(), containsString("Could not get the latest harvest run."));
        assertThat(check.getReport(), containsString("Records harvested:        1448"));
        // the harvest log is still checked
        assertThat(check.getReport(), containsString("warnings / errors:      5 / 0"));
    }

    @Test
    public void vloHarvestUnexpectedResponseIsAnError() {
        Map<String, String> responses = vloResponses();
        responses.put("/mv_endpoint_info", "{}");
        FakeVlo check = new FakeVlo(responses);
        check.report(new ReportInfo(7));

        assertThat(check.getErrors(), containsString("expected a JSON array"));
        assertThat(check.getReport(), not(containsString("not found")));
    }

    @Test
    public void vloHarvestEndpointNotFound() {
        Map<String, String> responses = vloResponses();
        responses.put("/mv_endpoint_info", "[]");
        FakeVlo check = new FakeVlo(responses);
        check.report(new ReportInfo(7));

        assertThat(check.getReport(), containsString("WARNING: endpoint [" + PROVIDER + "] not found"));
        assertThat(check.getReportJson().getBoolean("found"), is(false));
    }

    @Test
    public void vloHarvestMissingLogIsReportedButNotFatal() {
        Map<String, String> responses = vloResponses();
        responses.remove(".log");
        FakeVlo check = new FakeVlo(responses);
        check.report(new ReportInfo(7));

        assertThat(check.getErrors(), containsString("Could not get the harvest log."));
        assertThat(check.getReport(), containsString("Records harvested:        1448"));
    }

    @Test
    public void serviceFailureDoesNotThrow() {
        // no responses at all: the first request fails
        for (ClarinProviderCheck check : List.of(new FakeVlo(Map.of()), new FakeLinkChecker(Map.of()))) {
            check.report(new ReportInfo(7));
            assertThat(check.getErrors(), containsString("Could not get data from the CLARIN service."));
        }
    }

    @Test
    public void linkChecker() {
        FakeLinkChecker check = new FakeLinkChecker(linkCheckerResponses());
        check.report(new ReportInfo(7));

        String report = check.getReport();
        assertThat(check.getErrors(), is(""));
        assertThat(report, containsString("(created 2026-10-18 20:00:07)"));
        assertThat(report, containsString(String.format("  %-24s%8d\n", "Broken:", 202)));
        assertThat(report, containsString(String.format("  %-24s%8d\n", "Blocked_By_Robots_txt:", 2923)));
        assertThat(report, containsString("Broken links: 5\n"));
        assertThat(report, containsString("  404  https://redmine.ms.mff.cuni.cz/projects/pmltq-web"));
        assertThat(report, containsString("  -    https://tezaurus.ff.cuni.cz (Unknown host)"));
        assertThat(check.requested.get(1), is("https://curation.clarin.eu/download/linkchecker/" + PROVIDER
                + "/Broken?format=json"));

        JSONObject json = check.getReportJson();
        assertThat(json.getJSONObject("categories").getLong("Broken"), is(202L));
        assertThat(json.getJSONObject("categories").getLong("Ok"), is(13956L));
        assertThat(json.getInt("brokenMatching"), is(5));
    }

    @Test
    public void linkCheckerBrokenFilterAndLimit() {
        configurationService.setProperty(LinkCheckerCheck.BROKEN_FILTER_PROPERTY, "^https?://[^/]*cuni[.]cz");
        configurationService.setProperty(LinkCheckerCheck.MAX_BROKEN_PROPERTY, 2);
        FakeLinkChecker check = new FakeLinkChecker(linkCheckerResponses());
        check.report(new ReportInfo(7));

        String report = check.getReport();
        assertThat(report, containsString("Broken links matching [^https?://[^/]*cuni[.]cz]: 3\n"));
        assertThat(report, containsString("pmltq-web"));
        assertThat(report, containsString("CLEFeHealth"));
        assertThat(report, not(containsString("tezaurus")));
        assertThat(report, not(containsString("tst.inl.nl")));
        assertThat(report, containsString("and more..."));
        assertThat(check.getReportJson().getJSONArray("brokenShown").length(), is(2));
    }

    @Test
    public void linkCheckerKeepsCountsWhenBrokenListFails() {
        Map<String, String> responses = linkCheckerResponses();
        responses.remove("/Broken");
        FakeLinkChecker check = new FakeLinkChecker(responses);
        check.report(new ReportInfo(7));

        assertThat(check.getErrors(), containsString("Could not get the list of broken links."));
        assertThat(check.getReport(), containsString(String.format("  %-24s%8d\n", "Broken:", 202)));
    }

    @Test
    public void linkCheckerUnexpectedBrokenResponseIsAnError() {
        Map<String, String> responses = linkCheckerResponses();
        responses.put("/Broken", "{}");
        FakeLinkChecker check = new FakeLinkChecker(responses);
        check.report(new ReportInfo(7));

        assertThat(check.getErrors(), containsString("expected a JSON array"));
        assertThat(check.getReport(), not(containsString("Broken links: 0")));
        assertThat(check.getReportJson().has("brokenMatching"), is(false));
    }

    @Test
    public void linkCheckerAcceptsSingletonStatistics() {
        // the service returns "statistics" as an object, not an array, when all links are in one category
        configurationService.setProperty(ClarinProviderCheck.PROVIDER_NAME_PROPERTY, "Corpor_UCLouvain");
        FakeLinkChecker check = new FakeLinkChecker(linkCheckerResponses());
        check.report(new ReportInfo(7));

        assertThat(check.getErrors(), is(""));
        assertThat(check.getReport(), containsString(String.format("  %-24s%8d\n", "Ok:", 31)));
        assertThat(check.getReportJson().getJSONObject("categories").getLong("Ok"), is(31L));
        assertThat(check.getReportJson().getLong("total"), is(31L));
        // categories omitted by the service are reported as 0, so that report-diff can compute differences
        assertThat(check.getReport(), containsString(String.format("  %-24s%8d\n", "Broken:", 0)));
        assertThat(check.getReportJson().getJSONObject("categories").getLong("Broken"), is(0L));
        assertThat(check.getReportJson().getJSONObject("categories").getLong("Blocked_By_Robots_txt"), is(0L));
        // no broken links, so the list is not requested
        assertThat(check.requested.size(), is(1));
    }

    @Test
    public void linkCheckerProviderNotFound() {
        configurationService.setProperty(ClarinProviderCheck.PROVIDER_NAME_PROPERTY, "Unknown_provider");
        FakeLinkChecker check = new FakeLinkChecker(linkCheckerResponses());
        check.report(new ReportInfo(7));

        assertThat(check.getReport(), containsString("WARNING: provider [Unknown_provider] not found"));
        assertThat(check.getReportJson().getBoolean("found"), is(false));
    }

    private static Map<String, String> vloResponses() {
        Map<String, String> responses = new LinkedHashMap<>();
        responses.put("/mv_endpoint_info", resource("vlo-endpoint-info.json"));
        responses.put("/mv_harvest_info", resource("vlo-harvest-info.json"));
        responses.put(".log", resource("vlo-harvest.log"));
        return responses;
    }

    private static Map<String, String> linkCheckerResponses() {
        Map<String, String> responses = new LinkedHashMap<>();
        responses.put("/AllLinkcheckerReport", resource("linkchecker-all.json"));
        responses.put("/Broken", resource("linkchecker-broken.json"));
        return responses;
    }

    private static String resource(String name) {
        try (InputStream in = ClarinProviderCheckTest.class.getResourceAsStream(name)) {
            if (in == null) {
                fail("Missing test resource " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Serves the response whose key is contained in the requested URL; fails like a 404 otherwise.
     */
    private static String respond(Map<String, String> responses, List<String> requested, String url)
            throws IOException {
        requested.add(url);
        for (Map.Entry<String, String> response : responses.entrySet()) {
            if (url.contains(response.getKey())) {
                return response.getValue();
            }
        }
        throw new IOException("GET " + url + " returned HTTP 404");
    }

    private static class FakeVlo extends VloHarvestCheck {
        private final Map<String, String> responses;
        private final List<String> requested = new ArrayList<>();

        FakeVlo(Map<String, String> responses) {
            this.responses = responses;
        }

        @Override
        protected String fetch(String url) throws IOException {
            return respond(responses, requested, url);
        }

        @Override
        protected Instant now() {
            return NOW;
        }
    }

    private static class FakeLinkChecker extends LinkCheckerCheck {
        private final Map<String, String> responses;
        private final List<String> requested = new ArrayList<>();

        FakeLinkChecker(Map<String, String> responses) {
            this.responses = responses;
        }

        @Override
        protected String fetch(String url) throws IOException {
            return respond(responses, requested, url);
        }
    }
}
