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

import java.util.Locale;

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
import org.junit.Before;
import org.junit.Test;

/**
 * Tests {@link HandlePlugin#getRawHandleValues}, the entry point the Handle server calls to resolve a handle.
 * With `case_sensitive = "no"` in config.dct the Handle server uppercases the handle before calling the plugin.
 */
public class HandlePluginIT extends AbstractIntegrationTestWithDatabase {

    private static final String MIXED_CASE_HANDLE = "123456789/Evaldio-database-M9FH";
    private static final String EXTERNAL_URL = "https://example.org/evaldio";

    private final ConfigurationService configurationService =
            DSpaceServicesFactory.getInstance().getConfigurationService();
    private final HandlePlugin handlePlugin = new HandlePlugin();

    private Item item;

    @Before
    public void setup() throws Exception {
        context.turnOffAuthorisationSystem();
        parentCommunity = CommunityBuilder.createCommunity(context).withName("Parent Community").build();
        Collection collection = CollectionBuilder.createCollection(context, parentCommunity).build();
        item = ItemBuilder.createItem(context, collection).withTitle("Item").build();
        HandleBuilder.createExternalHandle(context, MIXED_CASE_HANDLE, EXTERNAL_URL).build();
        context.commit();
        context.restoreAuthSystemState();
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
        String expectedUrl = configurationService.getProperty("dspace.ui.url") + "/handle/" + item.getHandle();
        assertEquals(expectedUrl, resolveUrl(item.getHandle()));
    }

    @Test
    public void unknownHandleIsNotResolved() throws Exception {
        assertNull(handlePlugin.getRawHandleValues(Util.encodeString("123456789/DOES-NOT-EXIST"), null, null));
    }

    /**
     * Call the plugin the way the Handle server does and return the value of the URL handle value.
     */
    private String resolveUrl(String handle) throws Exception {
        byte[][] rawValues = handlePlugin.getRawHandleValues(Util.encodeString(handle), null, null);
        assertNotNull("Handle " + handle + " was not resolved", rawValues);
        for (byte[] rawValue : rawValues) {
            HandleValue value = new HandleValue();
            Encoder.decodeHandleValue(rawValue, 0, value);
            if (value.hasType(Util.encodeString("URL"))) {
                return value.getDataAsString();
            }
        }
        return null;
    }
}
