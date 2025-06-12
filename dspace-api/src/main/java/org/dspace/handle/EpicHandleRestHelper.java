/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */

package org.dspace.handle;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

public class EpicHandleRestHelper {

    private static final Client client = ClientBuilder.newClient();

    private EpicHandleRestHelper() {
    }

    static Response postCommand(String pidServiceURL,
                                String prefix,
                                Map<String, String> queryParameters,
                                String jsonData) {
        URI uri;
        try {
            uri = new URI(pidServiceURL);
        } catch (URISyntaxException e) {
            return Response.status(400, "invalid ePIC PID Service URL").build();
        }

        WebTarget webTarget = client.target(uri).path(prefix);
        if (queryParameters != null && !queryParameters.isEmpty()) {
            for (Map.Entry<String, String> entry : queryParameters.entrySet()) {
                webTarget = webTarget.queryParam(entry.getKey(), entry.getValue());
            }
        }

        return webTarget
                .request(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .post(Entity.json(jsonData));
    }

    static Response putCommand(String pidServiceURL, String prefix, String suffix, String jsonData) {
        URI uri;
        try {
            uri = new URI(pidServiceURL);
        } catch (URISyntaxException e) {
            return Response.status(400, "invalid ePIC PID Service URL").build();
        }

        return client.target(uri).path(prefix).path(suffix)
                .request(MediaType.APPLICATION_JSON)
                .put(Entity.json(jsonData));
    }

    static Response deleteCommand(String pidServiceURL, String prefix, String suffix) {
        URI uri;
        try {
            uri = new URI(pidServiceURL);
        } catch (URISyntaxException e) {
            return Response.status(400, "invalid ePIC PID Service URL").build();
        }

        return client.target(uri).path(prefix).path(suffix)
                .request()
                .delete();
    }

    static Response getAllCommand(String pidServiceURL,
                                  String prefix,
                                  Map<String, String> headers,
                                  Map<String, String> queryParameters) {
        URI uri;
        try {
            uri = new URI(pidServiceURL);
        } catch (URISyntaxException e) {
            return Response.status(400, "invalid ePIC PID Service URL").build();
        }

        WebTarget webTarget = client.target(uri).path(prefix);
        if (queryParameters != null && !queryParameters.isEmpty()) {
            for (Map.Entry<String, String> entry : queryParameters.entrySet()) {
                webTarget = webTarget.queryParam(entry.getKey(), entry.getValue());
            }
        }

        Invocation.Builder request = webTarget.request();
        if (headers != null && !headers.isEmpty()) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                request = request.header(entry.getKey(), entry.getValue());
            }
        }

        return request.accept(MediaType.APPLICATION_JSON).get();
    }

    static Response getCommand(String pidServiceURL, String prefix, String suffix) {
        URI uri;
        try {
            uri = new URI(pidServiceURL);
        } catch (URISyntaxException e) {
            return Response.status(400, "invalid ePIC PID Service URL").build();
        }

        return client.target(uri).path(prefix).path(suffix)
                .request()
                .accept(MediaType.APPLICATION_JSON)
                .get();
    }

}
