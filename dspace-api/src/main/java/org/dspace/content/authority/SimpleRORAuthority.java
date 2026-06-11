/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.content.authority;

import org.dspace.external.RorRestConnector;
import org.dspace.utils.DSpace;

/**
 * ChoiceAuthority using the ROR API.
 *
 * @author Milan Kuchtiak
 */
public class SimpleRORAuthority implements ChoiceAuthority {

    private String pluginInstanceName;

    private final RorRestConnector rorRestConnector = new DSpace().getServiceManager().getServiceByName(
            "RorRestConnector", RorRestConnector.class);

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
        return rorRestConnector.getMatches(text, start, limit, locale);
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
        return rorRestConnector.getBestMatch(text, locale);
    }

    @Override
    public Choice getChoice(String authKey, String locale) {
        return rorRestConnector.getChoice(authKey, locale);
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
        return rorRestConnector.getLabel(key, locale);
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

}
