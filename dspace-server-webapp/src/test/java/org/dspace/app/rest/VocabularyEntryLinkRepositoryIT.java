/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Objects;

import org.dspace.app.rest.test.AbstractControllerIntegrationTest;
import org.dspace.core.factory.CoreServiceFactory;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.hamcrest.Matchers;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.springframework.test.web.servlet.ResultActions;

public class VocabularyEntryLinkRepositoryIT extends AbstractControllerIntegrationTest {

    private static final String BASE_VOCABULARY_URL = "/api/submission/vocabularies";
    private static final String ROR_AUTHORITY_ENTRIES_URL = BASE_VOCABULARY_URL + "/SimpleRORAuthority/entries";
    private static final int MOCK_TOTAL_ELEMENTS = 30133;
    private static final String CHOICE_AUTHORITY_PLUGIN_KEY =
            "plugin.named.org.dspace.content.authority.ChoiceAuthority";

    private static String[] originalChoiceAuthorities;

    @BeforeClass
    public static void beforeClass() {
        ConfigurationService configurationService = DSpaceServicesFactory.getInstance().getConfigurationService();
        originalChoiceAuthorities = configurationService.getArrayProperty(CHOICE_AUTHORITY_PLUGIN_KEY);
        configurationService.setProperty(CHOICE_AUTHORITY_PLUGIN_KEY,
                new String[] {
                        "org.dspace.content.authority.SimpleRORAuthority = SimpleRORAuthority"
                });
        CoreServiceFactory.getInstance().getPluginService().clearNamedPluginClasses();
    }

    @AfterClass
    public static void afterClass() {
        // restore the original ChoiceAuthority plugin configuration so this class does not
        // leak the SimpleRORAuthority registration into other integration tests
        ConfigurationService configurationService = DSpaceServicesFactory.getInstance().getConfigurationService();
        configurationService.setProperty(CHOICE_AUTHORITY_PLUGIN_KEY, originalChoiceAuthorities);
        CoreServiceFactory.getInstance().getPluginService().clearNamedPluginClasses();
    }

    @Test
    public void rorAuthoritySizeNotDivisorOf20() throws Exception {
        getClient().perform(get(ROR_AUTHORITY_ENTRIES_URL)
                                .param("filter", "University")
                                .param("size", "3"))
                .andExpect(status().isBadRequest())
                .andExpect(result -> Assert.assertEquals(
                        "The page size must be a divisor of 20.",
                        Objects.requireNonNull(result.getResolvedException()).getMessage()));
    }

    @Test
    public void rorAuthorityTooManyPages() throws Exception {
        getClient().perform(get(ROR_AUTHORITY_ENTRIES_URL)
                        .param("filter", "University")
                        .param("page", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(result -> Assert.assertEquals(
                        "Exceeded maximal page number for the ROR API, which is 499, for page size 20.",
                        Objects.requireNonNull(result.getResolvedException()).getMessage()));
    }

    @Test
    public void rorAuthorityTooManyPagesForSize4() throws Exception {
        getClient().perform(get(ROR_AUTHORITY_ENTRIES_URL)
                        .param("filter", "University")
                        .param("size", "4")
                        .param("page", "2500"))
                .andExpect(status().isBadRequest())
                .andExpect(result -> Assert.assertEquals(
                        "Exceeded maximal page number for the ROR API, which is 2499, for page size 4.",
                        Objects.requireNonNull(result.getResolvedException()).getMessage()));
    }

    @Test
    public void rorAuthorityRequestWithEntryID() throws Exception {
        checkSingleItemResponse(getClient().perform(get(ROR_AUTHORITY_ENTRIES_URL)
                .param("entryID", "03ad39j10")), "University of Pisa", "University of Pisa");
    }

    @Test
    public void rorAuthorityRequestWithBadEntryID() throws Exception {
        getClient().perform(get(ROR_AUTHORITY_ENTRIES_URL)
                        .param("entryID", "wrong_entry_id"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.entries", Matchers.hasSize(0)))
                .andExpect(jsonPath("$.page.size", Matchers.is(20)))
                .andExpect(jsonPath("$.page.number", Matchers.is(0)))
                .andExpect(jsonPath("$.page.totalElements", Matchers.is(0)))
                .andExpect(jsonPath("$.page.totalPages", Matchers.is(0)));
    }

    @Test
    public void rorAuthorityRequestWithQueryExact() throws Exception {
        checkSingleItemResponse(getClient().perform(get(ROR_AUTHORITY_ENTRIES_URL)
                .param("filter", "University of Pisa")
                .param("exact", "true")), "University of Pisa", "University of Pisa");
    }

    @Test
    public void rorAuthorityRequestWithResponseInLocale() throws Exception {
        ConfigurationService configurationService = DSpaceServicesFactory.getInstance().getConfigurationService();
        String defaultLocale = configurationService.getProperty("default.locale");
        configurationService.setProperty("default.locale", "it");
        configurationService.setProperty("ror.authority.stored-name-type", "locale_label");

        try {
            checkSingleItemResponse(getClient().perform(get(ROR_AUTHORITY_ENTRIES_URL)
                    .param("filter", "University of Pisa")
                    .param("exact", "true")), "Università di Pisa", "Università di Pisa");
        } finally {
            configurationService.setProperty("default.locale", defaultLocale);
            configurationService.setProperty("ror.authority.stored-name-type", "en_label");
        }
    }

    @Test
    public void rorAuthorityRequestWithRorDisplaySelectionType() throws Exception {
        ConfigurationService configurationService = DSpaceServicesFactory.getInstance().getConfigurationService();
        String defaultLocale = configurationService.getProperty("default.locale");
        configurationService.setProperty("default.locale", "it");
        configurationService.setProperty("ror.authority.stored-name-type", "ror_display");

        try {
            checkSingleItemResponse(getClient().perform(get(ROR_AUTHORITY_ENTRIES_URL)
                    .param("filter", "University of Pisa")
                    .param("exact", "true")), "University of Pisa", "Università di Pisa");
        } finally {
            configurationService.setProperty("default.locale", defaultLocale);
            configurationService.setProperty("ror.authority.stored-name-type", "en_label");
        }
    }

    @Test
    public void rorAuthorityRequestWithQuery() throws Exception {
        getClient().perform(get(ROR_AUTHORITY_ENTRIES_URL)
                        .param("filter", "University of Pisa"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.entries", Matchers.hasSize(20)))
                .andExpect(jsonPath("$.page.size", Matchers.is(20)))
                .andExpect(jsonPath("$.page.number", Matchers.is(0)))
                .andExpect(jsonPath("$.page.totalElements", Matchers.is(MOCK_TOTAL_ELEMENTS)))
                .andExpect(jsonPath("$.page.totalPages", Matchers.is(MOCK_TOTAL_ELEMENTS / 20 + 1)));
    }

    @Test
    public void rorAuthorityRequestWithQueryAndPagination() throws Exception {
        getClient().perform(get(ROR_AUTHORITY_ENTRIES_URL)
                        .param("filter", "University of Pisa")
                        .param("size", "4")
                        .param("page", "2000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.entries", Matchers.hasSize(4)))
                .andExpect(jsonPath("$.page.size", Matchers.is(4)))
                .andExpect(jsonPath("$.page.number", Matchers.is(2000)))
                .andExpect(jsonPath("$.page.totalElements", Matchers.is(MOCK_TOTAL_ELEMENTS)))
                .andExpect(jsonPath("$.page.totalPages", Matchers.is(MOCK_TOTAL_ELEMENTS / 4 + 1)));
    }

    private void checkSingleItemResponse(ResultActions resultActions, String expectedValue, String expectedDisplay)
            throws Exception {
        resultActions.andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.entries", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.page.size", Matchers.is(20)))
                .andExpect(jsonPath("$.page.number", Matchers.is(0)))
                .andExpect(jsonPath("$.page.totalElements", Matchers.is(1)))
                .andExpect(jsonPath("$.page.totalPages", Matchers.is(1)))
                .andExpect(jsonPath("$._embedded.entries[0].authority", Matchers.is("03ad39j10")))
                .andExpect(jsonPath("$._embedded.entries[0].display", Matchers.is(expectedDisplay)))
                .andExpect(jsonPath("$._embedded.entries[0].value", Matchers.is(expectedValue)))
                .andExpect(jsonPath("$._embedded.entries[0].otherInformation.location",
                        Matchers.is("Pisa, Tuscany, Italy, Europe")));
    }

}
