/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest;

import static com.jayway.jsonpath.matchers.JsonPathMatchers.hasJsonPath;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.dspace.app.rest.test.AbstractControllerIntegrationTest;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.ItemBuilder;
import org.dspace.content.Collection;
import org.dspace.content.Community;
import org.dspace.content.Item;
import org.dspace.content.clarin.MatomoReportSubscription;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;

public class MatomoReportSubscriptionRestRepositoryIT extends AbstractControllerIntegrationTest {

    private static final String URL_PREFIX = "/api/core/matomoreportsubscriptions";

    private Item publicItem1;
    private Item publicItem2;

    @Before
    public void setup() throws Exception {
        context.turnOffAuthorisationSystem();

        //** GIVEN **
        //1. A community-collection structure with one parent community with sub-community and one collections.
        parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Parent Community")
                .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                .withName("Sub Community")
                .build();
        Collection col = CollectionBuilder.createCollection(context, child1).withName("Collection 1").build();

        publicItem1 = ItemBuilder.createItem(context, col)
                .withTitle("Public item 1")
                .withIssueDate("2017-10-17")
                .withAuthor("Smith, Donald").withAuthor("Doe, John")
                .withSubject("ExtraEntry")
                .build();

        publicItem2 = ItemBuilder.createItem(context, col)
                .withTitle("Public item 2")
                .withIssueDate("2016-02-13")
                .withAuthor("Smith, Maria").withAuthor("Doe, Jane")
                .withSubject("TestingForMore").withSubject("ExtraEntry")
                .build();

        context.restoreAuthSystemState();
    }

    @Test
    public void testRestAPI() throws Exception {
        String adminToken = getAuthToken(admin.getEmail(), password);
        String userToken = getAuthToken(eperson.getEmail(), password);

        String item1Url = URL_PREFIX + "/item/" + publicItem1.getID();
        String item2Url = URL_PREFIX + "/item/" + publicItem2.getID();

        String subscribe1Url = item1Url + "/subscribe";
        String subscribe2Url = item2Url + "/subscribe";

        String unSubscribe1Url = item1Url + "/unsubscribe";
        String unSubscribe2Url = item2Url + "/unsubscribe";

        MatomoReportSubscription expectedReport1 = new MatomoReportSubscription();
        expectedReport1.setId(1);
        expectedReport1.setEPerson(admin);
        expectedReport1.setItem(publicItem1);

        MatomoReportSubscription expectedReport2 = new MatomoReportSubscription();
        expectedReport2.setId(2);
        expectedReport2.setEPerson(eperson);
        expectedReport2.setItem(publicItem2);

        /* TEST subscribe items */

        // admin
        getClient(adminToken).perform(post(subscribe1Url))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", matchMatomoReportSubscriptionProperties(expectedReport1)));

        // common user
        getClient(userToken).perform(post(subscribe2Url))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", matchMatomoReportSubscriptionProperties(expectedReport2)));

        // un-authorized user
        getClient().perform(post(subscribe1Url))
                .andExpect(status().isUnauthorized());

        /* test findAll */

        // admin
        getClient(adminToken).perform(get(URL_PREFIX))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements", is(2)))
                .andExpect(jsonPath("$._embedded.matomoreportsubscriptions",
                        Matchers.containsInAnyOrder(
                                matchMatomoReportSubscriptionProperties(expectedReport1),
                                matchMatomoReportSubscriptionProperties(expectedReport2)
                        )));

        // common user
        getClient(userToken).perform(get(URL_PREFIX))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements", is(1)))
                .andExpect(jsonPath("$._embedded.matomoreportsubscriptions",
                        Matchers.contains(
                                matchMatomoReportSubscriptionProperties(expectedReport2)
                        )));
        // un-authorized user
        getClient().perform(get(URL_PREFIX))
                .andExpect(status().isUnauthorized());

        /* test findOne */

        // admin
        getClient(adminToken).perform(get(URL_PREFIX + "/2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", matchMatomoReportSubscriptionProperties(expectedReport2)));

        // common user
        getClient(userToken).perform(get(URL_PREFIX + "/2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", matchMatomoReportSubscriptionProperties(expectedReport2)));

        // common user tries to get other subscription
        getClient(userToken).perform(get(URL_PREFIX + "/1"))
                .andExpect(status().isForbidden());

        // common user tries to get non-existing subscription
        getClient(userToken).perform(get(URL_PREFIX + "/99"))
                .andExpect(status().isNotFound());

        // un-authorized user
        getClient().perform(get(URL_PREFIX + "/1"))
                .andExpect(status().isUnauthorized());

        /* test get subscription for item */

        // admin
        getClient(adminToken).perform(get(item1Url))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", matchMatomoReportSubscriptionProperties(expectedReport1)));

        // admin tries to get subscription for item admin is not subscribed for
        getClient(adminToken).perform(get(item2Url))
                .andExpect(status().isNotFound());

        // common user
        getClient(userToken).perform(get(item2Url))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", matchMatomoReportSubscriptionProperties(expectedReport2)));

        // common user tries to get subscription for item user is not subscribed for
        getClient(userToken).perform(get(item1Url))
                .andExpect(status().isNotFound());

        // un-authorized user
        getClient().perform(get(item1Url))
                .andExpect(status().isUnauthorized());

        /* test unsubscribe items */

        // common user tries to unsubscribe item that is not subscribed to this user
        getClient(userToken).perform(post(unSubscribe1Url))
                .andExpect(status().isBadRequest());

        // common user is unsubscribing item
        getClient(userToken).perform(post(unSubscribe2Url))
                .andExpect(status().isNoContent());

        // admin tries to unsubscribe item that is not subscribed to admin
        getClient(adminToken).perform(post(unSubscribe2Url))
                .andExpect(status().isBadRequest());

        // admin is unsubscribing item
        getClient(adminToken).perform(post(unSubscribe1Url))
                .andExpect(status().isNoContent());

        // check how many subscriptions left
        getClient(adminToken).perform(get(URL_PREFIX))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements", is(0)));
    }

    private static Matcher<? super Object> matchMatomoReportSubscriptionProperties(
            MatomoReportSubscription subscription) {
        return allOf(
                hasJsonPath("$.id", is(subscription.getID())),
                hasJsonPath("$.epersonId", is(subscription.getEPerson().getID().toString())),
                hasJsonPath("$.itemId", is(subscription.getItem().getID().toString())),
                hasJsonPath("$.type", is("matomoreportsubscription"))
        );
    }
}
