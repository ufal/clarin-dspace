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
import org.dspace.builder.ClarinLicenseBuilder;
import org.dspace.builder.ClarinUserMetadataBuilder;
import org.dspace.builder.ClarinUserRegistrationBuilder;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.EPersonBuilder;
import org.dspace.content.Community;
import org.dspace.content.clarin.ClarinUserRegistration;
import org.dspace.eperson.EPerson;
import org.junit.Test;

public class ClarinUserRegistrationRestRepositoryIT extends AbstractControllerIntegrationTest {

    @Test
    public void findAllTest() throws Exception {
        context.turnOffAuthorisationSystem();
        Community community = CommunityBuilder.createCommunity(context)
                .withName("Community")
                .build();
        CollectionBuilder.createCollection(context, community)
                .withName("Collection")
                .build();
        ClarinLicenseBuilder
                .createClarinLicense(context)
                .build();
        ClarinUserMetadataBuilder
                .createClarinUserMetadata(context)
                .build();
        ClarinUserRegistrationBuilder
                .createClarinUserRegistration(context)
                .build();
        context.restoreAuthSystemState();

        getClient().perform(get("/api/core/clarinuserregistrations"))
                .andExpect(status().isUnauthorized());

        String adminToken = getAuthToken(admin.getEmail(), password);
        getClient(adminToken).perform(get("/api/core/clarinuserregistrations"))
                .andExpect(status().isOk());

        String userToken = getAuthToken(eperson.getEmail(), password);
        getClient(userToken).perform(get("/api/core/clarinuserregistrations"))
                .andExpect(status().isForbidden());
    }

    @Test
    public void findOneTest() throws Exception {
        context.turnOffAuthorisationSystem();
        Community community = CommunityBuilder.createCommunity(context)
                .withName("Community")
                .build();
        CollectionBuilder.createCollection(context, community)
                .withName("Collection")
                .build();
        ClarinLicenseBuilder.createClarinLicense(context)
                .build();
        ClarinUserMetadataBuilder.createClarinUserMetadata(context)
                .build();
        ClarinUserRegistration clarinUserRegistration = ClarinUserRegistrationBuilder
                .createClarinUserRegistration(context)
                .withEPersonID(eperson.getID())
                .build();
        context.restoreAuthSystemState();

        // Test that the user registration is not visible to anonymous users
        getClient().perform(get("/api/core/clarinuserregistrations/" + clarinUserRegistration.getID()))
                .andExpect(status().isUnauthorized());

        // Test that the user registration is visible to admin
        String adminToken = getAuthToken(admin.getEmail(), password);
        getClient(adminToken).perform(get("/api/core/clarinuserregistrations/" +
                        clarinUserRegistration.getID()))
                .andExpect(status().isOk());

        // Test that the user registration is visible to authenticated users
        String userToken = getAuthToken(eperson.getEmail(), password);
        getClient(userToken).perform(get("/api/core/clarinuserregistrations/" +
                        clarinUserRegistration.getID()))
                .andExpect(status().isOk());

        context.turnOffAuthorisationSystem();
        // Test that the user registration is not visible to other users
        EPerson otherEPerson = EPersonBuilder.createEPerson(context)
                .withEmail("otherEperson@mail.com")
                .withPassword(password)
                .build();
        ClarinUserRegistration otherClarinUserRegistration = ClarinUserRegistrationBuilder
                .createClarinUserRegistration(context)
                .withEPersonID(otherEPerson.getID())
                .build();
        context.restoreAuthSystemState();

        String otherUserToken = getAuthToken(otherEPerson.getEmail(), password);
        // Check the new user can see their own user registration
        getClient(otherUserToken).perform(get("/api/core/clarinuserregistrations/" +
                        otherClarinUserRegistration.getID()))
                .andExpect(status().isOk());

        // Test that the user registration is not visible to other users
        getClient(userToken).perform(get("/api/core/clarinuserregistrations/" +
                        otherClarinUserRegistration.getID()))
                .andExpect(status().isForbidden());
    }
}
