/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.ctask.general;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Response;

import org.dspace.content.Item;
import org.dspace.content.MetadataValue;
import org.dspace.curate.Curator;
import org.glassfish.jersey.client.ClientProperties;

/**
 * A handle checker that builds upon the BasicLinkChecker to check the Handle URLs.
 *
 * @author Milan Kuchtiak
 */
public class ItemHandleChecker extends BasicLinkChecker {

    private static final int CONNECTION_TIMEOUT_SEC = 2;
    private static final int READ_TIMEOUT_SEC = 3;

    private List<String> ignoredUrls;

    private Map<String, HandleResponse> checkedResults;
    private Client client;
    private String handlePrefix;

    @Override
    public void init(Curator curator, String taskId) throws IOException {
        super.init(curator, taskId);
        client = ClientBuilder.newBuilder()
                .connectTimeout(CONNECTION_TIMEOUT_SEC, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
                // we want all the locations on the way
                .property(ClientProperties.FOLLOW_REDIRECTS, false)
                .build();
        String[] ignores = configurationService.getArrayProperty("curate.checklist.ignore");
        ignoredUrls = (ignores == null) ? List.of() : List.of(ignores);

        handlePrefix = configurationService.getProperty("handle.canonical.prefix", "http://hdl.handle.net/");

        checkedResults = new HashMap<>();
    }

    @Override
    protected List<String> getURLs(Item item) {
        List<MetadataValue> ids = itemService.getMetadata(item, "dc", "identifier", "uri", Item.ANY);
        List<String> theURLs = new ArrayList<>();
        for (MetadataValue id : ids) {
            String url = id.getValue();
            if (url != null && url.startsWith(handlePrefix) && !isIgnoredURL(url)) {
                theURLs.add(url);
            }
        }
        return theURLs;
    }

    @Override
    protected boolean checkURL(String url, StringBuilder results) {
        HandleResponse handleResponse = getHandleResponse(url, results);

        return (handleResponse.getFamily() == Response.Status.Family.SUCCESSFUL);
    }

    /**
     * Checks if given URL should be ignored.
     *
     * @param url URL to be checked
     * @return true if url should be ignored
     */
    protected boolean isIgnoredURL(String url) {
        return ignoredUrls.stream().anyMatch(url::contains);
    }

    private HandleResponse getHandleResponse(String url, StringBuilder results) {
        WebTarget target = client.target(url);

        HandleResponse checkedResult = checkedResults.get(url);
        if (checkedResult != null) {
            return checkedResult;
        }

        try (Response response = target.request().head()) {
            HandleResponse handleResponse = HandleResponse.fromResponse(response);
            appendResults(url, handleResponse, results);
            checkedResults.putIfAbsent(url, handleResponse);
            if (response.getStatusInfo().getFamily() == Response.Status.Family.REDIRECTION) {
                String location;
                URI locationUri = response.getLocation();
                if (!locationUri.isAbsolute()) {
                    // Resolve relative URI against the original URL
                    location = target.getUri().resolve(locationUri).toString();
                } else {
                    location = locationUri.toString();
                }
                return getHandleResponse(location, results);
            } else {
                return handleResponse;
            }
        } catch (Exception ex) {
            HandleResponse err = new HandleResponse(500, Response.Status.Family.SERVER_ERROR, ex.getMessage());
            appendResults(url, err, results);
            checkedResults.putIfAbsent(url, err);
            return err;
        }
    }

    private static void appendResults(String url, HandleResponse handleResponse, StringBuilder results) {
        switch (handleResponse.getFamily()) {
            case SUCCESSFUL:
                results.append(" - ").append(url).append(" = ").append(handleResponse.getStatus())
                        .append(" - OK\n");
                break;
            case REDIRECTION:
                results.append(" - ").append(url).append(" = ").append(handleResponse.getStatus())
                        .append(" - REDIRECTED\n");
                break;
            default: {
                results.append(" - ").append(url).append(" = ").append(handleResponse.getStatus())
                        .append(" - FAILED\n");
                if (handleResponse.getErrorMessage() != null) {
                    results.append(" - Error: ").append(handleResponse.getErrorMessage()).append("\n");
                }
            }
        }
    }

    private static final class HandleResponse {
        private final int status;
        private final Response.Status.Family family;
        private final String errorMessage;

        private HandleResponse(int status, Response.Status.Family family) {
            this(status, family, null);
        }

        private HandleResponse(int status, Response.Status.Family family, String errorMessage) {
            this.status = status;
            this.family = family;
            this.errorMessage = errorMessage;
        }

        public int getStatus() {
            return status;
        }

        public Response.Status.Family getFamily() {
            return family;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public static HandleResponse fromResponse(Response response) {
            return new HandleResponse(response.getStatus(), response.getStatusInfo().getFamily());
        }

        @Override
        public String toString() {
            return "HandleResponse{" +
                    "status=" + status +
                    ", family=" + family +
                    ", errorMessage='" + errorMessage + "'" +
                    '}';
        }
    }
}
