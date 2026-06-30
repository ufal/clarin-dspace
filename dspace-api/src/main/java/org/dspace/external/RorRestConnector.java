/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.external;

import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.core.Response;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.LocaleUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.dspace.content.authority.Choice;
import org.dspace.content.authority.Choices;
import org.dspace.external.model.ror.Location;
import org.dspace.external.model.ror.RorItem;
import org.dspace.external.model.ror.RorItems;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.springframework.cache.annotation.Cacheable;

/**
 * REST connector for ROR API. It is used by RORAuthority to retrieve data from ROR API.
 *
 * @author Milan Kuchtiak
 */
public class RorRestConnector {

    private static final Logger log = LogManager.getLogger(RorRestConnector.class);

    static final String ROR_ID_PATTERN = "^0[a-z0-9]{6}[0-9]{2}$";

    // this is the number of items returned by the ROR API in each page
    private static final int ROR_ITEMS_COUNT = 20;
    // maximum number of pages that can be returned by the ROR API is 500
    private static final int ROR_MAX_PAGES = 500;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final Client client = ClientBuilder.newClient();

    private String apiUrl;
    private String clientId;

    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public Response getByQuery(String query) {
        return getByQuery(query, 1);
    }

    public Response getByQuery(String query, int page) {
        return client.target(apiUrl)
                .queryParam("query", query)
                .queryParam("page", page)
                .request()
                .header("Client-Id", clientId)
                .accept("application/json")
                .get();
    }

    public Response getByID(String rorID) {
        if (rorID.matches(ROR_ID_PATTERN)) {
            return client.target(apiUrl).path(rorID)
                    .request()
                    .header("Client-Id", clientId)
                    .accept("application/json")
                    .get();
        } else {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
    }

    @Cacheable(cacheNames = "ror-labels", key = "#rorID + '_' + #locale", unless = "#result == null")
    public String getLabel(String rorID, String locale) {
        Choice choice = getChoice(rorID, locale);
        return choice != null ? choice.label : rorID;
    }

    public Choices getMatches(String text, int start, int limit, String locale) {
        if (text == null || text.trim().isEmpty()) {
            return new Choices(true);
        }

        // allow only limits that are a divisor of ROR_RESULTS_COUNT(20),
        // to avoid pagination complication in the UI
        if (limit <= 0) {
            limit = ROR_ITEMS_COUNT;
        } else if (limit > ROR_ITEMS_COUNT || ROR_ITEMS_COUNT % limit != 0) {
            throw new IllegalArgumentException("The page size must be a divisor of " + ROR_ITEMS_COUNT + ".");
        }

        // calculate the offset (page parameter) to use in the ROR API call
        int offset = start / ROR_ITEMS_COUNT;

        // if the offset is too high, it means the user is trying to access a page that doesn't exist,
        // so we return an empty result instead of making an API call
        if (offset + 1 > ROR_MAX_PAGES) {
            throw new IllegalArgumentException("Exceeded maximal page number for the ROR API, which is " +
                    (ROR_MAX_PAGES * (ROR_ITEMS_COUNT / limit) - 1) + ", for page size " + limit + ".");
        }

        try (Response response = getByQuery(text, offset + 1)) {
            if (response.getStatus() == Response.Status.OK.getStatusCode()) {
                try (InputStream is = response.readEntity(InputStream.class)) {
                    RorItems rorItems = OBJECT_MAPPER.readValue(is, RorItems.class);
                    int total = rorItems.getNoOfResults();
                    List<RorItem> items = rorItems.getItems();
                    if (items.isEmpty()) {
                        return new Choices(new Choice[0], start, total, Choices.CF_NOTFOUND, false);
                    }

                    String localeLanguage = getLocaleLanguage(locale);

                    StoredNameType storedNameType = resolveStoredNameType();
                    List<Choice> choices = items.stream()
                            .map(item -> toChoice(item, localeLanguage, storedNameType))
                            .collect(Collectors.toList());

                    // select sublist of results to return based on the start and limit parameters
                    int startIndex = 0;
                    if (limit != ROR_ITEMS_COUNT) {
                        startIndex = start % ROR_ITEMS_COUNT;
                        if (startIndex >= choices.size()) {
                            // the start index is greater than the choices size
                            // so we cannot select a sublist of results
                            return new Choices(new Choice[0], start, total, Choices.CF_NOTFOUND, false);
                        }
                        int endIndex = Math.min(startIndex + limit, choices.size());
                        choices = choices.subList(startIndex, endIndex);
                    }

                    int confidence = choices.isEmpty() ? Choices.CF_NOTFOUND :
                            choices.size() == 1 ? Choices.CF_UNCERTAIN : Choices.CF_AMBIGUOUS;

                    return new Choices(choices.toArray(Choice[]::new), start, total,
                            confidence, total > (offset * ROR_ITEMS_COUNT + startIndex + choices.size()));
                } catch (Exception e) {
                    log.error("Error during search", e);
                }
            }
        }
        return new Choices(true);
    }

    public Choices getBestMatch(String text, String locale) {
        try (Response response = getByQuery(sanitizeQuery(text))) {
            if (response.getStatus() == Response.Status.OK.getStatusCode()) {
                try (InputStream is = response.readEntity(InputStream.class)) {
                    RorItems rorItems = OBJECT_MAPPER.readValue(is, RorItems.class);
                    List<RorItem> items = rorItems.getItems();
                    if (items.isEmpty()) {
                        return new Choices(false);
                    }
                    Choice[] choices = {toChoice(items.get(0), getLocaleLanguage(locale), resolveStoredNameType())};
                    return new Choices(choices, 0, 1, Choices.CF_UNCERTAIN, false);
                } catch (Exception e) {
                    log.error("Error during search", e);
                }
            }
        }

        return new Choices(true);
    }

    public Choice getChoice(String authKey, String locale) {
        try (Response response = getByID(authKey)) {
            if (response.getStatus() == Response.Status.OK.getStatusCode()) {
                try (InputStream is = response.readEntity(InputStream.class)) {
                    RorItem rorItem = OBJECT_MAPPER.readValue(is, RorItem.class);
                    return RorRestConnector.toChoice(rorItem, getLocaleLanguage(locale), resolveStoredNameType());
                } catch (Exception e) {
                    log.error("Error during search", e);
                }
            }
        }
        return null;
    }

    private static String getLocaleLanguage(String locale) {
        try {
            return Optional.ofNullable(LocaleUtils.toLocale(locale)).map(Locale::getLanguage).orElse("en");
        } catch (IllegalArgumentException e) {
            log.warn("Invalid locale format: " + locale + ", using default 'en' locale.");
            return "en";
        }
    }

    private static Choice toChoice(RorItem rorItem, String localeLanguage, StoredNameType storedNameType) {
        String authority = rorItem.getId();
        int slashIndex = authority.lastIndexOf("/");
        if (slashIndex != -1) {
            authority = authority.substring(slashIndex + 1);
        }

        Choice c = new Choice();
        c.authority = authority;

        List<RorItem.Name> names = rorItem.getNames();
        if (!names.isEmpty()) {
            String label = null;
            String rorDisplay = null;
            String enLabel = null;
            StringBuilder aliases = new StringBuilder();
            // the label quality is the following:
            // 4 - locale label from labels, 3 - locale label from aliases, 2 - english label, 1 - any other label
            int labelQuality = 0;
            // the enLabelQuality is the following:
            // 2 - english label from labels, 1 - english label from aliasses
            int enLabelQuality = 0;

            for (RorItem.Name name : names) {
                if (rorDisplay == null && name.getTypes().contains("ror_display")) {
                    rorDisplay = name.getValue();
                }
                if (name.getTypes().contains("label")) {
                    if (enLabelQuality < 2 && "en".equals(name.getLang())) {
                        enLabelQuality = 2;
                        enLabel = name.getValue();
                    }
                    if (labelQuality < 4 && localeLanguage.equals(name.getLang())) {
                        labelQuality = 4;
                        label = name.getValue();
                    } else if (labelQuality < 2 && "en".equals(name.getLang())) {
                        labelQuality = 2;
                        label = name.getValue();
                    } else if (labelQuality < 1) {
                        labelQuality = 1;
                        label = name.getValue();
                    }
                }

                if (name.getTypes().contains("alias")) {
                    String lang = name.getLang();
                    if (enLabelQuality < 1 && "en".equals(lang)) {
                        enLabelQuality = 1;
                        enLabel = name.getValue();
                    }
                    if (labelQuality < 3 && localeLanguage.equals(lang)) {
                        labelQuality = 3;
                        label = name.getValue();
                    }
                    if (aliases.length() > 0) {
                        aliases.append(", ");
                    }
                    aliases.append(name.getValue());
                    if (lang != null) {
                        aliases.append(" (").append(lang).append(")");
                    }
                }
            }

            // fallback for label value if there is no label with type "label" in the ROR response
            if (label == null) {
                label = (rorDisplay != null) ? rorDisplay : names.get(0).getValue();
            }

            String value;
            // set tha value based on the configuration of the name selection type
            switch (storedNameType) {
                case ROR_DISPLAY : {
                    value = (rorDisplay != null) ? rorDisplay : label;
                    break;
                }
                case LOCALE_LABEL : {
                    value = label;
                    break;
                }
                default : {
                    value = enLabel != null ? enLabel : label;
                }
            }

            c.label = label;
            c.value = value;

            c.extras.put("ror-id", authority);

            // set other-name, if exists, to show it in the UI as additional information about the institution
            if (aliases.length() > 0) {
                c.extras.put("other-names", aliases.toString());
            }

            if (!rorItem.getLocations().isEmpty()) {
                Location location = rorItem.getLocations().get(0);
                Location.GeonamesDetails geonamesDetails = location.getGeonamesDetails();
                if (geonamesDetails != null) {
                    c.extras.put("location", geonamesDetails.getName() + ", " +
                            geonamesDetails.getCountrySubdivisionName() + ", " +
                            geonamesDetails.getCountryName() + ", " +
                            geonamesDetails.getContinentName());
                }
            }

        }
        return c;
    }

    private static StoredNameType resolveStoredNameType() {
        ConfigurationService configurationService = DSpaceServicesFactory.getInstance().getConfigurationService();
        return StoredNameType.fromString(
                configurationService.getProperty("ror.authority.stored-name-type", "en_label"));
    }

    private static String sanitizeQuery(String query) {
        if (query.startsWith("\"") && query.endsWith("\"")) {
            return query;
        } else {
            return "\"" + query + "\"";
        }
    }

    /**
     * The type of the name that will be stored in the metadata,
     * based on the configuration property "ror.authority.stored-name-type".
     */
    private enum StoredNameType {
        ROR_DISPLAY,
        EN_LABEL,
        LOCALE_LABEL;

        static StoredNameType fromString(String text) {
            try {
                return StoredNameType.valueOf(text.toUpperCase());
            } catch (IllegalArgumentException e) {
                return EN_LABEL;
            }
        }
    }

}
