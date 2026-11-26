/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.security.clarin;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.dspace.services.ConfigurationService;

/**
 * Strict validation of the URL a browser is redirected to after an OIDC login (CLARIN OIDC extension).
 * <p>
 * Unlike {@code Utils.getHostName} (java.net.URL host comparison), which a browser and Java can disagree on
 * (e.g. {@code https://attacker.example\@repo.example/} is "repo.example" for java.net.URL, but
 * "attacker.example" for a browser), the URL must be a syntactically valid absolute http(s) URI without
 * user info and its scheme, host and port must exactly match dspace.server.url, dspace.ui.url or one of
 * rest.cors.allowed-origins.
 */
public final class ClarinOidcRedirectUrlValidator {

    private ClarinOidcRedirectUrlValidator() {
    }

    /**
     * @param redirectUrl          the requested redirect URL, blank means dspace.ui.url
     * @param configurationService configuration service
     * @return the URL to redirect to, or null if it is not allowed
     */
    public static String validate(String redirectUrl, ConfigurationService configurationService) {
        if (StringUtils.isBlank(redirectUrl)) {
            redirectUrl = configurationService.getProperty("dspace.ui.url");
        }
        URI origin = origin(redirectUrl);
        if (origin == null) {
            return null;
        }
        List<String> allowed = new ArrayList<>();
        allowed.add(configurationService.getProperty("dspace.server.url"));
        allowed.add(configurationService.getProperty("dspace.ui.url"));
        allowed.addAll(List.of(configurationService.getArrayProperty("rest.cors.allowed-origins")));
        for (String allowedUrl : allowed) {
            if (origin.equals(origin(allowedUrl))) {
                return redirectUrl;
            }
        }
        return null;
    }

    /**
     * Scheme, host and (effective) port of an absolute http(s) URI without user info, or null if the value
     * isn't one. URI#equals compares the scheme and host case-insensitively.
     */
    private static URI origin(String url) {
        if (StringUtils.isBlank(url) || url.indexOf('\\') >= 0) {
            return null;
        }
        try {
            URI uri = new URI(url.trim());
            String scheme = uri.getScheme();
            if ((!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
                    || uri.getRawUserInfo() != null || StringUtils.isBlank(uri.getHost())) {
                return null;
            }
            int port = uri.getPort() != -1 ? uri.getPort() : ("https".equalsIgnoreCase(scheme) ? 443 : 80);
            return new URI(scheme, null, uri.getHost(), port, null, null, null);
        } catch (URISyntaxException ex) {
            return null;
        }
    }
}
