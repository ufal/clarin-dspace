/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.dspace.AbstractUnitTest;
import org.junit.Test;

/**
 * Unit tests for {@link Utils}
 *
 * @author Milan Majchrak (dspace at dataquest.sk)
 */
public class UtilsTest extends AbstractUnitTest {

    @Test
    public void testNormalizeDiscoverQueryWithMixedCharactersAndNumbers() {
        String searchValue = "my1Search2";
        String searchField = "dc.contributor.author";

        String expected = "dc.contributor.author:*my* AND dc.contributor.author:*Search* AND " +
                "dc.contributor.author:*1* AND dc.contributor.author:*2*";

        String result = Utils.normalizeDiscoverQuery(searchValue, searchField);
        assertEquals(expected, result);
    }

    @Test
    public void testNormalizeDiscoverQueryWithOnlyCharacters() {
        String searchValue = "mySearch";
        String searchField = "dc.contributor.author";

        String result = Utils.normalizeDiscoverQuery(searchValue, searchField);
        assertNull(result);
    }

    @Test
    public void testNormalizeDiscoverQueryWithOnlyNumbers() {
        String searchValue = "12345";
        String searchField = "dc.contributor.author";

        String result = Utils.normalizeDiscoverQuery(searchValue, searchField);
        assertNull(result);
    }

    @Test
    public void testNormalizeDiscoverQueryWithEmptyString() {
        String searchValue = "";
        String searchField = "dc.contributor.author";

        String result = Utils.normalizeDiscoverQuery(searchValue, searchField);
        assertNull(result);
    }
}
