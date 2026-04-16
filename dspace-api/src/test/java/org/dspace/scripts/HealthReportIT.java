/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.scripts;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.apache.commons.lang3.StringUtils;
import org.dspace.AbstractIntegrationTestWithDatabase;
import org.dspace.app.launcher.ScriptLauncher;
import org.dspace.app.scripts.handler.impl.TestDSpaceRunnableHandler;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.ItemBuilder;
import org.dspace.content.Bitstream;
import org.dspace.content.Bundle;
import org.dspace.content.Collection;
import org.dspace.content.Community;
import org.dspace.content.Item;
import org.dspace.content.MetadataValue;
import org.dspace.content.ReportResult;
import org.dspace.content.clarin.ClarinLicense;
import org.dspace.content.clarin.ClarinLicenseLabel;
import org.dspace.content.clarin.ClarinLicenseResourceMapping;
import org.dspace.content.factory.ClarinServiceFactory;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.BitstreamService;
import org.dspace.content.service.BundleService;
import org.dspace.content.service.ReportResultService;
import org.dspace.content.service.clarin.ClarinLicenseLabelService;
import org.dspace.content.service.clarin.ClarinLicenseResourceMappingService;
import org.dspace.content.service.clarin.ClarinLicenseService;
import org.dspace.core.Constants;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.junit.Test;

/**
 * Integration test for the HealthReport script
 * @author Milan Majchrak (milan.majchrak at dataquest.sk)
 * @author Matus Kasak (dspace at dataquest.sk)
 */
public class HealthReportIT extends AbstractIntegrationTestWithDatabase {
    private static final String PUB_LABEL = "PUB";
    private static final String PUB_LICENSE_NAME = "Public Domain Mark (PUB)";
    private static final String PUB_LICENSE_URL = "https://creativecommons.org/publicdomain/mark/1.0/";
    private static final String LICENSE_TEXT = "This is a PUB License.";

    @Test
    public void testDefaultHealthcheckRun() throws Exception {

        TestDSpaceRunnableHandler testDSpaceRunnableHandler = new TestDSpaceRunnableHandler();

        String[] args = new String[] { "health-report" };
        ScriptLauncher.handleScript(args, ScriptLauncher.getConfig(kernelImpl), testDSpaceRunnableHandler, kernelImpl);

        assertThat(testDSpaceRunnableHandler.getErrorMessages(), empty());
        assertThat(testDSpaceRunnableHandler.getWarningMessages(), empty());

        List<String> messages = testDSpaceRunnableHandler.getInfoMessages();
        assertThat(messages, hasSize(1));
        assertThat(messages, hasItem(containsString("HEALTH REPORT:")));
    }

    @Test
    public void testLicenseCheck() throws Exception {
        context.turnOffAuthorisationSystem();

        Community community = CommunityBuilder.createCommunity(context)
                .withName("Community")
                .build();

        Collection collection = CollectionBuilder.createCollection(context, community)
                .withName("Collection")
                .withSubmitterGroup(eperson)
                .build();

        Item itemPUB = ItemBuilder.createItem(context, collection)
                .withTitle("Test item with Bitstream")
                .build();

        ItemBuilder.createItem(context, collection)
                .withTitle("Test item without Bitstream")
                .build();

        BundleService bundleService = ContentServiceFactory.getInstance().getBundleService();
        BitstreamService bitstreamService = ContentServiceFactory.getInstance().getBitstreamService();
        ClarinLicenseService clarinLicenseService = ClarinServiceFactory.getInstance().getClarinLicenseService();
        ClarinLicenseLabelService clarinLicenseLabelService =
                ClarinServiceFactory.getInstance().getClarinLicenseLabelService();
        ClarinLicenseResourceMappingService clarinLicenseResourceMappingService =
                ClarinServiceFactory.getInstance().getClarinLicenseResourceMappingService();

        Bundle bundle = bundleService.create(context, itemPUB, Constants.DEFAULT_BUNDLE_NAME);
        InputStream inputStream = new ByteArrayInputStream(LICENSE_TEXT.getBytes(StandardCharsets.UTF_8));

        Bitstream bitstream = bitstreamService.create(context, bundle, inputStream);

        ClarinLicenseLabel clarinLicenseLabel = clarinLicenseLabelService.create(context);
        clarinLicenseLabel.setLabel(PUB_LABEL);
        clarinLicenseLabelService.update(context, clarinLicenseLabel);

        ClarinLicense clarinLicense = clarinLicenseService.create(context);
        clarinLicense.setName(PUB_LICENSE_NAME);
        clarinLicense.setDefinition(PUB_LICENSE_URL);

        Set<ClarinLicenseLabel> licenseLabels = new HashSet<>();
        licenseLabels.add(clarinLicenseLabel);
        clarinLicense.setLicenseLabels(licenseLabels);
        clarinLicenseService.update(context, clarinLicense);

        ClarinLicenseResourceMapping mapping = clarinLicenseResourceMappingService.create(context);
        mapping.setBitstream(bitstream);
        mapping.setLicense(clarinLicense);

        clarinLicenseResourceMappingService.update(context, mapping);
        bitstreamService.update(context, bitstream);
        bundleService.update(context, bundle);
        context.commit();

        TestDSpaceRunnableHandler testDSpaceRunnableHandler = new TestDSpaceRunnableHandler();
        // -c 3 run only third check, in this case License check
        String[] args = new String[] { "health-report", "-c", "3" };
        ScriptLauncher.handleScript(args, ScriptLauncher.getConfig(kernelImpl), testDSpaceRunnableHandler, kernelImpl);

        assertThat(testDSpaceRunnableHandler.getErrorMessages(), empty());
        List<String> messages = testDSpaceRunnableHandler.getInfoMessages();
        assertThat(messages, hasSize(1));
        assertThat(messages, hasItem(containsString("no bundle")));
        assertThat(messages, hasItem(containsString("UUIDs of items without license bundle:")));
        assertThat(messages, hasItem(containsString("PUB")));
    }

    @Test
    public void testMetadataCheck() throws Exception {
        context.turnOffAuthorisationSystem();

        Community community = CommunityBuilder.createCommunity(context)
                .withName("Community")
                .build();

        Collection collection = CollectionBuilder.createCollection(context, community)
                .withName("Collection")
                .withSubmitterGroup(eperson)
                .build();

        Item item1 = ItemBuilder.createItem(context, collection)
                .withTitle("Test item 1")
                .withType("corpus")
                .withMetadata("local", "branding", null, "Community")
                .build();

        Item item2 = ItemBuilder.createItem(context, collection)
                .withTitle("Test item 2")
                .withType("toolService")
                .withSubject("Test subject")
                .withMetadata("local", "branding", null, "Community")
                .withMetadata("dc", "relation", "replaces", findItemUri(item1))
                .build();

        ItemBuilder.createItem(context, collection)
                .withTitle("Test item 3")
                .withType("toolService")
                .withSubject("Test subject")
                .withMetadata("local", "branding", null, "Community")
                .withMetadata("dc", "relation", "isreplacedby", findItemUri(item2))
                .build();

        ItemBuilder.createItem(context, collection)
                .withTitle("Test item 4")
                .withMetadata("local", "branding", null, "Community")
                .build();

        ItemBuilder.createItem(context, collection)
                .withType("toolService")
                .withMetadata("local", "branding", null, "Community")
                .build();

        TestDSpaceRunnableHandler testDSpaceRunnableHandler = new TestDSpaceRunnableHandler();

        // with "health-report -c 5", only Metadata check is running
        String[] args = new String[]{"health-report", "-c", "5"};
        ScriptLauncher.handleScript(args, ScriptLauncher.getConfig(kernelImpl), testDSpaceRunnableHandler, kernelImpl);

        assertThat(testDSpaceRunnableHandler.getErrorMessages(), empty());
        List<String> messages = testDSpaceRunnableHandler.getInfoMessages();

        assertThat(messages, hasSize(1));
        assertThat(messages.get(0), containsString("dc.relation issues:  " + " ".repeat(15) + "2"));
        assertThat(messages.get(0), containsString("dc.title issues:     " + " ".repeat(15) + "1"));
        assertThat(messages.get(0), containsString("dc.type issues:      " + " ".repeat(15) + "1"));
        assertThat(messages.get(0), containsString("Error count total:   " + " ".repeat(15) + "4"));
        assertThat(messages.get(0), containsString("dc.subject issues:   " + " ".repeat(15) + "1"));
        assertThat(messages.get(0), containsString("Warning count total: " + " ".repeat(15) + "1"));
        assertThat(messages.get(0), containsString("Errors:"));
        assertThat(messages.get(0), containsString("Does not have dc.type metadata"));
        assertThat(messages.get(0), containsString("Item has no dc.title metadata"));
        assertThat(messages.get(0), containsString("does not refer back via dc.relation.isreplacedby"));
        assertThat(messages.get(0), containsString("does not refer back via dc.relation.replaces"));
        assertThat(messages.get(0), containsString("Warnings:"));
        assertThat(messages.get(0), containsString("does not contain any [dc.subject] values"));

        ReportResultService reportResultService = ContentServiceFactory.getInstance().getReportResultService();
        List<ReportResult> reportResults = reportResultService.findAll(context);
        ReportResult reportResult  = findLastReportResult(reportResults);
        assertThat(reportResult.getType(), is("healthcheck"));

        JsonNode root = new ObjectMapper().readTree(reportResult.getValue());
        JsonNode metadataCheckNode = findCheckByName(root, "Metadata check");
        assertThat(metadataCheckNode, notNullValue());

        JsonNode reportNode = metadataCheckNode.get("report");
        assertThat(reportNode, notNullValue());

        assertThat(reportNode.get("errorCount").asInt(), is(4));
        assertThat(reportNode.get("warningCount").asInt(), is(1));

        ArrayNode errorsNode = reportNode.withArray("errors");
        assertThat(errorsNode.size(), is(3));

        assertThat(errorsNode.get(0).get("count").asInt(), is(2));
        assertThat(errorsNode.get(0).get("type").asText(), is("dc.relation"));

        assertThat(errorsNode.get(1).get("count").asInt(), is(1));
        assertThat(errorsNode.get(1).get("type").asText(), is("dc.title"));

        assertThat(errorsNode.get(2).get("count").asInt(), is(1));
        assertThat(errorsNode.get(2).get("type").asText(), is("dc.type"));

        ArrayNode warningsNode = reportNode.withArray("warnings");
        assertThat(warningsNode.size(), is(1));
        assertThat(warningsNode.get(0).get("count").asInt(), is(1));
        assertThat(warningsNode.get(0).get("type").asText(), is("dc.subject"));
    }

    @Test
    public void testMetadataCheckWithRestrictedReportSize() throws Exception {
        // set max-errors-to-show to 8 and error-dispersion-quota to 1,
        // This test has 14 errors in total, but the report will contain only 8 error messages.
        // The errors with low frequency will be prioritized.
        // The error-dispersion-quota set to 1 means that the number of errors shown
        // for each error will be almost the same, in this case maximally 2 errors for each error type
        context.turnOffAuthorisationSystem();

        ConfigurationService configurationService = DSpaceServicesFactory.getInstance().getConfigurationService();
        configurationService.setProperty("healthcheck.metadata.max-errors-to-show", 8);
        configurationService.setProperty("healthcheck.metadata.error-dispersion-quota", 1);

        Community community = CommunityBuilder.createCommunity(context)
                .withName("Community")
                .build();

        Collection collection = CollectionBuilder.createCollection(context, community)
                .withName("Collection")
                .withSubmitterGroup(eperson)
                .build();

        Item item1 = ItemBuilder.createItem(context, collection)
                .withTitle("Test item 1")
                .withType("corpus")
                .withSubject("Test subject")
                .withMetadata("local", "branding", null, "Community")
                .build();

        Item item2 = ItemBuilder.createItem(context, collection)
                .withTitle("Test item 2")
                .withType("toolService")
                .withSubject("Test subject")
                .withMetadata("local", "branding", null, "Community")
                .withMetadata("dc", "relation", "replaces", findItemUri(item1))
                .build();

        ItemBuilder.createItem(context, collection)
                .withTitle("Test item 3")
                .withType("toolService")
                .withSubject("Test subject")
                .withMetadata("local", "branding", null, "Community")
                .withMetadata("dc", "relation", "isreplacedby", findItemUri(item2))
                .build();

        // create 4 items with missing title
        for (int i = 0; i < 4; i++) {
            ItemBuilder.createItem(context, collection)
                    .withType("toolService")
                    .withSubject("Test subject")
                    .withMetadata("local", "branding", null, "Community")
                    .build();
        }

        // create 4 items with missing type
        for (int i = 4; i < 8; i++) {
            ItemBuilder.createItem(context, collection)
                    .withTitle("Test Item " + i)
                    .withSubject("Test subject")
                    .withMetadata("local", "branding", null, "Community")
                    .build();
        }

        // create 4 items with duplicate type
        for (int i = 8; i < 12; i++) {
            ItemBuilder.createItem(context, collection)
                    .withTitle("Test Item " + i)
                    .withType("toolService")
                    .withType("corpus")
                    .withSubject("Test subject")
                    .withMetadata("local", "branding", null, "Community")
                    .build();
        }

        TestDSpaceRunnableHandler testDSpaceRunnableHandler = new TestDSpaceRunnableHandler();

        // with "health-report -c 5", only Metadata check is running
        String[] args = new String[]{"health-report", "-c", "5"};
        ScriptLauncher.handleScript(args, ScriptLauncher.getConfig(kernelImpl), testDSpaceRunnableHandler, kernelImpl);

        assertThat(testDSpaceRunnableHandler.getErrorMessages(), empty());
        List<String> messages = testDSpaceRunnableHandler.getInfoMessages();

        assertThat(messages, hasSize(1));
        assertThat(messages.get(0), containsString("dc.relation issues:  " + " ".repeat(15) + "2"));
        assertThat(messages.get(0), containsString("dc.title issues:     " + " ".repeat(15) + "4"));
        assertThat(messages.get(0), containsString("dc.type issues:      " + " ".repeat(15) + "4"));
        assertThat(messages.get(0), containsString("duplicate value issues:" + " ".repeat(13) + "4"));
        assertThat(messages.get(0), containsString("Error count total:   " + " ".repeat(14) + "14"));

        assertThat(messages.get(0), containsString("Errors:"));

        // check if dc.type error is present exactly 2 times
        assertThat(StringUtils.countMatches(messages.get(0), "Does not have dc.type metadata"), is(2));
        // check if dc.title error is present exactly 2 times
        assertThat(StringUtils.countMatches(messages.get(0), "Item has no dc.title metadata"), is(2));
        // check id duplicate value error is present exactly 2 times
        assertThat(StringUtils.countMatches(messages.get(0), "value [dc.type] is present multiple times"), is(2));

        // check if all dc.relation errors are present
        assertThat(StringUtils.countMatches(messages.get(0), "does not refer back via dc.relation.replaces"), is(1));
        assertThat(
                StringUtils.countMatches(messages.get(0), "does not refer back via dc.relation.isreplacedby"), is(1));
        assertThat(messages.get(0), containsString("and more..."));
    }

    private String findItemUri(Item item) {
        return item.getMetadata().stream()
                .filter(metadataValue -> "dc_identifier_uri".equals(metadataValue.getMetadataField().toString()))
                .findFirst()
                .map(MetadataValue::getValue)
                .orElse(null);
    }

    ReportResult findLastReportResult(List<ReportResult> reportResults) {
        return reportResults.stream().max((reportResult1, reportResult2) ->
                reportResult1.getLastModified().compareTo(reportResult2.getLastModified())).orElseThrow();
    }

    JsonNode findCheckByName(JsonNode root, String checkName) {
        for (JsonNode check : root.get("checks")) {
            if (check.get("name").asText().equals(checkName)) {
                return check;
            }
        }
        return null;
    }
}