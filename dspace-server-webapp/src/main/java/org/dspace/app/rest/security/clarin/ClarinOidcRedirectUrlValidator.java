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
import java.util.Locale;
import java.util.Objects;

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
        Origin origin = Origin.of(redirectUrl);
        if (origin == null) {
            return null;
        }
        List<String> allowed = new ArrayList<>();
        allowed.add(configurationService.getProperty("dspace.server.url"));
        allowed.add(configurationService.getProperty("dspace.ui.url"));
        allowed.addAll(List.of(configurationService.getArrayProperty("rest.cors.allowed-origins")));
        for (String allowedUrl : allowed) {
            if (origin.equals(Origin.of(allowedUrl))) {
                return redirectUrl;
            }
        }
        return null;
    }

    /**
     * Scheme, host and (effective) port of an absolute http(s) URI without user info, or null if the value
     * isn't one.
     */
    private static final class Origin {
        private final String scheme;
        private final String host;
        private final int port;

        private Origin(String scheme, String host, int port) {
            this.scheme = scheme;
            this.host = host;
            this.port = port;
        }

        static Origin of(String url) {
            if (StringUtils.isBlank(url) || url.indexOf('\\') >= 0) {
                return null;
            }
            try {
                URI uri = new URI(url.trim());
                String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
                if (!"http".equals(scheme) && !"https".equals(scheme)) {
                    return null;
                }
                if (uri.getRawUserInfo() != null || StringUtils.isBlank(uri.getHost())) {
                    return null;
                }
                int port = uri.getPort() != -1 ? uri.getPort() : ("https".equals(scheme) ? 443 : 80);
                return new Origin(scheme, uri.getHost().toLowerCase(Locale.ROOT), port);
            } catch (URISyntaxException ex) {
                return null;
            }
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Origin)) {
                return false;
            }
            Origin that = (Origin) other;
            return port == that.port && scheme.equals(that.scheme) && host.equals(that.host);
        }

        @Override
        public int hashCode() {
            return Objects.hash(scheme, host, port);
        }
    }
}
