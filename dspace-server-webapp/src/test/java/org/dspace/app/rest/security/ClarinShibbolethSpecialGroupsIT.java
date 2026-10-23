/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.security;

import static org.junit.Assert.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.InputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.codec.CharEncoding;
import org.apache.commons.io.IOUtils;
import org.dspace.app.rest.test.AbstractControllerIntegrationTest;
import org.dspace.app.util.Util;
import org.dspace.builder.BitstreamBuilder;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.EPersonBuilder;
import org.dspace.builder.GroupBuilder;
import org.dspace.builder.ItemBuilder;
import org.dspace.content.Bitstream;
import org.dspace.content.Collection;
import org.dspace.content.Community;
import org.dspace.content.Item;
import org.dspace.core.I18nUtil;
import org.dspace.eperson.EPerson;
import org.dspace.eperson.Group;
import org.dspace.services.ConfigurationService;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Integration test verifying that the Shibboleth special groups (e.g. the default `Authenticated` group)
 * survive into tokens which are minted on stateless REST requests after the login:
 * the short-lived token used for bitstream downloads and the refreshed login token.
 *
 * Replicates https://github.com/dataquest-dev/DSpace/issues/900 - a bitstream restricted to the
 * `Authenticated` group is visible after the Shibboleth login, but its download returns 403,
 * because the special groups are lost when the short-lived token is generated
 * (see ufal/clarin-dspace#1373).
 *
 * @author Milan Majchrak (milan.majchrak at dataquest.sk)
 */
public class ClarinShibbolethSpecialGroupsIT extends AbstractControllerIntegrationTest {

    public static final String[] SHIB_ONLY = {"org.dspace.authenticate.clarin.ClarinShibAuthentication"};
    private static final String NET_ID_TEST_EPERSON = "123456789";
    private static final String IDP_TEST_EPERSON = "Test Idp";

    private EPerson clarinEperson;
    private Bitstream restrictedBitstream;

    @Autowired
    ConfigurationService configurationService;

    @Before
    public void setup() throws Exception {
        super.setUp();

        // Enable Shibboleth login for all tests
        configurationService.setProperty("plugin.sequence.org.dspace.authenticate.AuthenticationMethod", SHIB_ONLY);

        context.turnOffAuthorisationSystem();

        // Create an eperson with netID - that means the user already exists in the database
        clarinEperson = EPersonBuilder.createEPerson(context)
                .withCanLogin(false)
                .withEmail("clarin@email.com")
                .withNameInMetadata("first", "last")
                .withLanguage(I18nUtil.getDefaultLocale().getLanguage())
                .withNetId(Util.formatNetId(NET_ID_TEST_EPERSON, IDP_TEST_EPERSON))
                .build();

        // The group every shibboleth-authenticated user is implicitly added to (as a special group)
        String defaultGroupName = configurationService.getProperty("authentication-shibboleth.default.auth.group");
        Group authenticatedGroup = GroupBuilder.createGroup(context)
                .withName(defaultGroupName)
                .build();

        // A bitstream readable only by the shibboleth default special group
        Community community = CommunityBuilder.createCommunity(context)
                .withName("Community")
                .build();
        Collection collection = CollectionBuilder.createCollection(context, community)
                .withName("Collection")
                .build();
        Item item = ItemBuilder.createItem(context, collection)
                .withTitle("Item with a restricted bitstream")
                .build();
        try (InputStream is = IOUtils.toInputStream("Restricted content", CharEncoding.UTF_8)) {
            restrictedBitstream = BitstreamBuilder.createBitstream(context, item, is)
                    .withName("restricted.txt")
                    .withMimeType("text/plain")
                    .withReaderGroup(authenticatedGroup)
                    .build();
        }

        context.restoreAuthSystemState();
    }

    /**
     * Replication of the issue #900:
     * 1. Sign in via Shibboleth - the user is implicitly added into the `Authenticated` special group.
     * 2. The bitstream restricted to the `Authenticated` group is readable with the login token.
     * 3. The UI downloads the bitstream with a short-lived token minted on a separate stateless request
     *    - the download must succeed too.
     */
    @Test
    public void shouldDownloadRestrictedBitstreamWithShortLivedTokenAfterShibLogin() throws Exception {
        String loginToken = shibLogin();

        // Sanity check: the login token keeps the special groups (its `sg` claim was computed
        // during the shibboleth login request), so the restricted bitstream is readable.
        getClient(loginToken).perform(get("/api/core/bitstreams/" + restrictedBitstream.getID() + "/content"))
                .andExpect(status().isOk());

        // The short-lived token is minted on a stateless request - the special groups must be
        // obtained from the user context (restored from the login token), not from the session.
        String shortLivedToken = getShortLivedToken(loginToken);
        getClient().perform(get("/api/core/bitstreams/" + restrictedBitstream.getID()
                        + "/content?authentication-token=" + shortLivedToken))
                .andExpect(status().isOk());
    }

    /**
     * The refreshed login token (POST /api/authn/login with the Bearer token, no shibboleth headers)
     * must keep the special groups too, otherwise the user loses the access after the first token refresh
     * (see ufal/clarin-dspace#1373).
     */
    @Test
    public void shouldKeepSpecialGroupsAfterLoginTokenRefresh() throws Exception {
        String loginToken = shibLogin();

        // Sanity check: the restricted bitstream is readable with the login token
        getClient(loginToken).perform(get("/api/core/bitstreams/" + restrictedBitstream.getID() + "/content"))
                .andExpect(status().isOk());

        // Refresh the login token on a stateless request (no shibboleth session/headers)
        String refreshedAuthHeader = getClient(loginToken).perform(post("/api/authn/login"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader(AUTHORIZATION_HEADER);
        assertNotNull("The token refresh must return the Authorization header", refreshedAuthHeader);
        String refreshedToken = refreshedAuthHeader.replace(AUTHORIZATION_TYPE, "");

        // The restricted bitstream must still be readable with the refreshed token
        getClient(refreshedToken).perform(get("/api/core/bitstreams/" + restrictedBitstream.getID() + "/content"))
                .andExpect(status().isOk());
    }

    private String shibLogin() throws Exception {
        String authHeader = getClient().perform(get("/api/authn/shibboleth")
                        .header("SHIB-MAIL", clarinEperson.getEmail())
                        .header("Shib-Identity-Provider", IDP_TEST_EPERSON)
                        .header("SHIB-NETID", NET_ID_TEST_EPERSON))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getHeader(AUTHORIZATION_HEADER);
        assertNotNull("The shibboleth login must return the Authorization header", authHeader);
        return authHeader.replace(AUTHORIZATION_TYPE, "");
    }

    private String getShortLivedToken(String loginToken) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        MvcResult mvcResult = getClient(loginToken).perform(post("/api/authn/shortlivedtokens"))
                .andExpect(status().isOk())
                .andReturn();
        String content = mvcResult.getResponse().getContentAsString();
        JsonNode token = mapper.readTree(content).get("token");
        assertNotNull("The shortlivedtokens response must contain the token field", token);
        return token.asText();
    }
}
