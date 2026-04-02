/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.external;

import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Response;

/**
 * REST connector for ROR API. It is used by RORAuthority to retrieve data from ROR API.
 *
 * @author Milan Kuchtiak
 */
public class RorRestConnector {

    public static final String ROR_ID_PATTERN = "^0[a-z|0-9]{6}[0-9]{2}$";

    private String apiUrl;
    private String clientId;

    public Response getByQuery(String query) {
        return getByQuery(query, 1);
    }

    public Response getByQuery(String query, int page) {
        return getTarget()
                .queryParam("query", query)
                .queryParam("page", page)
                .request()
                .header("Client-Id", clientId)
                .accept("application/json")
                .get();
    }

    public Response getByID(String rorID) {
        if (rorID.matches(ROR_ID_PATTERN)) {
            return getTarget().path(rorID)
                    .request()
                    .header("Client-Id", clientId)
                    .accept("application/json")
                    .get();
        } else {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
    }

    private WebTarget getTarget() {
        return ClientBuilder.newClient().target(apiUrl);
    }

    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

}
