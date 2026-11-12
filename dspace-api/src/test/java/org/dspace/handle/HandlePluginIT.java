/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.handle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import net.handle.hdllib.Encoder;
import net.handle.hdllib.HandleValue;
import net.handle.hdllib.Util;
import org.dspace.AbstractIntegrationTestWithDatabase;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.HandleBuilder;
import org.dspace.builder.ItemBuilder;
import org.dspace.content.Collection;
import org.dspace.content.Item;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests {@link HandlePlugin#getRawHandleValues}, the entry point the Handle server calls to resolve a handle.
 * With `case_sensitive = "no"` in config.dct the Handle server uppercases the handle before calling the plugin.
 */
public class HandlePluginIT extends AbstractIntegrationTestWithDatabase {

    private static final String MIXED_CASE_HANDLE = "123456789/Evaldio-database-M9FH";
    private static final String EXTERNAL_URL = "https://example.org/evaldio";
    private static final String MIXED_CASE_ITEM_HANDLE = "123456789/Mixed-Case-Item";
    private static final String PID_CONFIG_KEY = "lr.pid.community.configurations";
    private static final String ALTERNATIVE_PREFIX = "11234";
    private static final String ALTERNATIVE_PREFIX_SUFFIX = "Old-shortref-AB12";
    private static final String ALTERNATIVE_PREFIX_URL = "https://example.org/old";

    private final ConfigurationService configurationService =
            DSpaceServicesFactory.getInstance().getConfigurationService();
    private final HandlePlugin handlePlugin = new HandlePlugin();

    private Item item;
    private String uiUrl;
    private String[] originalPidConfig;

    @Before
    public void setup() throws Exception {
        context.turnOffAuthorisationSystem();
        parentCommunity = CommunityBuilder.createCommunity(context).withName("Parent Community").build();
        Collection collection = CollectionBuilder.createCollection(context, parentCommunity).build();
        item = ItemBuilder.createItem(context, collection).withTitle("Item").build();
        ItemBuilder.createItem(context, collection).withTitle("Mixed case item")
                .withHandle(MIXED_CASE_ITEM_HANDLE).build();
        HandleBuilder.createExternalHandle(context, MIXED_CASE_HANDLE, EXTERNAL_URL).build();
        Handle deadHandle = HandleBuilder.createExternalHandle(context,
                ALTERNATIVE_PREFIX + "/" + ALTERNATIVE_PREFIX_SUFFIX, ALTERNATIVE_PREFIX_URL).build();
        deadHandle.setDead(true);
        deadHandle.setDeadSince(new Date());
        context.commit();
        context.restoreAuthSystemState();
        uiUrl = configurationService.getProperty("dspace.ui.url");

        // the main prefix 123456789 also answers for handles stored under the alternative prefix 11234
        originalPidConfig = configurationService.getArrayProperty(PID_CONFIG_KEY);
        configurationService.setProperty(PID_CONFIG_KEY, new String[] {"community=*", "prefix=123456789",
            "type=local", "canonical_prefix=http://hdl.handle.net/", "subprefix=2",
            "alternative_prefixes=" + ALTERNATIVE_PREFIX});
        PIDConfiguration.getInstance().reloadPidCommunityConfigurations();
    }

    @After
    public void restorePidConfig() {
        configurationService.setProperty(PID_CONFIG_KEY, originalPidConfig);
        PIDConfiguration.getInstance().reloadPidCommunityConfigurations();
    }

    @Test
    public void resolvesMixedCaseHandleRequestedInUpperCase() throws Exception {
        assertEquals(EXTERNAL_URL, resolveUrl(MIXED_CASE_HANDLE.toUpperCase(Locale.ROOT)));
    }

    @Test
    public void resolvesMixedCaseHandleRequestedInLowerCase() throws Exception {
        assertEquals(EXTERNAL_URL, resolveUrl(MIXED_CASE_HANDLE.toLowerCase(Locale.ROOT)));
    }

    @Test
    public void resolvesMixedCaseHandleRequestedAsStored() throws Exception {
        assertEquals(EXTERNAL_URL, resolveUrl(MIXED_CASE_HANDLE));
    }

    @Test
    public void resolvesItemHandle() throws Exception {
        assertEquals(uiUrl + "/handle/" + item.getHandle(), resolveUrl(item.getHandle()));
    }

    /**
     * The UI's /handle/ route is case-sensitive, so the URL of an internal handle must use the stored spelling,
     * not the case of the request.
     */
    @Test
    public void internalHandleUrlKeepsStoredCase() throws Exception {
        String expectedUrl = uiUrl + "/handle/" + MIXED_CASE_ITEM_HANDLE;
        assertEquals(expectedUrl, resolveUrl(MIXED_CASE_ITEM_HANDLE.toUpperCase(Locale.ROOT)));
        assertEquals(expectedUrl, resolveUrl(MIXED_CASE_ITEM_HANDLE.toLowerCase(Locale.ROOT)));
    }

    /**
     * A dead handle stored under an alternative prefix, requested through the main prefix: the dead-handle
     * values (isDead/getDeadSince) must be looked up under the handle as stored, not as requested.
     */
    @Test
    public void resolvesDeadHandleViaAlternativePrefix() throws Exception {
        String storedHandle = ALTERNATIVE_PREFIX + "/" + ALTERNATIVE_PREFIX_SUFFIX;
        String requested = "123456789/" + ALTERNATIVE_PREFIX_SUFFIX;
        for (String handle : new String[] {requested, requested.toUpperCase(Locale.ROOT)}) {
            Map<String, String> values = resolve(handle);
            assertEquals(ALTERNATIVE_PREFIX_URL, values.get("ORIG_URL"));
            assertTrue(values.get("URL"), values.get("URL").endsWith("#hdl=" + storedHandle));
            assertNotNull(values.get("DEAD_SINCE"));
        }
    }

    @Test
    public void unknownHandleIsNotResolved() throws Exception {
        assertNull(handlePlugin.getRawHandleValues(Util.encodeString("123456789/DOES-NOT-EXIST"), null, null));
    }

    /**
     * Resolve the handle and return the data of its URL handle value.
     */
    private String resolveUrl(String handle) throws Exception {
        return resolve(handle).get("URL");
    }

    /**
     * Call the plugin the way the Handle server does and return the handle values as a type -> data map.
     */
    private Map<String, String> resolve(String handle) throws Exception {
        byte[][] rawValues = handlePlugin.getRawHandleValues(Util.encodeString(handle), null, null);
        assertNotNull("Handle " + handle + " was not resolved", rawValues);
        Map<String, String> values = new HashMap<>();
        for (byte[] rawValue : rawValues) {
            HandleValue value = new HandleValue();
            Encoder.decodeHandleValue(rawValue, 0, value);
            values.put(value.getTypeAsString(), value.getDataAsString());
        }
        return values;
    }
}
