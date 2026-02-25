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

    private static final String QA_METADATA_ERROR_PATTERNS_JSON = "qa-metadata-error-patterns.json";
    private static final String VALIDATION_TYPE_OTHER = "validation.other";
    private static final int COUNT_INDENTATION = 30;
    private static final int MAXIMUM_ERRORS_TO_SHOW = 50;

    private static Map<String, List<String>> errorPatterns;
    private static Map<String, String> errorExamples;
    private static Map<String, Integer> errorCounts;
    private static Map<String, List<String>> warningPatterns;
    private static Map<String, String> warningExamples;
    private static Map<String, Integer> warningCounts;

    @Override
    public String run(ReportInfo ri) {
        StringBuilder sb = new StringBuilder();
        JSONObject root = new JSONObject();

        try {
            loadPatterns();
        } catch (IOException e) {
            error(e, "Cannot load error patterns");
        }

        errorExamples = new HashMap<>();
        // Use TreeMap to keep the error types sorted
        errorCounts = new TreeMap<>();

        warningExamples = new HashMap<>();
        // Use TreeMap to keep the warning types sorted
        warningCounts = new TreeMap<>();

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

        int errorCount = errorCounts.values().stream().mapToInt(Integer::intValue).sum();
        int warningCount = warningCounts.values().stream().mapToInt(Integer::intValue).sum();

        // error statistics
        if (errorCount > 0) {
            sb.append("\nError statistics:\n\n");
            errorCounts.forEach((key, val) -> {
                String errorCode = formatErrorCode(key);
                sb.append(errorCode).append(" ".repeat(COUNT_INDENTATION - errorCode.length()))
                        .append(String.format("%7d", val)).append("\n");
            });
            sb.append("-".repeat(COUNT_INDENTATION + 7)).append("\n");
            sb.append("Error count total: ")
                    .append(" ".repeat(COUNT_INDENTATION - "Error count total: ".length()))
                    .append(String.format("%7d", errorCount)).append("\n");
        }

        // warning statistics
        if (warningCount > 0) {
            sb.append("\nWarning statistics:\n\n");
            warningCounts.forEach((key, val) -> {
                String errorCode = formatErrorCode(key);
                sb.append(errorCode).append(" ".repeat(COUNT_INDENTATION - errorCode.length()))
                        .append(String.format("%7d", val)).append("\n");
            });
            sb.append("-".repeat(COUNT_INDENTATION + 7)).append("\n");
            sb.append("Warning count total: ")
                    .append(" ".repeat(COUNT_INDENTATION - "Warning count total: ".length()))
                    .append(String.format("%7d", warningCount)).append("\n");
        }

        // list of errors
        if (errorCount > 0) {
            if (errorCount > MAXIMUM_ERRORS_TO_SHOW) {
                sb.append("\nError examples:\n");
                errorExamples.forEach((key, val) -> sb.append(val.substring(val.indexOf("ERROR! "))).append("\n"));
            } else {
                sb.append("\nErrors:\n");
                report.forEach(line -> {
                    if (line.contains("ERROR! ")) {
                        sb.append(line.substring(line.indexOf("ERROR! "))).append("\n");
                    }
                });
            }
        }

        // list of warnings
        if (warningCount > 0) {
            if (warningCount > MAXIMUM_ERRORS_TO_SHOW) {
                sb.append("\nWarning examples:\n");
                warningExamples.forEach((key, val) -> sb.append(val.substring(val.indexOf("Warning: "))).append("\n"));
            } else {
                sb.append("\nWarnings:\n");
                report.forEach(line -> {
                    if (!line.contains("ERROR! ") && line.contains("Warning: ")) {
                        sb.append(line.substring(line.indexOf("Warning: "))).append("\n");
                    }
                });
            }
        }

        // populate report with error counts
        root.put("errorCount", errorCount);
        root.put("warningCount", warningCount);

        JSONArray errors = new JSONArray();
        errorCounts.forEach((key, val) -> {
            JSONObject error = new JSONObject()
                    .put("type", key)
                    .put("count", val);
            errors.put(error);
        });
        root.put("errors", errors);

        JSONArray warnings = new JSONArray();
        warningCounts.forEach((key, val) -> {
            JSONObject warning = new JSONObject()
                    .put("type", key)
                    .put("count", val);
            warnings.put(warning);
        });

        root.put("errors", errors);
        root.put("warnings", warnings);

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
                populateData("ERROR! ", line, errorPatterns, errorCounts, errorExamples);
            } else if (line.contains("Warning: ")) {
                report.add(line);
                populateData("Warning: ", line, warningPatterns, warningCounts, warningExamples);
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
                .getContextClassLoader().getResourceAsStream(QA_METADATA_ERROR_PATTERNS_JSON);
        JsonNode root = new ObjectMapper().readTree(qaMetadataErrors);

        // Load error types and their associated error messages
        errorPatterns = getPatterns(root.withObject("errors"));
        warningPatterns = getPatterns(root.withObject("warnings"));
    }

    private Map<String, List<String>> getPatterns(JsonNode parentNode) {
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

    private static void populateData(String prefix,
                                     String line,
                                     Map<String, List<String>> patterns,
                                     Map<String, Integer> counts,
                                     Map<String, String> examples) {
        String message = line.substring(line.indexOf(prefix) + prefix.length(), line.indexOf("[[") - 1);
        boolean found = false;
        for (Map.Entry<String, List<String>> entry : patterns.entrySet()) {
            String type = entry.getKey();
            List<String> typePatterns = entry.getValue();
            for (String pattern : typePatterns) {
                boolean startsWithCaret = pattern.startsWith("^");
                boolean endsWithDollar = pattern.endsWith("$");
                if ((startsWithCaret && message.startsWith(pattern.substring(1))) ||
                        (endsWithDollar && message.endsWith(pattern.substring(0, pattern.length() - 1))) ||
                        (!startsWithCaret && !endsWithDollar && message.contains(pattern))
                ) {
                    // increase the count for this validation type
                    counts.merge(type, 1, Integer::sum);
                    examples.putIfAbsent(message, line);
                    found = true;
                    break;
                }
            }
            if (found) {
                break; // If a pattern is found, no need to check other patterns for this message
            }
        }
        if (!found) {
            // If no pattern matched, categorize under "validation.other"
            counts.merge(VALIDATION_TYPE_OTHER, 1, Integer::sum);
            examples.putIfAbsent(message, line);
        }
    }

    private static String formatErrorCode(String errorCode) {
        if (errorCode.startsWith("validation.")) {
            String validationCode = errorCode.substring("validation.".length());
            return validationCode.replaceAll("\\.", " ") + " issues: ";
        } else {
            return errorCode + " issues: ";
        }
    }
}
