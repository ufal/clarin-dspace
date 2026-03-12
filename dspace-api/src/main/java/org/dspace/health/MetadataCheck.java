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
import org.apache.commons.collections.ListUtils;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.core.Context;
import org.dspace.curate.Curator;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * @author Milan Kuchtiak
 */
public class MetadataCheck extends Check {

    private static final String QA_METADATA_ERROR_PATTERNS_JSON = "metadata-check-patterns.json";
    private static final String VALIDATION_TYPE_OTHER = "validation.other";
    private static final int COUNT_INDENTATION = 30;

    private static final int MAXIMUM_ERRORS_TO_SHOW = 100;
    private static final int MAXIMUM_WARNINGS_TO_SHOW = 50;
    private static final int ERRORS_DISPERSION_QUOTA = 10;
    private static final int WARNINGS_DISPERSION_QUOTA = 5;

    private static Map<String, List<String>> errorPatterns;
    private static Map<String, List<String>> warningPatterns;

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

        MetadataReporter reporter = new MetadataReporter();
        curator.setReporter(reporter);
        Context context = new Context();
        try {
            curator.curate(context, ContentServiceFactory.getInstance().getSiteService().findSite(context).getHandle());
        } catch (IOException | SQLException e) {
            error(e, "Error during curation");
        }

        Map<String, Integer> errorCounts = reporter.getErrorCount();
        int errorCount = errorCounts.values().stream().mapToInt(Integer::intValue).sum();

        Map<String, Integer> warningCounts = reporter.getWarningCount();
        int warningCount = warningCounts.values().stream().mapToInt(Integer::intValue).sum();

        Map<String, List<String>> errorMessages = reporter.getErrorMessages();
        Map<String, List<String>> warningMessages = reporter.getWarningMessages();

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
            sb.append("\nErrors:\n");
            errorMessages.forEach((key, messages) ->
                    messages.forEach(message -> sb.append(message).append("\n"))
            );
            if (errorCount > MAXIMUM_ERRORS_TO_SHOW) {
                sb.append("and more...\n");
            }
        }

        // list of warnings
        if (warningCount > 0) {
            sb.append("\nWarnings:\n");
            warningMessages.forEach((key, messages) ->
                    messages.forEach(message -> sb.append(message).append("\n"))
            );
            if (warningCount > MAXIMUM_WARNINGS_TO_SHOW) {
                sb.append("and more...\n");
            }
        }

        // populate JSON report
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

    private static String formatErrorCode(String errorCode) {
        if (errorCode.startsWith("validation.")) {
            String validationCode = errorCode.substring("validation.".length());
            return validationCode.replaceAll("\\.", " ") + " issues: ";
        } else {
            return errorCode + " issues: ";
        }
    }

    private static class MetadataReporter implements Appendable {
        private final Map<String, Integer> errorCount = new TreeMap<>();
        private final Map<String, Integer> warningCount = new TreeMap<>();

        // represent stored messages for errors and warnings
        private final StoredMessages errorMessages = new StoredMessages();
        private final StoredMessages warningMessages = new StoredMessages();

        Map<String, List<String>> getErrorMessages() {
            return errorMessages.getMessages();
        }

        Map<String, Integer> getErrorCount() {
            return errorCount;
        }

        Map<String, List<String>> getWarningMessages() {
            return warningMessages.getMessages();
        }

        Map<String, Integer> getWarningCount() {
            return warningCount;
        }

        @Override
        public Appendable append(CharSequence cs) throws IOException {
            String line = cs.toString();
            if (line.contains("ERROR! ")) {
                populateData(
                        "ERROR! ",
                        line,
                        errorPatterns,
                        errorCount,
                        errorMessages,
                        MAXIMUM_ERRORS_TO_SHOW,
                        ERRORS_DISPERSION_QUOTA
                );
            } else if (line.contains("Warning: ")) {
                populateData(
                        "Warning: ",
                        line,
                        warningPatterns,
                        warningCount,
                        warningMessages,
                        MAXIMUM_WARNINGS_TO_SHOW,
                        WARNINGS_DISPERSION_QUOTA
                );
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

        private void populateData(String prefix,
                                  String line,
                                  Map<String, List<String>> patterns,
                                  Map<String, Integer> counts,
                                  StoredMessages storedMessages,
                                  int limit,
                                  int dispersionQuota
                                  ) {
            int startIndex = line.indexOf(prefix) + prefix.length();
            String longMessage = line.substring(startIndex);
            String shortMessage = longMessage.substring(0, longMessage.indexOf("[[") - 1);
            MessageInfo messageInfo = new MessageInfo(shortMessage, longMessage);
            boolean found = false;
            for (Map.Entry<String, List<String>> entry : patterns.entrySet()) {
                String type = entry.getKey();
                List<String> typePatterns = entry.getValue();
                for (String pattern : typePatterns) {
                    boolean startsWithCaret = pattern.startsWith("^");
                    boolean endsWithDollar = pattern.endsWith("$");
                    if ((startsWithCaret && shortMessage.startsWith(pattern.substring(1))) ||
                            (endsWithDollar && shortMessage.endsWith(pattern.substring(0, pattern.length() - 1))) ||
                            (!startsWithCaret && !endsWithDollar && shortMessage.contains(pattern))
                    ) {
                        addMessage(type, messageInfo, counts, storedMessages, limit, dispersionQuota);
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
                addMessage(VALIDATION_TYPE_OTHER, messageInfo, counts, storedMessages, limit, dispersionQuota);
            }
        }

        private void addMessage(String validationType,
                                MessageInfo messageInfo,
                                Map<String, Integer> counts,
                                StoredMessages storedMessages,
                                int limit,
                                int dispersionQuota) {
            // increase the count for this validation type
            counts.merge(validationType, 1, Integer::sum);
            int mCount = storedMessages.getCount();
            Map<String, List<String>> messages = storedMessages.getMessages();
            if (mCount < limit) {
                // add error|warning to messages and increase the count
                messages.merge(messageInfo.getShortMessage(), List.of(messageInfo.getLongMessage()), ListUtils::union);
                storedMessages.setCount(mCount + 1);
            } else {
                replaceMessage(messageInfo, storedMessages, dispersionQuota);
            }
        }

        private void replaceMessage(MessageInfo messageInfo, StoredMessages storedMessages, int dispersionQuota) {
            if (storedMessages.getHighestCount() > 1) {
                Map<String, List<String>> messages = storedMessages.getMessages();
                String shortMessage = messageInfo.getShortMessage();
                List<String> fullMessages = messages.get(shortMessage);

                // recalculate the highest count of messages for any short message in messages,
                // because it can be changed after each replacement
                String messageWithHighestCount = getMessageWithHighestCount(messages);
                int highestMessageCount = messages.get(messageWithHighestCount).size();
                storedMessages.setHighestCount(highestMessageCount);

                if ((highestMessageCount > 1) &&
                    (fullMessages == null || (fullMessages.size() + dispersionQuota < highestMessageCount))) {
                    // either (1) short message is not present in messages yet,
                    // so the last message with the highest count is removed and this new message is added
                    //
                    // or (2) short message is present in messages,
                    // but the count of full messages for this short message is much lower
                    // than the count of full messages for the message with the highest count,
                    // so the last message with the highest count is removed and this new message is added
                    messages.get(messageWithHighestCount).remove(highestMessageCount - 1);
                    messages.merge(shortMessage, List.of(messageInfo.getLongMessage()), ListUtils::union);
                }
            }
        }

        private static String getMessageWithHighestCount(Map<String, List<String>> examples) {
            return examples.entrySet()
                    .stream()
                    .max((e1, e2) -> Integer.compare(e1.getValue().size(), e2.getValue().size()))
                    .map(Map.Entry::getKey)
                    .orElseThrow();
        }
    }

    private static class StoredMessages {
        private int count;
        private int highestCount;
        private final Map<String, List<String>> messages;

        StoredMessages() {
            this.count = 0;
            this.highestCount = Integer.MAX_VALUE;
            messages = new TreeMap<>();
        }

        public int getCount() {
            return count;
        }

        public void setCount(int count) {
            this.count = count;
        }

        public int getHighestCount() {
            return highestCount;
        }

        public void setHighestCount(int highestCount) {
            this.highestCount = highestCount;
        }

        public Map<String, List<String>> getMessages() {
            return messages;
        }
    }

    private static class MessageInfo {
        private final String shortMessage;
        private final String longMessage;

        public MessageInfo(String shortMessage, String longMessage) {
            this.shortMessage = shortMessage;
            this.longMessage = longMessage;
        }

        public String getShortMessage() {
            return shortMessage;
        }

        public String getLongMessage() {
            return longMessage;
        }
    }
}
