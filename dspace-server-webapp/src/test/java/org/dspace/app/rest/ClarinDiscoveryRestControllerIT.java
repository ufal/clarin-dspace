/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.matchers.JsonPathMatchers;
import org.dspace.app.rest.matcher.SearchResultMatcher;
import org.dspace.app.rest.test.AbstractControllerIntegrationTest;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.ItemBuilder;
import org.dspace.content.Collection;
import org.dspace.content.Community;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.junit.Test;

/**
 * Discovery tests for the CLARIN customisations that {@link DiscoveryRestControllerIT} does not cover: the
 * hierarchical {@code subject} facet with a {@code ::} splitter, the {@code subjectFirstValue} facet of the
 * CLARIN {@code homepage} configuration, which collapses such values to their first node, and the items-only
 * search configurations next to the inclusive {@code default} one.
 *
 * <p>On DSpace 7 this class was a full copy of {@code DiscoveryRestControllerIT}, because that class hard-coded
 * the vanilla facet list and therefore failed against the CLARIN {@code discovery.xml}. DSpace 9 derives its
 * expected facets and filters from the live configuration instead ({@code FacetEntryMatcher.defaultFacetMatchers},
 * {@code SearchFilterMatcher.searchFilterMatchers}), so {@code DiscoveryRestControllerIT} runs green against the
 * CLARIN configuration and only the tests below have no counterpart there.</p>
 *
 * @author Milan Majchrak (dspace at dataquest.sk)
 */
public class ClarinDiscoveryRestControllerIT extends AbstractControllerIntegrationTest {

    @Test
    public void showFacetValuesWithSplitterInSearchPage() throws Exception {
        context.turnOffAuthorisationSystem();

        parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Parent Community").build();

        Collection col1 = CollectionBuilder.createCollection(context, parentCommunity)
                .withName("Collection 1").build();

        ItemBuilder.createItem(context, col1)
                .withTitle("Public item 1")
                .withIssueDate("2017-10-17")
                .withAuthor("Smith, Donald")
                .withSubject("People")
                .build();

        ItemBuilder.createItem(context, col1)
                .withTitle("Public item 2")
                .withIssueDate("2020-02-13")
                .withAuthor("Doe, Jane")
                .withSubject("People::Jane")
                .build();

        ItemBuilder.createItem(context, col1)
                .withTitle("Public item 2")
                .withIssueDate("2020-02-13")
                .withAuthor("Doe, Jane")
                .withSubject("People::Jane")
                .build();

        context.restoreAuthSystemState();

        getClient().perform(get("/api/discover/facets/subject"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type", is("discover")))
                .andExpect(jsonPath("$.name", is("subject")))
                .andExpect(jsonPath("$.facetType", is("hierarchical")))
                .andExpect(jsonPath("$.scope", is(emptyOrNullString())))
                .andExpect(jsonPath("$._links.self.href",
                        containsString("api/discover/facets/subject")))
                .andExpect(jsonPath("$._embedded.values[0].label", is("People")))
                .andExpect(jsonPath("$._embedded.values[0].count", is(3)))
                .andExpect(jsonPath("$._embedded.values[1].label", is("People::Jane")))
                .andExpect(jsonPath("$._embedded.values[1].count", is(2)))
                .andExpect(jsonPath("$", JsonPathMatchers.hasNoJsonPath("_embedded.values[2].label")))
                .andExpect(jsonPath("$", JsonPathMatchers.hasNoJsonPath("_embedded.values[2].count")))
                .andExpect(jsonPath("$._embedded.values").value(Matchers.hasSize(2)));
    }

    @Test
    public void doNotShowFacetValuesWithSplitterInHomePage() throws Exception {
        context.turnOffAuthorisationSystem();

        parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Parent Community").build();

        Collection col1 = CollectionBuilder.createCollection(context, parentCommunity)
                .withName("Collection 1").build();

        ItemBuilder.createItem(context, col1)
                .withTitle("Public item 1")
                .withIssueDate("2017-10-17")
                .withAuthor("Smith, Donald")
                .withSubject("People")
                .build();

        ItemBuilder.createItem(context, col1)
                .withTitle("Public item 2")
                .withIssueDate("2020-02-13")
                .withAuthor("Doe, Jane")
                .withSubject("People::Jane")
                .build();

        ItemBuilder.createItem(context, col1)
                .withTitle("Public item 2")
                .withIssueDate("2020-02-13")
                .withAuthor("Doe, Jane")
                .withSubject("People::Jane")
                .build();

        ItemBuilder.createItem(context, col1)
                .withTitle("Public item 2")
                .withIssueDate("2020-02-13")
                .withAuthor("Doe, Jane")
                .withSubject("Another subject")
                .build();

        context.restoreAuthSystemState();

        getClient().perform(get("/api/discover/facets/subjectFirstValue")
                        .param("configuration", "homepage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type", is("discover")))
                .andExpect(jsonPath("$.name", is("subjectFirstValue")))
                .andExpect(jsonPath("$.facetType", is("hierarchical")))
                .andExpect(jsonPath("$.scope", is(emptyOrNullString())))
                .andExpect(jsonPath("$._links.self.href",
                        containsString("api/discover/facets/subjectFirstValue")))
                .andExpect(jsonPath("$._embedded.values[0].label", is("People")))
                .andExpect(jsonPath("$._embedded.values[0].count", is(3)))
                .andExpect(jsonPath("$._embedded.values[1].label", is("Another subject")))
                .andExpect(jsonPath("$._embedded.values[1].count", is(1)))
                .andExpect(jsonPath("$", JsonPathMatchers.hasNoJsonPath("_embedded.values[2].label")))
                .andExpect(jsonPath("$", JsonPathMatchers.hasNoJsonPath("_embedded.values[2].count")))
                .andExpect(jsonPath("$._embedded.values").value(Matchers.hasSize(2)));
    }

    /**
     * The scope selector sends no {@code configuration} parameter, so its request runs against the default
     * discovery configuration. A default filter query that keeps items only empties that selector.
     */
    @Test
    public void defaultSearchReturnsCommunitiesAndCollectionsByDsoType() throws Exception {
        context.turnOffAuthorisationSystem();

        parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Scope Selector Community").build();

        Collection col1 = CollectionBuilder.createCollection(context, parentCommunity)
                .withName("Scope Selector Corpora").build();

        ItemBuilder.createItem(context, col1)
                .withTitle("Scope Selector Item")
                .withIssueDate("2024-05-06")
                .build();

        context.restoreAuthSystemState();

        getClient().perform(get("/api/discover/search/objects")
                        .param("dsoType", "Collection"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.searchResult.page.totalElements", is(1)))
                .andExpect(jsonPath("$._embedded.searchResult._embedded.objects", Matchers.contains(
                        SearchResultMatcher.matchOnItemName("collection", "collections", "Scope Selector Corpora"))));

        getClient().perform(get("/api/discover/search/objects")
                        .param("dsoType", "Community"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.searchResult.page.totalElements", is(1)))
                .andExpect(jsonPath("$._embedded.searchResult._embedded.objects", Matchers.contains(
                        SearchResultMatcher.matchOnItemName("community", "communities",
                                "Scope Selector Community"))));

        getClient().perform(get("/api/discover/search/objects")
                        .param("dsoType", "Community")
                        .param("dsoType", "Collection")
                        .param("query", "Corpora"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.searchResult.page.totalElements", is(1)))
                .andExpect(jsonPath("$._embedded.searchResult._embedded.objects", Matchers.contains(
                        SearchResultMatcher.matchOnItemName("collection", "collections", "Scope Selector Corpora"))));
    }

    /**
     * The search page uses the "items" configuration and community and collection pages use "community" and
     * "collection". These return items only, while "default" still finds communities for the scope selector.
     */
    @Test
    public void itemsOnlyConfigurationsReturnNoCommunitiesOrCollections() throws Exception {
        context.turnOffAuthorisationSystem();

        parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Items Only Community").build();
        Community subCommunity = CommunityBuilder.createSubCommunity(context, parentCommunity)
                .withName("Items Only Subcommunity").build();
        Collection col1 = CollectionBuilder.createCollection(context, parentCommunity)
                .withName("Items Only Collection").build();
        Collection col2 = CollectionBuilder.createCollection(context, subCommunity)
                .withName("Items Only Subcollection").build();

        ItemBuilder.createItem(context, col1)
                .withTitle("Items Only Item One")
                .build();
        ItemBuilder.createItem(context, col2)
                .withTitle("Items Only Item Two")
                .build();

        context.restoreAuthSystemState();

        Matcher<Iterable<? extends Object>> bothItems = Matchers.containsInAnyOrder(
                SearchResultMatcher.matchOnItemName("item", "items", "Items Only Item One"),
                SearchResultMatcher.matchOnItemName("item", "items", "Items Only Item Two"));

        getClient().perform(get("/api/discover/search/objects")
                        .param("configuration", "items"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.searchResult.page.totalElements", is(2)))
                .andExpect(jsonPath("$._embedded.searchResult._embedded.objects", bothItems));

        getClient().perform(get("/api/discover/search/objects")
                        .param("configuration", "community")
                        .param("scope", parentCommunity.getID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.searchResult.page.totalElements", is(2)))
                .andExpect(jsonPath("$._embedded.searchResult._embedded.objects", bothItems));

        getClient().perform(get("/api/discover/search/objects")
                        .param("configuration", "collection"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.searchResult.page.totalElements", is(2)))
                .andExpect(jsonPath("$._embedded.searchResult._embedded.objects", bothItems));

        getClient().perform(get("/api/discover/search/objects")
                        .param("configuration", "default")
                        .param("dsoType", "Community"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.searchResult.page.totalElements", is(2)))
                .andExpect(jsonPath("$._embedded.searchResult._embedded.objects", Matchers.containsInAnyOrder(
                        SearchResultMatcher.matchOnItemName("community", "communities", "Items Only Community"),
                        SearchResultMatcher.matchOnItemName("community", "communities",
                                "Items Only Subcommunity"))));
    }
}
