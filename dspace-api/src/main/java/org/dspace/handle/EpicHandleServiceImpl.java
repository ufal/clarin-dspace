/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */

package org.dspace.handle;

import java.io.IOException;
import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.dspace.handle.service.EpicHandleService;
import org.dspace.services.ConfigurationService;
import org.springframework.beans.factory.annotation.Autowired;

public class EpicHandleServiceImpl implements EpicHandleService {
    private String pidServiceUrl;
    private String pidServiceUser;
    private String pidServicePassword;
    private final ObjectMapper objectMapper;

    @Autowired
    protected ConfigurationService configurationService;

    public EpicHandleServiceImpl() {
        objectMapper = new ObjectMapper();
    }

    private void initialize() throws IOException {
        pidServiceUrl = configurationService.getProperty("lr.pid.service.url");
        if (pidServiceUrl == null || pidServiceUrl.isBlank()) {
            throw new IOException("Missing lr.pid.service.url property in DSpace configuration");
        }
        pidServiceUser = configurationService.getProperty("lr.pid.service.user");
        pidServicePassword = configurationService.getProperty("lr.pid.service.pass");
        Authenticator authenticator = new EpicHandleServiceAuthenticator();
        Authenticator.setDefault(authenticator);
    }

    @Override
    public String resolveURLForHandle(String prefix, String suffix) throws IOException {
        initialize();
        try (Response response = EpicHandleRestHelper.getCommand(pidServiceUrl, prefix, suffix)) {
            if (response.getStatus() == Response.Status.NOT_FOUND.getStatusCode()) {
                return null;
            }
            if (response.getStatus() != Response.Status.OK.getStatusCode()) {
                throw new WebApplicationException(response);
            }
            String jsonResponse = response.readEntity(String.class);

            List<EpicPidData> epicPidDataList = objectMapper.readValue(jsonResponse, new TypeReference<>() {
            });

            return epicPidDataList.stream()
                    .filter(epicPidData -> "URL".equals(epicPidData.getType()))
                    .map(epicPidData -> epicPidData.getParsedData().toString())
                    .findFirst().orElse(null);
        }
    }

    @Override
    public List<Handle> search(String prefix, String urlQuery, Integer page, Integer limit) throws IOException {
        initialize();
        Map<String, String> queryParameters = new HashMap<>();
        queryParameters.put("URL", (urlQuery == null ? "*" : String.format("*%s*", urlQuery)));
        if (page != null) {
            queryParameters.put("page", String.valueOf(page));
        }
        if (limit != null) {
            queryParameters.put("limit", String.valueOf(limit));
        }

        Map<String, String> headers = Map.of("Depth", "1");

        try (Response response = EpicHandleRestHelper.getAllCommand(pidServiceUrl, prefix, headers, queryParameters)) {
            if (response.getStatus() != Response.Status.OK.getStatusCode()) {
                throw new WebApplicationException(response);
            }

            String jsonResponse = response.readEntity(String.class);
            Map<String, List<EpicPidData>> epicPidDataMap = objectMapper.readValue(jsonResponse, new TypeReference<>() {
            });

            List<Handle> handleList = new ArrayList<>();
            epicPidDataMap.forEach((key, value) -> {
                String url = getUrlFromEpicDataList(value);
                if (url != null) {
                    String handleKey = key.startsWith("/handles/") ? key.substring(9) : key;
                    handleList.add(new Handle(handleKey, url));
                }
            });
            return handleList;
        }
    }

    @Override
    public String createHandle(String prefix, String subPrefix, String subSuffix, String url) throws IOException {
        initialize();
        Map<String, String> queryParameters = new HashMap<>();
        if (subPrefix != null) {
            queryParameters.put("prefix", subPrefix);
        }
        if (subSuffix != null) {
            queryParameters.put("suffix", subSuffix);
        }
        String jsonData = getJsonDataForUrl(objectMapper, url).toString();
        try ( Response response = EpicHandleRestHelper.postCommand(
                pidServiceUrl,
                prefix,
                queryParameters,
                jsonData)) {
            if (response.getStatus() != Response.Status.CREATED.getStatusCode()) {
                throw new WebApplicationException(response);
            }
            return objectMapper.readValue(response.readEntity(String.class), EpicPid.class).getHandle();
        }
    }

    @Override
    public void updateHandle(String prefix, String suffix, String url) throws IOException {
        initialize();
        String jsonData = getJsonDataForUrl(objectMapper, url).toString();

        try (Response response = EpicHandleRestHelper.putCommand(pidServiceUrl, prefix, suffix, jsonData)) {
            if (response.getStatus() != Response.Status.NO_CONTENT.getStatusCode() &&
                    response.getStatus() != Response.Status.OK.getStatusCode()) {
                throw new WebApplicationException(response);
            }
        }
    }

    @Override
    public void deleteHandle(String prefix, String suffix) throws IOException {
        initialize();
        try (Response response = EpicHandleRestHelper.deleteCommand(pidServiceUrl, prefix, suffix)) {
            if (response.getStatus() != Response.Status.NO_CONTENT.getStatusCode()) {
                throw new WebApplicationException(response);
            }
        }
    }

    @Override
    public int count(String prefix, String urlQuery) throws IOException {
        initialize();
        Map<String, String> queryParameters = new HashMap<>();
        queryParameters.put("URL", (urlQuery == null ? "*" : String.format("*%s*", urlQuery)));
        // search for all records
        queryParameters.put("limit", "0");

        try (Response response = EpicHandleRestHelper.getAllCommand(pidServiceUrl, prefix, null, queryParameters)) {
            if (response.getStatus() != Response.Status.OK.getStatusCode()) {
                throw new WebApplicationException(response);
            }

            String jsonResponse = response.readEntity(String.class);
            List<String> epicPids = objectMapper.readValue(jsonResponse, new TypeReference<>() {});
            return epicPids.size();
        }
    }

    private static ArrayNode getJsonDataForUrl(ObjectMapper objectMapper, String url) {
        ArrayNode arrayNode = objectMapper.createArrayNode();
        ObjectNode epicPidDataNode = objectMapper.createObjectNode();
        epicPidDataNode.put("type", "URL");
        epicPidDataNode.put("parsed_data", url);
        arrayNode.add(epicPidDataNode);
        return arrayNode;
    }

    private static String getUrlFromEpicDataList(List<EpicPidData> epicPidDataList) {
        return epicPidDataList.stream()
                .filter(epicPidData -> "URL".equals(epicPidData.getType()))
                .map(epicPidData -> epicPidData.getParsedData().toString())
                .findFirst().orElse(null);
    }

    class EpicHandleServiceAuthenticator extends Authenticator {
        public PasswordAuthentication getPasswordAuthentication() {
            return new PasswordAuthentication(pidServiceUser, pidServicePassword.toCharArray());
        }
    }

    static class EpicPid {
        private final String handle;

        @JsonCreator
        public EpicPid(@JsonProperty("epic-pid") String handle) {
            this.handle = handle;
        }
        public String getHandle() {
            return handle;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class EpicPidData {
        private final int index;
        private final String type;
        private final Object parsedData;

        @JsonCreator
        public EpicPidData(@JsonProperty("idx") int index,
                           @JsonProperty("type") String type,
                           @JsonProperty("parsed_data") Object parsedData) {
            this.index = index;
            this.type = type;
            this.parsedData = parsedData;
        }

        public int getIndex() {
            return index;
        }

        public String getType() {
            return type;
        }

        public Object getParsedData() {
            return parsedData;
        }

        @Override
        public String toString() {
            return "EpicPidData[" + index + ", " + type + ", " + parsedData + "]";
        }
    }

}
