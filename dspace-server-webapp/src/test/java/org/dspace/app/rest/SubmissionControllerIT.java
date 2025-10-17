/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest;

import static com.jayway.jsonpath.JsonPath.read;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javax.ws.rs.core.MediaType;

import org.dspace.app.rest.test.AbstractControllerIntegrationTest;
import org.dspace.authorize.ResourcePolicy;
import org.dspace.authorize.service.AuthorizeService;
import org.dspace.builder.BitstreamBuilder;
import org.dspace.builder.BundleBuilder;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.EPersonBuilder;
import org.dspace.builder.GroupBuilder;
import org.dspace.builder.ResourcePolicyBuilder;
import org.dspace.builder.WorkspaceItemBuilder;
import org.dspace.content.Bitstream;
import org.dspace.content.Bundle;
import org.dspace.content.Collection;
import org.dspace.content.DSpaceObject;
import org.dspace.content.Item;
import org.dspace.content.WorkspaceItem;
import org.dspace.content.service.WorkspaceItemService;
import org.dspace.core.Constants;
import org.dspace.eperson.EPerson;
import org.dspace.eperson.Group;
import org.dspace.eperson.service.EPersonService;
import org.dspace.eperson.service.GroupService;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Integration test for the SubmissionController
 *
 * @author Milan Majchrak (dspace at dataquest.sk)
 */
public class SubmissionControllerIT extends AbstractControllerIntegrationTest {

    private static final String SUBMITTER_EMAIL = "submitter@example.com";
    @Autowired
    private WorkspaceItemService workspaceItemService;
    @Autowired
    private EPersonService ePersonService;
    @Autowired
    private GroupService groupService;
    @Autowired
    private AuthorizeService authorizeService;

    WorkspaceItem wsi;

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();
        context.turnOffAuthorisationSystem();
        //** GIVEN **
        //1. A community with one collection.
        parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Parent Community")
                .build();

        //2. create a normal user to use as submitter
        EPerson submitter = EPersonBuilder.createEPerson(context)
                .withEmail(SUBMITTER_EMAIL)
                .withPassword("dspace")
                .build();

        // Submitter group - allow deposit a new item without workflow
        Collection col = CollectionBuilder.createCollection(context, parentCommunity)
                .withName("Collection")
                .withSubmitterGroup(submitter)
                .build();

        wsi = WorkspaceItemBuilder.createWorkspaceItem(context, col)
                .withTitle("Item with custom handle")
                .withIssueDate("2017-10-17")
                .withSubmitter(submitter)
                .build();
        context.restoreAuthSystemState();
    }

    @Test
    public void generateShareTokenAndSetOwnerTest() throws Exception {
        AtomicReference<String> shareLink = new AtomicReference<>();
        EPerson currentUser = context.getCurrentUser();

        String adminToken = getAuthToken(admin.getEmail(), password);
        getClient(adminToken).perform(get("/api/submission/share")
                        .param("workspaceitemid", wsi.getID().toString())
                        .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shareLink", is(notNullValue())))
                .andDo(result -> shareLink.set(read(result.getResponse().getContentAsString(), "$.shareLink")));

        // Check that the share token was set on the WorkspaceItem and persisted into the database
        WorkspaceItem updatedWsi = workspaceItemService.find(context, wsi.getID());
        assertThat(wsi.getID(), is(updatedWsi.getID()));
        assertThat(updatedWsi.getSubmitter().getEmail(), is(SUBMITTER_EMAIL));
        assertThat(updatedWsi.getSubmitter().getEmail(), not(currentUser.getEmail()));

        EPerson adminUser = ePersonService.findByEmail(context, admin.getEmail());
        context.setCurrentUser(adminUser);
        // Set workspace item owner to the current user
        getClient(adminToken).perform(get("/api/submission/setOwner")
                .param("shareToken", updatedWsi.getShareToken())
                .param("workspaceitemid", updatedWsi.getID().toString())
                .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                .andExpect(status().isOk());

        // Check that the owner of the WorkspaceItem was set to the current user
        // Check the wsi was persisted into the database
        updatedWsi = workspaceItemService.find(context, wsi.getID());
        assertThat(updatedWsi.getSubmitter().getEmail(), is(adminUser.getEmail()));
        assertThat(updatedWsi.getSubmitter().getEmail(), not(SUBMITTER_EMAIL));
    }

    @Test
    public void generateShareTokenAndSetOwnerTo3rdPersonTest() throws Exception {
        context.turnOffAuthorisationSystem();
        EPerson submitter2 = EPersonBuilder.createEPerson(context)
                .withEmail("user@test.edu")
                .withPassword(password)
                .build();
        Group group = GroupBuilder.createCollectionSubmitterGroup(context, wsi.getCollection())
                .withName("Test Submitters Group").build();
        groupService.addMember(context, group, submitter2);
        context.restoreAuthSystemState();

        EPerson currentUser = context.getCurrentUser();

        String adminToken = getAuthToken(admin.getEmail(), password);
        getClient(adminToken).perform(get("/api/submission/share")
                        .param("workspaceitemid", wsi.getID().toString())
                        .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shareLink", is(notNullValue())));

        // Check that the share token was set on the WorkspaceItem and persisted into the database
        WorkspaceItem updatedWsi = workspaceItemService.find(context, wsi.getID());
        assertThat(wsi.getID(), is(updatedWsi.getID()));
        assertThat(updatedWsi.getSubmitter().getEmail(), is(SUBMITTER_EMAIL));
        assertThat(updatedWsi.getSubmitter().getEmail(), not(currentUser.getEmail()));

        EPerson adminUser = ePersonService.findByEmail(context, admin.getEmail());
        context.setCurrentUser(adminUser);
        // Set workspace item owner to the current user
        getClient(adminToken).perform(get("/api/submission/setOwner")
                        .param("shareToken", updatedWsi.getShareToken())
                        .param("workspaceitemid", updatedWsi.getID().toString())
                        .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                .andExpect(status().isOk());

        // Check that the owner of the WorkspaceItem was set to the current user
        // Check the wsi was persisted into the database
        updatedWsi = workspaceItemService.find(context, wsi.getID());
        assertThat(updatedWsi.getSubmitter().getEmail(), is(adminUser.getEmail()));
        assertThat(updatedWsi.getSubmitter().getEmail(), not(SUBMITTER_EMAIL));

        getClient(adminToken).perform(get("/api/submission/share")
                        .param("workspaceitemid", wsi.getID().toString())
                        .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shareLink", is(notNullValue())));

        updatedWsi = workspaceItemService.find(context, wsi.getID());

        context.setCurrentUser(submitter2);
        String userToken = getAuthToken(submitter2.getEmail(), password);
        // Set workspace item owner to the 3rd person
        getClient(userToken).perform(get("/api/submission/setOwner")
                        .param("shareToken", updatedWsi.getShareToken())
                        .param("workspaceitemid", updatedWsi.getID().toString())
                        .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                .andExpect(status().isOk());

        // Check that the owner of the WorkspaceItem was set to the current user
        // Check the wsi was persisted into the database
        updatedWsi = workspaceItemService.find(context, wsi.getID());
        assertThat(updatedWsi.getSubmitter().getEmail(), is(submitter2.getEmail()));
        assertThat(updatedWsi.getSubmitter().getEmail(), not(adminUser.getEmail()));
    }

    @Test
    public void testSetOwnerUpdatesBundleAndBitstreamPolicies() throws Exception {
        context.turnOffAuthorisationSystem();

        Collection collection = CollectionBuilder.createCollection(context, parentCommunity)
                .withName("Test Collection")
                .withSubmitterGroup(eperson)
                .build();

        // Create a workspace item with the original submitter
        WorkspaceItem workspaceItem = WorkspaceItemBuilder.createWorkspaceItem(context, collection)
                .withSubmitter(eperson)
                .withTitle("Test Item for Policy Transfer")
                .build();

        Item item = workspaceItem.getItem();
        Bundle bundle = BundleBuilder.createBundle(context, item)
                .withName("ORIGINAL")
                .build();

        Bitstream bitstream = BitstreamBuilder.createBitstream(context, bundle,
                InputStream.nullInputStream())
                .withName("test.txt")
                .withMimeType("text/plain")
                .build();

        // Create TYPE_SUBMISSION policies for item, bundle, and bitstream for the original submitter
        ResourcePolicy itemPolicy = ResourcePolicyBuilder.createResourcePolicy(context, eperson, null)
                .withDspaceObject(item)
                .withAction(Constants.READ)
                .withPolicyType(ResourcePolicy.TYPE_SUBMISSION)
                .build();

        ResourcePolicy bundlePolicy = ResourcePolicyBuilder.createResourcePolicy(context, eperson, null)
                .withDspaceObject(bundle)
                .withAction(Constants.READ)
                .withPolicyType(ResourcePolicy.TYPE_SUBMISSION)
                .build();

        ResourcePolicy bitstreamPolicy = ResourcePolicyBuilder.createResourcePolicy(context, eperson, null)
                .withDspaceObject(bitstream)
                .withAction(Constants.READ)
                .withPolicyType(ResourcePolicy.TYPE_SUBMISSION)
                .build();

        // Generate a share token for this workspace item
        String shareToken = generateShareToken();
        workspaceItem.setShareToken(shareToken);
        workspaceItemService.update(context, workspaceItem);

        context.restoreAuthSystemState();

        // Authenticate as a different user (the one who will claim ownership)
        String newOwnerToken = getAuthToken(admin.getEmail(), password);

        getClient(newOwnerToken).perform(get("/api/submission/setOwner")
                .param("shareToken", shareToken)
                .param("workspaceitemid", workspaceItem.getID().toString()))
                .andExpect(status().isOk());

        context.turnOffAuthorisationSystem();

        // Verify that item policy was updated
        List<ResourcePolicy> updatedItemPolicies = authorizeService.getPolicies(context, item);
        List<ResourcePolicy> itemSubmissionPolicies = updatedItemPolicies.stream()
                .filter(policy -> ResourcePolicy.TYPE_SUBMISSION.equals(policy.getRpType()))
                .collect(Collectors.toList());

        assertNotNull("Updated item policies should not be null", updatedItemPolicies);
        assertNotNull("Item submission policies should not be null", itemSubmissionPolicies);
        assertThat(itemSubmissionPolicies, hasSize(greaterThan(0)));
        assertThat(itemSubmissionPolicies.get(0).getEPerson(), equalTo(admin));

        // Verify that bundle policy was updated
        List<ResourcePolicy> updatedBundlePolicies = authorizeService.getPolicies(context, bundle);
        List<ResourcePolicy> bundleSubmissionPolicies = updatedBundlePolicies.stream()
                .filter(policy -> ResourcePolicy.TYPE_SUBMISSION.equals(policy.getRpType()))
                .collect(Collectors.toList());

        assertNotNull("Updated bundle policies should not be null", updatedBundlePolicies);
        assertNotNull("Bundle submission policies should not be null", bundleSubmissionPolicies);
        assertThat(bundleSubmissionPolicies, hasSize(greaterThan(0)));
        assertThat(bundleSubmissionPolicies.get(0).getEPerson(), equalTo(admin));

        // Verify that bitstream policy was updated
        List<ResourcePolicy> updatedBitstreamPolicies = authorizeService.getPolicies(context, bitstream);
        List<ResourcePolicy> bitstreamSubmissionPolicies = updatedBitstreamPolicies.stream()
                .filter(policy -> ResourcePolicy.TYPE_SUBMISSION.equals(policy.getRpType()))
                .collect(Collectors.toList());

        assertNotNull("Updated bitstream policies should not be null", updatedBitstreamPolicies);
        assertNotNull("Bitstream submission policies should not be null", bitstreamSubmissionPolicies);
        assertThat(bitstreamSubmissionPolicies, hasSize(greaterThan(0)));
        assertThat(bitstreamSubmissionPolicies.get(0).getEPerson(), equalTo(admin));

        // Additional verification: Check that the new owner has specific permissions
        context.setCurrentUser(admin);

        assertTrue("Admin should have READ access to item",
                   authorizeService.authorizeActionBoolean(context, item, Constants.READ));

        assertTrue("Admin should have WRITE access to bundle",
                   authorizeService.authorizeActionBoolean(context, bundle, Constants.WRITE));

        assertTrue("Admin should have ADD access to bundle",
                   authorizeService.authorizeActionBoolean(context, bundle, Constants.ADD));

        assertTrue("Admin should have READ access to bitstream",
                   authorizeService.authorizeActionBoolean(context, bitstream, Constants.READ));

        assertTrue("Admin should have WRITE access to bitstream",
                   authorizeService.authorizeActionBoolean(context, bitstream, Constants.WRITE));

        context.restoreAuthSystemState();
    }

    @Test
    public void testSetOwnerWithMultipleBundlesAndBitstreams() throws Exception {
        context.turnOffAuthorisationSystem();

        Collection collection = CollectionBuilder.createCollection(context, parentCommunity)
                .withName("Test Collection Multi")
                .withSubmitterGroup(eperson)
                .build();

        WorkspaceItem workspaceItem = WorkspaceItemBuilder.createWorkspaceItem(context, collection)
                .withSubmitter(eperson)
                .withTitle("Test Item Multiple Bundles")
                .build();

        Item item = workspaceItem.getItem();

        // Create multiple bundles with multiple bitstreams
        Bundle bundle1 = BundleBuilder.createBundle(context, item)
                .withName("ORIGINAL")
                .build();

        Bundle bundle2 = BundleBuilder.createBundle(context, item)
                .withName("THUMBNAIL")
                .build();

        Bitstream bitstream1 = BitstreamBuilder.createBitstream(context, bundle1,
                InputStream.nullInputStream())
                .withName("document.pdf")
                .withMimeType("application/pdf")
                .build();

        Bitstream bitstream2 = BitstreamBuilder.createBitstream(context, bundle1,
                InputStream.nullInputStream())
                .withName("readme.txt")
                .withMimeType("text/plain")
                .build();

        Bitstream bitstream3 = BitstreamBuilder.createBitstream(context, bundle2,
                InputStream.nullInputStream())
                .withName("thumbnail.jpg")
                .withMimeType("image/jpeg")
                .build();

        List<DSpaceObject> allObjects = Arrays.asList(item, bundle1, bundle2, bitstream1, bitstream2, bitstream3);
        for (DSpaceObject dso : allObjects) {
            ResourcePolicyBuilder.createResourcePolicy(context, eperson, null)
                    .withDspaceObject(dso)
                    .withAction(Constants.READ)
                    .withPolicyType(ResourcePolicy.TYPE_SUBMISSION)
                    .build();

            ResourcePolicyBuilder.createResourcePolicy(context, eperson, null)
                    .withDspaceObject(dso)
                    .withAction(Constants.WRITE)
                    .withPolicyType(ResourcePolicy.TYPE_SUBMISSION)
                    .build();
        }

        // Generate share token
        String shareToken = generateShareToken();
        workspaceItem.setShareToken(shareToken);
        workspaceItemService.update(context, workspaceItem);

        context.restoreAuthSystemState();

        // Transfer ownership to admin
        String newOwnerToken = getAuthToken(admin.getEmail(), password);
        getClient(newOwnerToken).perform(get("/api/submission/setOwner")
                .param("shareToken", shareToken)
                .param("workspaceitemid", workspaceItem.getID().toString()))
                .andExpect(status().isOk());

        context.turnOffAuthorisationSystem();

        // Verify all objects now have policies for the new owner (admin)
        for (DSpaceObject dso : allObjects) {
            List<ResourcePolicy> policies = authorizeService.getPolicies(context, dso);
            List<ResourcePolicy> submissionPolicies = policies.stream()
                    .filter(policy -> ResourcePolicy.TYPE_SUBMISSION.equals(policy.getRpType()))
                    .filter(policy -> policy.getEPerson() != null)
                    .filter(policy -> policy.getEPerson().equals(admin))
                    .collect(Collectors.toList());

            assertNotNull("DSpace object should not be null", dso);
            assertThat("Object " + dso.getName() + " should have submission policies for admin",
                    submissionPolicies, hasSize(greaterThan(0)));
        }

        // Verify the original submitter no longer has policies
        for (DSpaceObject dso : allObjects) {
            List<ResourcePolicy> policies = authorizeService.getPolicies(context, dso);
            List<ResourcePolicy> originalSubmitterPolicies = policies.stream()
                    .filter(policy -> ResourcePolicy.TYPE_SUBMISSION.equals(policy.getRpType()))
                    .filter(policy -> policy.getEPerson() != null)
                    .filter(policy -> policy.getEPerson().equals(eperson))
                    .collect(Collectors.toList());

            assertNotNull("DSpace object should not be null", dso);
            assertThat("Object " + dso.getName() + " should not have submission policies for original submitter",
                    originalSubmitterPolicies, hasSize(0));
        }

        context.restoreAuthSystemState();
    }

    private static String generateShareToken() {
        // UUID generates a 36-char string with hyphens, so we can strip them to get a 32-char string
        return UUID.randomUUID().toString().replace("-", "").substring(0, 32);
    }
}
