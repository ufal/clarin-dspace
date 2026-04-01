/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.content.authority;

import java.io.InputStream;
import java.util.List;
import java.util.stream.Collectors;
import javax.ws.rs.core.Response;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.dspace.external.RorRestConnector;
import org.dspace.external.model.ror.Location;
import org.dspace.external.model.ror.RorItem;
import org.dspace.external.model.ror.RorItems;
import org.dspace.utils.DSpace;

/**
 * ChoiceAuthority using the ROR API.
 */
public class SimpleRORAuthority implements ChoiceAuthority {

    private static final Logger log = LogManager.getLogger(SimpleRORAuthority.class);
    private static final String ROR_ID_PATTERN = "^0[a-z|0-9]{6}[0-9]{2}$";

    private String pluginInstanceName;

    private final RorRestConnector rorRestConnector = new DSpace().getServiceManager().getServiceByName(
            "RorRestConnector", RorRestConnector.class);

    // this is the number of items returned by the ROR API in each page
    private static final int ROR_ITEMS_COUNT = 20;
    // maximum number of pages that can be returned by the ROR API is 500
    private static final int ROR_MAX_PAGES = 500;

    /**
     * Get all values from the authority that match the preferred value.
     * Note that the offering was entered by the user and may contain
     * mixed/incorrect case, whitespace, etc so the plugin should be careful
     * to clean up user data before making comparisons.
     * <p>
     * Value of a "Name" field will be in canonical DSpace person name format,
     * which is "Lastname, Firstname(s)", e.g. "Smith, John Q.".
     * <p>
     * Some authorities with a small set of values may simply return the whole
     * set for any sample value, although it's a good idea to set the
     * defaultSelected index in the Choices instance to the choice, if any,
     * that matches the value.
     *
     * @param text   user's value to match
     * @param start  choice at which to start, 0 is first.
     * @param limit  maximum number of choices to return, 0 for no limit.
     * @param locale explicit localization key if available, or null
     * @return a Choices object (never null).
     */
    @Override
    public Choices getMatches(String text, int start, int limit, String locale) {

        if (text == null || text.trim().isEmpty()) {
            return new Choices(true);
        }

        // allow only limits that are a divisor of ROR_RESULTS_COUNT(20),
        // to avoid pagination complication in the UI
        if (limit <= 0) {
            limit = ROR_ITEMS_COUNT;
        } else if (limit > ROR_ITEMS_COUNT || ROR_ITEMS_COUNT % limit != 0) {
            throw new IllegalArgumentException("The page size limit must be a divisor of " + ROR_ITEMS_COUNT);
        }

        // calculate the offset (page parameter) to use in the ROR API call
        int offset = start / ROR_ITEMS_COUNT;

        // if the offset is too high, it means the user is trying to access a page that doesn't exist,
        // so we return an empty result instead of making an API call
        if (offset + 1 > ROR_MAX_PAGES) {
            throw new IllegalArgumentException("Exceeded maximal page number for the ROR API, which is " +
                    (ROR_MAX_PAGES * (ROR_ITEMS_COUNT / limit) - 1) + " for page size limit: " + limit);
        }

        try (Response response = rorRestConnector.getByQuery(text, offset + 1)) {
            if (response.getStatus() == Response.Status.OK.getStatusCode()) {
                try (InputStream is = response.readEntity(InputStream.class)) {
                    RorItems rorItems = new ObjectMapper().readValue(is, RorItems.class);
                    int total = rorItems.getNoOfResults();
                    List<RorItem> items = rorItems.getItems();
                    if (items.isEmpty()) {
                        return new Choices(new Choice[0], start, total, Choices.CF_NOTFOUND, false);
                    }
                    List<Choice> choices = items.stream()
                            .map(item -> toChoice(item, locale))
                            .collect(Collectors.toList());

                    // select sublist of results to return based on the start and limit parameters
                    int startIndex = 0;
                    if (limit != ROR_ITEMS_COUNT) {
                        startIndex = start % ROR_ITEMS_COUNT;
                        if (startIndex > choices.size()) {
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

    /**
     * Get the single "best" match (if any) of a value in the authority
     * to the given user value.  The "confidence" element of Choices is
     * expected to be set to a meaningful value about the circumstances of
     * this match.
     * <p>
     * This call is typically used in non-interactive metadata ingest
     * where there is no interactive agent to choose from among options.
     *
     * @param text   user's value to match
     * @param locale explicit localization key if available, or null
     * @return a Choices object (never null) with 1 or 0 values.
     */
    @Override
    public Choices getBestMatch(String text, String locale) {
        if (text.matches(ROR_ID_PATTERN)) {
            Choice choice = getChoice(text, locale);
            if (choice != null) {
                return new Choices(new Choice[]{choice}, 0, 1, Choices.CF_ACCEPTED, false);
            } else {
                return new Choices(false);
            }
        } else {
            try (Response response = rorRestConnector.getByQuery(sanitizeQuery(text))) {
                if (response.getStatus() == Response.Status.OK.getStatusCode()) {
                    try (InputStream is = response.readEntity(InputStream.class)) {
                        RorItems rorItems = new ObjectMapper().readValue(is, RorItems.class);
                        List<RorItem> items = rorItems.getItems();
                        if (items.isEmpty()) {
                            return new Choices(false);
                        }
                        Choice[] choices = {toChoice(items.get(0), locale)};
                        return new Choices(choices, 0, 1, Choices.CF_UNCERTAIN, false);
                    } catch (Exception e) {
                        log.error("Error during search", e);
                    }
                }
            }
        }

        return new Choices(true);
    }

    @Override
    public Choice getChoice(String authKey, String locale) {
        try (Response response = rorRestConnector.getByID(authKey)) {
            if (response.getStatus() == Response.Status.OK.getStatusCode()) {
                try (InputStream is = response.readEntity(InputStream.class)) {
                    RorItem rorItem = new ObjectMapper().readValue(is, RorItem.class);
                    return toChoice(rorItem, locale);
                } catch (Exception e) {
                    log.error("Error during search", e);
                }
            }
        }
        return null;
    }

    /**
     * Get the canonical user-visible "label" (i.e. short descriptive text)
     * for a key in the authority.  Can be localized given the implicit
     * or explicit locale specification.
     * <p>
     * This may get called many times while populating a Web page so it should
     * be implemented as efficiently as possible.
     *
     * @param key    authority key known to this authority.
     * @param locale explicit localization key if available, or null
     * @return descriptive label - should always return something, never null.
     */
    @Override
    public String getLabel(String key, String locale) {
        return key;
    }

    /**
     * Get the instance's particular name.
     * Returns the name by which the class was chosen when
     * this instance was created.  Only works for instances created
     * by <code>PluginService</code>, or if someone remembers to call <code>setPluginName.</code>
     * <p>
     * Useful when the implementation class wants to be configured differently
     * when it is invoked under different names.
     *
     * @return name or null if not available.
     */
    @Override
    public String getPluginInstanceName() {
        return pluginInstanceName;
    }

    /**
     * Set the name under which this plugin was instantiated.
     * Not to be invoked by application code, it is
     * called automatically by <code>PluginService.getNamedPlugin()</code>
     * when the plugin is instantiated.
     *
     * @param name -- name used to select this class.
     */
    @Override
    public void setPluginInstanceName(String name) {
        this.pluginInstanceName = name;
    }

    private String sanitizeQuery(String query) {
        if (query.startsWith("\"") && query.endsWith("\"")) {
            return query;
        } else {
            return "\"" + query + "\"";
        }
    }

    private Choice toChoice(RorItem rorItem, String locale) {
        String loc = (locale == null) ? "en" : locale;
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
            String value = null;
            StringBuilder aliases = new StringBuilder();
            int labelQuality = 0; // 1- any label, 2- english label, 3- label in the same language as the locale
            for (RorItem.Name name : names) {
                if (value == null && name.getTypes().contains("ror_display")) {
                    value = name.getValue();
                }
                if (labelQuality < 3 && name.getTypes().contains("label")) {
                    if (loc.equals(name.getLang())) {
                        labelQuality = 3;
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
                    if (aliases.length() > 0) {
                        aliases.append(" | ");
                    }
                    aliases.append(name.getValue());
                }
            }

            if (label == null) {
                label = names.get(0).getValue();
            }
            // set label and value to the same value,
            // as the ROR API doesn't provide a specific value for the institution, but only the label
            if (value == null) {
                value = label;
            }
            c.label = label;
            c.value = value;

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

}
