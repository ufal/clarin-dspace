/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.ctask.general;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;

import org.dspace.content.Item;
import org.dspace.content.MetadataValue;
import org.dspace.curate.Curator;
import org.glassfish.jersey.client.ClientProperties;

/**
 * A link handle checker that builds upon the BasicLinkChecker to check the Handle URLs.
 *
 * @author Milan Kuchtiak
 */
public class ItemHandleChecker extends BasicLinkChecker {

    private List<String> ignoredUrls;

    private Map<String, HandleResponse> checkedResults;
    private Client client;

    @Override
    public void init(Curator curator, String taskId) throws IOException {
        super.init(curator, taskId);
        client = ClientBuilder.newClient().property(ClientProperties.FOLLOW_REDIRECTS, Boolean.TRUE);
        String ignores = configurationService.getProperty("curate.checklist.ignore");
        if (ignores != null && ignores.isEmpty()) {
            ignoredUrls = Arrays.asList(ignores.split(","));
        } else {
            ignoredUrls = List.of();
        }
        checkedResults = new HashMap<>();
    }

    @Override
    protected List<String> getURLs(Item item) {
        List<MetadataValue> handles = itemService.getMetadata(item, "dc", "identifier", "uri", Item.ANY);
        List<String> theURLs = new ArrayList<String>();
        for (MetadataValue url : handles) {
            if ((url.getValue().startsWith("http://")) || (url.getValue().startsWith("https://"))) {
                theURLs.add(url.getValue());
            }
        }
        return theURLs;
    }

    @Override
    protected boolean checkURL(String url, StringBuilder results) {

        HandleResponse handleResponse = getHandleResponse(url, results);
        checkedResults.put(url, handleResponse);

        return handleResponse.getFamily() == Response.Status.Family.SUCCESSFUL;
    }

    /**
     * Checks if given URL should be ignored
     *
     * @param url URL to be checked
     * @return True if url should be ignored
     */
    protected boolean isIgnoredURL(String url) {
        return ignoredUrls.stream().anyMatch(url::contains);
    }

    private HandleResponse getHandleResponse(String url, StringBuilder results) {
        WebTarget target = client.target(url);

        HandleResponse checkedResult = checkedResults.get(url);
        if (checkedResult != null) {
            showResults(url, checkedResult, results);
            return checkedResult;
        }

        try (Response response = target.request().head()) {
            HandleResponse handleResponse = HandleResponse.fromResponse(response);
            showResults(url, handleResponse, results);
            if (response.getStatusInfo().getFamily() == Response.Status.Family.REDIRECTION) {
                String location = response.getHeaderString(HttpHeaders.LOCATION);
                return getHandleResponse(location, results);
            } else {
                return handleResponse;
            }
        }
    }

    private static void showResults(String url, HandleResponse handleResponse, StringBuilder results) {
        switch (handleResponse.getFamily()) {
            case SUCCESSFUL:
                results.append(" - ").append(url).append(" = ").append(handleResponse.getStatus())
                        .append(" - OK\n");
                break;
            case REDIRECTION:
                results.append(" - ").append(url).append(" = ").append(handleResponse.getStatus())
                        .append(" - REDIRECTED\n");
                break;
            default:
                results.append(" - ").append(url).append(" = ").append(handleResponse.getStatus())
                        .append(" - FAILED\n");
        }
    }

    private static final class HandleResponse {
        private final int status;
        private final Response.Status.Family family;

        private HandleResponse(int status, Response.Status.Family family) {
            this.status = status;
            this.family = family;
        }

        public int getStatus() {
            return status;
        }

        public Response.Status.Family getFamily() {
            return family;
        }

        public static HandleResponse fromResponse(Response response) {
            return new HandleResponse(response.getStatus(), response.getStatusInfo().getFamily());
        }

        @Override
        public String toString() {
            return "HandleResponse{" +
                    "status=" + status +
                    ", family=" + family +
                    '}';
        }
    }
}
