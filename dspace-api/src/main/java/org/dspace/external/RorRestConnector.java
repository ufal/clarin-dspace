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
 * @author Milan Kuchtiak
 */
public class RorRestConnector {

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
        return getTarget().path(rorID)
                .request()
                .header("Client-Id", clientId)
                .accept("application/json")
                .get();
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
