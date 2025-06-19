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

    public static Response createHandle(String pidServiceURL,
                                        String prefix,
                                        String subPrefix,
                                        String subSuffix,
                                        String jsonData) {
        URI uri;
        try {
            uri = new URI(pidServiceURL);
        } catch (URISyntaxException e) {
            return Response.status(400, "invalid ePIC PID Service URL").build();
        }

        WebTarget webTarget = client.target(uri).path(prefix);
        if (subPrefix != null) {
            webTarget = webTarget.queryParam("prefix", subPrefix);
        }
        if (subSuffix != null) {
            webTarget = webTarget.queryParam("suffix", subSuffix);
        }

        return webTarget
                .request(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .post(Entity.json(jsonData));
    }

    public static Response updateHandle(String pidServiceURL, String prefix, String suffix, String jsonData) {
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

    public static Response deleteHandle(String pidServiceURL, String prefix, String suffix) {
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

    public static Response searchHandles(String pidServiceURL,
                                         String prefix,
                                         String urlParameter,
                                         Integer page,
                                         Integer limit) {
        URI uri;
        try {
            uri = new URI(pidServiceURL);
        } catch (URISyntaxException e) {
            return Response.status(400, "invalid ePIC PID Service URL").build();
        }

        WebTarget webTarget = client.target(uri).path(prefix);
        webTarget = webTarget.queryParam("URL", urlParameter);
        if (page != null) {
            webTarget = webTarget.queryParam("page", page);
        }
        if (limit != null) {
            webTarget = webTarget.queryParam("limit", limit);
        }

        Invocation.Builder request = webTarget.request().header("Depth", "1");

        return request.accept(MediaType.APPLICATION_JSON).get();
    }

    public static Response countHandles(String pidServiceURL, String prefix, String urlParameter) {
        URI uri;
        try {
            uri = new URI(pidServiceURL);
        } catch (URISyntaxException e) {
            return Response.status(400, "invalid ePIC PID Service URL").build();
        }

        WebTarget webTarget = client.target(uri).path(prefix)
                .queryParam("URL", urlParameter)
                .queryParam("limit", "0");

        return webTarget.request().accept(MediaType.APPLICATION_JSON).get();
    }

    public static Response getHandle(String pidServiceURL, String prefix, String suffix) {
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
