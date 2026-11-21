/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.apache.commons.io.IOUtils;
import org.dspace.app.rest.test.AbstractControllerIntegrationTest;
import org.dspace.builder.BitstreamBuilder;
import org.dspace.builder.ClarinLicenseBuilder;
import org.dspace.builder.ClarinLicenseResourceMappingBuilder;
import org.dspace.builder.ClarinLicenseResourceUserAllowanceBuilder;
import org.dspace.builder.ClarinUserMetadataBuilder;
import org.dspace.builder.ClarinUserRegistrationBuilder;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.EPersonBuilder;
import org.dspace.builder.ItemBuilder;
import org.dspace.content.Bitstream;
import org.dspace.content.Collection;
import org.dspace.content.Community;
import org.dspace.content.Item;
import org.dspace.content.clarin.ClarinLicenseResourceMapping;
import org.dspace.content.clarin.ClarinLicenseResourceUserAllowance;
import org.dspace.content.clarin.ClarinUserMetadata;
import org.dspace.content.clarin.ClarinUserRegistration;
import org.dspace.eperson.EPerson;
import org.junit.After;
import org.junit.Test;

public class ClarinUserMetadataRestRepositoryIT extends AbstractControllerIntegrationTest {

    private ClarinLicenseResourceUserAllowance allowance;
    private ClarinLicenseResourceMapping mapping;

    @After
    public void deleteAllowance() throws Exception {
        if (allowance != null) {
            ClarinLicenseResourceUserAllowanceBuilder.deleteClarinLicenseResourceUserAllowance(allowance.getID());
        }
    }

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

        getClient().perform(get("/api/core/clarinusermetadata"))
                .andExpect(status().isUnauthorized());

        String adminToken = getAuthToken(admin.getEmail(), password);
        getClient(adminToken).perform(get("/api/core/clarinusermetadata"))
                .andExpect(status().isOk());

        String userToken = getAuthToken(eperson.getEmail(), password);
        getClient(userToken).perform(get("/api/core/clarinusermetadata"))
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
        ClarinUserRegistration clarinUserRegistration = ClarinUserRegistrationBuilder
                .createClarinUserRegistration(context)
                .withEPersonID(eperson.getID())
                .build();
        ClarinUserMetadata clarinUserMetadata = ClarinUserMetadataBuilder.createClarinUserMetadata(context)
                .withUserRegistration(clarinUserRegistration)
                .build();

        context.restoreAuthSystemState();

        // Test that the user registration is not visible to anonymous users
        getClient().perform(get("/api/core/clarinusermetadata/" + clarinUserMetadata.getID()))
                .andExpect(status().isUnauthorized());

        // Test that the user registration is visible to admin
        String adminToken = getAuthToken(admin.getEmail(), password);
        getClient(adminToken).perform(get("/api/core/clarinusermetadata/" +
                        clarinUserMetadata.getID()))
                .andExpect(status().isOk());

        // Test that the user registration is visible to authenticated users
        String userToken = getAuthToken(eperson.getEmail(), password);
        getClient(userToken).perform(get("/api/core/clarinusermetadata/" +
                        clarinUserMetadata.getID()))
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
        ClarinUserMetadata otherClarinUserMetadata = ClarinUserMetadataBuilder
                .createClarinUserMetadata(context)
                .withUserRegistration(otherClarinUserRegistration)
                .build();
        context.restoreAuthSystemState();

        String otherUserToken = getAuthToken(otherEPerson.getEmail(), password);
        // Check the new user can see their own user registration
        getClient(otherUserToken).perform(get("/api/core/clarinusermetadata/" +
                        otherClarinUserMetadata.getID()))
                .andExpect(status().isOk());

        // Test that the user registration is not visible to other users
        getClient(userToken).perform(get("/api/core/clarinusermetadata/" +
                        otherClarinUserMetadata.getID()))
                .andExpect(status().isForbidden());
    }

    @Test
    public void findOneWithoutUserRegistrationTest() throws Exception {
        context.turnOffAuthorisationSystem();
        ClarinUserMetadata clarinUserMetadata = ClarinUserMetadataBuilder.createClarinUserMetadata(context)
                .build();
        context.restoreAuthSystemState();

        // User metadata without a user registration come from an anonymous download, only admin can read them
        getClient().perform(get("/api/core/clarinusermetadata/" + clarinUserMetadata.getID()))
                .andExpect(status().isUnauthorized());

        String userToken = getAuthToken(eperson.getEmail(), password);
        getClient(userToken).perform(get("/api/core/clarinusermetadata/" + clarinUserMetadata.getID()))
                .andExpect(status().isForbidden());

        String adminToken = getAuthToken(admin.getEmail(), password);
        getClient(adminToken).perform(get("/api/core/clarinusermetadata/" + clarinUserMetadata.getID()))
                .andExpect(status().isOk());
    }

    @Test
    public void findByUserRegistrationAndBitstreamTest() throws Exception {
        context.turnOffAuthorisationSystem();
        Bitstream bitstream = createBitstreamWithLicense();
        ClarinUserRegistration clarinUserRegistration = ClarinUserRegistrationBuilder
                .createClarinUserRegistration(context)
                .withEPersonID(eperson.getID())
                .build();
        allowance = ClarinLicenseResourceUserAllowanceBuilder.createClarinLicenseResourceUserAllowance(context)
                .withUser(clarinUserRegistration)
                .withMapping(mapping)
                .build();
        ClarinUserMetadata clarinUserMetadata = ClarinUserMetadataBuilder.createClarinUserMetadata(context)
                .withUserRegistration(clarinUserRegistration)
                .build();
        clarinUserMetadata.setTransaction(allowance);
        EPerson otherEPerson = EPersonBuilder.createEPerson(context)
                .withEmail("otherEperson@mail.com")
                .withPassword(password)
                .build();
        context.restoreAuthSystemState();

        String search = "/api/core/clarinusermetadata/search/byUserRegistrationAndBitstream?userRegUUID=" +
                clarinUserRegistration.getID() + "&bitstreamUUID=" + bitstream.getID();

        getClient().perform(get(search))
                .andExpect(status().isUnauthorized());

        String userToken = getAuthToken(eperson.getEmail(), password);
        getClient(userToken).perform(get(search))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements", is(1)));

        String otherUserToken = getAuthToken(otherEPerson.getEmail(), password);
        getClient(otherUserToken).perform(get(search))
                .andExpect(status().isForbidden());

        String adminToken = getAuthToken(admin.getEmail(), password);
        getClient(adminToken).perform(get(search))
                .andExpect(status().isOk());
    }

    private Bitstream createBitstreamWithLicense() throws Exception {
        Community community = CommunityBuilder.createCommunity(context)
                .withName("Community")
                .build();
        Collection collection = CollectionBuilder.createCollection(context, community)
                .withName("Collection")
                .build();
        Item item = ItemBuilder.createItem(context, collection)
                .withTitle("Item")
                .build();
        Bitstream bitstream = BitstreamBuilder
                .createBitstream(context, item, IOUtils.toInputStream("content", StandardCharsets.UTF_8))
                .withName("file.txt")
                .build();
        mapping = ClarinLicenseResourceMappingBuilder.createClarinLicenseResourceMapping(context).build();
        mapping.setLicense(ClarinLicenseBuilder.createClarinLicense(context).build());
        mapping.setBitstream(bitstream);
        return bitstream;
    }
}
