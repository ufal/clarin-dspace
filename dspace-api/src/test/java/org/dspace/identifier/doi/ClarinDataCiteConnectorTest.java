/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.identifier.doi;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Tests for the ClarinDataCiteConnector class.
 */
public class ClarinDataCiteConnectorTest {

    @Test
    public void testDoiPrefixIsTakenFromDoi() {
        ClarinDataCiteConnector connector = new ClarinDataCiteConnector();
        assertEquals("10.83111", connector.getDoiPrefix("doi:10.83111/clarin.1.abc"));
        assertEquals("10.83222", connector.getDoiPrefix("10.83222/clarin.2.xyz"));
    }

}
