/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.authenticate.clarin;

import static org.dspace.authenticate.clarin.ClarinOidcAuthenticationBean.matchesClaimValue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * How the VALUE of an authentication-oidc.claim.&lt;claim&gt; = VALUE =&gt; GROUP rule matches a claim value.
 */
public class ClarinOidcClaimValueMatchTest {

    private static final String EINFRA = "urn:geant:cesnet.cz:group:einfra";
    private static final String PERUN = "#Perun Identity and Management System - Maintenance";

    @Test
    public void testExactMatch() {
        assertTrue(matchesClaimValue("staff", "staff"));
        assertTrue(matchesClaimValue(" staff ", "staff"));
        assertFalse(matchesClaimValue("staff", "Staff"));
        assertFalse(matchesClaimValue("staff", "staff@cuni.cz"));
        assertFalse(matchesClaimValue("staff@cuni.cz", "staff"));
    }

    @Test
    public void testRoleWildcardMatchesAnyScope() {
        assertTrue(matchesClaimValue("member@*", "member@cuni.cz"));
        assertTrue(matchesClaimValue("member@*", "member@mff.cuni.cz"));
        assertFalse(matchesClaimValue("member@*", "staff@cuni.cz"));
        assertFalse(matchesClaimValue("member@*", "member"));
        assertFalse(matchesClaimValue("member@*", "@cuni.cz"));
    }

    @Test
    public void testScopeWildcardMatchesAnyRole() {
        assertTrue(matchesClaimValue("*@cuni.cz", "member@cuni.cz"));
        assertTrue(matchesClaimValue("*@cuni.cz", "staff@cuni.cz"));
        // the whole scope, not a suffix of it
        assertFalse(matchesClaimValue("*@cuni.cz", "member@mff.cuni.cz"));
        assertFalse(matchesClaimValue("*@cuni.cz", "member@cuni.cz.evil.org"));
        assertFalse(matchesClaimValue("*@cuni.cz", "cuni.cz"));
    }

    @Test
    public void testOnlyWholeRoleOrScopeWildcards() {
        assertFalse(matchesClaimValue("*@*", "member@cuni.cz"));
        assertFalse(matchesClaimValue("*", "member@cuni.cz"));
        assertFalse(matchesClaimValue("mem*@cuni.cz", "member@cuni.cz"));
        assertFalse(matchesClaimValue("*@*.cuni.cz", "member@mff.cuni.cz"));
        assertTrue(matchesClaimValue("*", "*"));
    }

    @Test
    public void testAuthorityOfEntitlementUrnIgnored() {
        assertTrue(matchesClaimValue(EINFRA, EINFRA + PERUN));
        assertTrue(matchesClaimValue(EINFRA + "#another authority", EINFRA + PERUN));
        assertTrue(matchesClaimValue(EINFRA + PERUN, EINFRA));
        // subgroups and roles are different values
        assertFalse(matchesClaimValue(EINFRA, EINFRA + ":members" + PERUN));
        assertFalse(matchesClaimValue(EINFRA, EINFRA + ":role=manager" + PERUN));
    }

    @Test
    public void testHashKeptInOtherValues() {
        assertFalse(matchesClaimValue("team", "team#1"));
        assertTrue(matchesClaimValue("team#1", "team#1"));
    }
}
