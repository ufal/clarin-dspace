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
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.apache.commons.collections.ListUtils;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.core.Context;
import org.dspace.curate.Curator;
import org.dspace.services.ConfigurationService;
import org.dspace.utils.DSpace;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * @author Milan Kuchtiak
 */
public class MetadataCheck extends Check {

    private static final String QA_METADATA_ERROR_PATTERNS_JSON = "metadata-check-patterns.json";
    private static final String VALIDATION_TYPE_OTHER = "validation.other";
    private static final int COUNT_INDENTATION = 30;

    // default values for configuration properties, which can be overridden in configuration

    // the maximum number of errors to be shown in the report
    private static final int MAXIMUM_ERRORS_TO_SHOW = 100;
    // the maximum number of warnings to be shown in the report
    private static final int MAXIMUM_WARNINGS_TO_SHOW = 50;
    // This number is only relevant when the number of errors exceeds the maximum number of errors to be shown.
    // Represents the dispersion of the error messages.
    // The frequency of the new (upcoming) message in the report is compared with the frequency of the
    // most frequent message in the report, and the replacement is made when the frequency of the new message
    // is significantly lower than the frequency of the most frequent message
    // (when the difference in occurrence is higher than the dispersion quota).
    private static final int ERROR_DISPERSION_QUOTA = 5;
    // the same as ERROR_DISPERSION_QUOTA but for warnings
    private static final int WARNING_DISPERSION_QUOTA = 5;

    private static Map<String, List<String>> errorPatterns;
    private static Map<String, List<String>> warningPatterns;

    static {
        try {
            loadPatterns();
        } catch (IOException e) {
            throw new RuntimeException("Cannot load error patterns", e);
        }
    }

    @Override
    public String run(ReportInfo ri) {

        ConfigurationService configurationService = new DSpace().getConfigurationService();

        int maxErrorsToShow = configurationService.getIntProperty("healthcheck.metadata.max-errors-to-show",
                MAXIMUM_ERRORS_TO_SHOW);

        int maxWarningsToShow = configurationService.getIntProperty("healthcheck.metadata.max-warnings-to-show",
                MAXIMUM_WARNINGS_TO_SHOW);

        int errorDispersionQuota = configurationService.getIntProperty("healthcheck.metadata.error-dispersion-quota",
                ERROR_DISPERSION_QUOTA);

        int warningDispersionQuota =
                configurationService.getIntProperty("healthcheck.metadata.warning-dispersion-quota",
                        WARNING_DISPERSION_QUOTA);

        StringBuilder sb = new StringBuilder();
        JSONObject root = new JSONObject();

        Curator curator = new Curator();
        curator.addTask("metadataqa");

        MetadataReporter reporter = new MetadataReporter(
                maxErrorsToShow,
                maxWarningsToShow,
                errorDispersionQuota,
                warningDispersionQuota);

        curator.setReporter(reporter);
        try (Context context = new Context()) {
            curator.curate(context, ContentServiceFactory.getInstance().getSiteService().findSite(context).getHandle());
            context.complete();
        } catch (IOException | SQLException e) {
            error(e, "Error during curation");
        }

        Map<String, Integer> errorCounts = reporter.getErrorCount();
        int overallErrorCount = errorCounts.values().stream().mapToInt(Integer::intValue).sum();

        Map<String, Integer> warningCounts = reporter.getWarningCount();
        int overallWarningCount = warningCounts.values().stream().mapToInt(Integer::intValue).sum();

        Map<String, List<String>> errorMessages = reporter.getErrorMessages();
        Map<String, List<String>> warningMessages = reporter.getWarningMessages();

        // error statistics
        if (overallErrorCount > 0) {
            sb.append("\nError statistics:\n\n");
            errorCounts.forEach((key, val) -> {
                String errorCode = formatErrorCode(key);
                sb.append(errorCode).append(" ".repeat(COUNT_INDENTATION - errorCode.length()))
                        .append(String.format("%7d", val)).append("\n");
            });
            sb.append("-".repeat(COUNT_INDENTATION + 7)).append("\n");
            sb.append("Error count total: ")
                    .append(" ".repeat(COUNT_INDENTATION - "Error count total: ".length()))
                    .append(String.format("%7d", overallErrorCount)).append("\n");
        }

        // warning statistics
        if (overallWarningCount > 0) {
            sb.append("\nWarning statistics:\n\n");
            warningCounts.forEach((key, val) -> {
                String errorCode = formatErrorCode(key);
                sb.append(errorCode).append(" ".repeat(COUNT_INDENTATION - errorCode.length()))
                        .append(String.format("%7d", val)).append("\n");
            });
            sb.append("-".repeat(COUNT_INDENTATION + 7)).append("\n");
            sb.append("Warning count total: ")
                    .append(" ".repeat(COUNT_INDENTATION - "Warning count total: ".length()))
                    .append(String.format("%7d", overallWarningCount)).append("\n");
        }

        // list of errors
        if (overallErrorCount > 0) {
            sb.append("\nErrors:\n");
            errorMessages.forEach((key, messages) ->
                    messages.forEach(message -> sb.append(message).append("\n"))
            );
            if (overallErrorCount > maxErrorsToShow) {
                sb.append("and more...\n");
            }
        }

        // list of warnings
        if (overallWarningCount > 0) {
            sb.append("\nWarnings:\n");
            warningMessages.forEach((key, messages) ->
                    messages.forEach(message -> sb.append(message).append("\n"))
            );
            if (overallWarningCount > maxWarningsToShow) {
                sb.append("and more...\n");
            }
        }

        // populate JSON report
        root.put("errorCount", overallErrorCount);
        root.put("warningCount", overallWarningCount);

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
        root.put("warnings", warnings);

        this.setReportJson(root);
        return sb.toString();
    }

    private static void loadPatterns() throws IOException {
        try (InputStream qaMetadataErrors = Thread.currentThread()
                .getContextClassLoader().getResourceAsStream(QA_METADATA_ERROR_PATTERNS_JSON);) {
            if (qaMetadataErrors == null) {
                throw new IOException("Resource '" + QA_METADATA_ERROR_PATTERNS_JSON
                        + "' not found in classpath");
            }
            JsonNode root = new ObjectMapper().readTree(qaMetadataErrors);

            // Load error types and their associated error patterns
            errorPatterns = getPatterns(root.withObject("errors"));
            // Load warning types and their associated warning patterns
            warningPatterns = getPatterns(root.withObject("warnings"));
        }
    }

    private static Map<String, List<String>> getPatterns(JsonNode parentNode) {
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

        private final int maxErrorsToShow;
        private final int maxWarningsToShow;
        private final int errorDispersionQuota;
        private final int warningDispersionQuota;

        private final Map<String, Integer> errorCount = new TreeMap<>();
        private final Map<String, Integer> warningCount = new TreeMap<>();

        // represent stored messages for errors and warnings
        private final StoredMessagesInfo errorMessages = new StoredMessagesInfo();
        private final StoredMessagesInfo warningMessages = new StoredMessagesInfo();

        Map<String, List<String>> getErrorMessages() {
            return errorMessages.getStoredMessages();
        }

        Map<String, Integer> getErrorCount() {
            return errorCount;
        }

        Map<String, List<String>> getWarningMessages() {
            return warningMessages.getStoredMessages();
        }

        Map<String, Integer> getWarningCount() {
            return warningCount;
        }

        MetadataReporter(int maxErrorsToShow,
                         int maxWarningsToShow,
                         int errorDispersionQuota,
                         int warningDispersionQuota) {
            this.maxErrorsToShow = maxErrorsToShow;
            this.maxWarningsToShow = maxWarningsToShow;
            this.errorDispersionQuota = errorDispersionQuota;
            this.warningDispersionQuota = warningDispersionQuota;
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
                        maxErrorsToShow,
                        errorDispersionQuota
                );
            } else if (line.contains("Warning: ")) {
                populateData(
                        "Warning: ",
                        line,
                        warningPatterns,
                        warningCount,
                        warningMessages,
                        maxWarningsToShow,
                        warningDispersionQuota
                );
            }
            return this;
        }

        @Override
        public Appendable append(CharSequence cs, int i, int i1) throws IOException {
            return this.append(cs.subSequence(i, i1));
        }

        @Override
        public Appendable append(char c) throws IOException {
            return this.append(String.valueOf(c));
        }

        private void populateData(String prefix,
                                  String line,
                                  Map<String, List<String>> patterns,
                                  Map<String, Integer> counts,
                                  StoredMessagesInfo storedMessagesInfo,
                                  int limit,
                                  int dispersionQuota
                                  ) {
            int startIndex = line.indexOf(prefix) + prefix.length();
            String fullMessage = line.substring(startIndex);
            int endIndex = fullMessage.lastIndexOf("[[");
            String messageKey;
            if (endIndex > 0) {
                messageKey = fullMessage.substring(0, endIndex - 1);
            } else {
                messageKey = fullMessage;
            }
            Message message = new Message(messageKey, fullMessage);
            boolean found = false;
            for (Map.Entry<String, List<String>> entry : patterns.entrySet()) {
                String type = entry.getKey();
                List<String> typePatterns = entry.getValue();
                for (String pattern : typePatterns) {
                    boolean startsWithCaret = pattern.startsWith("^");
                    boolean endsWithDollar = pattern.endsWith("$");
                    if ((startsWithCaret && messageKey.startsWith(pattern.substring(1))) ||
                            (endsWithDollar && messageKey.endsWith(pattern.substring(0, pattern.length() - 1))) ||
                            (!startsWithCaret && !endsWithDollar && messageKey.contains(pattern))
                    ) {
                        addMessage(type, message, counts, storedMessagesInfo, limit, dispersionQuota);
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
                addMessage(VALIDATION_TYPE_OTHER, message, counts, storedMessagesInfo, limit, dispersionQuota);
            }
        }

        private void addMessage(String validationType,
                                Message message,
                                Map<String, Integer> counts,
                                StoredMessagesInfo storedMessagesInfo,
                                int limit,
                                int dispersionQuota) {
            // increase the count for this validation type
            counts.merge(validationType, 1, Integer::sum);
            int mCount = storedMessagesInfo.getCount();
            Map<String, List<String>> messages = storedMessagesInfo.getStoredMessages();
            if (mCount < limit) {
                // add error|warning to messages and increase the overall messages count
                messages.merge(message.getMessageKey(), List.of(message.getFullMessage()), ListUtils::union);
                storedMessagesInfo.count++;
            } else {
                // replace one of the stored messages with new message when possible
                // but don't change the overall messages count
                replaceMessage(message, storedMessagesInfo, dispersionQuota);
            }
        }

        /**
         * Try to replace one of the stored messages, with the highest frequency, with the new message.
         * The replacement is made when the new message is entirely new
         * or the frequency of the new message is significantly lower than the messages with the highest frequency.
         *
         * @param message the new message that should be added to stored messages
         * @param storedMessagesInfo the messages that are already stored for the report
         * @param dispersionQuota quota saying how much of the messages with the highest frequency is acceptable to keep
         *                        comparing to the frequency of the new message
         */
        private void replaceMessage(Message message, StoredMessagesInfo storedMessagesInfo, int dispersionQuota) {
            int highestMessageFrequency = storedMessagesInfo.getHighestFrequency();
            if (highestMessageFrequency <= 1) {
                // no replacement, as there are no messages with the frequency higher than 1, so the replacement
                // of any message would not increase the diversity of messages in stored messages
                return;
            }
            String messageKey = message.getMessageKey();
            Map<String, List<String>> storedMessages = storedMessagesInfo.getStoredMessages();
            List<String> storedMessagesForMessageKey = storedMessages.get(messageKey);

            if (storedMessagesForMessageKey != null &&
                    (storedMessagesForMessageKey.size() + dispersionQuota >= highestMessageFrequency)) {
                // no replacement, as the frequency of the new message is not significantly lower
                // than the frequency of the message with the highest frequency
                return;
            }

            // recalculate the highest frequency of messages for any short message in storedmessages,
            // because it can be changed after each replacement
            String messageKeyWithHighestFrequency = Objects.requireNonNull(getMessageWithHighestCount(storedMessages));
            highestMessageFrequency = storedMessages.get(messageKeyWithHighestFrequency).size();
            storedMessagesInfo.setHighestFrequency(highestMessageFrequency);

            if (highestMessageFrequency <= 1) {
                // no replacement, as there are no messages with the frequency higher than 1 anymore
                // (after the recalculation)
                return;
            }

            if (storedMessagesForMessageKey == null ||
                    (storedMessagesForMessageKey.size() + dispersionQuota < highestMessageFrequency)) {
                // either (1) message key is not present in stored messages yet,
                // so the last stored message with the highest frequency is removed and this new message is added
                //
                // or (2) message key is present in stored messages,
                // but the frequency of messages for this message key is much lower
                // than the frequency of other stored messages,
                // so the (last) stored message with the highest frequency is removed and this new message is added
                storedMessages.get(messageKeyWithHighestFrequency).remove(highestMessageFrequency - 1);
                storedMessages.merge(messageKey, List.of(message.getFullMessage()), ListUtils::union);
            }
        }

        private static String getMessageWithHighestCount(Map<String, List<String>> storedMessages) {
            return storedMessages.entrySet()
                    .stream()
                    .max((e1, e2) -> Integer.compare(e1.getValue().size(), e2.getValue().size()))
                    .map(Map.Entry::getKey)
                    .orElseThrow();
        }
    }

    /**
     * Abstraction of the stored messages for errors and warnings, which are stored during the processing of messages
     * and then used to generate the final report.
     * The messages are stored in te form of a map, where the key is the message_key and
     * the value is the list of full messages stored for this message_key.
     * The count represents the overall number of stored messages.
     *
     * Example of message_key: "value [dc.date.available] is present multiple times"
     * Example of the list of full messages:
     * [
     *     "value [dc.date.available] is present multiple times [[http://hdl.handle.net/123456789/2-7371]]",
     *     "value [dc.date.available] is present multiple times [[http://hdl.handle.net/123456789/2-7373]]",
     *     "value [dc.date.available] is present multiple times [[http://hdl.handle.net/123456789/2-7375]]"
     *  ]
     *
     */
    private static class StoredMessagesInfo {
        private int count;
        private int highestFrequency;
        private final Map<String, List<String>> storedMessages;

        StoredMessagesInfo() {
            this.count = 0;
            this.highestFrequency = Integer.MAX_VALUE;
            storedMessages = new TreeMap<>();
        }

        public int getCount() {
            return count;
        }

        public int getHighestFrequency() {
            return highestFrequency;
        }

        public void setHighestFrequency(int highestFrequency) {
            this.highestFrequency = highestFrequency;
        }

        public Map<String, List<String>> getStoredMessages() {
            return storedMessages;
        }
    }

    private static class Message {
        private final String messageKey;
        private final String fullMessage;

        public Message(String messageKey, String fullMessage) {
            this.messageKey = messageKey;
            this.fullMessage = fullMessage;
        }

        public String getMessageKey() {
            return messageKey;
        }

        public String getFullMessage() {
            return fullMessage;
        }
    }
}
