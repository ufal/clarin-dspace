/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.security.clarin;

import static org.junit.Assert.assertFalse;

import org.dspace.app.rest.security.RestAuthenticationService;
import org.dspace.app.rest.test.AbstractControllerIntegrationTest;
import org.dspace.app.rest.utils.ContextUtil;
import org.dspace.core.Context;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;

/**
 * Spring Security stops the filter chain after a failed login, so the request context filter never closes the
 * context. The test client wraps the whole chain in that filter, so this calls the login filter directly.
 */
public class ClarinShibbolethLoginFilterContextIT extends AbstractControllerIntegrationTest {

    @Autowired
    private RestAuthenticationService restAuthenticationService;

    @Test
    public void unsuccessfulAuthenticationClosesTheContext() throws Exception {
        ClarinShibbolethLoginFilter filter =
                new ClarinShibbolethLoginFilter("/api/authn/shibboleth", null, restAuthenticationService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        Context requestContext = ContextUtil.obtainContext(request);

        filter.unsuccessfulAuthentication(request, new MockHttpServletResponse(),
                new BadCredentialsException("Login failed"));

        assertFalse(requestContext.isValid());
    }
}
