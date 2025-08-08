/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.dspace.app.rest.test.AbstractControllerIntegrationTest;
import org.dspace.app.rest.utils.Utils;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.ItemBuilder;
import org.dspace.content.Collection;
import org.dspace.content.Item;
import org.dspace.services.ConfigurationService;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The Integration Test class for the ClarinRefBoxController.
 */
public class ClarinRefBoxControllerIT extends AbstractControllerIntegrationTest {

    @Autowired
    ConfigurationService configurationService;

    // FS = featuredService
    private Item itemWithFS;
    private Item item;
    private Collection collection;

    @Before
    public void setup() {
        context.turnOffAuthorisationSystem();
        parentCommunity = CommunityBuilder.createCommunity(context).withName("test").build();
        collection = CollectionBuilder.createCollection(context, parentCommunity).withName("Collection 1").build();
        item = ItemBuilder.createItem(context, collection)
                .withTitle("Public item 2")
                .withIssueDate("2016-02-13")
                .withAuthor("Test author 2")
                .withSubject("TestingForMore 2")
                .build();
        itemWithFS = ItemBuilder.createItem(context, collection)
                .withTitle("Public item 1")
                .withIssueDate("2016-02-13")
                .withAuthor("Test author")
                .withSubject("TestingForMore")
                .withMetadata("local","featuredService","kontext", "Slovak|URLSlovak")
                .withMetadata("local","featuredService","kontext", "Czech|URLCzech")
                .withMetadata("local","featuredService","pmltq", "Arabic|URLArabic")
                .build();
        context.restoreAuthSystemState();
    }

    @Test
    public void returnFeaturedServiceWithoutLinks() throws Exception {
        String token = getAuthToken(admin.getEmail(), password);
        getClient(token).perform(get("/api/core/refbox/services?id=" + item.getID()))
                .andExpect(status().isOk());
    }

    @Test
    public void returnFeaturedServiceWithLinks() throws Exception {
        String token = getAuthToken(admin.getEmail(), password);
        getClient(token).perform(get("/api/core/refbox/services?id=" + itemWithFS.getID()))
                .andExpect(status().isOk());
    }

    @Test
    public void testReturnAllRefboxInfoForItemWithFeaturedService() throws Exception {
        String token = getAuthToken(admin.getEmail(), password);
        String handle = itemWithFS.getHandle();
        String baseUrl = configurationService.getProperty("dspace.server.url") +
                "/api/core/refbox/citations?handle=/" + Utils.getCanonicalHandleUrlNoProtocol(itemWithFS);
        String bibtexUrl = baseUrl + "&type=bibtex";
        String cmdiUrl = baseUrl + "&type=cmdi";

        getClient(token).perform(get("/api/core/refbox?handle=" + handle))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayText").value(org.hamcrest.Matchers.containsString("Test author")))
                .andExpect(jsonPath("$.title").value("Public item 1"))
                // For exportFormats
                .andExpect(jsonPath("$.exportFormats.exportFormat[*].name", hasItem("bibtex")))
                .andExpect(jsonPath("$.exportFormats.exportFormat[*].name", hasItem("cmdi")))
                .andExpect(jsonPath("$.exportFormats.exportFormat[?(@.name=='bibtex')].url").value(hasItem(bibtexUrl)))
                .andExpect(jsonPath("$.exportFormats.exportFormat[?(@.name=='cmdi')].url").value(hasItem(cmdiUrl)))
                // For featuredServices
                .andExpect(jsonPath("$.featuredServices.featuredService[*].name", hasItem("KonText")))
                .andExpect(jsonPath("$.featuredServices.featuredService[*].name", hasItem("PML-TQ")))
                .andExpect(jsonPath("$.featuredServices.featuredService[?(@.name=='KonText')].links.entry[*].key"
                        , hasItem("Slovak")))
                .andExpect(jsonPath("$.featuredServices.featuredService[?(@.name=='KonText')].links.entry[*].value"
                        , hasItem("URLSlovak")))
                .andExpect(jsonPath("$.featuredServices.featuredService[?(@.name=='KonText')].links.entry[*].key"
                        , hasItem("Czech")))
                .andExpect(jsonPath("$.featuredServices.featuredService[?(@.name=='KonText')].links.entry[*].value"
                        , hasItem("URLCzech")))
                .andExpect(jsonPath("$.featuredServices.featuredService[?(@.name=='PML-TQ')].links.entry[*].key"
                        , hasItem("Arabic")))
                .andExpect(jsonPath("$.featuredServices.featuredService[?(@.name=='PML-TQ')].links.entry[*].value"
                        , hasItem("URLArabic")));
    }

    @Test
    public void testReturnAllRefboxInfoForItemWithEmptyFeaturedService() throws Exception {
        String token = getAuthToken(admin.getEmail(), password);
        String handle = item.getHandle();
        String baseUrl = configurationService.getProperty("dspace.server.url") +
                "/api/core/refbox/citations?handle=/" + Utils.getCanonicalHandleUrlNoProtocol(item);
        String bibtexUrl = baseUrl + "&type=bibtex";
        String cmdiUrl = baseUrl + "&type=cmdi";

        getClient(token).perform(get("/api/core/refbox?handle=" + handle))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayText")
                        .value(org.hamcrest.Matchers.containsString("Test author 2")))
                .andExpect(jsonPath("$.displayText")
                        .value(org.hamcrest.Matchers.containsString(item.getHandle())))
                .andExpect(jsonPath("$.title").value("Public item 2"))
                .andExpect(jsonPath("$.exportFormats.exportFormat[*].name", hasItem("bibtex")))
                .andExpect(jsonPath("$.exportFormats.exportFormat[*].name", hasItem("cmdi")))
                .andExpect(jsonPath("$.exportFormats.exportFormat[?(@.name=='bibtex')].url")
                        .value(hasItem(bibtexUrl)))
                .andExpect(jsonPath("$.exportFormats.exportFormat[?(@.name=='cmdi')].url")
                        .value(hasItem(cmdiUrl)))
                .andExpect(jsonPath("$.exportFormats.exportFormat[?(@.name=='bibtex')].extract")
                        .value(hasItem("")))
                .andExpect(jsonPath("$.exportFormats.exportFormat[?(@.name=='cmdi')].extract")
                        .value(hasItem("")))
                .andExpect(jsonPath("$.exportFormats.exportFormat[?(@.name=='bibtex')].dataType")
                        .value(hasItem("json")))
                .andExpect(jsonPath("$.exportFormats.exportFormat[?(@.name=='cmdi')].dataType")
                        .value(hasItem("json")))
                .andExpect(jsonPath("$.featuredServices.featuredService").isEmpty());
    }

    @Test
    public void testRefboxInfoWithNullHandleParam() throws Exception {
        String token = getAuthToken(admin.getEmail(), password);
        getClient(token).perform(get("/api/core/refbox"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    public void testRefboxInfoWithMalformedHandle() throws Exception {
        String token = getAuthToken(admin.getEmail(), password);
        getClient(token).perform(get("/api/core/refbox?handle=notAHandle"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    public void testRefboxInfoWithOnlyPublisher() throws Exception {
        context.turnOffAuthorisationSystem();
        Item itemPublisher = ItemBuilder.createItem(context, collection)
                .withMetadata("dc", "publisher", null, "Test Publisher")
                .build();
        context.restoreAuthSystemState();

        String token = getAuthToken(admin.getEmail(), password);
        getClient(token).perform(get("/api/core/refbox?handle=" + itemPublisher.getHandle()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayText").value(org.hamcrest.Matchers.containsString("Test Publisher")));
    }

    @Test
    public void testRefboxInfoWithOnlyYear() throws Exception {
        context.turnOffAuthorisationSystem();
        Item itemYear = ItemBuilder.createItem(context, collection)
                .withIssueDate("2022")
                .build();
        context.restoreAuthSystemState();

        String token = getAuthToken(admin.getEmail(), password);
        getClient(token).perform(get("/api/core/refbox?handle=" + itemYear.getHandle()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayText").value(org.hamcrest.Matchers.containsString("2022")));
    }

    @Test
    public void testRefboxInfoWithOnlyTitle() throws Exception {
        context.turnOffAuthorisationSystem();
        Item itemTitle = ItemBuilder.createItem(context, collection)
                .withTitle("Title Only")
                .build();
        context.restoreAuthSystemState();

        String token = getAuthToken(admin.getEmail(), password);
        getClient(token).perform(get("/api/core/refbox?handle=" + itemTitle.getHandle()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayText").value(org.hamcrest.Matchers.containsString("Title Only")));
    }

    @Test
    public void testRefboxInfoWithDOIAndHandle() throws Exception {
        context.turnOffAuthorisationSystem();
        Item itemDOI = ItemBuilder.createItem(context, collection)
                .withTitle("DOI Item")
                .withDoiIdentifier("10.1234/abcd")
                .build();
        context.restoreAuthSystemState();

        String token = getAuthToken(admin.getEmail(), password);
        getClient(token).perform(get("/api/core/refbox?handle=" + itemDOI.getHandle()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayText").value(org.hamcrest.Matchers.containsString("10.1234/abcd")));
    }

    @Test
    public void testRefboxInfoWithWhitespaceMetadata() throws Exception {
        context.turnOffAuthorisationSystem();
        Item itemWhitespace = ItemBuilder.createItem(context, collection)
                .withTitle("   ")
                .withAuthor(" ")
                .build();
        context.restoreAuthSystemState();

        String token = getAuthToken(admin.getEmail(), password);
        getClient(token).perform(get("/api/core/refbox?handle=" + itemWhitespace.getHandle()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayText").exists());
    }

    @Test
    public void testFeaturedServiceWithDuplicateEntries() throws Exception {
        context.turnOffAuthorisationSystem();
        Item itemDupFS = ItemBuilder.createItem(context, collection)
                .withMetadata("local", "featuredService", "kontext", "Key1|Value1")
                .withMetadata("local", "featuredService", "kontext", "Key1|Value1")
                .build();
        context.restoreAuthSystemState();

        String token = getAuthToken(admin.getEmail(), password);
        getClient(token).perform(get("/api/core/refbox?handle=" + itemDupFS.getHandle()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.featuredServices.featuredService[0].links.entry.length()").value(2));
    }

    @Test
    public void testFeaturedServiceWithMalformedLink() throws Exception {
        context.turnOffAuthorisationSystem();
        Item itemMalformed = ItemBuilder.createItem(context, collection)
                .withMetadata("local", "featuredService", "kontext", "NoPipeDelimiter")
                .build();
        context.restoreAuthSystemState();

        String token = getAuthToken(admin.getEmail(), password);
        getClient(token).perform(get("/api/core/refbox?handle=" + itemMalformed.getHandle()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.featuredServices.featuredService").isEmpty());
    }

    @Test
    public void testRefboxInfoWithBothAuthorAndPublisher() throws Exception {
        context.turnOffAuthorisationSystem();
        Item itemWithBoth = ItemBuilder.createItem(context, collection)
                .withTitle("Test Item")
                .withAuthor("Test Author")
                .withMetadata("dc", "publisher", null, "Test Publisher")
                .withIssueDate("2023-01-01")
                .build();
        context.restoreAuthSystemState();

        String token = getAuthToken(admin.getEmail(), password);
        getClient(token).perform(get("/api/core/refbox?handle=" + itemWithBoth.getHandle()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayText").value(org.hamcrest.Matchers.containsString("Test Author")))
                .andExpect(jsonPath("$.displayText").value(org.hamcrest.Matchers.containsString("Test Publisher")));
    }

    @Test
    public void testRefboxInfoWithCustomFormatTemplate() throws Exception {
        context.turnOffAuthorisationSystem();
        Item itemWithTemplate = ItemBuilder.createItem(context, collection)
                .withTitle("Custom Title")
                .withAuthor("Custom Author")
                .withMetadata("dc", "publisher", null, "Custom Publisher")
                .withMetadata("local", "refbox", "format",
                    "{authors} ({year}). {title}. {publisher}. {repository}. {pid}")
                .withIssueDate("2024-01-01")
                .build();
        context.restoreAuthSystemState();

        String token = getAuthToken(admin.getEmail(), password);
        getClient(token).perform(get("/api/core/refbox?handle=" + itemWithTemplate.getHandle()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayText").value(org.hamcrest.Matchers
                    .containsString("Custom Author (2024). Custom Title. Custom Publisher.")));
    }

    @Test
    public void testRefboxInfoWithCustomFormatTemplatePartialVariables() throws Exception {
        context.turnOffAuthorisationSystem();
        Item itemWithPartialTemplate = ItemBuilder.createItem(context, collection)
                .withTitle("Partial Title")
                .withAuthor("Partial Author")
                .withMetadata("local", "refbox", "format", "{authors}: {title} [{year}]")
                .withIssueDate("2023-12-31")
                .build();
        context.restoreAuthSystemState();

        String token = getAuthToken(admin.getEmail(), password);
        getClient(token).perform(get("/api/core/refbox?handle=" + itemWithPartialTemplate.getHandle()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayText").value(org.hamcrest.Matchers
                    .containsString("Partial Author: Partial Title [2023]")));
    }

    @Test
    public void testRefboxInfoWithoutCustomFormatUsesDefault() throws Exception {
        context.turnOffAuthorisationSystem();
        Item itemWithoutTemplate = ItemBuilder.createItem(context, collection)
                .withTitle("Default Title")
                .withAuthor("Default Author")
                .withMetadata("dc", "publisher", null, "Default Publisher")
                .withIssueDate("2024-02-01")
                .build();
        context.restoreAuthSystemState();

        String token = getAuthToken(admin.getEmail(), password);
        getClient(token).perform(get("/api/core/refbox?handle=" + itemWithoutTemplate.getHandle()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayText").value(org.hamcrest.Matchers
                    .containsString("Default Author")))
                .andExpect(jsonPath("$.displayText").value(org.hamcrest.Matchers
                    .containsString("Default Publisher")))
                .andExpect(jsonPath("$.displayText").value(org.hamcrest.Matchers
                    .containsString("<i>Default Title</i>")));
    }

    @Test
    public void testDisplayTextWithOneAuthor() throws Exception {
        context.turnOffAuthorisationSystem();
        Item itemOneAuthor = ItemBuilder.createItem(context, collection)
                .withAuthor("Single Author")
                .build();
        context.restoreAuthSystemState();

        String token = getAuthToken(admin.getEmail(), password);
        getClient(token).perform(get("/api/core/refbox?handle=" + itemOneAuthor.getHandle()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayText").value(org.hamcrest.Matchers.containsString("Single Author")));
    }

    @Test
    public void testDisplayTextWithTwoAuthors() throws Exception {
        context.turnOffAuthorisationSystem();
        Item itemTwoAuthors = ItemBuilder.createItem(context, collection)
                .withAuthor("Author One")
                .withAuthor("Author Two")
                .build();
        context.restoreAuthSystemState();

        String token = getAuthToken(admin.getEmail(), password);
        getClient(token).perform(get("/api/core/refbox?handle=" + itemTwoAuthors.getHandle()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayText").value(org.hamcrest.Matchers.containsString(
                        "Author One and Author Two")));
    }

    @Test
    public void testDisplayTextWithFiveAuthors() throws Exception {
        context.turnOffAuthorisationSystem();
        Item itemFiveAuthors = ItemBuilder.createItem(context, collection)
                .withAuthor("A1")
                .withAuthor("A2")
                .withAuthor("A3")
                .withAuthor("A4")
                .withAuthor("A5")
                .build();
        context.restoreAuthSystemState();

        String token = getAuthToken(admin.getEmail(), password);
        getClient(token).perform(get("/api/core/refbox?handle=" + itemFiveAuthors.getHandle()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayText").value(org.hamcrest.Matchers.containsString(
                        "A1; A2; A3; A4 and A5")));
    }

    @Test
    public void testDisplayTextWithMoreThanFiveAuthors() throws Exception {
        context.turnOffAuthorisationSystem();
        Item itemManyAuthors = ItemBuilder.createItem(context, collection)
                .withAuthor("First Author")
                .withAuthor("Second Author")
                .withAuthor("Third Author")
                .withAuthor("Fourth Author")
                .withAuthor("Fifth Author")
                .withAuthor("Sixth Author")
                .build();
        context.restoreAuthSystemState();

        String token = getAuthToken(admin.getEmail(), password);
        getClient(token).perform(get("/api/core/refbox?handle=" + itemManyAuthors.getHandle()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayText").value(org.hamcrest.Matchers.containsString(
                        "First Author; et al.")));
    }
}
