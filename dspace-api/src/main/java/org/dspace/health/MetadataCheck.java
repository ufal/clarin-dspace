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
import java.util.TreeMap;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.core.Context;
import org.dspace.curate.Curator;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * @author Milan Kuchtiak
 */
public class MetadataCheck extends Check {

    private static final String QA_METADATA_ERRORS = "qa-metadata-errors.json";
    private static final int COUNT_INDENTATION = 30;
    private static final int MAXIMUM_ERRORS_TO_SHOW = 50;

    private static Map<String, List<String>> errorPatterns;
    private static Map<String, String> patternExamples;
    private static Map<String, Integer> errorCounts;

    @Override
    public String run(ReportInfo ri) {
        StringBuilder sb = new StringBuilder();
        JSONObject root = new JSONObject();

        try {
            loadPatterns();
        } catch (IOException e) {
            error(e, "Cannot load error patterns");
        }

        patternExamples = new HashMap<>();
        // Use TreeMap to keep the error types sorted
        errorCounts = new TreeMap<>();

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
        sb.append("Error Count: ").append(" ".repeat(COUNT_INDENTATION - "Error Count: ".length()))
                .append(String.format("%7d", report.size())).append("\n");

        errorCounts.forEach((key, val) -> {
            String errorCode = formatErrorCode(key);
            sb.append(errorCode).append(" ".repeat(COUNT_INDENTATION - errorCode.length()))
                    .append(String.format("%7d",val)).append("\n");
        });

        if (report.size() > MAXIMUM_ERRORS_TO_SHOW) {
            sb.append("\nError examples:\n");
            patternExamples.forEach((key, val) -> sb.append(val.substring(val.indexOf("ERROR! "))).append("\n"));
        } else {
            sb.append("\nErrors:\n");
            report.forEach(line -> sb.append(line.substring(line.indexOf("ERROR! "))).append("\n"));
        }

        // populate report with error counts
        root.put("errorCount", report.size());
        JSONArray errors = new JSONArray();
        errorCounts.forEach((key, val) -> {
            JSONObject error = new JSONObject()
                    .put("type", key)
                    .put("count", val);
            errors.put(error);
        });
        root.put("errors", errors);
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
            String line = cs.toString();
            if (line.contains("ERROR! ")) {
                report.add(line);
                populateData(line, errorPatterns, errorCounts, patternExamples);
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

    private static void populateData(String line,
                                     Map<String, List<String>> errorPatterns,
                                     Map<String, Integer> errorCounts,
                                     Map<String, String> patternExamples) {
        String errorMessage = line.substring(line.indexOf("ERROR! ") + 7, line.indexOf("[[") - 1);
        for (Map.Entry<String, List<String>> entry : errorPatterns.entrySet()) {
            String validationType = entry.getKey();
            List<String> patterns = entry.getValue();
            boolean found = false;
            for (String pattern : patterns) {
                boolean startsWithCaret = pattern.startsWith("^");
                boolean endsWithDollar = pattern.endsWith("$");
                if ((startsWithCaret && errorMessage.startsWith(pattern.substring(1))) ||
                        (endsWithDollar && errorMessage.endsWith(pattern.substring(0, pattern.length() - 1))) ||
                        (!startsWithCaret && !endsWithDollar && errorMessage.contains(pattern))
                ) {
                    // increase the count for this validation type
                    errorCounts.merge(validationType, 1, Integer::sum);
                    patternExamples.putIfAbsent(errorMessage, line);
                    found = true;
                    break;
                }
            }
            if (found) {
                break; // If a pattern is found, no need to check other patterns for this message
            }
        }
    }

    private static String formatErrorCode(String errorCode) {
        if (errorCode.startsWith("validation.")) {
            String validationCode = errorCode.substring("validation.".length());
            return validationCode.replaceAll("\\.", " ") + " errors: ";
        } else {
            return errorCode + " errors: ";
        }
    }
}
