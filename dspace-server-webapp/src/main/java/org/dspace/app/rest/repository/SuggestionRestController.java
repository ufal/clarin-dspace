/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.repository;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.BadRequestException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;
import org.dspace.app.rest.model.VocabularyEntryRest;
import org.dspace.app.rest.model.hateoas.VocabularyEntryResource;
import org.dspace.app.rest.utils.Utils;
import org.dspace.core.Context;
import org.dspace.discovery.DiscoverQuery;
import org.dspace.discovery.DiscoverResult;
import org.dspace.discovery.SearchService;
import org.dspace.discovery.SearchServiceException;
import org.dspace.discovery.indexobject.IndexableItem;
import org.dspace.services.ConfigurationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedResourcesAssembler;
import org.springframework.hateoas.PagedModel;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Returns VocabularyEntries that contain searchValue. The search is performed on the specific index that is defined by
 * the `autocompleteCustom` parameter in the `submission-forms.xml`.
 *
 * @author Milan Majchrak (dspace at dataquest.sk)
 */
@RestController
@RequestMapping("/api/suggestions")
public class SuggestionRestController extends AbstractDSpaceRestRepository {

    private static final Logger log = org.apache.logging.log4j.LogManager.getLogger(SuggestionRestController.class);


    /**
     * Prefix for the configuration that defines the separator for the autocompleteCustom parameter.
     */
    private static final String AUTOCOMPLETE_CUSTOM_CFG_FORMAT_PREFIX = "autocomplete.custom.separator.";

    /**
     * Solr prefix for the autocompleteCustom parameter that define the source of the suggestions.
     */
    private static final String AUTOCOMPLETE_CUSTOM_SOLR_PREFIX = "solr-";

    /**
     * Json file prefix for the autocompleteCustom parameter that define the source of the suggestions.
     */
    private static final String AUTOCOMPLETE_CUSTOM_JSON_PREFIX = "json_static-";

    /**
     * Query parameter from the autocompleteCustom parameter that define specific query for the Solr search.
     */
    private static final String AUTOCOMPLETE_CUSTOM_SOLR_QUERY_PARAM = "query=";

    /**
     * Limit of suggestions that will be returned from the JSON file. The limit is used to prevent
     * the loading of a large amount of data from the JSON file.
     */
    private static final int JSON_SUGGESTIONS_LIMIT = 8;

    @Autowired
    private SearchService searchService;

    @Autowired
    private ConfigurationService configurationService;

    /**
     * Map that contains loaded JSON suggestions. The key is the autocompleteCustom parameter and the value is the
     * loaded JSON data. The JSON data is loaded only once and stored in the map for further use.
     */
    Map<String, JsonNode> jsonSuggestions = new HashMap<>();

    /**
     * Returns a list of VocabularyEntryRest objects that contain values that contain searchValue.
     * The search is performed on the specific index or a specific json file that is defined
     * by the autocompleteCustom parameter.
     */
    @PreAuthorize("hasAuthority('AUTHENTICATED')")
    @RequestMapping(method = RequestMethod.GET)
    public PagedModel<VocabularyEntryResource> filter(@Nullable HttpServletRequest request,
                                                      @Nullable Pageable optionalPageable,
                                                      @RequestParam(name = "autocompleteCustom", required = false)
                                                        String autocompleteCustom,
                                                      @RequestParam(name = "searchValue", required = false)
                                                        String searchValue,
                                                      PagedResourcesAssembler assembler) throws SearchServiceException {
        // If the searching for the autocompleteCustom parameter is not allowed, return an error
        if (!isAllowedSearching(autocompleteCustom)) {
            String errorMessage = "Searching for autocompleteCustom: " + autocompleteCustom + " is not allowed";
            log.warn(errorMessage);
            throw new BadRequestException(errorMessage);
        }

        Pageable pageable = utils.getPageable(optionalPageable);
        List<VocabularyEntryRest> results;
        // Load suggestions from the specific source (Solr or JSON)
        if (autocompleteCustom.startsWith(AUTOCOMPLETE_CUSTOM_JSON_PREFIX)) {
            results = getSuggestions(autocompleteCustom, searchValue, AUTOCOMPLETE_CUSTOM_JSON_PREFIX);
        } else if (autocompleteCustom.startsWith(AUTOCOMPLETE_CUSTOM_SOLR_PREFIX)) {
            results = getSuggestions(autocompleteCustom, searchValue, AUTOCOMPLETE_CUSTOM_SOLR_PREFIX);
        } else {
            log.warn("Cannot fetch suggestions for autocompleteCustom: {} with searching value: {}",
                    autocompleteCustom, searchValue);
            // Return empty list
            results = new ArrayList<>(0);
        }

        // If no results are found, return null
        if (CollectionUtils.isEmpty(results)) {
            log.info("No suggestions found for autocompleteCustom: {} with searching value: {}",
                    autocompleteCustom, searchValue);
        }

        // Remove duplicates from the results
        List<VocabularyEntryRest> finalResults = results.stream()
                .filter(Utils.distinctByKey(VocabularyEntryRest::getValue))
                .collect(Collectors.toList());

        // Remove `?query` from the autocompleteCustom parameter if it contains this specific query parameter
        String autocompleteCustomWithoutQuery = updateAutocompleteAndQuery(autocompleteCustom, null);
        // Format the values according to the configuration
        finalResults = finalResults.stream()
                .map(ver -> formatValue(ver, autocompleteCustomWithoutQuery))
                .collect(Collectors.toList());

        // Create a page with the final results. The page is needed for the better processing in the frontend.
        Page<VocabularyEntryRest> resultsPage = new PageImpl<>(finalResults, pageable, finalResults.size());
        PagedModel<VocabularyEntryResource> response = assembler.toModel(resultsPage);
        return response;
    }

    /**
     * Returns a list of VocabularyEntryRest objects which contain values with searching value.
     * The search is performed on the specific index or json file that is defined by the autocompleteCustom parameter.
     */
    private List<VocabularyEntryRest> getSuggestions(String autocompleteCustom, String searchValue, String prefix)
            throws SearchServiceException {
        // Remove the prefix from the autocompleteCustom parameter
        String normalizedAutocompleteCustom = removeAutocompleteCustomPrefix(prefix, autocompleteCustom);
        // Normalize the search value - remove leading and trailing whitespaces
        String normalizedSearchValue = searchValue.trim();
        // Create a list of VocabularyEntryRest objects that will be filtered from duplicate values and returned
        // as a response.
        List<VocabularyEntryRest> results = new ArrayList<>();

        if (prefix.equals(AUTOCOMPLETE_CUSTOM_SOLR_PREFIX)) {
            // Load suggestions from Solr
            results = loadSuggestionsFromSolr(normalizedAutocompleteCustom, normalizedSearchValue, results);
        } else if (prefix.equals(AUTOCOMPLETE_CUSTOM_JSON_PREFIX)) {
            // Load suggestions from JSON
            results = loadSuggestionsFromJson(normalizedAutocompleteCustom, normalizedSearchValue, results);
        }

        return results;

    }

    /**
     * Load suggestions from the JSON file. The JSON file is loaded only once and stored in the map for further use.
     * The search is performed on the specific key in the JSON file. The key is the autocompleteCustom parameter.
     */
    private List<VocabularyEntryRest> loadSuggestionsFromJson(String autocompleteCustom, String searchValue,
                                                   List<VocabularyEntryRest> results) {
        try {
            // Load the JSON data from the file.
            JsonNode jsonData;
            if (!jsonSuggestions.containsKey(autocompleteCustom)) {
                // Load the JSON data from the file and store it in the map for further use.
                JsonNode loadedJsonSuggestions = loadJsonFromFile(autocompleteCustom);
                jsonData = loadedJsonSuggestions;
                jsonSuggestions.put(autocompleteCustom, loadedJsonSuggestions);
            } else {
                // Get the JSON data from the map
                jsonData = jsonSuggestions.get(autocompleteCustom);
            }

            if (jsonData == null) {
                log.warn("Cannot load JSON suggestions from file: {}", autocompleteCustom);
                return results;
            }

            // Search for a specific key
            results = searchByKey(jsonData, searchValue, results);

        } catch (IOException e) {
            log.error("Error while loading JSON suggestions from file: {} because: {}", autocompleteCustom,
                    e.getMessage());
        }
        return results;
    }

    /**
     * Load suggestions from Solr. The search is performed on the specific index that is defined by the
     * autocompleteCustom parameter.
     */
    private List<VocabularyEntryRest> loadSuggestionsFromSolr(String autocompleteCustom, String searchValue,
                                                   List<VocabularyEntryRest> results)
            throws SearchServiceException {
        Context context = obtainContext();
        // Create a DiscoverQuery object that will be used to search for the results.
        DiscoverQuery discoverQuery = new DiscoverQuery();
        // Process the custom query if it contains the specific query parameter `?query=`
        autocompleteCustom = updateAutocompleteAndQuery(autocompleteCustom, discoverQuery);
        // TODO - search facets and process facet results instead of indexable objects
        discoverQuery.setMaxResults(500);
        // return only metadata field values
        discoverQuery.addSearchField(autocompleteCustom);

        String normalizedQuery = Utils.normalizeDiscoverQuery(searchValue, autocompleteCustom);
        if (StringUtils.isNotBlank(normalizedQuery)) {
            discoverQuery.setQuery(normalizedQuery);
        }

        // Search for the results
        DiscoverResult searchResult = searchService.search(context, discoverQuery);

        // Iterate over all indexable objects in the search result. We need indexable object to get search documents.
        // Each search document contains values from the specific index.
        processSolrSearchResults(searchResult, autocompleteCustom, searchValue, results);

        return results;
    }

    /**
     * Process the search results from Solr. The search results are processed and filtered according to the searchValue.
     */
    private void processSolrSearchResults(DiscoverResult searchResult, String autocompleteCustom, String searchValue,
                                          List<VocabularyEntryRest> results) {
        searchResult.getIndexableObjects().forEach(object -> {
            if (!(object instanceof IndexableItem)) {
                return;
            }
            IndexableItem item = (IndexableItem) object;
            // Get all search documents for the item.
            searchResult.getSearchDocument(item).forEach((searchDocument) -> {
                VocabularyEntryRest vocabularyEntryRest = new VocabularyEntryRest();
                // All values from Item's specific index - it could contain values we are not looking for.
                // The must be filtered out.
                List<String> docValues = searchDocument.getSearchFieldValues(autocompleteCustom);

                // Filter values that contain searchValue
                List<String> filteredValues = docValues.stream()
                        .filter(value -> value.contains(searchValue))
                        .collect(Collectors.toList());

                // Add filtered values to the results. It contains only values that contain searchValue.
                filteredValues.forEach(value -> {
                    vocabularyEntryRest.setDisplay(value);
                    vocabularyEntryRest.setValue(value);
                    results.add(vocabularyEntryRest);
                });
            });
        });
    }

    /**
     * Process the custom query if it contains the specific query parameter `?query=`.
     * The query is processed and set to the DiscoverQuery object.
     * The method returns the part before the query parameter as the new autocompleteCustom parameter.
     * @param discoverQuery could be null
     */
    private String updateAutocompleteAndQuery(String autocompleteCustom, DiscoverQuery discoverQuery) {
        if (!autocompleteCustom.contains(AUTOCOMPLETE_CUSTOM_SOLR_QUERY_PARAM)) {
            return autocompleteCustom;
        }

        // Query parameter starts with `?`
        String[] parts = autocompleteCustom.split("\\?" + AUTOCOMPLETE_CUSTOM_SOLR_QUERY_PARAM);
        // 2 parts are expected - the part before the query parameter and after the query parameter
        if (parts.length == 2) {
            if (discoverQuery != null) {
                discoverQuery.setQuery(parts[1]);
            }
            return parts[0];  // Return the part before "?query="
        }

        return autocompleteCustom;
    }

    /**
     * Load JSON data from the file. The JSON data is loaded from the resources' folder.
     */
    public JsonNode loadJsonFromFile(String filePath) throws IOException {
        // Load the file from the resources folder
        ClassPathResource resource = new ClassPathResource(filePath);

        // Use Jackson ObjectMapper to read the JSON file
        ObjectMapper objectMapper = new ObjectMapper();
        return objectMapper.readTree(resource.getInputStream());
    }

    /**
     * Search for the specific key in the JSON object. The search is performed on the specific key in the JSON object.
     * The key is the autocompleteCustom parameter.
     */
    public List<VocabularyEntryRest> searchByKey(JsonNode jsonNode, String searchKey,
                                                 List<VocabularyEntryRest> results) {
        // Iterate over all fields (keys) in the JSON object
        Iterator<String> fieldNames = jsonNode.fieldNames();
        while (fieldNames.hasNext() && results.size() < JSON_SUGGESTIONS_LIMIT) {
            String key = fieldNames.next();

            // If the key matches or contains the search term (case-insensitive)
            if (key.toLowerCase().contains(searchKey.toLowerCase())) {
                // Add key-value pair to the result
                VocabularyEntryRest vocabularyEntryRest = new VocabularyEntryRest();
                vocabularyEntryRest.setDisplay(key);
                vocabularyEntryRest.setValue(jsonNode.get(key).asText());
                results.add(vocabularyEntryRest);
            }
        }
        return results;
    }

    /**
     * Format the value according to the configuration.
     * The result value could consist of multiple parts separated by a separator. Keep the correct part separated by
     * the separator loaded from the configuration.
     */
    private VocabularyEntryRest formatValue(VocabularyEntryRest ver, String autocompleteCustom) {
        if (StringUtils.isEmpty(ver.getValue()) || StringUtils.isEmpty(autocompleteCustom)) {
            return ver;
        }

        // Load separator from the configuration `autocomplete.custom.separator.<autocompleteCustom>
        String separator = configurationService.getProperty(AUTOCOMPLETE_CUSTOM_CFG_FORMAT_PREFIX + autocompleteCustom);
        if (StringUtils.isEmpty(separator)) {
            return ver;
        }

        // Split the value by the separator and keep the correct - second part
        String[] parts = ver.getValue().split(separator);
        // Check the length of the parts - the correct value is the second part
        if (parts.length > 1) {
            String formattedValue = parts[1].trim(); // The correct value is the second part
            ver.setValue(formattedValue);
            ver.setDisplay(formattedValue);
        }

        return ver;
    }

    /**
     * Remove the prefix from the autocompleteCustom parameter. E.g. remove "solr-" or "json_static-".
     */
    private String removeAutocompleteCustomPrefix(String prefix, String autocompleteCustom) {
        return autocompleteCustom.replace(prefix, "");
    }

    /**
     * Check if the autocompleteCustom parameter is allowed to be searched.
     * To allow searching, the `autocomplete.custom.allowed` property must be defined in the configuration.
     */
    private boolean isAllowedSearching(String autocompleteCustom) {
        // Check if the autocompleteCustom parameter is allowed to be searched
        String[] allowedAutocompleteCustom = configurationService.getArrayProperty("autocomplete.custom.allowed",
                new String[0]);

        // Remove `?query` from the autocompleteCustom parameter if it contains this specific query parameter
        String normalizedAutocompleteCustom = updateAutocompleteAndQuery(autocompleteCustom, null);

        // If the allowedAutocompleteCustom parameter is not defined, return false
        if (Objects.isNull(allowedAutocompleteCustom)) {
            return false;
        }

        // Convert the allowedAutocompleteCustom array to a list
        List<String> allowedAutocompleteCustomList = Arrays.asList(allowedAutocompleteCustom);
        return allowedAutocompleteCustomList.contains(normalizedAutocompleteCustom);
    }
}
