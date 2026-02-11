/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.reportdiff;

import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import javax.mail.MessagingException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flipkart.zjsonpatch.JsonDiff;
import org.apache.commons.cli.ParseException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.dspace.app.healthreport.HealthReport;
import org.dspace.content.ReportResult;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.ReportResultService;
import org.dspace.core.Context;
import org.dspace.core.Email;
import org.dspace.core.I18nUtil;
import org.dspace.eperson.factory.EPersonServiceFactory;
import org.dspace.eperson.service.EPersonService;
import org.dspace.health.DateFormatConstants;
import org.dspace.scripts.DSpaceRunnable;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.dspace.utils.DSpace;

/**
 * This class implements a DSpace script that compares two health reports
 * and shows the differences between them.
 * It allows users to specify a date range
 *
 * @author Milan Majchrak (dspace at dataquest.sk)
 */
public class ReportDiff extends DSpaceRunnable<ReportDiffScriptConfiguration> {
    private static final Logger log = LogManager.getLogger(ReportDiff.class);

    private static final ObjectMapper mapper = new ObjectMapper();

    private ReportResultService reportResultService;
    private EPersonService ePersonService;

    /**
     * `-i`: Info, show help information.
     */
    private boolean info = false;

    /**
     * `-d`: Dates, show all dates that the report was generated for a specific check type.
     */
    private boolean showDates = false;

    /**
     * `-l`: Limits the number of report entries (dates) displayed when using the --date option.
     * Default is -1 (no limit).
     */
    private long limit = -1;

    /**
     * `-c`: Check, perform only specific check by index (0-`getNumberOfChecks()`).
     */
    private int specificCheck = -1;

    /**
     * `-f`: From, specify the start date for the report.
     */
    private Date from = null;

    /**
     * `-t`: Till, specify the end date for the report.
     */
    private Date to = null;

    /**
     * `-e`: Email, send report to specified email address.
     */
    private String[] emails;

    private static final DateTimeFormatter FORMATTER = DateFormatConstants.DATETIME_WITH_MILLIS_FORMATTER;

    private static final String REPORT_DIFF_FIELDS = "report-diff-fields.json";
    private static final String FIELD_MAPPINGS_KEY = "fieldMappings";
    private static final String FIELD_ORDER_KEY = "fieldOrder";

    // Field configuration cache
    private static Map<String, String> fieldMappings = null;
    private static List<String> fieldOrder = null;

    /**
     * Load field configuration from JSON resource file.
     */
    private void loadFieldConfiguration() {
        if (fieldMappings != null && fieldOrder != null) {
            return; // Already loaded
        }

        try {
            InputStream configStream = Thread.currentThread()
                    .getContextClassLoader().getResourceAsStream(REPORT_DIFF_FIELDS);
            if (configStream != null) {
                JsonNode config = mapper.readTree(configStream);

                // Load field mappings
                fieldMappings = new LinkedHashMap<>();
                JsonNode mappingsNode = config.get(FIELD_MAPPINGS_KEY);
                if (mappingsNode != null) {
                    mappingsNode.fieldNames().forEachRemaining(fieldName ->
                        fieldMappings.put(fieldName, mappingsNode.get(fieldName).asText()));
                }
                // Load field order
                fieldOrder = new ArrayList<>();
                JsonNode orderNode = config.get(FIELD_ORDER_KEY);
                if (orderNode != null && orderNode.isArray()) {
                    for (JsonNode fieldNode : orderNode) {
                        fieldOrder.add(fieldNode.asText());
                    }
                }
            }
        } catch (IOException e) {
            log.error("Error loading report diff fields configuration '{}': {}. Using empty configuration.",
                    REPORT_DIFF_FIELDS, e.getMessage(), e);
            log.warn("ReportDiff functionality will be degraded: field mappings are missing due to failed" +
                    " configuration load. Please check '{}' and ensure it is present and readable.",
                    REPORT_DIFF_FIELDS);
            // Fallback to empty configuration if file cannot be read
            fieldMappings = new HashMap<>();
            fieldOrder = new ArrayList<>();
        }
    }

    @Override
    public ReportDiffScriptConfiguration getScriptConfiguration() {
        return new DSpace().getServiceManager()
                .getServiceByName("report-diff", ReportDiffScriptConfiguration.class);
    }

    @Override
    public void setup() throws ParseException {
        ePersonService = EPersonServiceFactory.getInstance().getEPersonService();
        reportResultService = ContentServiceFactory.getInstance().getReportResultService();
        // `-i`: Info, show help information.
        if (commandLine.hasOption('i')) {
            info = true;
            return;
        }

        // `-c`: Check, perform only specific check by index (0-`getNumberOfChecks()`).
        if (commandLine.hasOption('c')) {
            specificCheck = parseCheckOption(commandLine.getOptionValue('c'));
            if (specificCheck == -1) {
                // Error already logged in parseCheckOption
                return;
            }
        }

        // `-d`: Dates, show all dates that the report was generated for a specific check type.
        if (commandLine.hasOption('d')) {
            showDates = true;
            try {
                if (commandLine.hasOption("l")) {
                    limit = Long.parseLong(commandLine.getOptionValue("l"));
                }
            } catch (NumberFormatException e) {
                handler.logError("Invalid value for -l. Must be a valid number.");
                return;
            }
        }


        // `-f`: From, specify the start date for the report.
        from = parseDateOption(commandLine.getOptionValue('f'));
        // `-t`: To, specify the end date for the report.
        to = parseDateOption(commandLine.getOptionValue('t'));

        if (commandLine.hasOption('e')) {
            emails = commandLine.getOptionValues('e');
            handler.logInfo("\nReport sent to this email address: " + String.join(", ", emails));
        }
    }

    @Override
    public void internalRun() throws Exception {
        // If the user requested help information, we will display it.
        if (info) {
            printHelp();
            return;
        }

        // If the user requested to see all report dates, we will display them.
        if (showDates) {
            displayReportDates();
            return;
        }
        try (Context context = new Context()) {
            defaultDate(context);

            // If the user specified a specific check, we need to ensure that both `from` and `to` dates are set.
            if (!validateDateRange()) {
                return;
            }

            // If the user specified a specific check, we will parse the dates and compare the reports.
            compareReports(context);
        }
    }


    /**
     * Parse the check option and return the index of the check.
     * If the option is invalid, log an error and return -1.
     *
     * @param checkOption the check option value
     * @return the index of the check or -1 if invalid
     */
    private int parseCheckOption(String checkOption) {
        try {
            int index = Integer.parseInt(checkOption);
            if (index < 0 || index >= HealthReport.getNumberOfChecks()) {
                handler.logError("Invalid value for check. Must be between 0 and " +
                        (HealthReport.getNumberOfChecks() - 1) + ". Using all checks.");
                return -1;
            }
            return index;
        } catch (NumberFormatException e) {
            handler.logError("Invalid value for check. It must be a NUMBER from the displayed range.");
            return -1;
        }
    }

    /**
     * Parse the date option and return a Date object.
     * If the option is invalid, log an error and return null.
     * The date format is expected to be "yyyy-MM-dd HH:mm:ss.SSS".
     *
     * @param optionValue the date option value
     * @return the parsed Date or null if invalid
     */
    private Date parseDateOption(String optionValue) {
        if (optionValue == null) {
            return null;
        }
        try {
            LocalDateTime ldt = LocalDateTime.parse(optionValue, FORMATTER);
            return Date.from(ldt.atZone(ZoneId.systemDefault()).toInstant());
        } catch (Exception e) {
            handler.logError("Cannot create a Date from the input: " + optionValue);
            return null;
        }
    }

    /**
     * Validate the date range specified by `from` and `to`.
     * If the dates are invalid, log an error and return false.
     * If both dates are set, ensure that `to` is not before `from`.
     *
     * @return true if the date range is valid, false otherwise
     */
    private boolean validateDateRange() {
        if (to != null && from != null && to.before(from)) {
            handler.logError("The 'to' date cannot be before the 'from' date.");
            return false;
        } else if (Objects.isNull(from) || Objects.isNull(to)) {
            handler.logError("Both 'from' and 'to' dates must be specified when using a specific check.");
            return false;
        }
        return true;
    }

    /**
     * Sets default values for the `from` and `to` dates if they are not already specified.
     *
     * @param context the application context used for fetching reports and logging
     */
    private void defaultDate(Context context) {
        if (Objects.nonNull(from) && Objects.nonNull(to)) {
            return;
        }
        handler.logInfo("No dates specified, using the last two dates from the database.");
        try {
            List<ReportResult> allReports = reportResultService.findAll(context);

            if (allReports == null || allReports.isEmpty()) {
                handler.logInfo("No reports found in the database.");
                return;
            }

            int size = allReports.size();

            if (Objects.isNull(to) && size > 0) {
                to = allReports.get(size - 1).getLastModified();
            }
            if (Objects.isNull(from) && size > 1) {
                from = allReports.get(size - 2).getLastModified();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Display all report dates for the specified check type.
     * If no reports are found, log an appropriate message.
     * Display the last 20 report dates for each type, sorted by date.
     * In the format "Report Type: <type>\n  - <date> | <args>\n",
     */
    private void displayReportDates() {
        try (Context context = new Context()) {
            context.setCurrentUser(ePersonService.find(context, getEpersonIdentifier()));
            List<ReportResult> allReports = reportResultService.findAll(context);
            // Determine how many reports to process, respecting the `limit` if it's within valid range
            long limitCount = (limit > 0 && limit < allReports.size()) ? limit : allReports.size();
            Map<String, List<DateWithArgs>> reportDatesMap = new HashMap<>();
            for (long i = 0; i < limitCount; i++) {
                // the newest report is at the end of the list, so we reverse the index
                ReportResult report = allReports.get(allReports.size() - 1 - (int) i);
                String formattedDate = FORMATTER.format(report.getLastModified()
                        .toInstant()
                        .atZone(ZoneId.systemDefault()).toLocalDateTime());
                reportDatesMap.computeIfAbsent(report.getType(), k -> new ArrayList<>())
                        .add(new DateWithArgs(formattedDate, report.getArgs()));
            }

            StringBuilder sb = new StringBuilder("Report Dates Summary:\n");
            reportDatesMap.forEach((type, dates) -> {
                sb.append("Report Type: ").append(type).append("\n");
                dates.stream()
                        .sorted(Comparator.comparing(DateWithArgs::getDate).reversed())
                        .limit(20)
                        .forEach(dwa -> sb
                                .append("  - ")
                                .append(dwa.getDate())
                                .append(" | ")
                                .append(dwa.getArgs() != null ? dwa.getArgs().strip() : "")
                                .append("\n"));
            });

            handler.logInfo(sb.toString());
        } catch (Exception e) {
            handler.logError("Error fetching report dates: " + e.getMessage());
        }
    }

    /**
     * Compare two reports based on the specified `from` and `to` dates.
     * If the reports are not found, log an appropriate message.
     * If the reports are found, generate a comparison report showing the differences.
     *
     * @param context the application context
     */
    private void compareReports(Context context) {
        try {
            context.setCurrentUser(ePersonService.find(context, getEpersonIdentifier()));

            ReportResult fromReport = specificCheck != -1
                    ? reportResultService.findByLastModifiedAndCheckType(context, from, specificCheck)
                    : reportResultService.findByLastModified(context, from);

            ReportResult toReport = specificCheck != -1
                    ? reportResultService.findByLastModifiedAndCheckType(context, to, specificCheck)
                    : reportResultService.findByLastModified(context, to);

            if (fromReport == null || toReport == null) {
                handler.logInfo("No reports found for specified dates.");
                return;
            }

            String reportDif = generateReportComparison(fromReport, toReport);
            // send email to email address from argument
            if (emails != null && emails.length > 0) {
                try {
                    Email e = Email.getEmail(I18nUtil.getEmailFilename(Locale.getDefault(), "report_diff"));
                    for (String recipient : emails) {
                        e.addRecipient(recipient);
                    }
                    e.addArgument(reportDif);
                    e.send();
                    handler.logInfo("Report sent to: " + String.join(", ", emails));
                } catch (IOException | MessagingException e) {
                    handler.logError("Error sending email: " + e.getMessage());
                }
            }

            handler.logInfo(reportDif);
        } catch (Exception e) {
            handler.logError("Error comparing reports: " + e.getMessage());
        }
    }

    /**
     * Generate a comparison report between two ReportResult objects.
     * The report includes the type, last modified dates, and the differences in JSON format.
     *
     * @param fromReport the "from" report
     * @param toReport   the "to" report
     * @return a string containing the comparison report
     * @throws IOException if an error occurs while generating the diff
     */
    private String generateReportComparison(ReportResult fromReport, ReportResult toReport) throws IOException {
        String fromJson = fromReport.getValue();
        String toJson = toReport.getValue();

        if (fromJson == null || toJson == null) {
            return "One of the reports has no value. Cannot compare.";
        }

        StringBuilder sb = new StringBuilder();

        // Header
        ConfigurationService configurationService = DSpaceServicesFactory.getInstance().getConfigurationService();
        String dspaceName = configurationService.getProperty("dspace.name", "DSpace");
        sb.append(dspaceName).append(": Repository Health Report Diff\n\n");

        // Executive Summary
        sb.append("Section 1: Executive Summary\n");
        sb.append("\n");

        // Report metadata
        sb.append("Report Type: ").append(toReport.getType()).append("\n");
        sb.append("From: ").append(fromReport.getLastModified()).append("\n");
        sb.append("To: ").append(toReport.getLastModified()).append("\n");

        // Calculate time period
        String timePeriod = calculateTimePeriod(fromReport.getLastModified(), toReport.getLastModified());
        sb.append("Report Period: ").append(timePeriod).append("\n\n");

        // Enhanced Key Changes Table
        String keyChangesTable = generateEnhancedKeyChangesTable(fromJson, toJson,
                fromReport.getLastModified(), toReport.getLastModified());
        sb.append(keyChangesTable);

        // Detailed Change Log
        sb.append("Section 2: Detailed Change Log\n\n");
        sb.append("Changes Summary\n");
        String detailedSummary = generateDetailedSummary(fromJson, toJson);
        sb.append(detailedSummary).append("\n");

        sb.append(generateDiff(fromJson, toJson));

        return sb.toString();
    }

    /**
     * Calculate the time period between two dates with human-readable format.
     *
     * @param fromDate the start date
     * @param toDate   the end date
     * @return formatted time period string
     */
    private String calculateTimePeriod(Date fromDate, Date toDate) {
        return org.apache.commons.lang3.time.DurationFormatUtils.formatDurationWords(
                Math.abs(toDate.getTime() - fromDate.getTime()), true, true);
    }

    /**
     * Pad a string to the right with spaces to reach the specified width.
     *
     * @param text the text to pad
     * @param width the desired width
     * @return padded string
     */
    private String padRight(String text, int width) {
        return String.format(java.util.Locale.ROOT, "%1$-" + width + "." + width + "s",
                java.util.Objects.toString(text, ""));
    }

    /**
     * Get a display-friendly version of a JSON node value.
     *
     * @param node the JSON node
     * @return display string
     */
    private String getDisplayValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "null";
        }

        if (node.isTextual()) {
            String text = node.asText();
            if (text.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")) {
                return text; // Keep datetime as-is
            }
            return text.replaceAll("\"", "");
        }

        return node.asText();
    }

    /**
     * Calculate the difference between two JSON node values.
     *
     * @param oldValue the old value
     * @param newValue the new value
     * @return difference string
     */
    private String calculateDifference(JsonNode oldValue, JsonNode newValue) {
        if (oldValue.isNumber() && newValue.isNumber()) {
            long oldNum = oldValue.asLong();
            long newNum = newValue.asLong();
            long diff = newNum - oldNum;
            return diff >= 0 ? "+" + diff : String.valueOf(diff);
        }

        // For sizes, try to extract numeric values
        if (oldValue.isTextual() && newValue.isTextual()) {
            String oldText = oldValue.asText();
            String newText = newValue.asText();

            if (oldText.matches(".*\\d+\\s*(bytes?|KB|MB|GB).*") && newText.matches(".*\\d+\\s*(bytes?|KB|MB|GB).*")) {
                long oldBytes = convertToBytes(oldText);
                long newBytes = convertToBytes(newText);
                if (oldBytes >= 0 && newBytes >= 0) {
                    long diffBytes = newBytes - oldBytes;
                    return diffBytes >= 0 ? "+" + formatBytes(diffBytes) : "-" + formatBytes(Math.abs(diffBytes));
                }
            }
        }

        return "Changed";
    }

    /**
     * Convert a size string like "345 KB" to bytes.
     *
     * @param sizeStr the size string
     * @return bytes, or -1 if parsing fails
     */
    private long convertToBytes(String sizeStr) {
        try {
            String[] parts = sizeStr.trim().split("\\s+");
            if (parts.length >= 2) {
                double value = Double.parseDouble(parts[0]);
                String unit = parts[1].toUpperCase();
                switch (unit) {
                    case "BYTE":
                    case "BYTES":
                        return (long) value;
                    case "KB":
                        return (long) (value * 1024);
                    case "MB":
                        return (long) (value * 1024 * 1024);
                    case "GB":
                        return (long) (value * 1024 * 1024 * 1024);
                    default:
                        break;
                }
            }
        } catch (NumberFormatException e) {
            // Ignore parsing errors
        }
        return -1;
    }

    /**
     * Format bytes into human-readable string.
     *
     * @param bytes the number of bytes
     * @return formatted string
     */
    private String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return (bytes / 1024) + " KB";
        }
        if (bytes < 1024 * 1024 * 1024) {
            return (bytes / (1024 * 1024)) + " MB";
        }
        return (bytes / (1024 * 1024 * 1024)) + " GB";
    }

    /**
     * Generate enhanced key changes table with dynamic sizing and configurable field names.
     *
     * @param oldJson the old JSON report
     * @param newJson the new JSON report
     * @param fromDate the date of the old report
     * @param toDate the date of the new report
     * @return formatted table string
     * @throws IOException if JSON parsing fails
     */
    private String generateEnhancedKeyChangesTable(String oldJson, String newJson,
                                                   Date fromDate, Date toDate) throws IOException {
        loadFieldConfiguration();

        JsonNode oldNode = mapper.readTree(oldJson);
        JsonNode newNode = mapper.readTree(newJson);

        // Collect changes for configured fields only
        List<TableRow> changes = new ArrayList<>();

        for (String fieldPath : fieldOrder) {
            JsonNode oldValue = getValueFromPath(oldNode, fieldPath);
            JsonNode newValue = getValueFromPath(newNode, fieldPath);

            if (!Objects.equals(getDisplayValue(oldValue), getDisplayValue(newValue))) {
                String displayName = fieldMappings.getOrDefault(fieldPath, fieldPath);
                String oldDisplay = getDisplayValue(oldValue);
                String newDisplay = getDisplayValue(newValue);
                String difference = calculateDifference(oldValue, newValue);

                changes.add(new TableRow(displayName, oldDisplay, newDisplay, difference));
            }
        }

        if (changes.isEmpty()) {
            return "Key Changes Between Reports\n\n" +
                   "No significant changes detected between reports.\n\n";
        }

        // Format dates for column headers using thread-safe DateTimeFormatter
        String fromDateStr = fromDate.toInstant().atZone(java.time.ZoneId.systemDefault()).format(FORMATTER);
        String toDateStr = toDate.toInstant().atZone(java.time.ZoneId.systemDefault()).format(FORMATTER);

        // Calculate dynamic column widths including header content
        int fieldWidth = Math.max("Field".length(),
                changes.stream().mapToInt(r -> r.field.length()).max().orElse(25));
        int oldWidth = Math.max(fromDateStr.length(),
                changes.stream().mapToInt(r -> r.oldValue.length()).max().orElse(15));
        int newWidth = Math.max(toDateStr.length(),
                changes.stream().mapToInt(r -> r.newValue.length()).max().orElse(15));
        int diffWidth = Math.max("Difference".length(),
                changes.stream().mapToInt(r -> r.difference.length()).max().orElse(12));

        StringBuilder table = new StringBuilder();

        // Title and separator (calculate exact width needed for table)
        int totalWidth = fieldWidth + oldWidth + newWidth + diffWidth + 13; // 13 = spaces and pipes
        table.append("Key Changes Between Reports\n");
        String separator = "=".repeat(totalWidth);

        // Header with separator
        table.append(separator).append("\n");
        table.append("| ").append(padRight("Field", fieldWidth))
             .append(" | ").append(padRight(fromDateStr, oldWidth))
             .append(" | ").append(padRight(toDateStr, newWidth))
             .append(" | ").append(padRight("Difference", diffWidth))
             .append(" |\n");
        table.append(separator).append("\n");

        // Data rows
        for (TableRow row : changes) {
            table.append("| ").append(padRight(row.field, fieldWidth))
                 .append(" | ").append(padRight(row.oldValue, oldWidth))
                 .append(" | ").append(padRight(row.newValue, newWidth))
                 .append(" | ").append(padRight(row.difference, diffWidth))
                 .append(" |\n");
        }

        table.append(separator).append("\n\n");

        return table.toString();
    }

    /**
     * Get value from JSON node using path notation (JSON Pointer style).
     */
    private JsonNode getValueFromPath(JsonNode node, String path) {
        try {
            // Use Jackson's JSON Pointer functionality for paths like /checks/0/report/publishedItems
            if (path.startsWith("/")) {
                return node.at(path);
            }

            // Fallback for simple dot notation paths
            return getValueFromSimplePath(node, path);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Get value from simple dot-notation path.
     */
    private JsonNode getValueFromSimplePath(JsonNode node, String path) {
        if (path.isEmpty()) {
            return node;
        }
        String[] parts = path.split("\\.");
        JsonNode current = node;

        for (String part : parts) {
            if (current == null || !current.has(part)) {
                return null;
            }
            current = current.get(part);
        }

        return current;
    }

    /**
     * Simple data class for table rows.
     */
    private static class TableRow {
        final String field;
        final String oldValue;
        final String newValue;
        final String difference;

        TableRow(String field, String oldValue, String newValue, String difference) {
            this.field = field;
            this.oldValue = oldValue;
            this.newValue = newValue;
            this.difference = difference;
        }
    }

    /**
     * Generate detailed summary of changes.
     *
     * @param oldJson the old JSON report
     * @param newJson the new JSON report
     * @return detailed summary string
     * @throws IOException if JSON parsing fails
     */
    private String generateDetailedSummary(String oldJson, String newJson) throws IOException {
        JsonNode patch = JsonDiff.asJson(mapper.readTree(oldJson), mapper.readTree(newJson));

        if (!patch.isArray()) {
            return "- No operations detected";
        }

        int replaceOps = 0;
        int addOps = 0;
        int removeOps = 0;
        int totalFields = 0;

        for (JsonNode op : patch) {
            String operation = op.path("op").asText();
            switch (operation) {
                case "replace":
                    replaceOps++;
                    break;
                case "add":
                    addOps++;
                    break;
                case "remove":
                    removeOps++;
                    break;
                default:
                    break;
            }
            totalFields++;
        }

        StringBuilder summary = new StringBuilder();
        summary.append("- Total operations: ").append(totalFields);
        if (replaceOps > 0) {
            summary.append(" (").append(replaceOps).append(" REPLACE");
        }
        if (addOps > 0) {
            summary.append(", ").append(addOps).append(" ADD");
        }
        if (removeOps > 0) {
            summary.append(", ").append(removeOps).append(" REMOVE");
        }
        if (replaceOps > 0 || addOps > 0 || removeOps > 0) {
            summary.append(")");
        }
        summary.append("\n");
        summary.append("- Fields modified: ").append(totalFields).append("\n");

        return summary.toString();
    }

    @Override
    public void printHelp() {
        handler.printHelp(getScriptConfiguration().getOptions(), getScriptConfiguration().getName());
        handler.logInfo("This script compares two health reports and shows the differences between them.");
        handler.logInfo("You can specify the 'from' and 'to' dates to compare reports from specific dates.");
        handler.logInfo("If you want to see all available report dates, use the '-d' option.");
        handler.logInfo("If you want to compare a specific check, use the '-c' option with the check index, " +
                "in this case you must also specify the `from` and `to` dates.");
        handler.logInfo("If you want to send the report to a specified email address, use '-e'.");
    }


    /**
     * Compute a JSON Patch (RFC 6902) between oldJson and newJson and return a human-readable summary.
     *
     * @param oldJson  the JSON string from the “previous” report
     * @param newJson  the JSON string from the “new” report
     * @return A multiline String describing each add/replace/remove/move/copy/test operation,
     *         with special characters shown in escaped form and using " -> " for replacements.
     * @throws IOException if parsing of either JSON string fails
     */
    public static String generateDiff(String oldJson, String newJson) throws IOException {
        JsonNode oldNode = mapper.readTree(oldJson);
        JsonNode newNode = mapper.readTree(newJson);

        JsonNode patch = JsonDiff.asJson(oldNode, newNode);

        if (!patch.isArray() || patch.size() == 0) {
            return "No differences found.";
        }

        StringBuilder sb = new StringBuilder();

        for (JsonNode op : patch) {
            String operation = op.path("op").asText();
            String path = op.path("path").asText();

            switch (operation) {
                case "replace":
                    appendReplace(sb, oldNode, op, path);
                    break;
                case "add":
                    appendAdd(sb, op, path);
                    break;
                case "remove":
                    appendRemove(sb, oldNode, path);
                    break;
                case "move":
                    appendMove(sb, op, path);
                    break;
                case "copy":
                    appendCopy(sb, op, path);
                    break;
                case "test":
                    appendTest(sb, op, path);
                    break;
                default:
                    sb.append(String.format("%-7s at %s (unhandled op)%n", operation.toUpperCase(), path));
            }
        }

        return sb.toString();
    }

    /**
     * Append a replace operation to the StringBuilder.
     *
     * @param sb        the StringBuilder to append to
     * @param oldNode   the old JSON node
     * @param op        the JSON patch operation node
     * @param path      the path where the operation occurs
     */
    private static void appendReplace(StringBuilder sb, JsonNode oldNode, JsonNode op, String path) {
        JsonNode newValue = op.path("value");
        JsonNode oldValue = oldNode.at(path);
        sb.append(String.format(
                "- REPLACE at %s: %s -> %s%n",
                path,
                nodeToEscapedString(oldValue),
                nodeToEscapedString(newValue)
        ));
    }

    /**
     * Append an add operation to the StringBuilder.
     *
     * @param sb        the StringBuilder to append to
     * @param op        the JSON patch operation node
     * @param path      the path where the operation occurs
     */
    private static void appendAdd(StringBuilder sb, JsonNode op, String path) {
        JsonNode addedValue = op.path("value");
        sb.append(String.format(
                "- ADD     at %s: %s%n",
                path,
                nodeToEscapedString(addedValue)
        ));
    }

    /**
     * Append a remove operation to the StringBuilder.
     *
     * @param sb        the StringBuilder to append to
     * @param oldNode   the old JSON node
     * @param path      the path where the operation occurs
     */
    private static void appendRemove(StringBuilder sb, JsonNode oldNode, String path) {
        JsonNode removedValue = oldNode.at(path);
        sb.append(String.format(
                "- REMOVE  at %s: %s%n",
                path,
                nodeToEscapedString(removedValue)
        ));
    }

    /**
     * Append a move operation to the StringBuilder.
     *
     * @param sb        the StringBuilder to append to
     * @param op        the JSON patch operation node
     * @param path      the path where the operation occurs
     */
    private static void appendMove(StringBuilder sb, JsonNode op, String path) {
        String from = op.path("from").asText();
        sb.append(String.format(
                "- MOVE    from %s to %s%n",
                from,
                path
        ));
    }

    /**
     * Append a copy operation to the StringBuilder.
     *
     * @param sb        the StringBuilder to append to
     * @param op        the JSON patch operation node
     * @param path      the path where the operation occurs
     */
    private static void appendCopy(StringBuilder sb, JsonNode op, String path) {
        String from = op.path("from").asText();
        sb.append(String.format(
                "- COPY    from %s to %s%n",
                from,
                path
        ));
    }

    /**
     * Append a test operation to the StringBuilder.
     *
     * @param sb        the StringBuilder to append to
     * @param op        the JSON patch operation node
     * @param path      the path where the operation occurs
     */
    private static void appendTest(StringBuilder sb, JsonNode op, String path) {
        JsonNode testValue = op.path("value");
        sb.append(String.format(
                "- TEST    at %s: must equal %s%n",
                path,
                nodeToEscapedString(testValue)
        ));
    }

    /**
     * Return the node’s JSON-string representation, so that special characters
     * like newline (\n) appear as "\\n" inside the returned quote marks.
     */
    private static String nodeToEscapedString(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "null";
        }
        // For any primitive or object/array, toString() returns valid JSON.
        // In particular, a text node will come out as "\"some text\\n\"" (with \\n escaped).
        return node.toString();
    }
}

/**
 * A simple class to hold a date and its associated arguments.
 * Used for displaying report dates with their arguments.
 */
class DateWithArgs {
    private final String date;
    private final String args;

    public DateWithArgs(String date, String args) {
        this.date = date;
        this.args = args;
    }

    public String getDate() {
        return date;
    }

    public String getArgs() {
        return args;
    }
}
