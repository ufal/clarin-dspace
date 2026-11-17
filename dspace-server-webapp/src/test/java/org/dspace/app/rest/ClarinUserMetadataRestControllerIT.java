/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest;

import static org.dspace.app.rest.repository.ClarinLicenseRestRepository.OPERATION_PATH_LICENSE_RESOURCE;
import static org.dspace.app.rest.repository.ClarinUserMetadataRestController.CHECK_EMAIL_RESPONSE_CONTENT;
import static org.dspace.content.clarin.ClarinLicense.Confirmation;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.InputStream;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dspace.app.requestitem.RequestItem;
import org.dspace.app.rest.model.ClarinUserMetadataRest;
import org.dspace.app.rest.model.patch.Operation;
import org.dspace.app.rest.model.patch.ReplaceOperation;
import org.dspace.app.rest.test.AbstractControllerIntegrationTest;
import org.dspace.authorize.AuthorizeException;
import org.dspace.builder.ClarinLicenseBuilder;
import org.dspace.builder.ClarinLicenseLabelBuilder;
import org.dspace.builder.ClarinUserMetadataBuilder;
import org.dspace.builder.ClarinUserRegistrationBuilder;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.RequestItemBuilder;
import org.dspace.builder.WorkspaceItemBuilder;
import org.dspace.content.Bitstream;
import org.dspace.content.Collection;
import org.dspace.content.Community;
import org.dspace.content.Item;
import org.dspace.content.MetadataValue;
import org.dspace.content.WorkspaceItem;
import org.dspace.content.clarin.ClarinLicense;
import org.dspace.content.clarin.ClarinLicenseLabel;
import org.dspace.content.clarin.ClarinLicenseResourceUserAllowance;
import org.dspace.content.clarin.ClarinUserMetadata;
import org.dspace.content.clarin.ClarinUserRegistration;
import org.dspace.content.service.ItemService;
import org.dspace.content.service.clarin.ClarinLicenseLabelService;
import org.dspace.content.service.clarin.ClarinLicenseService;
import org.dspace.content.service.clarin.ClarinUserMetadataService;
import org.dspace.core.Email;
import org.dspace.eperson.EPerson;
import org.dspace.services.ConfigurationService;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

public class ClarinUserMetadataRestControllerIT extends AbstractControllerIntegrationTest {

    @Autowired
    ClarinLicenseService clarinLicenseService;
    @Autowired
    ClarinLicenseLabelService clarinLicenseLabelService;
    @Autowired
    ClarinUserMetadataService clarinUserMetadataService;
    @Autowired
    ItemService itemService;
    @Autowired
    ConfigurationService configurationService;

    WorkspaceItem witem;
    WorkspaceItem witem2;
    ClarinLicense clarinLicense;
    Bitstream bitstream;
    Bitstream bitstream2;

    // Attach ClarinLicense to the Bitstream
    private void prepareEnvironment(String requiredInfo, Confirmation confirmation) throws Exception {
        // 1. Create Workspace Item with uploaded file
        // 2. Create Clarin License
        // 3. Send request to add Clarin License to the Workspace Item
        // 4. Check if the Clarin License name was added to the Item's metadata `dc.rights`
        // 5. Check if the Clarin License was attached to the Bitstream

        // 1. Create WI with uploaded file
        context.turnOffAuthorisationSystem();
        witem = this.createWorkspaceItemWithFile(false);
        witem2 = this.createWorkspaceItemWithFile(true);

        List<Operation> replaceOperations = new ArrayList<Operation>();
        String clarinLicenseName = "Test Clarin License";

        // 2. Create clarin license with clarin license label
        clarinLicense = createClarinLicense(clarinLicenseName, "Test Def", requiredInfo, confirmation);

        // creating replace operation
        Map<String, String> licenseReplaceOpValue = new HashMap<String, String>();
        licenseReplaceOpValue.put("value", clarinLicenseName);
        replaceOperations.add(new ReplaceOperation("/" + OPERATION_PATH_LICENSE_RESOURCE,
                licenseReplaceOpValue));

        context.restoreAuthSystemState();
        String updateBody = getPatchContent(replaceOperations);

        // 3. Send request to add Clarin License to the Workspace Item
        String tokenAdmin = getAuthToken(admin.getEmail(), password);
        getClient(tokenAdmin).perform(patch("/api/submission/workspaceitems/" + witem.getID())
                        .content(updateBody)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
        getClient(tokenAdmin).perform(patch("/api/submission/workspaceitems/" + witem2.getID())
                        .content(updateBody)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        // 4. Check if the Clarin License name was added to the Item's metadata `dc.rights`
        getClient(tokenAdmin).perform(get("/api/submission/workspaceitems/" + witem.getID()))
                .andExpect(status().isOk());
        getClient(tokenAdmin).perform(get("/api/submission/workspaceitems/" + witem2.getID()))
                .andExpect(status().isOk());
        witem = context.reloadEntity(witem);
        witem2 = context.reloadEntity(witem2);
        List<MetadataValue> mv1 = itemService.getMetadata(witem.getItem(), "dc", "rights", null, Item.ANY);
        List<MetadataValue> mv2 = itemService.getMetadata(witem2.getItem(), "dc", "rights", null, Item.ANY);
        assertThat(mv1.size(), is(1));
        assertThat(mv1.get(0).getValue(), is(clarinLicenseName));
        assertThat(mv2.size(), is(1));
        assertThat(mv2.get(0).getValue(), is(clarinLicenseName));

        // 5. Check if the Clarin License was attached to the Bitstream
        getClient(tokenAdmin).perform(get("/api/core/clarinlicenses/" + clarinLicense.getID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bitstreams", is(2)));
    }

    @Test
    public void notAuthorizedUser_withLicenseConfirmation_ALLOW_ANONYMOUS_USER() throws Exception {
        this.prepareEnvironment("NAME", Confirmation.ALLOW_ANONYMOUS);
        ObjectMapper mapper = new ObjectMapper();
        ClarinUserMetadataRest clarinUserMetadata1 = new ClarinUserMetadataRest();
        clarinUserMetadata1.setMetadataKey("NAME");
        clarinUserMetadata1.setMetadataValue("Test");

        ClarinUserMetadataRest clarinUserMetadata2 = new ClarinUserMetadataRest();
        clarinUserMetadata2.setMetadataKey("NAME");
        clarinUserMetadata2.setMetadataValue("Test2");

        List<ClarinUserMetadataRest> clarinUserMetadataRestList = new ArrayList<>();
        clarinUserMetadataRestList.add(clarinUserMetadata1);
        clarinUserMetadataRestList.add(clarinUserMetadata2);

        String adminToken = getAuthToken(admin.getEmail(), password);
        // Load bitstream from the item.
        getClient().perform(post("/api/core/clarinusermetadata/manage?bitstreamUUID=" + bitstream.getID())
                        .content(mapper.writeValueAsBytes(clarinUserMetadataRestList.toArray()))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", notNullValue()))
                .andExpect(jsonPath("$", not(CHECK_EMAIL_RESPONSE_CONTENT)));

        // Get created CLRUA
        getClient(adminToken).perform(get("/api/core/clarinlruallowances")
                        .contentType(contentType))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements", is(1)));
    }

    @Test
    public void notAuthorizedUser_withLicenseConfirmation_NOT_REQUIRED() throws Exception {
        requestTokenForUnauthorizedUser(Confirmation.NOT_REQUIRED);
    }

    @Test
    public void notAuthorizedUser_withLicenseConfirmation_ASK_ONLY_ONCE() throws Exception {
        requestTokenForUnauthorizedUser(Confirmation.ASK_ONLY_ONCE);
    }

    @Test
    public void notAuthorizedUser_withLicenseConfirmation_ASK_ALWAYS() throws Exception {
        requestTokenForUnauthorizedUser(Confirmation.ASK_ALWAYS);
    }

    @Test
    public void notAuthorizedUser_withAllowingAnonymousLicense_shouldSendEmail() throws Exception {
        this.prepareEnvironment("SEND_TOKEN", Confirmation.ALLOW_ANONYMOUS);
        ObjectMapper mapper = new ObjectMapper();
        ClarinUserMetadataRest clarinUserMetadata1 = new ClarinUserMetadataRest();
        clarinUserMetadata1.setMetadataKey("NAME");
        clarinUserMetadata1.setMetadataValue("Test");

        ClarinUserMetadataRest clarinUserMetadata2 = new ClarinUserMetadataRest();
        clarinUserMetadata2.setMetadataKey("NAME");
        clarinUserMetadata2.setMetadataValue("Test2");

        ClarinUserMetadataRest clarinUserMetadata3 = new ClarinUserMetadataRest();
        clarinUserMetadata3.setMetadataKey("SEND_TOKEN");

        ClarinUserMetadataRest clarinUserMetadata4 = new ClarinUserMetadataRest();
        clarinUserMetadata4.setMetadataKey("EXTRA_EMAIL");
        clarinUserMetadata4.setMetadataValue("test@test.edu");

        List<ClarinUserMetadataRest> clarinUserMetadataRestList = new ArrayList<>();
        clarinUserMetadataRestList.add(clarinUserMetadata1);
        clarinUserMetadataRestList.add(clarinUserMetadata2);
        clarinUserMetadataRestList.add(clarinUserMetadata3);
        clarinUserMetadataRestList.add(clarinUserMetadata4);

        String adminToken = getAuthToken(admin.getEmail(), password);
        // Load bitstream from the item.
        getClient().perform(post("/api/core/clarinusermetadata/manage?bitstreamUUID=" + bitstream.getID())
                        .content(mapper.writeValueAsBytes(clarinUserMetadataRestList.toArray()))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", notNullValue()))
                .andExpect(jsonPath("$", is(CHECK_EMAIL_RESPONSE_CONTENT)));

        // Get created CLRUA
        getClient(adminToken).perform(get("/api/core/clarinlruallowances")
                        .contentType(contentType))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements", is(1)));
    }

    /**
     * The e-mailed download link keeps a valid request-a-copy access token of the item the UI sent with the user
     * metadata.
     */
    @Test
    public void emailedDownloadLinkCarriesTheRequestACopyAccessToken() throws Exception {
        this.prepareEnvironment("SEND_TOKEN", Confirmation.ALLOW_ANONYMOUS);
        RequestItem request = acceptedAllFilesRequestFor(witem.getItem(), "a+b");

        List<Object> arguments = mailArgumentsForAccessToken(request.getAccess_token());

        String linkPattern = ".*/bitstreams/" + bitstream.getID() + "/download\\?dtoken=[^&]+&accessToken=a%2Bb";
        assertTrue("No e-mail argument is the download link with both tokens: " + arguments,
                arguments.stream().anyMatch(argument -> String.valueOf(argument).matches(linkPattern)));
        RequestItemBuilder.deleteRequestItem(request.getToken());
    }

    /**
     * An unknown access token, or a valid one of another item, does not go into the e-mailed download link.
     */
    @Test
    public void emailedDownloadLinkLeavesOutAnAccessTokenThatIsNotValidForTheFile() throws Exception {
        this.prepareEnvironment("SEND_TOKEN", Confirmation.ALLOW_ANONYMOUS);
        RequestItem otherItemRequest = acceptedAllFilesRequestFor(witem2.getItem(), "other-item-token");
        String plainLinkPattern = ".*/bitstreams/" + bitstream.getID() + "/download\\?dtoken=[^&]+";

        for (String accessToken : List.of("unknown-token", otherItemRequest.getAccess_token())) {
            List<Object> arguments = mailArgumentsForAccessToken(accessToken);

            assertTrue("No e-mail argument is the plain download link for " + accessToken + ": " + arguments,
                    arguments.stream().anyMatch(argument -> String.valueOf(argument).matches(plainLinkPattern)));
            assertTrue("The access token " + accessToken + " went into the e-mail: " + arguments,
                    arguments.stream().noneMatch(argument -> String.valueOf(argument).contains("accessToken")));
        }
        RequestItemBuilder.deleteRequestItem(otherItemRequest.getToken());
    }

    /**
     * With request-a-copy switched off ({@code request.item.type} not set) a valid access token does not go into
     * the e-mailed download link. Once the key is back, it does.
     */
    @Test
    public void emailedDownloadLinkLeavesOutTheAccessTokenWhenRequestACopyIsOff() throws Exception {
        this.prepareEnvironment("SEND_TOKEN", Confirmation.ALLOW_ANONYMOUS);
        RequestItem request = acceptedAllFilesRequestFor(witem.getItem(), "request-copy-off-token");
        String requestItemType = configurationService.getProperty("request.item.type");

        configurationService.setProperty("request.item.type", null);
        List<Object> arguments;
        try {
            arguments = mailArgumentsForAccessToken(request.getAccess_token());
        } finally {
            configurationService.setProperty("request.item.type", requestItemType);
        }
        String plainLinkPattern = ".*/bitstreams/" + bitstream.getID() + "/download\\?dtoken=[^&]+";
        assertTrue("No e-mail argument is the plain download link: " + arguments,
                arguments.stream().anyMatch(argument -> String.valueOf(argument).matches(plainLinkPattern)));
        assertTrue("The access token went into the e-mail: " + arguments,
                arguments.stream().noneMatch(argument -> String.valueOf(argument).contains("accessToken")));

        arguments = mailArgumentsForAccessToken(request.getAccess_token());
        String linkWithTokenPattern = plainLinkPattern + "&accessToken=request-copy-off-token";
        assertTrue("No e-mail argument is the download link with the access token: " + arguments,
                arguments.stream().anyMatch(argument -> String.valueOf(argument).matches(linkWithTokenPattern)));
        RequestItemBuilder.deleteRequestItem(request.getToken());
    }

    /**
     * Mints an accepted, unexpired request-a-copy access token for all files of an item.
     */
    private RequestItem acceptedAllFilesRequestFor(Item requested, String accessToken) throws Exception {
        context.turnOffAuthorisationSystem();
        RequestItem requestItem = RequestItemBuilder.createRequestItem(context, context.reloadEntity(requested), null)
                .withAllFiles(true)
                .withAcceptRequest(true)
                .withDecisionDate(Instant.now())
                .withAccessToken(accessToken)
                .withAccessExpiry(Instant.now().plus(1, ChronoUnit.DAYS))
                .build();
        context.restoreAuthSystemState();
        context.commit();
        return requestItem;
    }

    /**
     * Posts the user metadata of a SEND_TOKEN licence for the bitstream with the access token and returns the
     * arguments of the e-mails it sent.
     */
    private List<Object> mailArgumentsForAccessToken(String accessToken) throws Exception {
        ClarinUserMetadataRest sendToken = new ClarinUserMetadataRest();
        sendToken.setMetadataKey("SEND_TOKEN");
        ClarinUserMetadataRest extraEmail = new ClarinUserMetadataRest();
        extraEmail.setMetadataKey("EXTRA_EMAIL");
        extraEmail.setMetadataValue("test@test.edu");

        Email email = Mockito.spy(Email.class);
        doNothing().when(email).send();
        try (MockedStatic<Email> emailMock = Mockito.mockStatic(Email.class)) {
            emailMock.when(() -> Email.getEmail(any())).thenReturn(email);

            getClient().perform(post("/api/core/clarinusermetadata/manage?bitstreamUUID=" + bitstream.getID())
                            .param("accessToken", accessToken)
                            .content(new ObjectMapper().writeValueAsBytes(
                                    new ClarinUserMetadataRest[] {sendToken, extraEmail}))
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", is(CHECK_EMAIL_RESPONSE_CONTENT)));
        }

        ArgumentCaptor<Object> arguments = ArgumentCaptor.forClass(Object.class);
        verify(email, atLeastOnce()).addArgument(arguments.capture());
        return arguments.getAllValues();
    }

    @Test
    public void authorizedUserWithoutMetadata_shouldReturnToken() throws Exception {
        this.prepareEnvironment("NAME", Confirmation.NOT_REQUIRED);
        context.turnOffAuthorisationSystem();
        ClarinUserRegistration clarinUserRegistration = ClarinUserRegistrationBuilder
                .createClarinUserRegistration(context).withEPersonID(admin.getID()).build();
        context.restoreAuthSystemState();
        ObjectMapper mapper = new ObjectMapper();
        ClarinUserMetadataRest clarinUserMetadata1 = new ClarinUserMetadataRest();
        clarinUserMetadata1.setMetadataKey("NAME");
        clarinUserMetadata1.setMetadataValue("Test");

        ClarinUserMetadataRest clarinUserMetadata2 = new ClarinUserMetadataRest();
        clarinUserMetadata2.setMetadataKey("ADDRESS");
        clarinUserMetadata2.setMetadataValue("Test2");

        List<ClarinUserMetadataRest> clarinUserMetadataRestList = new ArrayList<>();
        clarinUserMetadataRestList.add(clarinUserMetadata1);
        clarinUserMetadataRestList.add(clarinUserMetadata2);

        String adminToken = getAuthToken(admin.getEmail(), password);

        // There should exist record in the UserRegistration table
        getClient(adminToken).perform(get("/api/core/clarinuserregistrations")
                        .contentType(contentType))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements", is(1)));

        // Manage UserMetadata and get token
        getClient(adminToken).perform(post("/api/core/clarinusermetadata/manage?bitstreamUUID=" + bitstream.getID())
                        .content(mapper.writeValueAsBytes(clarinUserMetadataRestList.toArray()))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", notNullValue()))
                .andExpect(jsonPath("$", not(CHECK_EMAIL_RESPONSE_CONTENT)));

        // Get created CLRUA
        getClient(adminToken).perform(get("/api/core/clarinlruallowances")
                        .contentType(contentType))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements", is(1)));

        ClarinUserMetadataBuilder.deleteClarinUserMetadata(clarinUserRegistration.getID());
    }

    @Test
    public void authorizedUserWithoutMetadata_shouldSendEmail() throws Exception {
        this.prepareEnvironment("SEND_TOKEN", Confirmation.NOT_REQUIRED);
        context.turnOffAuthorisationSystem();
        ClarinUserRegistration clarinUserRegistration = ClarinUserRegistrationBuilder
                .createClarinUserRegistration(context).withEPersonID(admin.getID()).build();
        context.restoreAuthSystemState();
        ObjectMapper mapper = new ObjectMapper();
        ClarinUserMetadataRest clarinUserMetadata1 = new ClarinUserMetadataRest();
        clarinUserMetadata1.setMetadataKey("NAME");
        clarinUserMetadata1.setMetadataValue("Test");

        ClarinUserMetadataRest clarinUserMetadata2 = new ClarinUserMetadataRest();
        clarinUserMetadata2.setMetadataKey("ADDRESS");
        clarinUserMetadata2.setMetadataValue("Test2");

        ClarinUserMetadataRest clarinUserMetadata3 = new ClarinUserMetadataRest();
        clarinUserMetadata3.setMetadataKey("SEND_TOKEN");

        ClarinUserMetadataRest clarinUserMetadata4 = new ClarinUserMetadataRest();
        clarinUserMetadata4.setMetadataKey("EXTRA_EMAIL");
        clarinUserMetadata4.setMetadataValue("test@test.edu");

        List<ClarinUserMetadataRest> clarinUserMetadataRestList = new ArrayList<>();
        clarinUserMetadataRestList.add(clarinUserMetadata1);
        clarinUserMetadataRestList.add(clarinUserMetadata2);
        clarinUserMetadataRestList.add(clarinUserMetadata3);
        clarinUserMetadataRestList.add(clarinUserMetadata4);

        String adminToken = getAuthToken(admin.getEmail(), password);

        // There should exist record in the UserRegistration table
        getClient(adminToken).perform(get("/api/core/clarinuserregistrations")
                        .contentType(contentType))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements", is(1)));

        // Manage UserMetadata and get token
        getClient(adminToken).perform(post("/api/core/clarinusermetadata/manage?bitstreamUUID=" + bitstream.getID())
                        .content(mapper.writeValueAsBytes(clarinUserMetadataRestList.toArray()))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", notNullValue()))
                .andExpect(jsonPath("$", is(CHECK_EMAIL_RESPONSE_CONTENT)));

        // Get created CLRUA
        getClient(adminToken).perform(get("/api/core/clarinlruallowances")
                        .contentType(contentType))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements", is(1)));

        ClarinUserMetadataBuilder.deleteClarinUserMetadata(clarinUserRegistration.getID());
    }

    @Test
    public void authorizedUserWithMetadata_shouldSendToken() throws Exception {
        this.prepareEnvironment("NAME,ADDRESS", Confirmation.NOT_REQUIRED);
        context.turnOffAuthorisationSystem();
        ClarinUserRegistration clarinUserRegistration = ClarinUserRegistrationBuilder
                .createClarinUserRegistration(context).withEPersonID(admin.getID()).build();
        ClarinUserMetadataBuilder.createClarinUserMetadata(context)
                .withUserRegistration(clarinUserRegistration)
                .build();
        context.restoreAuthSystemState();

        ObjectMapper mapper = new ObjectMapper();
        ClarinUserMetadataRest clarinUserMetadata1 = new ClarinUserMetadataRest();
        clarinUserMetadata1.setMetadataKey("NAME");
        clarinUserMetadata1.setMetadataValue("Test");

        ClarinUserMetadataRest clarinUserMetadata2 = new ClarinUserMetadataRest();
        clarinUserMetadata2.setMetadataKey("ADDRESS");
        clarinUserMetadata2.setMetadataValue("Test2");

        List<ClarinUserMetadataRest> clarinUserMetadataRestList = new ArrayList<>();
        clarinUserMetadataRestList.add(clarinUserMetadata1);
        clarinUserMetadataRestList.add(clarinUserMetadata2);

        String adminToken = getAuthToken(admin.getEmail(), password);

        // There should exist record in the UserRegistration table
        getClient(adminToken).perform(get("/api/core/clarinuserregistrations")
                        .contentType(contentType))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements", is(1)));

        // Manage UserMetadata and get token
        getClient(adminToken).perform(post("/api/core/clarinusermetadata/manage?bitstreamUUID=" + bitstream.getID())
                        .content(mapper.writeValueAsBytes(clarinUserMetadataRestList.toArray()))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", notNullValue()))
                .andExpect(jsonPath("$", not(CHECK_EMAIL_RESPONSE_CONTENT)));

        // Get created CLRUA
        getClient(adminToken).perform(get("/api/core/clarinlruallowances")
                        .contentType(contentType))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements", is(1)));

        ClarinUserMetadataBuilder.deleteClarinUserMetadata(clarinUserRegistration.getID());
    }

    @Test
    public void authorizedUserWithMetadata_shouldSendEmail() throws Exception {
        this.prepareEnvironment("SEND_TOKEN,NAME,ADDRESS", Confirmation.NOT_REQUIRED);
        context.turnOffAuthorisationSystem();
        ClarinUserRegistration clarinUserRegistration = ClarinUserRegistrationBuilder
                .createClarinUserRegistration(context).withEPersonID(admin.getID()).build();
        ClarinUserMetadataBuilder.createClarinUserMetadata(context)
                .withUserRegistration(clarinUserRegistration)
                .build();
        context.restoreAuthSystemState();

        ObjectMapper mapper = new ObjectMapper();
        ClarinUserMetadataRest clarinUserMetadata1 = new ClarinUserMetadataRest();
        clarinUserMetadata1.setMetadataKey("NAME");
        clarinUserMetadata1.setMetadataValue("Test");

        ClarinUserMetadataRest clarinUserMetadata2 = new ClarinUserMetadataRest();
        clarinUserMetadata2.setMetadataKey("ADDRESS");
        clarinUserMetadata2.setMetadataValue("Test2");

        ClarinUserMetadataRest clarinUserMetadata3 = new ClarinUserMetadataRest();
        clarinUserMetadata3.setMetadataKey("SEND_TOKEN");

        ClarinUserMetadataRest clarinUserMetadata4 = new ClarinUserMetadataRest();
        clarinUserMetadata4.setMetadataKey("EXTRA_EMAIL");
        clarinUserMetadata4.setMetadataValue("test@test.edu");

        List<ClarinUserMetadataRest> clarinUserMetadataRestList = new ArrayList<>();
        clarinUserMetadataRestList.add(clarinUserMetadata1);
        clarinUserMetadataRestList.add(clarinUserMetadata2);
        clarinUserMetadataRestList.add(clarinUserMetadata3);
        clarinUserMetadataRestList.add(clarinUserMetadata4);

        String adminToken = getAuthToken(admin.getEmail(), password);

        // There should exist record in the UserRegistration table
        getClient(adminToken).perform(get("/api/core/clarinuserregistrations")
                        .contentType(contentType))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements", is(1)));

        // Manage UserMetadata and get token
        getClient(adminToken).perform(post("/api/core/clarinusermetadata/manage?bitstreamUUID=" + bitstream.getID())
                        .content(mapper.writeValueAsBytes(clarinUserMetadataRestList.toArray()))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", notNullValue()))
                .andExpect(jsonPath("$", is(CHECK_EMAIL_RESPONSE_CONTENT)));

        // Get created CLRUA
        getClient(adminToken).perform(get("/api/core/clarinlruallowances")
                        .contentType(contentType))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements", is(1)));

        ClarinUserMetadataBuilder.deleteClarinUserMetadata(clarinUserRegistration.getID());
    }

    // Confirmation = 1
    @Test
    public void authorizedUserWithoutMetadata_shouldDownloadToken() throws Exception {
        this.prepareEnvironment(null, Confirmation.ASK_ONLY_ONCE);
        context.turnOffAuthorisationSystem();
        ClarinUserRegistration clarinUserRegistration = ClarinUserRegistrationBuilder
                .createClarinUserRegistration(context).withEPersonID(admin.getID()).build();
        context.restoreAuthSystemState();
        ObjectMapper mapper = new ObjectMapper();

        String adminToken = getAuthToken(admin.getEmail(), password);

        // There should exist record in the UserRegistration table
        getClient(adminToken).perform(get("/api/core/clarinuserregistrations")
                        .contentType(contentType))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements", is(1)));

        // Manage UserMetadata and get token
        getClient(adminToken).perform(post("/api/core/clarinusermetadata/manage?bitstreamUUID=" + bitstream.getID())
                        .content(mapper.writeValueAsBytes(new ArrayList<ClarinUserMetadataRest>(0)))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", notNullValue()))
                .andExpect(jsonPath("$", not(CHECK_EMAIL_RESPONSE_CONTENT)));

        // Get created CLRUA
        getClient(adminToken).perform(get("/api/core/clarinlruallowances")
                        .contentType(contentType))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements", is(1)));
    }

    @Test
    public void shouldNotCreateDuplicateUserMetadataBasedOnHistory() throws Exception {
        // Prepare environment with Clarin License, resource mapping, allowance, user registration and user metadata
        // then try to download the same bitstream again and the user metadata should not be created based on history
        this.prepareEnvironment("NAME,ADDRESS", Confirmation.NOT_REQUIRED);
        context.turnOffAuthorisationSystem();
        ClarinUserRegistration clarinUserRegistration = ClarinUserRegistrationBuilder
                .createClarinUserRegistration(context).withEPersonID(admin.getID()).build();
        context.restoreAuthSystemState();

        ObjectMapper mapper = new ObjectMapper();
        ClarinUserMetadataRest clarinUserMetadata1 = new ClarinUserMetadataRest();
        clarinUserMetadata1.setMetadataKey("NAME");
        clarinUserMetadata1.setMetadataValue("Test");

        ClarinUserMetadataRest clarinUserMetadata2 = new ClarinUserMetadataRest();
        clarinUserMetadata2.setMetadataKey("ADDRESS");
        clarinUserMetadata2.setMetadataValue("Test2");

        List<ClarinUserMetadataRest> clarinUserMetadataRestList = new ArrayList<>();
        clarinUserMetadataRestList.add(clarinUserMetadata1);
        clarinUserMetadataRestList.add(clarinUserMetadata2);

        String adminToken = getAuthToken(admin.getEmail(), password);

        // There should exist record in the UserRegistration table
        getClient(adminToken).perform(get("/api/core/clarinuserregistrations")
                        .contentType(contentType))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements", is(1)));

        // Manage UserMetadata and get token
        getClient(adminToken).perform(post("/api/core/clarinusermetadata/manage?bitstreamUUID=" + bitstream.getID())
                        .content(mapper.writeValueAsBytes(clarinUserMetadataRestList.toArray()))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", notNullValue()))
                .andExpect(jsonPath("$", not(CHECK_EMAIL_RESPONSE_CONTENT)));

        // Get created CLRUA
        getClient(adminToken).perform(get("/api/core/clarinlruallowances")
                        .contentType(contentType))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements", is(1)));


        // Get created User Metadata - there should be 2 records
        getClient(adminToken).perform(get("/api/core/clarinusermetadatas")
                        .contentType(contentType))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements", is(2)));

        // Second download

        // Manage UserMetadata and get token
        getClient(adminToken).perform(post("/api/core/clarinusermetadata/manage?bitstreamUUID=" + bitstream2.getID())
                        .content(mapper.writeValueAsBytes(clarinUserMetadataRestList.toArray()))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", notNullValue()))
                .andExpect(jsonPath("$", not(CHECK_EMAIL_RESPONSE_CONTENT)));

        // Get created two CLRUA
        getClient(adminToken).perform(get("/api/core/clarinlruallowances")
                        .contentType(contentType))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements", is(2)));

        // Get created User Metadata - there should be 4 records
        getClient(adminToken).perform(get("/api/core/clarinusermetadatas")
                        .contentType(contentType))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements", is(4)));

        // The User Metadata should not have updated transaction ID after a new download - test for fixed issue
        EPerson currentUser = context.getCurrentUser();
        context.setCurrentUser(admin);
        List<ClarinUserMetadata> allUserMetadata = clarinUserMetadataService.findAll(context);
        context.setCurrentUser(currentUser);
        ClarinLicenseResourceUserAllowance clrua1 = allUserMetadata.get(0).getTransaction();
        ClarinLicenseResourceUserAllowance clrua2 = allUserMetadata.get(3).getTransaction();
        assertThat(clrua1.getID(), not(clrua2.getID()));

        // Check that the user registration for test data full user has been created
        // Test /api/core/clarinusermetadatas search by userRegistrationAndBitstream endpoint
        getClient(adminToken).perform(get("/api/core/clarinusermetadatas/search/byUserRegistrationAndBitstream")
                .param("userRegUUID", String.valueOf(clarinUserRegistration.getID()))
                .param("bitstreamUUID", String.valueOf(bitstream2.getID()))
                .contentType(contentType))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page.totalElements", is(2)));

        // Download again the second bitstream and the user metadata should be returned only from the last transaction

        // Create a new User Metadata
        ClarinUserMetadataRest clarinUserMetadata3 = new ClarinUserMetadataRest();
        clarinUserMetadata3.setMetadataKey("NAME");
        clarinUserMetadata3.setMetadataValue("New Test");

        ClarinUserMetadataRest clarinUserMetadata4 = new ClarinUserMetadataRest();
        clarinUserMetadata4.setMetadataKey("ADDRESS");
        clarinUserMetadata4.setMetadataValue("New Test");

        List<ClarinUserMetadataRest> newUserMetadataRestList = new ArrayList<>();
        newUserMetadataRestList.add(clarinUserMetadata3);
        newUserMetadataRestList.add(clarinUserMetadata4);

        // Manage UserMetadata and get token
        getClient(adminToken).perform(post("/api/core/clarinusermetadata/manage?bitstreamUUID=" + bitstream2.getID())
                        .content(mapper.writeValueAsBytes(newUserMetadataRestList.toArray()))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", notNullValue()))
                .andExpect(jsonPath("$", not(CHECK_EMAIL_RESPONSE_CONTENT)));

        // Get created two CLRUA
        getClient(adminToken).perform(get("/api/core/clarinlruallowances")
                        .contentType(contentType))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements", is(3)));

        // Get created User Metadata from the new transaction - there should be 2 records
        getClient(adminToken).perform(get("/api/core/clarinusermetadatas/search/byUserRegistrationAndBitstream")
                        .param("userRegUUID", String.valueOf(clarinUserRegistration.getID()))
                        .param("bitstreamUUID", String.valueOf(bitstream2.getID()))
                        .contentType(contentType))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements", is(2)));

        // Delete all created user metadata - clean test environment
        ClarinUserMetadataBuilder.deleteClarinUserMetadata(clarinUserRegistration.getID());
    }

    private WorkspaceItem createWorkspaceItemWithFile(boolean secondBitstream) {
        parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Parent Community")
                .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                .withName("Sub Community")
                .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1)
                .withName("Collection 1")
                .build();

        context.setCurrentUser(eperson);
        InputStream pdf = getClass().getResourceAsStream("simple-article.pdf");

        WorkspaceItem witem = WorkspaceItemBuilder.createWorkspaceItem(context, col1)
                .withTitle("Test WorkspaceItem")
                .withIssueDate("2017-10-17")
                .withFulltext("simple-article.pdf", "/local/path/simple-article.pdf", pdf)
                .build();

        if (secondBitstream) {
            this.bitstream2 = witem.getItem().getBundles().get(0).getBitstreams().get(0);
        } else {
            this.bitstream = witem.getItem().getBundles().get(0).getBitstreams().get(0);
        }

        return witem;
    }

    /**
     * Create Clarin License Label object for testing purposes.
     */
    private ClarinLicenseLabel createClarinLicenseLabel(String label, boolean extended, String title)
            throws SQLException, AuthorizeException {
        ClarinLicenseLabel clarinLicenseLabel = ClarinLicenseLabelBuilder.createClarinLicenseLabel(context).build();
        clarinLicenseLabel.setLabel(label);
        clarinLicenseLabel.setExtended(extended);
        clarinLicenseLabel.setTitle(title);

        clarinLicenseLabelService.update(context, clarinLicenseLabel);
        return clarinLicenseLabel;
    }


    /**
     * Create ClarinLicense object with ClarinLicenseLabel object for testing purposes.
     */
    private ClarinLicense createClarinLicense(String name, String definition, String requiredInfo,
                                              Confirmation confirmation)
            throws SQLException, AuthorizeException {
        ClarinLicense clarinLicense = ClarinLicenseBuilder.createClarinLicense(context).build();
        clarinLicense.setConfirmation(confirmation);
        clarinLicense.setDefinition(definition);
        clarinLicense.setRequiredInfo(requiredInfo);
        clarinLicense.setName(name);

        // Add ClarinLicenseLabels to the ClarinLicense
        HashSet<ClarinLicenseLabel> clarinLicenseLabels = new HashSet<>();
        ClarinLicenseLabel clarinLicenseLabel = createClarinLicenseLabel("lbl", false, "Test Title");
        clarinLicenseLabels.add(clarinLicenseLabel);
        clarinLicense.setLicenseLabels(clarinLicenseLabels);

        clarinLicenseService.update(context, clarinLicense);
        return clarinLicense;
    }

    private void requestTokenForUnauthorizedUser(Confirmation licenseConfirmation) throws Exception {
        this.prepareEnvironment("NAME", licenseConfirmation);

        ObjectMapper mapper = new ObjectMapper();
        ClarinUserMetadataRest clarinUserMetadata = new ClarinUserMetadataRest();
        clarinUserMetadata.setMetadataKey("NAME");
        clarinUserMetadata.setMetadataValue("Test");

        List<ClarinUserMetadataRest> clarinUserMetadataRestList = new ArrayList<>();
        clarinUserMetadataRestList.add(clarinUserMetadata);

        getClient().perform(post("/api/core/clarinusermetadata/manage?bitstreamUUID=" + bitstream.getID())
                        .content(mapper.writeValueAsBytes(clarinUserMetadataRestList.toArray()))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

}
