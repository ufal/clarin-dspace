/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.eperson.clarin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.dspace.AbstractIntegrationTestWithDatabase;
import org.dspace.builder.EPersonBuilder;
import org.dspace.eperson.EPerson;
import org.dspace.eperson.factory.EPersonServiceFactory;
import org.dspace.eperson.service.clarin.ClarinIdentityService;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests {@link ClarinIdentityService}: alias-only resolution, attach idempotency/conflict, and
 * the auto-link decision matrix (no match / exactly one match / ambiguous match).
 */
public class ClarinIdentityServiceTest extends AbstractIntegrationTestWithDatabase {

    private static final String ALLOWLISTED_PROXY = "login.e-infra.cz/idp";

    private ClarinIdentityService identityService;

    /**
     * Any EPerson created ad hoc by a test (in addition to the shared {@code eperson}/{@code admin}),
     * tracked here so {@link #cleanupNetidAliases()} can detach its aliases before the superclass
     * teardown deletes the builder-tracked EPerson itself.
     */
    private EPerson other;

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();
        identityService = EPersonServiceFactory.getInstance().getClarinIdentityService();

        ConfigurationService configurationService = DSpaceServicesFactory.getInstance().getConfigurationService();
        configurationService.setProperty("identity.auto-link.proxy-allowlist", ALLOWLISTED_PROXY);
    }

    /**
     * Alias rows are not builder-tracked, so an alias left attached to a builder-created EPerson
     * (e.g. {@code other}) would make the superclass's {@code destroy()} fail with a foreign-key
     * violation when it deletes that EPerson. JUnit runs subclass {@code @After} methods before
     * superclass ones, so detaching here always runs before that deletion.
     */
    @After
    public void cleanupNetidAliases() throws Exception {
        context.turnOffAuthorisationSystem();
        for (EPersonNetidAlias alias : identityService.findAliases(context, eperson)) {
            identityService.detach(context, alias);
        }
        if (other != null) {
            for (EPersonNetidAlias alias : identityService.findAliases(context, other)) {
                identityService.detach(context, alias);
            }
        }
        context.restoreAuthSystemState();
    }

    @Test
    public void resolveReturnsNullWhenNoAliasExists() throws Exception {
        assertNull(identityService.resolve(context, "nobody@example.org[https://idp.example.org]"));
    }

    @Test
    public void attachThenResolveFindsTheEPerson() throws Exception {
        String netid = "novak@cuni.cz[https://cas.cuni.cz/idp/shibboleth]";
        identityService.attach(context, eperson, netid, "migration", null);

        assertEquals(eperson, identityService.resolve(context, netid));
    }

    @Test
    public void attachIsIdempotentForTheSameEPerson() throws Exception {
        String netid = "novak@cuni.cz[https://cas.cuni.cz/idp/shibboleth]";
        EPersonNetidAlias first = identityService.attach(context, eperson, netid, "migration", null);
        EPersonNetidAlias second = identityService.attach(context, eperson, netid, "migration", null);

        assertEquals(first.getID(), second.getID());
    }

    @Test
    public void attachRefusesToStealAnAliasFromAnotherEPerson() throws Exception {
        context.turnOffAuthorisationSystem();
        other = EPersonBuilder.createEPerson(context).withEmail("other@example.org").build();
        context.restoreAuthSystemState();
        String netid = "novak@cuni.cz[https://cas.cuni.cz/idp/shibboleth]";
        identityService.attach(context, eperson, netid, "migration", null);

        assertThrows(IllegalStateException.class,
            () -> identityService.attach(context, other, netid, "admin", null));
    }

    @Test
    public void autoLinkIgnoresProxiesNotOnTheAllowlist() throws Exception {
        identityService.attach(context, eperson, "novak@cuni.cz[https://cas.cuni.cz/idp/shibboleth]",
            "migration", null);

        EPerson result = identityService.autoLink(context, Collections.singletonList("novak@cuni.cz"),
            "https://untrusted-proxy.example.org/idp", "einfra-id[https://untrusted-proxy.example.org/idp]");

        assertNull(result);
    }

    @Test
    public void autoLinkAttachesToTheSingleMatchingEPerson() throws Exception {
        identityService.attach(context, eperson, "novak@cuni.cz[https://cas.cuni.cz/idp/shibboleth]",
            "migration", null);
        String proxyNetid = "hash123[" + ALLOWLISTED_PROXY + "]";

        EPerson result = identityService.autoLink(context, Collections.singletonList("novak@cuni.cz"),
            ALLOWLISTED_PROXY, proxyNetid);

        assertEquals(eperson, result);
        assertEquals(eperson, identityService.resolve(context, proxyNetid));
    }

    @Test
    public void autoLinkRefusesToGuessWhenMultipleEPersonsMatch() throws Exception {
        context.turnOffAuthorisationSystem();
        other = EPersonBuilder.createEPerson(context).withEmail("other@example.org").build();
        context.restoreAuthSystemState();
        identityService.attach(context, eperson, "novak@cuni.cz[https://cas.cuni.cz/idp/shibboleth]",
            "migration", null);
        identityService.attach(context, other, "novak@jinauni.cz[https://jinauni.cz/idp/shibboleth]",
            "migration", null);
        String proxyNetid = "hash123[" + ALLOWLISTED_PROXY + "]";
        List<String> releasedEppns = Arrays.asList("novak@cuni.cz", "novak@jinauni.cz");

        EPerson result = identityService.autoLink(context, releasedEppns, ALLOWLISTED_PROXY, proxyNetid);

        assertNull(result);
        assertNull(identityService.resolve(context, proxyNetid));
    }

    @Test
    public void autoLinkFindsNoMatchWhenNoAliasesOverlap() throws Exception {
        String proxyNetid = "hash123[" + ALLOWLISTED_PROXY + "]";

        EPerson result = identityService.autoLink(context, Collections.singletonList("unknown@cuni.cz"),
            ALLOWLISTED_PROXY, proxyNetid);

        assertNull(result);
    }
}
