/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.external;

import java.util.Optional;

import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.glassfish.jersey.message.internal.OutboundJaxrsResponse;
import org.glassfish.jersey.message.internal.OutboundMessageContext;

/**
 * Mock implementation of RorRestConnector for testing purposes.
 * It returns predefined responses based on the input query or ID.
 *
 * @author Milan Kuchtiak
 */
public class MockRorRestConnector extends RorRestConnector {

    @Override
    public Response getByQuery(String query, int page) {
        if (query != null && query.startsWith("\"")) {
            return getMockResponse("/org/dspace/external/ror/UniversityOfPisaByQueryExact.json");
        } else {
            return getMockResponse("/org/dspace/external/ror/UniversityOfPisa.json");
        }
    }

    @Override
    public Response getByID(String id) {
        if (id.matches(ROR_ID_PATTERN)) {
            return getMockResponse("/org/dspace/external/ror/UniversityOfPisaByID.json");
        } else {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
    }

    private static Response getMockResponse(String filePath) {
        return new MockResponse<>(Response.Status.OK,
                Optional.ofNullable(MockRorRestConnector.class.getResourceAsStream(filePath))
                        .orElseThrow(() -> new IllegalStateException("Resource " + filePath + " not found.")));
    }

    public static class MockResponse<T> extends OutboundJaxrsResponse {
        T responseBody;

        public MockResponse(Status status, T responseBody) {
            super(status, new OutboundMessageContext((Configuration) null));
            this.responseBody = responseBody;
        }

        @Override
        public <E> E readEntity(Class<E> cls) throws ProcessingException {
            return (E) responseBody;
        }

        @Override
        public MediaType getMediaType() {
            return MediaType.APPLICATION_JSON_TYPE;
        }
    }
}
