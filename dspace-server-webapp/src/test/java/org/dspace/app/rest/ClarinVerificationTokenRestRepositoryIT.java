/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.dspace.app.rest.test.AbstractControllerIntegrationTest;
import org.dspace.content.clarin.ClarinVerificationToken;
import org.dspace.content.service.clarin.ClarinVerificationTokenService;
import org.dspace.core.Context;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;

public class ClarinVerificationTokenRestRepositoryIT extends AbstractControllerIntegrationTest {

    private static final String BASE_URL = "/api/core/clarinverificationtokens/";

    @Autowired
    private ClarinVerificationTokenService clarinVerificationTokenService;

    private ClarinVerificationToken verificationToken;

    @Before
    public void createVerificationToken() throws Exception {
        String suffix = UUID.randomUUID().toString();
        context.turnOffAuthorisationSystem();
        verificationToken = clarinVerificationTokenService.create(context);
        verificationToken.setePersonNetID("netid-" + suffix);
        verificationToken.setEmail("token@example.com");
        verificationToken.setToken("token-" + suffix);
        verificationToken.setShibHeaders("shib-identity-provider=Test Idp");
        clarinVerificationTokenService.update(context, verificationToken);
        context.restoreAuthSystemState();
    }

    @After
    public void deleteVerificationToken() throws Exception {
        try (Context c = new Context()) {
            ClarinVerificationToken found = clarinVerificationTokenService.find(c, verificationToken.getID());
            if (found != null) {
                clarinVerificationTokenService.delete(c, found);
            }
            c.complete();
        }
    }

    @Test
    public void findOneIsForAdminsOnly() throws Exception {
        getClient().perform(get(BASE_URL + verificationToken.getID()))
                .andExpect(status().isUnauthorized());

        String userToken = getAuthToken(eperson.getEmail(), password);
        getClient(userToken).perform(get(BASE_URL + verificationToken.getID()))
                .andExpect(status().isForbidden());

        String adminToken = getAuthToken(admin.getEmail(), password);
        getClient(adminToken).perform(get(BASE_URL + verificationToken.getID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(verificationToken.getID())));
    }

    @Test
    public void deleteIsForAdminsOnly() throws Exception {
        getClient().perform(delete(BASE_URL + verificationToken.getID()))
                .andExpect(status().isUnauthorized());
        assertNotNull(clarinVerificationTokenService.find(context, verificationToken.getID()));

        String userToken = getAuthToken(eperson.getEmail(), password);
        getClient(userToken).perform(delete(BASE_URL + verificationToken.getID()))
                .andExpect(status().isForbidden());
        assertNotNull(clarinVerificationTokenService.find(context, verificationToken.getID()));

        String adminToken = getAuthToken(admin.getEmail(), password);
        getClient(adminToken).perform(delete(BASE_URL + verificationToken.getID()))
                .andExpect(status().isNoContent());
        assertNull(clarinVerificationTokenService.find(context, verificationToken.getID()));
    }

    @Test
    public void searchByNetIdIsForAdminsOnly() throws Exception {
        String url = BASE_URL + "search/byNetId";
        String netId = verificationToken.getePersonNetID();

        getClient().perform(get(url).param("netid", netId))
                .andExpect(status().isUnauthorized());

        String userToken = getAuthToken(eperson.getEmail(), password);
        getClient(userToken).perform(get(url).param("netid", netId))
                .andExpect(status().isForbidden());

        String adminToken = getAuthToken(admin.getEmail(), password);
        getClient(adminToken).perform(get(url).param("netid", netId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.clarinverificationtokens[0].id", is(verificationToken.getID())));
    }

    /**
     * The autoregistration page loads the token by its value before the user is signed in.
     */
    @Test
    public void searchByTokenWorksWithoutLogin() throws Exception {
        getClient().perform(get(BASE_URL + "search/byToken").param("token", verificationToken.getToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.clarinverificationtokens[0].id", is(verificationToken.getID())))
                .andExpect(jsonPath("$._embedded.clarinverificationtokens[0].shibHeaders",
                        is(verificationToken.getShibHeaders())));
    }

    @Test
    public void searchByUnknownTokenReturnsNothing() throws Exception {
        getClient().perform(get(BASE_URL + "search/byToken").param("token", "unknown-" + UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements", is(0)));
    }
}
