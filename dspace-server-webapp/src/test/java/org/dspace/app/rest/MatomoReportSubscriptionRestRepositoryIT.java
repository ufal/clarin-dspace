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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.dspace.app.rest.test.AbstractControllerIntegrationTest;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.ItemBuilder;
import org.dspace.content.Collection;
import org.dspace.content.Community;
import org.dspace.content.Item;
import org.dspace.content.clarin.MatomoReportSubscription;
import org.dspace.content.service.clarin.MatomoReportSubscriptionService;
import org.dspace.eperson.EPerson;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;

public class MatomoReportSubscriptionRestRepositoryIT extends AbstractControllerIntegrationTest {

    private static final String ENTITY_TYPE = "matomoreportsubscription";
    private static final String URL_PREFIX = "/api/core/matomoreportsubscriptions";

    private Item publicItem1;
    private Item publicItem2;

    private String adminToken;
    private String userToken;

    String item1Url;
    String item2Url;
    String subscribe1Url;
    String subscribe2Url;
    String unSubscribe1Url;
    String unSubscribe2Url;

    MatomoReportSubscription subscription1;
    MatomoReportSubscription subscription2;
    MatomoReportSubscription subscription3;

    @Autowired
    private MatomoReportSubscriptionService matomoReportSubscriptionService;

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

        item1Url = URL_PREFIX + "/item/" + publicItem1.getID();
        item2Url = URL_PREFIX + "/item/" + publicItem2.getID();
        subscribe1Url = item1Url + "/subscribe";
        subscribe2Url = item2Url + "/subscribe";
        unSubscribe1Url = item1Url + "/unsubscribe";
        unSubscribe2Url = item2Url + "/unsubscribe";

        adminToken = getAuthToken(admin.getEmail(), password);
        userToken = getAuthToken(eperson.getEmail(), password);

        subscription1 = new MatomoReportSubscription();
        subscription1.setId(1);
        subscription1.setEPerson(admin);
        subscription1.setItem(publicItem1);

        subscription2 = new MatomoReportSubscription();
        subscription2.setId(2);
        subscription2.setEPerson(eperson);
        subscription2.setItem(publicItem2);

        subscription3 = new MatomoReportSubscription();
        subscription3.setId(3);
        subscription3.setEPerson(eperson);
        subscription3.setItem(publicItem1);
    }

    @Test
    public void testSubscribe() throws Exception {
        // admin
        getClient(adminToken).perform(post(subscribe1Url))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", matchMatomoReportSubscriptionProperties(subscription1)));

        // common user
        getClient(userToken).perform(post(subscribe2Url))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", matchMatomoReportSubscriptionProperties(subscription2)));

        getClient(userToken).perform(post(subscribe1Url))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", matchMatomoReportSubscriptionProperties(subscription3)));

        // un-authorized user
        getClient().perform(post(subscribe1Url))
                .andExpect(status().isUnauthorized());

        checkSubscriptions(3);
    }

    @Test
    public void testUnsubscribe() throws Exception {
        subscribeItems(List.of(publicItem1, publicItem2), List.of(admin, eperson));

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

        checkSubscriptions(0);
    }

    @Test
    public void testFindAll() throws Exception {
        subscribeItems(List.of(publicItem1, publicItem2, publicItem1), List.of(admin, eperson, eperson));

        // admin
        getClient(adminToken).perform(get(URL_PREFIX))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements", is(3)))
                .andExpect(jsonPath("$._embedded.matomoreportsubscriptions",
                        Matchers.containsInAnyOrder(
                                matchMatomoReportSubscriptionProperties(subscription1),
                                matchMatomoReportSubscriptionProperties(subscription2),
                                matchMatomoReportSubscriptionProperties(subscription3)
                        )));

        // common user
        getClient(userToken).perform(get(URL_PREFIX))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements", is(2)))
                .andExpect(jsonPath("$._embedded.matomoreportsubscriptions",
                        Matchers.containsInAnyOrder(
                                matchMatomoReportSubscriptionProperties(subscription2),
                                matchMatomoReportSubscriptionProperties(subscription3)
                        )));
        // un-authorized user
        getClient().perform(get(URL_PREFIX))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void testFindOne() throws Exception {
        Integer[] ids = subscribeItems(List.of(publicItem1, publicItem2), List.of(admin, eperson));

        // admin
        getClient(adminToken).perform(get(URL_PREFIX + "/" + ids[1]))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", matchMatomoReportSubscriptionProperties(subscription2)));

        // common user
        getClient(userToken).perform(get(URL_PREFIX + "/" + ids[1]))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", matchMatomoReportSubscriptionProperties(subscription2)));

        // common user tries to get other subscription
        getClient(userToken).perform(get(URL_PREFIX + "/" + ids[0]))
                .andExpect(status().isForbidden());

        // common user tries to get non-existing subscription
        getClient(userToken).perform(get(URL_PREFIX + "/99"))
                .andExpect(status().isNotFound());

        // un-authorized user
        getClient().perform(get(URL_PREFIX + "/" + ids[0]))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void testGetSubscriptionForItem() throws Exception {
        subscribeItems(List.of(publicItem1, publicItem2), List.of(admin, eperson));

        // admin
        getClient(adminToken).perform(get(item1Url))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", matchMatomoReportSubscriptionProperties(subscription1)));

        // admin tries to get subscription for item admin is not subscribed for
        getClient(adminToken).perform(get(item2Url))
                .andExpect(status().isNotFound());

        // common user
        getClient(userToken).perform(get(item2Url))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", matchMatomoReportSubscriptionProperties(subscription2)));

        // common user tries to get subscription for item user is not subscribed for
        getClient(userToken).perform(get(item1Url))
                .andExpect(status().isNotFound());

        // un-authorized user
        getClient().perform(get(item1Url))
                .andExpect(status().isUnauthorized());
    }

    private static Matcher<? super Object> matchMatomoReportSubscriptionProperties(
            MatomoReportSubscription subscription) {
        return allOf(
                hasJsonPath("$.id", Matchers.greaterThan(0)),
                hasJsonPath("$.epersonId", is(subscription.getEPerson().getID().toString())),
                hasJsonPath("$.itemId", is(subscription.getItem().getID().toString())),
                hasJsonPath("$.type", is(ENTITY_TYPE)),
                hasJsonPath("$._links.self.href", containsString(URL_PREFIX))
        );
    }

    private Integer[] subscribeItems(List<Item> items, List<EPerson> users) throws Exception {
        Integer[] subscriptionIds = new Integer[items.size()];
        context.turnOffAuthorisationSystem();
        for (int i = 0; i < items.size(); i++) {
            context.setCurrentUser(users.get(i));
            MatomoReportSubscription sub = matomoReportSubscriptionService.subscribe(context, items.get(i));
            subscriptionIds[i] = sub.getID();
        }
        context.restoreAuthSystemState();
        checkSubscriptions(items.size());
        return subscriptionIds;
    }

    private void checkSubscriptions(int expectedCount) throws Exception {
        getClient(adminToken).perform(get(URL_PREFIX))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements", is(expectedCount)));
    }
}
