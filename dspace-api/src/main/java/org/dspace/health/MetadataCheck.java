/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.health;

import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.core.Context;
import org.dspace.curate.Curator;
import org.json.JSONObject;

/**
 * @author Milan Kuchtiak
 */
public class MetadataCheck extends Check {

    private static final String QA_METADATA_ERRORS = "qa-metadata-errors.json";

    private Map<String, List<String>> errorPatterns;
    private Map<String, List<String>> warningPatterns;

    @Override
    public String run(ReportInfo ri) {
        StringBuilder sb = new StringBuilder();
        JSONObject root = new JSONObject();

        try {
            loadPatterns();
        } catch (IOException e) {
            error(e, "Cannot load error patterns");
        }

        Curator curator = new Curator();
        curator.addTask("metadataqa");

        ErrorReporter reporter = new ErrorReporter();
        curator.setReporter(reporter);
        Context context = new Context();
        try {
            curator.curate(context, ContentServiceFactory.getInstance().getSiteService().findSite(context).getHandle());
        } catch (IOException | SQLException e) {
            error(e, "Error during curation");
        }

        List<String> report = reporter.getReport();
        sb.append("Wrong Metadata: ").append(report.size()).append("\n");
        // report.forEach(line -> sb.append(line).append("\n"));

        root.put("wrongMetadata", report.size());

        this.setReportJson(root);
        return sb.toString();
    }

    static class ErrorReporter implements Appendable {
        private final List<String> report = new ArrayList<>();

        /**
         * Get the content of the report accumulator.
         * @return accumulated reports.
         */
        List<String> getReport() {
            return report;
        }

        @Override
        public Appendable append(CharSequence cs) throws IOException {
            if (cs.toString().contains("ERROR!")) {
                report.add(cs.toString());
            }
            return this;
        }

        @Override
        public Appendable append(CharSequence cs, int i, int i1) throws IOException {
            throw new UnsupportedOperationException("Not supported.");
        }

        @Override
        public Appendable append(char c)
                throws IOException {
            throw new UnsupportedOperationException("Not supported.");
        }
    }

    private void loadPatterns() throws IOException {
        InputStream qaMetadataErrors = Thread.currentThread()
                .getContextClassLoader().getResourceAsStream(QA_METADATA_ERRORS);
        JsonNode root = new ObjectMapper().readTree(qaMetadataErrors);

        // Load error types and their associated error messages
        errorPatterns = getValidationPatterns(root.withObject("errors"));
        warningPatterns = getValidationPatterns(root.withObject("warnings"));
    }

    private Map<String, List<String>> getValidationPatterns(JsonNode parentNode) {
        Map<String, List<String>> validationPatterns = new HashMap<>();
        parentNode.fieldNames().forEachRemaining(validationType -> {
            List<String> validationMessages = new ArrayList<>();
            ArrayNode patterns = parentNode.withArray(validationType);
            StreamSupport.stream(patterns.spliterator(), false).forEach(message -> {
                validationMessages.add(message.asText());
            });
            validationPatterns.put(validationType, validationMessages);
        });

        return validationPatterns;
    }
}
