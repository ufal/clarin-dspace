/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.scripts;

import static org.hamcrest.CoreMatchers.containsString;
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
import org.dspace.content.clarin.ClarinLicense;
import org.dspace.content.clarin.ClarinLicenseLabel;
import org.dspace.content.clarin.ClarinLicenseResourceMapping;
import org.dspace.content.factory.ClarinServiceFactory;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.BitstreamService;
import org.dspace.content.service.BundleService;
import org.dspace.content.service.clarin.ClarinLicenseLabelService;
import org.dspace.content.service.clarin.ClarinLicenseResourceMappingService;
import org.dspace.content.service.clarin.ClarinLicenseService;
import org.dspace.core.Constants;
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
}