/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest;

import static com.jayway.jsonpath.matchers.JsonPathMatchers.hasJsonPath;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.dspace.app.rest.test.AbstractControllerIntegrationTest;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.ItemBuilder;
import org.dspace.content.Collection;
import org.dspace.content.Item;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;

/**
 * Integration test for the {@link org.dspace.app.rest.repository.SuggestionRestController}
 *
 * @author Milan Majchrak (dspace at dataquest.sk)
 */
public class SuggestionRestControllerIT extends AbstractControllerIntegrationTest {

    private Item publicItem;
    private Collection col;
    private final String SUBJECT_SEARCH_VALUE = "test subject";
    private final String LANGUAGE_SEARCH_VALUE_KEY = "Alumu-Tesu";
    private final String LANGUAGE_SEARCH_VALUE_VALUE = "aab";
    private final String ITEM_TITLE = "Item title";

    @Before
    public void setup() throws Exception {
        context.turnOffAuthorisationSystem();
        // 1. A community-collection structure with one parent community and one collection
        parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Parent Community")
                .build();

        col = CollectionBuilder.createCollection(context, parentCommunity).withName("Collection").build();

        // 2. Create item and add it to the collection
        publicItem = ItemBuilder.createItem(context, col)
                .withTitle(ITEM_TITLE)
                .withMetadata("dc", "subject", null, SUBJECT_SEARCH_VALUE )
                .build();

        context.restoreAuthSystemState();
    }

    /**
     * Should return formatted suggestions in the VocabularyEntryRest objects
     */
    @Test
    public void testSearchBySubjectAcSolrIndex() throws Exception {
        String userToken = getAuthToken(eperson.getEmail(), password);
        // substring = find only by the `test` value
        getClient(userToken).perform(get("/api/suggestions?autocompleteCustom=solr-subject_ac&searchValue=" +
                        SUBJECT_SEARCH_VALUE.substring(0, 4)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(contentType))
                .andExpect(jsonPath("$._embedded.vocabularyEntryRests", Matchers.hasItem(
                        allOf(
                                hasJsonPath("$.display", is(SUBJECT_SEARCH_VALUE)),
                                hasJsonPath("$.value", is(SUBJECT_SEARCH_VALUE)),
                                hasJsonPath("$.type", is("vocabularyEntry"))
                ))));
    }

    /**
     * Should return no suggestions
     */
    @Test
    public void testSearchBySubjectAcSolrIndex_noResults() throws Exception {
        String userToken = getAuthToken(eperson.getEmail(), password);
        // substring = find only by the `test` value
        getClient(userToken).perform(get("/api/suggestions?autocompleteCustom=solr-subject_ac&searchValue=" +
                        "no such subject"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(contentType))
                .andExpect(jsonPath("$.page.totalElements", is(0)))
                .andExpect(jsonPath("$._embedded.vocabularyEntryRests").doesNotExist());
    }

    /**
     * Should return suggestions from the JSON file
     */
    @Test
    public void testSearchByLanguageFromJson() throws Exception {
        String userToken = getAuthToken(eperson.getEmail(), password);
        getClient(userToken).perform(
                get("/api/suggestions?autocompleteCustom=json_static-iso_langs.json&searchValue=" +
                        LANGUAGE_SEARCH_VALUE_KEY))
                .andExpect(status().isOk())
                .andExpect(content().contentType(contentType))
                .andExpect(jsonPath("$.page.totalElements", is(1)))
                .andExpect(jsonPath("$._embedded.vocabularyEntryRests", Matchers.hasItem(
                        allOf(
                                hasJsonPath("$.display", is(LANGUAGE_SEARCH_VALUE_KEY)),
                                hasJsonPath("$.value", is(LANGUAGE_SEARCH_VALUE_VALUE)),
                                hasJsonPath("$.type", is("vocabularyEntry"))
                        ))));
    }

    /**
     * Should return no suggestions from the JSON file
     */
    @Test
    public void testSearchByLanguageFromJson_noResults() throws Exception {
        String userToken = getAuthToken(eperson.getEmail(), password);
        getClient(userToken).perform(
                get("/api/suggestions?autocompleteCustom=json_static-iso_langs.json&searchValue=" +
                        "no such language"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(contentType))
                .andExpect(jsonPath("$.page.totalElements", is(0)))
                .andExpect(jsonPath("$._embedded.vocabularyEntryRests").doesNotExist());
    }

    /**
     * Should return suggestions from the solr `title_ac` index.
     * Compose specific query from the definition and the search value.
     */
    @Test
    public void testSearchBySpecificQueryFromSolr() throws Exception {
        String userToken = getAuthToken(eperson.getEmail(), password);
        getClient(userToken).perform(
                get("/api/suggestions?autocompleteCustom=solr-title_ac?query=title_ac:**&searchValue=" +
                        ITEM_TITLE.substring(0, 4)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(contentType))
                .andExpect(jsonPath("$.page.totalElements", is(1)))
                .andExpect(jsonPath("$._embedded.vocabularyEntryRests", Matchers.hasItem(
                        allOf(
                                hasJsonPath("$.display", is(ITEM_TITLE)),
                                hasJsonPath("$.value", is(ITEM_TITLE)),
                                hasJsonPath("$.type", is("vocabularyEntry"))
                        ))));
    }

    /**
     * Should return suggestions from the solr `title_ac` index.
     * Compose specific query from the definition and the search value.
     */
    @Test
    public void testSearchBySpecificQueryFromSolr_noresults() throws Exception {
        String userToken = getAuthToken(eperson.getEmail(), password);
        getClient(userToken).perform(
                get("/api/suggestions?autocompleteCustom=solr-title_ac?query=title_ac:**&searchValue=" +
                        "no such title"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(contentType))
                .andExpect(jsonPath("$.page.totalElements", is(0)))
                .andExpect(jsonPath("$._embedded.vocabularyEntryRests").doesNotExist());
    }

    /**
     * Should return 401 Forbidden
     */
    @Test
    public void testShouldNotAuthorized() throws Exception {
        getClient().perform(get("/api/suggestions?autocompleteCustom=solr-title_ac?query=title_ac:**&searchValue=" +
                        "no such title"))
                .andExpect(status().isUnauthorized());
    }
}
