/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.oai;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.lyncode.xoai.dataprovider.xml.xoai.Element;
import com.lyncode.xoai.dataprovider.xml.xoai.Metadata;
import org.dspace.app.rest.test.AbstractControllerIntegrationTest;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.ItemBuilder;
import org.dspace.content.Collection;
import org.dspace.content.Community;
import org.dspace.content.Item;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.BitstreamService;
import org.dspace.content.service.BundleService;
import org.dspace.services.ConfigurationService;
import org.dspace.xoai.util.ItemUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Integration tests that verify which bundles are exposed through the XOAI
 * representation used by OAI-PMH crosswalks (including the CLARIN CMDI one).
 */
public class OAIPMHBundleExposureIT extends AbstractControllerIntegrationTest {

    @Autowired
    private ConfigurationService configurationService;

    private final BitstreamService bitstreamService =
            ContentServiceFactory.getInstance().getBitstreamService();
    private final BundleService bundleService =
            ContentServiceFactory.getInstance().getBundleService();

    private Collection collection;
    private String originalOaiBundleExcluded;

    @Before
    public void setupStructure() throws Exception {
        context.turnOffAuthorisationSystem();
        Community community = CommunityBuilder.createCommunity(context)
                .withName("Test Community")
                .build();
        collection = CollectionBuilder.createCollection(context, community)
                .withName("Test Collection")
                .build();
        context.restoreAuthSystemState();

        // Preserve the loaded value so each test can safely mutate this property.
        originalOaiBundleExcluded = configurationService.getProperty("oai.bundle.excluded");
    }

    @After
    public void restoreOaiBundleExcludedConfiguration() {
        configurationService.setProperty("oai.bundle.excluded", originalOaiBundleExcluded);
    }

    /**
     * Build an item that has an ORIGINAL bitstream plus the derivative/internal
     * bundles that {@code dspace filter-media} / SWORD typically create.
     */
    private Item buildItemWithDerivativeBundles() throws Exception {
        context.turnOffAuthorisationSystem();
        Item item = ItemBuilder.createItem(context, collection)
                .withTitle("Item with TEXT and THUMBNAIL bundles")
                .withIssueDate("2026-01-01")
                .build();

        addBitstream(item, "ORIGINAL", "payload.pdf", "binary data");
        addBitstream(item, "TEXT", "payload.pdf.txt", "extracted text from pdf");
        addBitstream(item, "THUMBNAIL", "payload.pdf.jpg", "fake thumbnail bytes");
        addBitstream(item, "SWORD", "sword-deposit.zip", "sword payload");

        context.restoreAuthSystemState();
        return item;
    }

    private void addBitstream(Item item, String bundleName, String name, String content)
            throws Exception {
        org.dspace.content.Bundle bundle;
        List<org.dspace.content.Bundle> bundles =
                ContentServiceFactory.getInstance().getItemService()
                        .getBundles(item, bundleName);
        if (bundles.isEmpty()) {
            bundle = bundleService.create(context, item, bundleName);
        } else {
            bundle = bundles.get(0);
        }
        org.dspace.content.Bitstream bitstream = bitstreamService.create(
                context, bundle,
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        bitstream.setName(context, name);
        bitstreamService.update(context, bitstream);
    }

    private List<String> bundleNames(Metadata metadata) {
        List<String> names = new ArrayList<>();
        Element bundles = ItemUtils.getElement(metadata.getElement(), "bundles");
        if (bundles == null) {
            return names;
        }
        for (Element bundle : bundles.getElement()) {
            for (Element.Field field : bundle.getField()) {
                if ("name".equals(field.getName())) {
                    names.add(field.getValue());
                }
            }
        }
        return names;
    }

    /**
     * With the default configuration, TEXT, THUMBNAIL and SWORD bundles must be
     * hidden from the XOAI document.
     */
    @Test
    public void defaultConfiguration_hidesFilterMediaAndSwordBundles() throws Exception {
        // Ensure we rely on the built-in default; drop any stale override.
        configurationService.setProperty("oai.bundle.excluded", null);

        Item item = buildItemWithDerivativeBundles();

        Metadata metadata = ItemUtils.retrieveMetadata(context, item);

        List<String> exposed = bundleNames(metadata);

        assertThat("ORIGINAL bundle must always be exposed via OAI-PMH",
                exposed, hasItem("ORIGINAL"));
        assertThat("TEXT bundle (dspace filter-media output) must not be exposed via OAI-PMH",
                exposed, not(hasItem("TEXT")));
        assertThat("THUMBNAIL bundle (dspace filter-media output) must not be exposed via OAI-PMH",
                exposed, not(hasItem("THUMBNAIL")));
        assertThat("SWORD bundle (internal deposit package) must not be exposed via OAI-PMH",
                exposed, not(hasItem("SWORD")));
    }

    /**
     * The administrator may reduce the exclusion list; when only THUMBNAIL is
     * excluded, TEXT (and others) are exposed again.
     */
    @Test
    public void customExcludedBundles_allowsOverrideOfDefaults() throws Exception {
        configurationService.setProperty("oai.bundle.excluded", "THUMBNAIL");

        Item item = buildItemWithDerivativeBundles();
        Metadata metadata = ItemUtils.retrieveMetadata(context, item);

        List<String> exposed = bundleNames(metadata);

        assertThat("With a custom exclusion list the ORIGINAL, TEXT and SWORD "
                   + "bundles must be exposed and only THUMBNAIL must be hidden",
                exposed,
                containsInAnyOrder("ORIGINAL", "TEXT", "SWORD"));
    }

    /**
     * An empty value must fall back to the built-in defaults, otherwise a
     * mis-configuration would regress to the pre-fix behaviour.
     */
    @Test
    public void emptyExcludedBundles_fallsBackToDefaults() throws Exception {
        configurationService.setProperty("oai.bundle.excluded", "");

        Item item = buildItemWithDerivativeBundles();
        Metadata metadata = ItemUtils.retrieveMetadata(context, item);

        List<String> exposed = bundleNames(metadata);

        assertThat(exposed, hasItem("ORIGINAL"));
        assertThat(exposed, not(hasItem("TEXT")));
        assertThat(exposed, not(hasItem("THUMBNAIL")));
        assertThat(exposed, not(hasItem("SWORD")));
    }
}
