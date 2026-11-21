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
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.io.IOUtils;
import org.dspace.app.rest.test.AbstractControllerIntegrationTest;
import org.dspace.builder.BitstreamBuilder;
import org.dspace.builder.ClarinLicenseBuilder;
import org.dspace.builder.ClarinLicenseResourceMappingBuilder;
import org.dspace.builder.ClarinLicenseResourceUserAllowanceBuilder;
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
import org.dspace.content.clarin.ClarinUserRegistration;
import org.dspace.eperson.EPerson;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ClarinLicenseResourceUserAllowanceRestRepositoryIT extends AbstractControllerIntegrationTest {

    private Bitstream bitstream;
    private ClarinLicenseResourceMapping mapping;
    private ClarinUserRegistration clarinUserRegistration;
    private EPerson otherEPerson;
    private final List<Integer> allowanceIds = new ArrayList<>();

    @Before
    public void setup() throws Exception {
        context.turnOffAuthorisationSystem();
        Community community = CommunityBuilder.createCommunity(context)
                .withName("Community")
                .build();
        Collection collection = CollectionBuilder.createCollection(context, community)
                .withName("Collection")
                .build();
        Item item = ItemBuilder.createItem(context, collection)
                .withTitle("Item")
                .build();
        bitstream = BitstreamBuilder
                .createBitstream(context, item, IOUtils.toInputStream("content", StandardCharsets.UTF_8))
                .withName("file.txt")
                .build();
        mapping = ClarinLicenseResourceMappingBuilder.createClarinLicenseResourceMapping(context).build();
        mapping.setLicense(ClarinLicenseBuilder.createClarinLicense(context).build());
        mapping.setBitstream(bitstream);
        clarinUserRegistration = ClarinUserRegistrationBuilder.createClarinUserRegistration(context)
                .withEPersonID(eperson.getID())
                .build();
        otherEPerson = EPersonBuilder.createEPerson(context)
                .withEmail("otherEperson@mail.com")
                .withPassword(password)
                .build();
        context.restoreAuthSystemState();
    }

    @After
    public void deleteAllowances() throws Exception {
        for (Integer id : allowanceIds) {
            ClarinLicenseResourceUserAllowanceBuilder.deleteClarinLicenseResourceUserAllowance(id);
        }
    }

    @Test
    public void findOneTest() throws Exception {
        ClarinLicenseResourceUserAllowance allowance = createAllowance(clarinUserRegistration);
        String url = "/api/core/clarinlruallowances/" + allowance.getID();

        getClient().perform(get(url))
                .andExpect(status().isUnauthorized());

        String userToken = getAuthToken(eperson.getEmail(), password);
        getClient(userToken).perform(get(url))
                .andExpect(status().isOk());

        String otherUserToken = getAuthToken(otherEPerson.getEmail(), password);
        getClient(otherUserToken).perform(get(url))
                .andExpect(status().isForbidden());

        String adminToken = getAuthToken(admin.getEmail(), password);
        getClient(adminToken).perform(get(url))
                .andExpect(status().isOk());
    }

    @Test
    public void findOneWithoutUserRegistrationTest() throws Exception {
        // Allowances without a user registration come from an anonymous download, only admin can read them
        ClarinLicenseResourceUserAllowance allowance = createAllowance(null);
        String url = "/api/core/clarinlruallowances/" + allowance.getID();

        getClient().perform(get(url))
                .andExpect(status().isUnauthorized());

        String userToken = getAuthToken(eperson.getEmail(), password);
        getClient(userToken).perform(get(url))
                .andExpect(status().isForbidden());

        String adminToken = getAuthToken(admin.getEmail(), password);
        getClient(adminToken).perform(get(url))
                .andExpect(status().isOk());
    }

    @Test
    public void linksTest() throws Exception {
        ClarinLicenseResourceUserAllowance allowance = createAllowance(clarinUserRegistration);

        String userToken = getAuthToken(eperson.getEmail(), password);
        String otherUserToken = getAuthToken(otherEPerson.getEmail(), password);
        for (String link : List.of("userRegistration", "resourceMapping")) {
            String url = "/api/core/clarinlruallowances/" + allowance.getID() + "/" + link;

            getClient().perform(get(url))
                    .andExpect(status().isUnauthorized());

            getClient(otherUserToken).perform(get(url))
                    .andExpect(status().isForbidden());

            getClient(userToken).perform(get(url))
                    .andExpect(status().isOk());
        }
    }

    @Test
    public void findByBitstreamAndUserTest() throws Exception {
        createAllowance(clarinUserRegistration);
        String search = "/api/core/clarinlruallowances/search/byBitstreamAndUser?bitstreamUUID=" +
                bitstream.getID() + "&userUUID=" + eperson.getID();

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

    private ClarinLicenseResourceUserAllowance createAllowance(ClarinUserRegistration owner) throws Exception {
        context.turnOffAuthorisationSystem();
        ClarinLicenseResourceUserAllowance allowance = ClarinLicenseResourceUserAllowanceBuilder
                .createClarinLicenseResourceUserAllowance(context)
                .withUser(owner)
                .withMapping(mapping)
                .withToken("downloadToken")
                .build();
        context.restoreAuthSystemState();
        allowanceIds.add(allowance.getID());
        return allowance;
    }
}
