/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.stream.Collectors;
import javax.servlet.Filter;

import org.dspace.app.rest.security.clarin.ClarinOidcLoginConfigurer;
import org.dspace.app.rest.test.AbstractControllerIntegrationTest;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MvcResult;

/**
 * A deployment that doesn't enable the ClarinOidcAuthentication plugin (the default test configuration) gets
 * none of the CLARIN OIDC login: no OAuth2 filters in the security filter chain, no start or callback endpoint,
 * no HTTP session.
 */
public class ClarinOidcLoginNotInstalledIT extends AbstractControllerIntegrationTest {

    @Autowired
    private FilterChainProxy springSecurityFilterChain;

    @Test
    public void testNoOAuth2Filters() {
        List<String> oauth2Filters = springSecurityFilterChain.getFilterChains().stream()
                .flatMap(chain -> chain.getFilters().stream())
                .map(Filter::getClass)
                .map(Class::getName)
                .filter(name -> name.startsWith("org.springframework.security.oauth2."))
                .collect(Collectors.toList());
        assertThat(oauth2Filters, empty());
    }

    @Test
    public void testStartAndCallbackNotHandled() throws Exception {
        MvcResult start = getClient().perform(get("/api/authn/oidc/start/clarin-oidc"))
                .andExpect(status().isNotFound())
                .andReturn();
        assertThat(start.getRequest().getSession(false), nullValue());
        getClient().perform(get(ClarinOidcLoginConfigurer.CALLBACK_PATH).param("code", "123").param("state", "x"))
                .andExpect(status().isNotFound());
    }
}
