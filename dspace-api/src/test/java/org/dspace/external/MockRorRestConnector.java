/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.external;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import javax.ws.rs.ProcessingException;
import javax.ws.rs.core.Configuration;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.commons.io.IOUtils;
import org.glassfish.jersey.message.internal.OutboundJaxrsResponse;
import org.glassfish.jersey.message.internal.OutboundMessageContext;

public class MockRorRestConnector extends RorRestConnector {

    @Override
    public Response getByQuery(String query, int page) {
        try (InputStream is = MockRorRestConnector.class
                .getResourceAsStream( query.startsWith("\"") ?
                        "/org/dspace/external/ror/UniversityOfPisaByQueryExact.json" :
                        "/org/dspace/external/ror/UniversityOfPisa.json") ) {
            return new MockResponse<>(Response.Status.OK, getMockResponse(is));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Response getByID(String id) {
        if (id.matches(ROR_ID_PATTERN)) {
            try (InputStream is = MockRorRestConnector.class
                    .getResourceAsStream("/org/dspace/external/ror/UniversityOfPisaById.json")) {
                return new MockResponse<>(Response.Status.OK, getMockResponse(is));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        } else {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
    }

    private InputStream getMockResponse(InputStream original) throws IOException {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        IOUtils.copy(Objects.requireNonNull(original), os);
        return new ByteArrayInputStream(os.toByteArray());
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
