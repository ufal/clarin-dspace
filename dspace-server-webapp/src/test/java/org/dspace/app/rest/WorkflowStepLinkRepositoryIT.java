/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.dspace.app.rest.test.AbstractControllerIntegrationTest;
import org.dspace.builder.ClaimedTaskBuilder;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.EPersonBuilder;
import org.dspace.builder.PoolTaskBuilder;
import org.dspace.content.Collection;
import org.dspace.content.Community;
import org.dspace.eperson.EPerson;
import org.dspace.xmlworkflow.storedcomponents.ClaimedTask;
import org.dspace.xmlworkflow.storedcomponents.PoolTask;
import org.junit.Test;

/**
 * Authorization tests for {@code /api/workflow/{pooltasks,claimedtasks,workflowitems}/{id}/step}.
 * Each /step subresource requires the same READ permission as its parent.
 */
public class WorkflowStepLinkRepositoryIT extends AbstractControllerIntegrationTest {

    @Test
    public void poolTaskStepEnforcesAuthorization() throws Exception {
        context.turnOffAuthorisationSystem();

        EPerson reviewer = EPersonBuilder.createEPerson(context)
                .withEmail("reviewer-step@example.com").withPassword(password).build();
        EPerson otherEPerson = EPersonBuilder.createEPerson(context)
                .withEmail("other-step@example.com").withPassword(password).build();
        EPerson submitter = EPersonBuilder.createEPerson(context)
                .withEmail("submitter-step@example.com").withPassword(password).build();

        parentCommunity = CommunityBuilder.createCommunity(context).withName("Parent Community").build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                .withName("Sub Community").build();
        Collection col1 = CollectionBuilder.createCollection(context, child1).withName("Collection 1")
                .withWorkflowGroup(1, reviewer).build();

        context.setCurrentUser(submitter);
        PoolTask poolTask = PoolTaskBuilder.createPoolTask(context, col1, reviewer)
                .withTitle("Workflow Item Pool").withIssueDate("2017-10-17").build();

        context.restoreAuthSystemState();

        String reviewerToken = getAuthToken(reviewer.getEmail(), password);
        String otherToken = getAuthToken(otherEPerson.getEmail(), password);
        String adminToken = getAuthToken(admin.getEmail(), password);

        String stepPath = "/api/workflow/pooltasks/" + poolTask.getID() + "/step";

        // anonymous caller must not reach the handler
        getClient().perform(get(stepPath)).andExpect(status().isUnauthorized());
        // an authenticated user without READ permission on the task must be blocked
        getClient(otherToken).perform(get(stepPath)).andExpect(status().isForbidden());
        // the task owner may read the step
        getClient(reviewerToken).perform(get(stepPath)).andExpect(status().isOk());
        // an administrator may read the step
        getClient(adminToken).perform(get(stepPath)).andExpect(status().isOk());
    }

    @Test
    public void claimedTaskStepEnforcesAuthorization() throws Exception {
        context.turnOffAuthorisationSystem();

        EPerson reviewer = EPersonBuilder.createEPerson(context)
                .withEmail("reviewer-step@example.com").withPassword(password).build();
        EPerson otherEPerson = EPersonBuilder.createEPerson(context)
                .withEmail("other-step@example.com").withPassword(password).build();
        EPerson submitter = EPersonBuilder.createEPerson(context)
                .withEmail("submitter-step@example.com").withPassword(password).build();

        parentCommunity = CommunityBuilder.createCommunity(context).withName("Parent Community").build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                .withName("Sub Community").build();
        Collection col1 = CollectionBuilder.createCollection(context, child1).withName("Collection 1")
                .withWorkflowGroup(1, reviewer).build();

        context.setCurrentUser(submitter);
        ClaimedTask claimedTask = ClaimedTaskBuilder.createClaimedTask(context, col1, reviewer)
                .withTitle("Workflow Item Claimed").withIssueDate("2017-10-17").build();

        context.restoreAuthSystemState();

        String reviewerToken = getAuthToken(reviewer.getEmail(), password);
        String otherToken = getAuthToken(otherEPerson.getEmail(), password);
        String adminToken = getAuthToken(admin.getEmail(), password);

        String stepPath = "/api/workflow/claimedtasks/" + claimedTask.getID() + "/step";

        // anonymous caller must not reach the handler
        getClient().perform(get(stepPath)).andExpect(status().isUnauthorized());
        // an authenticated user without READ permission on the task must be blocked
        getClient(otherToken).perform(get(stepPath)).andExpect(status().isForbidden());
        // the task owner may read the step
        getClient(reviewerToken).perform(get(stepPath)).andExpect(status().isOk());
        // an administrator may read the step
        getClient(adminToken).perform(get(stepPath)).andExpect(status().isOk());
    }

    @Test
    public void workflowItemStepEnforcesAuthorization() throws Exception {
        context.turnOffAuthorisationSystem();

        EPerson reviewer = EPersonBuilder.createEPerson(context)
                .withEmail("reviewer-step@example.com").withPassword(password).build();
        EPerson otherEPerson = EPersonBuilder.createEPerson(context)
                .withEmail("other-step@example.com").withPassword(password).build();
        EPerson submitter = EPersonBuilder.createEPerson(context)
                .withEmail("submitter-step@example.com").withPassword(password).build();

        parentCommunity = CommunityBuilder.createCommunity(context).withName("Parent Community").build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                .withName("Sub Community").build();
        Collection col1 = CollectionBuilder.createCollection(context, child1).withName("Collection 1")
                .withWorkflowGroup(1, reviewer).build();

        context.setCurrentUser(submitter);
        PoolTask poolTask = PoolTaskBuilder.createPoolTask(context, col1, reviewer)
                .withTitle("Workflow Item Pool").withIssueDate("2017-10-17").build();

        context.restoreAuthSystemState();

        String otherToken = getAuthToken(otherEPerson.getEmail(), password);
        String adminToken = getAuthToken(admin.getEmail(), password);

        String stepPath = "/api/workflow/workflowitems/" + poolTask.getWorkflowItem().getID() + "/step";

        // anonymous caller must not reach the handler
        getClient().perform(get(stepPath)).andExpect(status().isUnauthorized());
        // an authenticated user without READ permission on the workflow item must be blocked
        getClient(otherToken).perform(get(stepPath)).andExpect(status().isForbidden());
        // an administrator may read the step
        getClient(adminToken).perform(get(stepPath)).andExpect(status().isOk());
    }

    @Test
    public void workflowItemStepWithUnknownIdIsNotFound() throws Exception {
        context.turnOffAuthorisationSystem();

        EPerson ePerson = EPersonBuilder.createEPerson(context)
                .withEmail("lookup-step@example.com").withPassword(password).build();

        context.restoreAuthSystemState();

        String token = getAuthToken(ePerson.getEmail(), password);

        // unknown id must give 404, not 500
        getClient(token).perform(get("/api/workflow/workflowitems/" + Integer.MAX_VALUE + "/step"))
                .andExpect(status().isNotFound());
    }

}
