/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest;

import static com.jayway.jsonpath.JsonPath.read;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dspace.app.rest.converter.ClarinLicenseLabelConverter;
import org.dspace.app.rest.matcher.ClarinLicenseLabelMatcher;
import org.dspace.app.rest.model.ClarinLicenseLabelRest;
import org.dspace.app.rest.projection.Projection;
import org.dspace.app.rest.test.AbstractControllerIntegrationTest;
import org.dspace.builder.ClarinLicenseLabelBuilder;
import org.dspace.content.clarin.ClarinLicenseLabel;
import org.dspace.content.service.clarin.ClarinLicenseLabelService;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Integration tests for the Clarin License Label Rest Repository
 *
 * @author Milan Majchrak (milan.majchrak at dataquest.sk)
 */
public class ClarinLicenseLabelRestRepositoryIT extends AbstractControllerIntegrationTest {

    @Autowired
    ClarinLicenseLabelService clarinLicenseLabelService;

    @Autowired
    ClarinLicenseLabelConverter clarinLicenseLabelConverter;

    @Autowired
    private ObjectMapper objectMapper;

    ClarinLicenseLabel firstCLicenseLabel;
    ClarinLicenseLabel secondCLicenseLabel;
    ClarinLicenseLabel thirdCLicenseLabel;

    @Before
    public void setup() throws Exception {
        context.turnOffAuthorisationSystem();
        // create LicenseLabels
        firstCLicenseLabel = ClarinLicenseLabelBuilder.createClarinLicenseLabel(context).build();
        firstCLicenseLabel.setLabel("CC");
        firstCLicenseLabel.setExtended(true);
        firstCLicenseLabel.setTitle("CLL Title1");
        firstCLicenseLabel.setIcon(new byte[100]);
        clarinLicenseLabelService.update(context, firstCLicenseLabel);

        secondCLicenseLabel = ClarinLicenseLabelBuilder.createClarinLicenseLabel(context).build();
        secondCLicenseLabel.setLabel("CCC");
        secondCLicenseLabel.setExtended(true);
        secondCLicenseLabel.setTitle("CLL Title2");
        secondCLicenseLabel.setIcon(new byte[200]);
        clarinLicenseLabelService.update(context, secondCLicenseLabel);

        thirdCLicenseLabel = ClarinLicenseLabelBuilder.createClarinLicenseLabel(context).build();
        thirdCLicenseLabel.setLabel("DBC");
        thirdCLicenseLabel.setExtended(false);
        thirdCLicenseLabel.setTitle("CLL Title3");
        thirdCLicenseLabel.setIcon(new byte[300]);
        clarinLicenseLabelService.update(context, thirdCLicenseLabel);
        context.restoreAuthSystemState();
    }

    @Test
    public void clarinLicenseLabelsAreInitialized() throws Exception {
        Assert.assertNotNull(firstCLicenseLabel);
        Assert.assertNotNull(secondCLicenseLabel);
        Assert.assertNotNull(thirdCLicenseLabel);
    }

    @Test
    public void findAll() throws Exception {
        String authTokenAdmin = getAuthToken(admin.getEmail(), password);
        getClient(authTokenAdmin).perform(get("/api/core/clarinlicenselabels"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(contentType))
                .andExpect(jsonPath("$._embedded.clarinlicenselabels", Matchers.hasItem(
                        ClarinLicenseLabelMatcher.matchClarinLicenseLabel(firstCLicenseLabel))
                ))
                .andExpect(jsonPath("$._embedded.clarinlicenselabels", Matchers.hasItem(
                        ClarinLicenseLabelMatcher.matchClarinLicenseLabel(secondCLicenseLabel))
                ))
                .andExpect(jsonPath("$._embedded.clarinlicenselabels", Matchers.hasItem(
                        ClarinLicenseLabelMatcher.matchClarinLicenseLabel(thirdCLicenseLabel))
                ))
                .andExpect(jsonPath("$._links.self.href",
                        Matchers.containsString("/api/core/clarinlicenselabels")))
        ;
    }

    @Test
    public void create() throws Exception {
        // create a new clarin license label
        context.turnOffAuthorisationSystem();
        ClarinLicenseLabel clarinLicenseLabel = ClarinLicenseLabelBuilder.createClarinLicenseLabel(context).build();
        clarinLicenseLabel.setLabel("new");
        clarinLicenseLabel.setExtended(true);
        clarinLicenseLabel.setTitle("New CLL");
        clarinLicenseLabel.setIcon(new byte[100]);

        ClarinLicenseLabelRest clarinLicenseLabelRest = clarinLicenseLabelConverter.convert(clarinLicenseLabel,
                Projection.DEFAULT);
        context.restoreAuthSystemState();

        // id of created clarin license
        AtomicReference<Integer> idRef = new AtomicReference<>();
        String authTokenAdmin = getAuthToken(admin.getEmail(), password);
        try {
            getClient(authTokenAdmin).perform(post("/api/core/clarinlicenselabels")
                            .content(objectMapper.writeValueAsBytes(clarinLicenseLabelRest))
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.label", is(clarinLicenseLabelRest.getLabel())))
                    .andExpect(jsonPath("$.title",
                            is(clarinLicenseLabelRest.getTitle())))
                    .andExpect(jsonPath("$.extended",
                            is(clarinLicenseLabelRest.isExtended())))
                    .andExpect(jsonPath("$.icon",
                            is(notNullValue())))
                    .andExpect(jsonPath("$.type",
                            is(ClarinLicenseLabelRest.NAME)))

                    .andDo(result -> idRef.set(read(result.getResponse().getContentAsString(),
                            "$.id")));
        } finally {
            if (Objects.nonNull(idRef.get())) {
                // remove created clarin license
                ClarinLicenseLabelBuilder.deleteClarinLicenseLabel(idRef.get());
            }
        }
    }

    @Test
    public void updateOk() throws Exception {
        String authTokenAdmin = getAuthToken(admin.getEmail(), password);
        Integer clarinLicenseLabelId = null;
        try {
            context.turnOffAuthorisationSystem();
            ClarinLicenseLabel clarinLicenseLabel = ClarinLicenseLabelBuilder.createClarinLicenseLabel(context).build();
            clarinLicenseLabel.setLabel("CLL");
            clarinLicenseLabel.setExtended(true);
            clarinLicenseLabel.setTitle("CLL Title4");
            clarinLicenseLabelService.update(context, clarinLicenseLabel);

            clarinLicenseLabelId = Objects.requireNonNull(clarinLicenseLabel.getID());
            context.restoreAuthSystemState();

            ClarinLicenseLabelRest clarinLicenseLabelRest = clarinLicenseLabelConverter.convert(clarinLicenseLabel,
                    Projection.DEFAULT);
            clarinLicenseLabelRest.setLabel("CLL-X");
            clarinLicenseLabelRest.setTitle("Updated CLL Title");
            clarinLicenseLabelRest.setExtended(false);

            // test if the id from the path is used instead of the id from the body
            clarinLicenseLabelRest.setId(999);

            getClient(authTokenAdmin).perform(put("/api/core/clarinlicenselabels/" + clarinLicenseLabelId)
                            .content(objectMapper.writeValueAsBytes(clarinLicenseLabelRest))
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id", is(clarinLicenseLabelId)))
                    .andExpect(jsonPath("$.label", is("CLL-X")))
                    .andExpect(jsonPath("$.title", is("Updated CLL Title")))
                    .andExpect(jsonPath("$.extended", is(false)))
                    .andExpect(jsonPath("$.icon", is(clarinLicenseLabel.getIcon())))
                    .andExpect(jsonPath("$.type", is(ClarinLicenseLabelRest.NAME)));

            getClient(authTokenAdmin).perform(get("/api/core/clarinlicenselabels/" + clarinLicenseLabelId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id", is(clarinLicenseLabelId)))
                    .andExpect(jsonPath("$.label", is("CLL-X")))
                    .andExpect(jsonPath("$.title", is("Updated CLL Title")))
                    .andExpect(jsonPath("$.extended", is(false)))
                    .andExpect(jsonPath("$.icon", is(clarinLicenseLabel.getIcon())))
                    .andExpect(jsonPath("$.type", is(ClarinLicenseLabelRest.NAME)));
        } finally {
            ClarinLicenseLabelBuilder.deleteClarinLicenseLabel(clarinLicenseLabelId);
        }
    }

    @Test
    public void updateNotFound() throws Exception {
        String authTokenAdmin = getAuthToken(admin.getEmail(), password);
        getClient(authTokenAdmin).perform(put("/api/core/clarinlicenselabels/999")
                        .content("{}")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    public void updateInvalidBody() throws Exception {
        String authTokenAdmin = getAuthToken(admin.getEmail(), password);
        getClient(authTokenAdmin).perform(put("/api/core/clarinlicenselabels/" + firstCLicenseLabel.getID())
                        .content("{\"label\": \"test label\", \"invalid_property\": 0}")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void updateMissingTitle() throws Exception {
        String authTokenAdmin = getAuthToken(admin.getEmail(), password);
        getClient(authTokenAdmin).perform(put("/api/core/clarinlicenselabels/" + firstCLicenseLabel.getID())
                        .content("{\"label\": \"test label\"}")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void updateNotAuthorized() throws Exception {
        getClient().perform(put("/api/core/clarinlicenselabels/999")
                        .content("{}")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void deleteNotAuthorized() throws Exception {
        getClient().perform(delete("/api/core/clarinlicenselabels/" + firstCLicenseLabel.getID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void deleteOk() throws Exception {
        String authTokenAdmin = getAuthToken(admin.getEmail(), password);
        getClient(authTokenAdmin).perform(delete("/api/core/clarinlicenselabels/" + firstCLicenseLabel.getID()))
                .andExpect(status().isNoContent());
    }

    @Test
    public void deleteNotFound() throws Exception {
        String authTokenAdmin = getAuthToken(admin.getEmail(), password);
        getClient(authTokenAdmin).perform(delete("/api/core/clarinlicenselabels/999"))
                .andExpect(status().isNotFound());
    }

}
