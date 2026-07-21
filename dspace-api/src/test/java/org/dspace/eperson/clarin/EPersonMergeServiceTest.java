/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.eperson.clarin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.apache.commons.lang3.StringUtils;
import org.dspace.AbstractIntegrationTestWithDatabase;
import org.dspace.authorize.ResourcePolicy;
import org.dspace.authorize.service.ResourcePolicyService;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.EPersonBuilder;
import org.dspace.builder.GroupBuilder;
import org.dspace.builder.ItemBuilder;
import org.dspace.builder.ResourcePolicyBuilder;
import org.dspace.content.Collection;
import org.dspace.content.Item;
import org.dspace.core.Constants;
import org.dspace.eperson.EPerson;
import org.dspace.eperson.Group;
import org.dspace.eperson.factory.EPersonServiceFactory;
import org.dspace.eperson.service.EPersonService;
import org.dspace.eperson.service.GroupService;
import org.dspace.eperson.service.clarin.ClarinIdentityService;
import org.dspace.eperson.service.clarin.EPersonMergeService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Covers the core FK re-pointing/dedupe/tombstone/audit mechanics of {@link EPersonMergeService}.
 * Not every table in the design's merge inventory has a dedicated test here (workflow tables,
 * subscriptions, orcid/clarin tokens, etc. follow the same re-point-or-skip-duplicate pattern
 * exercised below for item submitter, group membership and resourcepolicy).
 */
public class EPersonMergeServiceTest extends AbstractIntegrationTestWithDatabase {

    private EPersonMergeService ePersonMergeService;
    private ClarinIdentityService identityService;
    private GroupService groupService;
    private EPersonService ePersonService;

    private EPerson from;
    private EPerson to;
    private Collection collection;

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();
        ePersonMergeService = EPersonServiceFactory.getInstance().getEPersonMergeService();
        identityService = EPersonServiceFactory.getInstance().getClarinIdentityService();
        groupService = EPersonServiceFactory.getInstance().getGroupService();
        ePersonService = EPersonServiceFactory.getInstance().getEPersonService();

        context.turnOffAuthorisationSystem();
        from = EPersonBuilder.createEPerson(context).withEmail("from@example.org").build();
        to = EPersonBuilder.createEPerson(context).withEmail("to@example.org").build();

        parentCommunity = CommunityBuilder.createCommunity(context).build();
        collection = CollectionBuilder.createCollection(context, parentCommunity).build();
        context.restoreAuthSystemState();
    }

    /**
     * Alias rows are not builder-tracked, so an alias left attached to `from` or `to` (either
     * directly attached by a test, or re-pointed onto `to` by {@code merge}'s alias reassignment)
     * would make the superclass's {@code destroy()} fail with a foreign-key violation when it
     * deletes those builder-created EPersons. JUnit runs subclass {@code @After} methods before
     * superclass ones, so detaching here always runs before that deletion.
     */
    @After
    public void cleanupNetidAliases() throws Exception {
        context.turnOffAuthorisationSystem();
        for (EPersonNetidAlias alias : identityService.findAliases(context, from)) {
            identityService.detach(context, alias);
        }
        for (EPersonNetidAlias alias : identityService.findAliases(context, to)) {
            identityService.detach(context, alias);
        }
        context.restoreAuthSystemState();
    }

    @Test
    public void mergeReassignsItemSubmitter() throws Exception {
        context.turnOffAuthorisationSystem();
        context.setCurrentUser(from);
        Item item = ItemBuilder.createItem(context, collection).withTitle("Submitted by from").build();
        context.setCurrentUser(admin);
        context.restoreAuthSystemState();

        context.turnOffAuthorisationSystem();
        ePersonMergeService.merge(context, from, to, admin);
        context.restoreAuthSystemState();

        assertEquals(to, item.getSubmitter());
    }

    @Test
    public void mergeUnionsGroupMembershipAndSkipsDuplicates() throws Exception {
        context.turnOffAuthorisationSystem();
        Group onlyFromGroup = GroupBuilder.createGroup(context).withName("only-from").build();
        Group sharedGroup = GroupBuilder.createGroup(context).withName("shared").build();
        groupService.addMember(context, onlyFromGroup, from);
        groupService.addMember(context, sharedGroup, from);
        groupService.addMember(context, sharedGroup, to);
        context.restoreAuthSystemState();

        context.turnOffAuthorisationSystem();
        ePersonMergeService.merge(context, from, to, admin);
        context.restoreAuthSystemState();

        assertTrue("to should have joined from's exclusive group",
            groupService.isDirectMember(onlyFromGroup, to));
        assertFalse("from should no longer be a member of any group it held",
            groupService.isDirectMember(onlyFromGroup, from));
        assertFalse("from's membership in the shared group should be removed, not duplicated",
            groupService.isDirectMember(sharedGroup, from));
    }

    @Test
    public void mergeSkipsDuplicateResourcePolicies() throws Exception {
        context.turnOffAuthorisationSystem();
        ResourcePolicyBuilder.createResourcePolicy(context, from, null)
            .withAction(Constants.READ)
            .withDspaceObject(collection)
            .withPolicyType(ResourcePolicy.TYPE_CUSTOM)
            .build();
        ResourcePolicyBuilder.createResourcePolicy(context, to, null)
            .withAction(Constants.READ)
            .withDspaceObject(collection)
            .withPolicyType(ResourcePolicy.TYPE_CUSTOM)
            .build();
        context.restoreAuthSystemState();

        ResourcePolicyService resourcePolicyService = org.dspace.authorize.factory.AuthorizeServiceFactory
            .getInstance().getResourcePolicyService();
        int toPoliciesBefore = resourcePolicyService.findByEPerson(context, to, -1, -1).size();

        context.turnOffAuthorisationSystem();
        ePersonMergeService.merge(context, from, to, admin);
        context.restoreAuthSystemState();

        int toPoliciesAfter = resourcePolicyService.findByEPerson(context, to, -1, -1).size();
        assertEquals("the duplicate policy should have been skipped, not duplicated onto `to`",
            toPoliciesBefore, toPoliciesAfter);
    }

    @Test
    public void mergeMovesResourcePoliciesThatOnlyDifferByDates() throws Exception {
        context.turnOffAuthorisationSystem();
        java.util.Date start = new java.util.Date(0);
        java.util.Date end = new java.util.Date(1_000_000_000L);
        ResourcePolicyBuilder.createResourcePolicy(context, from, null)
            .withAction(Constants.READ)
            .withDspaceObject(collection)
            .withPolicyType(ResourcePolicy.TYPE_CUSTOM)
            .withStartDate(start)
            .withEndDate(end)
            .build();
        // `to` already has a policy for the same object/action/type but with no dates - it must
        // NOT be treated as a duplicate of the dated policy above, since the access semantics
        // differ (time-limited vs. unlimited).
        ResourcePolicyBuilder.createResourcePolicy(context, to, null)
            .withAction(Constants.READ)
            .withDspaceObject(collection)
            .withPolicyType(ResourcePolicy.TYPE_CUSTOM)
            .build();
        context.restoreAuthSystemState();

        ResourcePolicyService resourcePolicyService = org.dspace.authorize.factory.AuthorizeServiceFactory
            .getInstance().getResourcePolicyService();
        int toPoliciesBefore = resourcePolicyService.findByEPerson(context, to, -1, -1).size();

        context.turnOffAuthorisationSystem();
        ePersonMergeService.merge(context, from, to, admin);
        context.restoreAuthSystemState();

        int toPoliciesAfter = resourcePolicyService.findByEPerson(context, to, -1, -1).size();
        assertEquals("the dated policy differs in access semantics and must be moved, not skipped",
            toPoliciesBefore + 1, toPoliciesAfter);
    }

    @Test
    public void mergeMovesOnlyOneOfIdenticalDuplicatePoliciesFromSource() throws Exception {
        context.turnOffAuthorisationSystem();
        ResourcePolicyBuilder.createResourcePolicy(context, from, null)
            .withAction(Constants.READ)
            .withDspaceObject(collection)
            .withPolicyType(ResourcePolicy.TYPE_CUSTOM)
            .build();
        // A second policy on `from`, identical in every field the signature considers - `to`
        // holds none for this object/action, so both would previously have been moved onto it.
        ResourcePolicyBuilder.createResourcePolicy(context, from, null)
            .withAction(Constants.READ)
            .withDspaceObject(collection)
            .withPolicyType(ResourcePolicy.TYPE_CUSTOM)
            .build();
        context.restoreAuthSystemState();

        ResourcePolicyService resourcePolicyService = org.dspace.authorize.factory.AuthorizeServiceFactory
            .getInstance().getResourcePolicyService();

        context.turnOffAuthorisationSystem();
        ePersonMergeService.merge(context, from, to, admin);
        context.restoreAuthSystemState();

        long toPoliciesForCollection = resourcePolicyService.findByEPerson(context, to, -1, -1).stream()
            .filter(rp -> collection.equals(rp.getdSpaceObject()) && rp.getAction() == Constants.READ)
            .count();
        assertEquals("only one of the two identical `from` policies should have been moved onto `to`",
            1, toPoliciesForCollection);
    }

    @Test
    public void mergeTombstonesFromAndWritesAudit() throws Exception {
        context.turnOffAuthorisationSystem();
        identityService.attach(context, from, "novak@cuni.cz[https://cas.cuni.cz/idp/shibboleth]",
            "migration", null);
        context.restoreAuthSystemState();

        context.turnOffAuthorisationSystem();
        EPersonMergeAudit audit = ePersonMergeService.merge(context, from, to, admin);
        context.restoreAuthSystemState();

        assertFalse("tombstoned account must not be able to log in", from.canLogIn());
        assertTrue("from's email should be parked, freeing the original for reuse",
            from.getEmail().contains("from@example.org") && !from.getEmail().equals("from@example.org"));
        assertEquals(to, identityService.resolve(context,
            "novak@cuni.cz[https://cas.cuni.cz/idp/shibboleth]"));

        assertNotNull(audit);
        assertEquals(from.getID(), audit.getFromEPerson());
        assertEquals(to.getID(), audit.getToEPerson());
        assertEquals(admin.getID(), audit.getPerformedBy());
        assertTrue(audit.getDetail().contains("item.submitter"));
    }

    @Test
    public void mergeTombstonesFromWithLongEmailWithoutExceedingColumnLength() throws Exception {
        String longLocalPart = StringUtils.repeat('a', 80);
        String longEmail = longLocalPart + "@example.org";
        context.turnOffAuthorisationSystem();
        from.setEmail(longEmail);
        ePersonService.update(context, from);
        context.restoreAuthSystemState();

        context.turnOffAuthorisationSystem();
        ePersonMergeService.merge(context, from, to, admin);
        context.restoreAuthSystemState();

        assertTrue("parked email must fit in the eperson.email column (64 chars)",
            from.getEmail().length() <= 64);
        assertFalse("from's email must actually have changed away from the original (too long) address",
            from.getEmail().equals(longEmail));
    }
}
