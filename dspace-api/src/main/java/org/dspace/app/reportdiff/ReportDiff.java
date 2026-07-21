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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.mail.MessagingException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

    private ReportResultService reportResultService = ContentServiceFactory.getInstance().getReportResultService();
    private EPersonService ePersonService = EPersonServiceFactory.getInstance().getEPersonService();

    /**
     * `-h`: Help, show help information.
     */
    private boolean help = false;

    /**
     * `-l`: List all stored reports with IDs, timestamps and arguments.
     */
    private boolean showList = false;

    /**
     * `-m`: Maximum number of report entries displayed when using --list.
     * Default is -1 (no limit).
     */
    private long maxEntries = -1;

    /**
     * `-c`: Check, perform only specific checks by index (0-`getNumberOfChecks()`).
     * Supports multiple values.
     */
    private List<Integer> specificChecks = new ArrayList<>();

    /**
     * `-s`: Source, specify source report ID.
     */
    private Integer sourceReportId = null;

    /**
     * `-t`: Till, specify target report ID.
     */
    private Integer targetReportId = null;

    /**
     * `-e`: Email, send report to specified email address.
     */
    private String[] emails;

    private static final DateTimeFormatter FORMATTER = DateFormatConstants.DATETIME_WITH_MILLIS_FORMATTER;

    private static final String REPORT_DIFF_FIELDS = "report-diff-fields.json";
    private static final String FIELD_MAPPINGS_KEY = "fieldMappings";
    private static final String FIELD_ORDER_KEY = "fieldOrder";
    private static final Pattern SHORT_ARG_WITH_VALUE = Pattern.compile("^-([a-zA-Z]):\\s*(.*)$");
    private static final Pattern SHORT_ARG_WITHOUT_VALUE = Pattern.compile("^-([a-zA-Z])$");

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
            } else {
                log.warn("Report diff fields configuration '{}' not found on the classpath. " +
                        "Field mappings will be empty.", REPORT_DIFF_FIELDS);
                fieldMappings = new LinkedHashMap<>();
                fieldOrder = new ArrayList<>();
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
        // `-h`: Help, show help information.
        if (commandLine.hasOption('h')) {
            help = true;
            return;
        }

        // `-c`: Check, perform only specific checks by index (0-`getNumberOfChecks()`).
        // Supports multiple values e.g. -c 0 3 4
        if (commandLine.hasOption('c')) {
            String[] checkOptions = commandLine.getOptionValues('c');
            for (String checkOption : checkOptions) {
                int parsedCheck = parseCheckOption(checkOption);
                if (parsedCheck == -1) {
                    handler.logWarning("Invalid value for -c: '" + checkOption
                            + "'. All checks will be compared.");
                    specificChecks.clear();
                    break;
                }
                specificChecks.add(parsedCheck);
            }
        }

        // `-l`: List all available reports with IDs/timestamps/args.
        if (commandLine.hasOption('l')) {
            showList = true;
            if (commandLine.hasOption('m')) {
                String mValue = commandLine.getOptionValue('m');
                try {
                    long parsedMax = Long.parseLong(mValue);
                    if (parsedMax <= 0) {
                        handler.logWarning("Invalid value for -m: '" + mValue
                                + "'. Must be a positive integer. All entries will be shown.");
                        maxEntries = -1;
                    } else {
                        maxEntries = parsedMax;
                    }
                } catch (NumberFormatException e) {
                    handler.logWarning("Invalid value for -m: '" + mValue
                            + "'. Must be a positive integer. All entries will be shown.");
                    maxEntries = -1;
                }
            }
        }


        // `-s`: Source, specify source report ID.
        if (commandLine.hasOption('s')) {
            String sValue = commandLine.getOptionValue('s');
            sourceReportId = parseReportIdOption(sValue);
            if (sourceReportId == null) {
                handler.logWarning("Invalid value for -s: '" + sValue
                        + "'. The last report from the database will be used instead.");
            }
        }

        // `-t`: Target, specify target report ID.
        if (commandLine.hasOption('t')) {
            String tValue = commandLine.getOptionValue('t');
            targetReportId = parseReportIdOption(tValue);
            if (targetReportId == null) {
                handler.logWarning("Invalid value for -t: '" + tValue
                        + "'. The last report from the database will be used instead.");
            }
        }

        if (commandLine.hasOption('e')) {
            emails = commandLine.getOptionValues('e');
            handler.logInfo("\nReport will be sent to: " + String.join(", ", emails));
        }
    }

    @Override
    public void internalRun() throws Exception {
        // If the user requested help information, we will display it.
        if (help) {
            printHelp();
            return;
        }

        // If the user requested to see all report dates, we will display them.
        if (showList) {
            displayReportDates();
            return;
        }
        try (Context context = new Context()) {
            // Validate the explicitly provided report IDs before defaulting the missing ones,
            // so no defaulting message is logged when a provided report ID is invalid.
            if (!validateReportIdSelection()) {
                return;
            }
            if (!reportExists(context, sourceReportId) || !reportExists(context, targetReportId)) {
                return;
            }

            // If at least one of -s/-t is missing, fill missing values from latest reports.
            if (sourceReportId == null || targetReportId == null) {
                defaultReportIds(context);
                if (sourceReportId == null || targetReportId == null) {
                    handler.logInfo("Need at least 2 reports in the database to perform a comparison. Aborting.");
                    return;
                }
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
                return -1;
            }
            return index;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Parse report ID option and return an Integer.
     * If the option is invalid, log an error and return null.
     *
     * @param optionValue the date option value
     * @return the parsed report ID or null if invalid
     */
    private Integer parseReportIdOption(String optionValue) {
        if (optionValue == null) {
            return null;
        }
        try {
            return Integer.parseInt(optionValue);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Validate the explicitly provided report IDs.
     * The report IDs are optional (missing ones are defaulted later),
     * but when provided they must be positive integers.
     *
     * @return true if the provided report IDs are valid, false otherwise
     */
    private boolean validateReportIdSelection() {
        if (sourceReportId != null && sourceReportId <= 0) {
            handler.logError("The 'source' report ID must be a positive integer.");
            return false;
        }

        if (targetReportId != null && targetReportId <= 0) {
            handler.logError("The 'target' report ID must be a positive integer.");
            return false;
        }

        return true;
    }

    /**
     * Check that an explicitly provided report ID exists in the database.
     * A {@code null} report ID is considered valid because it is defaulted later.
     *
     * @param context  the application context
     * @param reportId the report ID to check, may be null
     * @return true if the report ID is null or the report exists, false otherwise
     * @throws SQLException if a database error occurs
     */
    private boolean reportExists(Context context, Integer reportId) throws SQLException {
        if (reportId == null) {
            return true;
        }
        if (reportResultService.find(context, reportId) == null) {
            handler.logInfo("No report found for report ID: " + reportId);
            return false;
        }
        return true;
    }

    /**
     * Sets default values for the source and target report IDs if not already specified.
     *
     * @param context the application context used for fetching reports and logging
     */
    private void defaultReportIds(Context context) {
        boolean bothMissing = sourceReportId == null && targetReportId == null;
        try {
            List<ReportResult> allReports = reportResultService.findAll(context);

            if (allReports == null || allReports.isEmpty()) {
                handler.logInfo("No reports found in the database.");
                return;
            }

            // findAll() does not guarantee ordering; sort by lastModified ascending so the
            // newest reports are at the end of the list.
            allReports.sort(Comparator.comparing(ReportResult::getLastModified));
            int size = allReports.size();

            if (bothMissing) {
                handler.logInfo("No report IDs specified, using the last two reports from the database.");
                if (size > 0) {
                    targetReportId = allReports.get(size - 1).getID();
                }
                if (size > 1) {
                    sourceReportId = allReports.get(size - 2).getID();
                }
                return;
            }

            if (sourceReportId == null) {
                handler.logInfo("Only '-t' was specified; '-s' will be set to the latest report from the "
                        + "database.");
                if (size > 0) {
                    sourceReportId = allReports.get(size - 1).getID();
                }
            }

            if (targetReportId == null) {
                handler.logInfo("Only '-s' was specified; '-t' will be set to the latest report from the "
                        + "database.");
                if (size > 0) {
                    targetReportId = allReports.get(size - 1).getID();
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
        * Display available reports with their IDs and timestamps.
     * If no reports are found, log an appropriate message.
        * Display the last 20 report entries for each type, sorted by date.
        * In the format "Report Type: <type>\n  - ID: <id> | <date> | <args>\n",
     */
    private void displayReportDates() {
        try (Context context = new Context()) {
            context.setCurrentUser(ePersonService.find(context, getEpersonIdentifier()));
            List<ReportResult> allReports = reportResultService.findAll(context);
            if (allReports == null || allReports.isEmpty()) {
                handler.logInfo("No reports found in the database.");
                return;
            }
            // findAll() does not guarantee ordering; sort by lastModified ascending so the
            // newest reports are at the end of the list.
            allReports.sort(Comparator.comparing(ReportResult::getLastModified));
            // Determine how many reports to process, respecting maxEntries if it's within valid range
            long limitCount = (maxEntries > 0 && maxEntries < allReports.size()) ? maxEntries : allReports.size();
            Map<String, List<DateWithArgs>> reportDatesMap = new HashMap<>();
            for (long i = 0; i < limitCount; i++) {
                // the newest report is at the end of the list, so we reverse the index
                ReportResult report = allReports.get(allReports.size() - 1 - (int) i);
                String formattedDate = FORMATTER.format(report.getLastModified()
                        .toInstant()
                        .atZone(ZoneId.systemDefault()).toLocalDateTime());
                reportDatesMap.computeIfAbsent(report.getType(), k -> new ArrayList<>())
                    .add(new DateWithArgs(report.getID(), formattedDate, report.getArgs()));
            }

            StringBuilder sb = new StringBuilder("Available Reports Summary:\n");
            reportDatesMap.forEach((type, dates) -> {
                sb.append("Report Type: ").append(type).append("\n");
                dates.stream()
                        .sorted(Comparator.comparing(DateWithArgs::getDate).reversed())
                        .limit(20)
                        .forEach(dwa -> sb
                                .append("  - ")
                                .append("ID: ").append(dwa.getId())
                                .append(" | ")
                                .append(dwa.getDate())
                                .append(" | ")
                                .append(formatReportArgsForDisplay(dwa.getArgs()))
                                .append("\n"));
            });

            handler.logInfo(sb.toString());
        } catch (Exception e) {
            handler.logError("Error fetching report dates: " + e.getMessage());
        }
    }

    private String formatReportArgsForDisplay(String args) {
        if (args == null || args.isBlank()) {
            return "";
        }

        List<String> formattedEntries = new ArrayList<>();
        String[] lines = args.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            Matcher withValue = SHORT_ARG_WITH_VALUE.matcher(trimmed);
            if (withValue.matches()) {
                String shortOpt = withValue.group(1);
                String value = withValue.group(2);
                String longOpt = resolveHealthReportLongOption(shortOpt);

                if ("c".equals(shortOpt)) {
                    value = appendCheckName(value);
                }

                if (longOpt != null) {
                    formattedEntries.add("--" + longOpt + ": " + value);
                } else {
                    formattedEntries.add(trimmed);
                }
                continue;
            }

            Matcher withoutValue = SHORT_ARG_WITHOUT_VALUE.matcher(trimmed);
            if (withoutValue.matches()) {
                String shortOpt = withoutValue.group(1);
                String longOpt = resolveHealthReportLongOption(shortOpt);
                if (longOpt != null) {
                    formattedEntries.add("--" + longOpt);
                } else {
                    formattedEntries.add(trimmed);
                }
                continue;
            }

            formattedEntries.add(trimmed);
        }

        return String.join(", ", formattedEntries);
    }

    private String resolveHealthReportLongOption(String shortOpt) {
        switch (shortOpt) {
            case "h":
                return "help";
            case "e":
                return "email";
            case "c":
                return "check";
            case "f":
                return "for";
            case "r":
                return "report";
            default:
                return null;
        }
    }

    private String appendCheckName(String value) {
        try {
            int checkIndex = Integer.parseInt(value.trim());
            String checkName = HealthReport.getCheckName(checkIndex);
            if (checkName != null) {
                return checkIndex + " (" + checkName + ")";
            }
            return value;
        } catch (NumberFormatException e) {
            return value;
        }
    }

    /**
     * Compare two reports based on the specified `from` and `to` dates.
     * If the reports are not found, log an appropriate message.
     * If the reports are found, generate a comparison report showing the differences.
     * The comparison is based on the intersection of check names present in both reports.
     *
     * @param context the application context
     */
    private void compareReports(Context context) {
        try {
            context.setCurrentUser(ePersonService.find(context, getEpersonIdentifier()));

            ReportResult fromReport = reportResultService.find(context, sourceReportId);
            ReportResult toReport = reportResultService.find(context, targetReportId);

            if (fromReport == null) {
                handler.logInfo("No report found for report ID: " + sourceReportId);
                return;
            }
            if (toReport == null) {
                handler.logInfo("No report found for report ID: " + targetReportId);
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
     * Holds the result of normalizing two reports to their intersection,
     * including information about checks that were skipped (present in one report only).
     */
    private static class NormalizationResult {
        final String normalizedFromJson;
        final String normalizedToJson;
        /** Check names that exist only in the "from" report. */
        final List<String> onlyInFrom;
        /** Check names that exist only in the "to" report. */
        final List<String> onlyInTo;
        /** True when the two reports share at least one check eligible for comparison. */
        final boolean hasCommonChecks;

        NormalizationResult(String normalizedFromJson, String normalizedToJson,
                            List<String> onlyInFrom, List<String> onlyInTo,
                            boolean hasCommonChecks) {
            this.normalizedFromJson = normalizedFromJson;
            this.normalizedToJson = normalizedToJson;
            this.onlyInFrom = onlyInFrom;
            this.onlyInTo = onlyInTo;
            this.hasCommonChecks = hasCommonChecks;
        }
    }

    /**
     * Normalize two report JSON strings so that they only contain checks
     * that are present (by name) in both reports. This allows correct comparison
     * when reports were created with different check selections.
     *
     * If the `-c` option was specified, additionally filters to only include
     * checks matching the specified check index (by name from the configured check list).
     *
     * @param fromJson the JSON string of the "from" report
     * @param toJson   the JSON string of the "to" report
     * @return a {@link NormalizationResult} containing normalized JSON and skipped check info
     * @throws IOException if JSON parsing fails
     */
    private NormalizationResult normalizeReportsToIntersection(String fromJson, String toJson) throws IOException {
        JsonNode fromRoot = mapper.readTree(fromJson);
        JsonNode toRoot = mapper.readTree(toJson);

        JsonNode fromChecks = fromRoot.get("checks");
        JsonNode toChecks = toRoot.get("checks");

        if (fromChecks == null || toChecks == null || !fromChecks.isArray() || !toChecks.isArray()) {
            return new NormalizationResult(fromJson, toJson,
                    new ArrayList<>(), new ArrayList<>(), true);
        }

        // Build maps of check name -> check node for both reports
        Map<String, JsonNode> fromCheckMap = new LinkedHashMap<>();
        for (JsonNode check : fromChecks) {
            JsonNode nameNode = check.get("name");
            if (nameNode != null) {
                fromCheckMap.put(nameNode.asText(), check);
            }
        }

        Map<String, JsonNode> toCheckMap = new LinkedHashMap<>();
        for (JsonNode check : toChecks) {
            JsonNode nameNode = check.get("name");
            if (nameNode != null) {
                toCheckMap.put(nameNode.asText(), check);
            }
        }

        // Compute intersection of check names
        List<String> commonNames = new ArrayList<>(fromCheckMap.keySet());
        commonNames.retainAll(toCheckMap.keySet());

        // If specificChecks are set, further filter to only those check names
        if (!specificChecks.isEmpty()) {
            List<String> targetCheckNames = new ArrayList<>();
            for (int checkIndex : specificChecks) {
                String targetCheckName = HealthReport.getCheckName(checkIndex);
                if (targetCheckName != null) {
                    targetCheckNames.add(targetCheckName);
                }
            }
            commonNames.retainAll(targetCheckNames);
        }

        if (commonNames.isEmpty()) {
            handler.logInfo("No common checks found between the two reports for comparison.");
        }

        // Determine checks that are only in one report
        List<String> onlyInFrom = new ArrayList<>(fromCheckMap.keySet());
        onlyInFrom.removeAll(toCheckMap.keySet());
        List<String> onlyInTo = new ArrayList<>(toCheckMap.keySet());
        onlyInTo.removeAll(fromCheckMap.keySet());

        // When specific checks are requested, do not report other checks as skipped
        if (!specificChecks.isEmpty()) {
            onlyInFrom.clear();
            onlyInTo.clear();
        }

        // Build normalized JSON with only the common checks (in the same order)
        ObjectNode normalizedFrom = mapper.createObjectNode();
        ArrayNode normalizedFromChecks = mapper.createArrayNode();
        for (String name : commonNames) {
            normalizedFromChecks.add(fromCheckMap.get(name));
        }
        normalizedFrom.set("checks", normalizedFromChecks);

        ObjectNode normalizedTo = mapper.createObjectNode();
        ArrayNode normalizedToChecks = mapper.createArrayNode();
        for (String name : commonNames) {
            normalizedToChecks.add(toCheckMap.get(name));
        }
        normalizedTo.set("checks", normalizedToChecks);

        return new NormalizationResult(
                mapper.writeValueAsString(normalizedFrom),
                mapper.writeValueAsString(normalizedTo),
                onlyInFrom, onlyInTo,
                !commonNames.isEmpty());
    }

    /**
     * Generate a comparison report between two ReportResult objects.
     * The report includes the type, last modified dates, and the differences in JSON format.
     * When comparing reports with different check selections, only the intersection
     * of common check names is compared.
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

        // Normalize both reports to contain only intersection of check names
        NormalizationResult normalized = normalizeReportsToIntersection(fromJson, toJson);
        String normalizedFromJson = normalized.normalizedFromJson;
        String normalizedToJson = normalized.normalizedToJson;

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
        sb.append("Source Report: ID ").append(fromReport.getID())
            .append(" at ").append(fromReport.getLastModified()).append("\n");
        sb.append("Target Report: ID ").append(toReport.getID())
            .append(" at ").append(toReport.getLastModified()).append("\n");

        // Calculate time period
        String timePeriod = calculateTimePeriod(fromReport.getLastModified(), toReport.getLastModified());
        sb.append("Report Period: ").append(timePeriod).append("\n\n");

        // When there are no checks in common between the two reports there is nothing to diff.
        // In that case, only show the executive summary and the list of skipped checks so the
        // user can immediately see why the comparison was not performed.
        if (!normalized.hasCommonChecks) {
            appendSkippedChecksSection(sb, normalized, fromReport, toReport);
            return sb.toString();
        }

        // Enhanced Key Changes Table
        String keyChangesTable = generateEnhancedKeyChangesTable(normalizedFromJson, normalizedToJson,
                fromReport.getID(), toReport.getID());
        sb.append(keyChangesTable);

        // Keep output concise when the compared (common) checks are identical.
        if (!hasDifferences(normalizedFromJson, normalizedToJson)) {
            return sb.toString();
        }

        // Section 2: Skipped Checks (not present in both reports)
        appendSkippedChecksSection(sb, normalized, fromReport, toReport);

        // Section 3: Detailed Change Log
        sb.append("Section 3: Detailed Change Log\n\n");
        sb.append("Changes Summary\n");
        String detailedSummary = generateDetailedSummary(normalizedFromJson, normalizedToJson);
        sb.append(detailedSummary).append("\n");

        sb.append(generateDiff(normalizedFromJson, normalizedToJson));

        return sb.toString();
    }

    /**
     * Append the "Section 2: Skipped Checks" block listing checks that are present
     * in only one of the compared reports. Appends nothing when no check was skipped.
     *
     * @param sb         the StringBuilder to append to
     * @param normalized the normalization result holding the skipped check names
     * @param fromReport the "from" report
     * @param toReport   the "to" report
     */
    private void appendSkippedChecksSection(StringBuilder sb, NormalizationResult normalized,
                                            ReportResult fromReport, ReportResult toReport) {
        if (normalized.onlyInFrom.isEmpty() && normalized.onlyInTo.isEmpty()) {
            return;
        }
        sb.append("Section 2: Skipped Checks\n\n");
        sb.append("The following checks could not be compared because they were not present in " +
                "both reports.\n\n");
        if (!normalized.onlyInFrom.isEmpty()) {
            sb.append("Only in source report (ID ").append(fromReport.getID()).append("):\n");
            for (String name : normalized.onlyInFrom) {
                sb.append("  - ").append(name).append("\n");
            }
            sb.append("\n");
        }
        if (!normalized.onlyInTo.isEmpty()) {
            sb.append("Only in target report (ID ").append(toReport.getID()).append("):\n");
            for (String name : normalized.onlyInTo) {
                sb.append("  - ").append(name).append("\n");
            }
            sb.append("\n");
        }
    }

    /**
     * Determine whether two normalized report JSON payloads differ.
     *
     * @param oldJson source report JSON
     * @param newJson target report JSON
     * @return true if there is at least one JSON Patch operation, false otherwise
     * @throws IOException if JSON parsing fails
     */
    private boolean hasDifferences(String oldJson, String newJson) throws IOException {
        JsonNode patch = JsonDiff.asJson(mapper.readTree(oldJson), mapper.readTree(newJson));
        return patch.isArray() && !patch.isEmpty();
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
     * Check whether a JSON node carries no usable value, i.e. it is {@code null},
     * a missing node or a JSON null. Note this is different from {@link JsonNode#isEmpty()},
     * which checks for empty containers.
     *
     * @param node the JSON node to check, may be null
     * @return true if the node is null, missing or a JSON null
     */
    private static boolean isNullOrMissing(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull();
    }

    /**
     * Get a display-friendly version of a JSON node value.
     *
     * @param node the JSON node
     * @return display string
     */
    private String getDisplayValue(JsonNode node) {
        if (isNullOrMissing(node)) {
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
        boolean oldMissing = isNullOrMissing(oldValue);
        boolean newMissing = isNullOrMissing(newValue);
        if (oldMissing || newMissing) {
            if (oldMissing && !newMissing) {
                return "Added";
            }
            if (newMissing && !oldMissing) {
                return "Removed";
            }
            return "Changed";
        }

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
     * Format an unsigned byte count with two-decimal precision for KB/MB/GB; used for value
     * columns of byte-typed fields in the Key Changes table.
     *
     * @param bytes byte count (negatives are treated as their absolute value)
     * @return formatted string, e.g. {@code 65.32 MB}
     */
    private String formatBytesHuman(long bytes) {
        long abs = Math.abs(bytes);
        if (abs < 1024L) {
            return abs + " B";
        }
        if (abs < 1024L * 1024) {
            return String.format(java.util.Locale.ROOT, "%.2f KB", abs / 1024.0);
        }
        if (abs < 1024L * 1024 * 1024) {
            return String.format(java.util.Locale.ROOT, "%.2f MB", abs / (1024.0 * 1024));
        }
        return String.format(java.util.Locale.ROOT, "%.2f GB", abs / (1024.0 * 1024 * 1024));
    }

    /**
     * Format a (possibly negative) byte delta into a signed, human-readable string used in the
     * Difference column for byte-typed fields. Produces values such as {@code -5.71 KB} or
     * {@code +123 B} so administrators don't have to read raw byte counts.
     *
     * @param bytes signed byte delta
     * @return formatted signed string
     */
    private String formatSignedBytesHuman(long bytes) {
        if (bytes == 0) {
            return "0 B";
        }
        String sign = bytes > 0 ? "+" : "-";
        long abs = Math.abs(bytes);
        if (abs < 1024L) {
            return sign + abs + " B";
        }
        if (abs < 1024L * 1024) {
            return String.format(java.util.Locale.ROOT, "%s%.2f KB", sign, abs / 1024.0);
        }
        if (abs < 1024L * 1024 * 1024) {
            return String.format(java.util.Locale.ROOT, "%s%.2f MB", sign, abs / (1024.0 * 1024));
        }
        return String.format(java.util.Locale.ROOT, "%s%.2f GB", sign, abs / (1024.0 * 1024 * 1024));
    }

    /**
     * Resolve a field path with attribute selectors to a JSON value.
     * <p>
     * Supports XPath-like selector syntax for matching array elements by a named field:
     * <pre>
     *   /checks/[name=General Information]/report/publishedItems
     * </pre>
     * The segment {@code [name=General Information]} means: find the element in the {@code checks}
     * array whose {@code "name"} field equals {@code "General Information"}.
     * <p>
     * Regular path segments (e.g. {@code /report/collectionsSizesInfo/totalSize}) are resolved
     * as standard JSON object field traversal. Numeric segments (e.g. {@code /0}) are resolved
     * as array indices.
     *
     * @param rootNode  the root JSON node to resolve against
     * @param fieldPath the selector path, e.g.
     *                  {@code /checks/[name=Item summary]/report/communitiesCount}
     * @return the resolved {@link JsonNode}, or {@code null} if not found
     */
    private JsonNode resolveFieldPath(JsonNode rootNode, String fieldPath) {
        if (fieldPath == null || rootNode == null) {
            return null;
        }

        // Remove leading slash and split into segments
        String path = fieldPath.startsWith("/") ? fieldPath.substring(1) : fieldPath;
        // Split carefully: we need to handle segments like [name=General Information]
        // which contain spaces but no slashes
        List<String> segments = splitPathSegments(path);

        JsonNode current = rootNode;
        for (String segment : segments) {
            if (current == null) {
                return null;
            }

            if (segment.startsWith("[") && segment.endsWith("]")) {
                // Attribute selector, e.g. [name=General Information]
                // The previous segment should have navigated us to an array node
                if (!current.isArray()) {
                    return null;
                }
                String selectorContent = segment.substring(1, segment.length() - 1);
                int eqIndex = selectorContent.indexOf('=');
                if (eqIndex < 0) {
                    return null;
                }
                String attrName = selectorContent.substring(0, eqIndex).trim();
                String attrValue = selectorContent.substring(eqIndex + 1).trim();

                // Find matching element in the array
                JsonNode matched = null;
                for (JsonNode element : current) {
                    JsonNode attrNode = element.get(attrName);
                    if (attrNode != null && attrValue.equals(attrNode.asText())) {
                        matched = element;
                        break;
                    }
                }
                current = matched;
            } else if (current.isArray() && segment.matches("\\d+")) {
                // Numeric index into array
                int index = Integer.parseInt(segment);
                current = (index >= 0 && index < current.size()) ? current.get(index) : null;
            } else {
                // Regular object field
                current = current.get(segment);
            }
        }

        return current;
    }

    /**
     * Split a path string into segments, keeping bracket selectors as single segments.
     * For example, {@code "checks/[name=General Information]/report/directoryStats/0/size_bytes"}
     * becomes: {@code ["checks", "[name=General Information]", "report", "directoryStats", "0", "size_bytes"]}.
     *
     * <p><b>Limitation:</b> The parser finds the first {@code ]} after an opening {@code [}, so check
     * names that themselves contain bracket characters (e.g., {@code [name=Check [beta]]}) are not
     * supported and will produce incorrect segments. Check names must not contain {@code [} or {@code ]}.
     *
     * @param path the path to split (without leading slash)
     * @return list of path segments
     */
    private List<String> splitPathSegments(String path) {
        List<String> segments = new ArrayList<>();
        int i = 0;
        while (i < path.length()) {
            if (path.charAt(i) == '[') {
                // Find matching closing bracket
                int closeBracket = path.indexOf(']', i);
                if (closeBracket < 0) {
                    closeBracket = path.length() - 1;
                }
                segments.add(path.substring(i, closeBracket + 1));
                i = closeBracket + 1;
                // Skip following slash if present
                if (i < path.length() && path.charAt(i) == '/') {
                    i++;
                }
            } else {
                // Regular segment - find next slash or bracket
                int nextSlash = path.indexOf('/', i);
                int nextBracket = path.indexOf('[', i);
                int end;
                if (nextSlash < 0 && nextBracket < 0) {
                    end = path.length();
                } else if (nextSlash < 0) {
                    end = nextBracket;
                } else if (nextBracket < 0) {
                    end = nextSlash;
                } else {
                    end = Math.min(nextSlash, nextBracket);
                }
                String segment = path.substring(i, end);
                if (!segment.isEmpty()) {
                    segments.add(segment);
                }
                i = end;
                // Skip slash separator
                if (i < path.length() && path.charAt(i) == '/') {
                    i++;
                }
            }
        }
        return segments;
    }

    /**
     * Generate enhanced key changes table with dynamic sizing and configurable field names.
     * Uses selector-based field resolution that works regardless of check ordering or selection.
     * Field paths use XPath-like syntax, e.g. {@code /checks/[name=Item summary]/report/publishedItems}.
     *
     * @param oldJson the old JSON report
     * @param newJson the new JSON report
     * @param sourceReportId the ID of the source (older) report, used in column headers
     * @param targetReportId the ID of the target (newer) report, used in column headers
     * @return formatted table string
     * @throws IOException if JSON parsing fails
     */
    private String generateEnhancedKeyChangesTable(String oldJson, String newJson,
                                                   Integer sourceReportId, Integer targetReportId)
            throws IOException {
        loadFieldConfiguration();

        JsonNode oldNode = mapper.readTree(oldJson);
        JsonNode newNode = mapper.readTree(newJson);

        // Collect changes for configured fields only
        List<TableRow> changes = new ArrayList<>();

        for (String fieldPath : fieldOrder) {
            JsonNode oldValue = resolveFieldPath(oldNode, fieldPath);
            JsonNode newValue = resolveFieldPath(newNode, fieldPath);

            // Skip fields that don't exist in either report (check not present in both)
            boolean oldMissing = oldValue == null || oldValue.isMissingNode();
            boolean newMissing = newValue == null || newValue.isMissingNode();
            if (oldMissing && newMissing) {
                continue;
            }

            // For byte-typed fields (paths ending with size_bytes) render values and diff in
            // human-readable units so administrators get e.g. -5.71 KB instead of -5850.
            boolean isByteField = fieldPath.endsWith("size_bytes");
            String oldDisplay;
            String newDisplay;
            String difference;
            if (isByteField && oldValue != null && newValue != null
                    && oldValue.isNumber() && newValue.isNumber()) {
                long oldBytes = oldValue.asLong();
                long newBytes = newValue.asLong();
                oldDisplay = formatBytesHuman(oldBytes);
                newDisplay = formatBytesHuman(newBytes);
                difference = formatSignedBytesHuman(newBytes - oldBytes);
            } else {
                oldDisplay = getDisplayValue(oldValue);
                newDisplay = getDisplayValue(newValue);
                difference = calculateDifference(oldValue, newValue);
            }

            if (!Objects.equals(oldDisplay, newDisplay)) {
                String displayName = fieldMappings.getOrDefault(fieldPath, fieldPath);
                changes.add(new TableRow(displayName, oldDisplay, newDisplay, difference));
            }
        }

        if (changes.isEmpty()) {
            return "Key Changes Between Reports\n\n" +
                   "No significant changes detected between reports.\n\n";
        }

        // Compact ID-only column headers; full timestamps appear in the Executive Summary above.
        String fromHeader = "Source: ID " + sourceReportId;
        String toHeader = "Target: ID " + targetReportId;

        // Calculate dynamic column widths including header content
        int fieldWidth = Math.max("Field".length(),
                changes.stream().mapToInt(r -> r.field.length()).max().orElse(25));
        int oldWidth = Math.max(fromHeader.length(),
                changes.stream().mapToInt(r -> r.oldValue.length()).max().orElse(15));
        int newWidth = Math.max(toHeader.length(),
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
             .append(" | ").append(padRight(fromHeader, oldWidth))
             .append(" | ").append(padRight(toHeader, newWidth))
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
        handler.logInfo("Use '-s/--source' and '-t/--target' with report IDs to pick the source" +
                " and target report.");
        handler.logInfo("Use '-l/--list' to list all available reports with their IDs and timestamps.");
        handler.logInfo("Use '-m/--max' together with '--list' to limit how many entries are shown.");
        handler.logInfo("If you want to compare a specific check, use the '-c' option with the check index, " +
                "in this case you must also specify the source and target report IDs.");
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

        if (!patch.isArray() || patch.isEmpty()) {
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
     * For any primitive or object/array, toString() returns valid JSON. MissingNode.toString()
     * would return an empty string, so null/missing/JSON-null nodes are all rendered as "null".
     */
    private static String nodeToEscapedString(JsonNode node) {
        return isNullOrMissing(node) ? "null" : node.toString();
    }
}

/**
 * A simple class to hold a date and its associated arguments.
 * Used for displaying report dates with their arguments.
 */
class DateWithArgs {
    private final Integer id;
    private final String date;
    private final String args;

    public DateWithArgs(Integer id, String date, String args) {
        this.id = id;
        this.date = date;
        this.args = args;
    }

    public Integer getId() {
        return id;
    }

    public String getDate() {
        return date;
    }

    public String getArgs() {
        return args;
    }
}
