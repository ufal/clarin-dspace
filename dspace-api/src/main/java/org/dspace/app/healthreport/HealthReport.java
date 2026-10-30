/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.healthreport;

import static org.apache.commons.io.IOUtils.toInputStream;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import jakarta.mail.MessagingException;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.ParseException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.dspace.content.ReportResult;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.ReportResultService;
import org.dspace.core.Context;
import org.dspace.core.Email;
import org.dspace.core.I18nUtil;
import org.dspace.core.factory.CoreServiceFactory;
import org.dspace.core.service.PluginService;
import org.dspace.eperson.factory.EPersonServiceFactory;
import org.dspace.eperson.service.EPersonService;
import org.dspace.health.Check;
import org.dspace.health.ReportInfo;
import org.dspace.scripts.DSpaceRunnable;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.dspace.utils.DSpace;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * This class is used to generate a health report of the DSpace instance.
 * @author Matus Kasak (dspace at dataquest.sk)
 * @author Milan Majchrak (dspace at dataquest.sk)
 */
public class HealthReport extends DSpaceRunnable<HealthReportScriptConfiguration> {
    private static final Logger log = LogManager.getLogger(HealthReport.class);

    private ConfigurationService configurationService = DSpaceServicesFactory.getInstance().getConfigurationService();
    private ReportResultService reportResultService = ContentServiceFactory.getInstance().getReportResultService();
    private EPersonService ePersonService;

    /**
     * Checks to be performed.
     */
    private static final LinkedHashMap<String, Check> checks = getChecks();

    /**
     * `-h`: Help, show help information.
     */
    private boolean help = false;

    /**
     * `-e`: Email, send report to specified email address.
     */
    private String[] emails;

    /**
     * `-c`: Check, perform only specific checks by index (0-`getNumberOfChecks()`).
     * Supports multiple values.
     */
    private List<Integer> specificChecks = new ArrayList<>();

    /**
     * `-f`: For, specify the last N days to consider.
     * Default value is set in dspace.cfg.
     */
    private int forLastNDays = configurationService.getIntProperty("healthcheck.last_n_days");

    /**
     * `-r`: Report, specify a file to save the report.
     */
    private String reportFile;

    @Override
    public HealthReportScriptConfiguration getScriptConfiguration() {
        return new DSpace().getServiceManager()
                .getServiceByName("health-report", HealthReportScriptConfiguration.class);
    }

    @Override
    public void setup() throws ParseException {
        ePersonService = EPersonServiceFactory.getInstance().getEPersonService();
        // `-h`: Help, show help information.
        if (commandLine.hasOption('h')) {
            help = true;
            return;
        }

        // `-e`: Email, send report to specified email address.
        if (commandLine.hasOption('e')) {
            emails = commandLine.getOptionValues('e');
        }

        // `-c`: Check, perform only specific checks by index (0-`getNumberOfChecks()`).
        // Supports multiple values e.g. -c 0 -c 3 -c 4
        if (commandLine.hasOption('c')) {
            String[] checkOptions = commandLine.getOptionValues('c');
            for (String checkOption : checkOptions) {
                try {
                    int checkIndex = Integer.parseInt(checkOption);
                    if (checkIndex < 0 || checkIndex >= getNumberOfChecks()) {
                        handler.logError("Invalid value for check: " + checkOption +
                                ". Must be an integer from 0 to " + (getNumberOfChecks() - 1) + ".");
                        throw new ParseException("Invalid check index: " + checkOption);
                    }
                    specificChecks.add(checkIndex);
                } catch (NumberFormatException e) {
                    handler.logError("Invalid value for check: '" + checkOption +
                            "'. It has to be an integer number from 0 to " + (getNumberOfChecks() - 1) + ".");
                    throw new ParseException("Invalid check value: " + checkOption);
                }
            }
        }

        // `-f`: For, specify the last N days to consider. Must be a positive integer.
        if (commandLine.hasOption('f')) {
            String daysOption = commandLine.getOptionValue('f');
            try {
                forLastNDays = Integer.parseInt(daysOption);
                if (forLastNDays <= 0) {
                    handler.logError("Invalid value for -f: " + daysOption +
                            ". Must be a positive integer (greater than 0).");
                    throw new ParseException("Invalid -f value: " + daysOption);
                }
            } catch (NumberFormatException e) {
                handler.logError("Invalid value for -f: '" + daysOption +
                        "'. Must be a positive integer.");
                throw new ParseException("Invalid -f value: " + daysOption);
            }
        }

        // `-r`: Report, specify a file to save the report.
        if (commandLine.hasOption('r')) {
            reportFile = commandLine.getOptionValue('r');
        }
    }

    @Override
    public void internalRun() throws Exception {
        if (help) {
            printHelp();
            return;
        }

        try (Context context = new Context()) {
            context.setCurrentUser(ePersonService.find(context, this.getEpersonIdentifier()));

            ReportInfo ri = new ReportInfo(this.forLastNDays);

            StringBuilder sbReport = new StringBuilder();

            int position = -1;
            JSONObject root = new JSONObject();
            // Create the array
            JSONArray checksArray = new JSONArray();
            for (Map.Entry<String, Check> check_entry : checks.entrySet()) {
                ++position;
                if (!specificChecks.isEmpty() && !specificChecks.contains(position)) {
                    continue;
                }

                String name = check_entry.getKey();
                Check check = check_entry.getValue();

                log.info("#{}. Processing [{}] at [{}]", position, name, new SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss.SSS").format(new Date()));

                sbReport.append("\n######################\n\n").append(name).append(":\n");
                check.report(ri);
                sbReport.append(check.getReport());

                // JSON:
                // Check name: {Report}
                JSONObject report = check.getReportJson();  // assume check is already defined
                if (report == null) {
                    report = new JSONObject(); // or handle appropriately
                    log.warn("Check {} returned null JSON report", name);
                }
                JSONObject checkJson = new JSONObject();
                checkJson.put("name", name);
                checkJson.put("report", report);
                // Add items to array
                checksArray.put(checkJson);
            }

            // Add array to root object under a key "checks"
            root.put("checks", checksArray);

            // Add health report summary to the ReportResult object
            ReportResult reportResult = reportResultService.create(context);
            reportResult.setArgs(printCommandlineOptions());
            reportResult.setExecutor(context.getCurrentUser());
            reportResult.setType("healthcheck");
            reportResult.setValue(root.toString());
            reportResultService.update(context, reportResult);
            context.commit();

            // Prepend the header with the persisted report ID so users can refer to it later
            String finalReport = "\n\nHEALTH REPORT " + reportResult.getID() + ":\n" + sbReport.toString();

            // save output to file
            if (reportFile != null) {
                InputStream inputStream = toInputStream(finalReport, StandardCharsets.UTF_8);
                handler.writeFilestream(context, reportFile, inputStream, "export");
                context.commit();

                context.restoreAuthSystemState();

            }

            // send email to email address from argument
            if (emails != null && emails.length > 0) {
                try {
                    Email e = Email.getEmail(I18nUtil.getEmailFilename(Locale.getDefault(), "healthcheck"));
                    for (String recipient : emails) {
                        e.addRecipient(recipient);
                    }
                    e.addArgument(finalReport);
                    e.send();
                    handler.logInfo("Report sent to: " + String.join(", ", emails));
                } catch (IOException | MessagingException e) {
                    log.error("Error sending email:", e);
                    handler.logError("Error sending email to " + String.join(", ", emails)
                            + ": " + e.getMessage());
                }
            }

            handler.logInfo(finalReport);
        }
    }

    @Override
    public void printHelp() {
        int configuredForLastNDays = configurationService.getIntProperty("healthcheck.last_n_days");
        handler.logInfo("\n\nHELP\nThis process creates a health report of your DSpace.\n" +
                "You can choose from these available options:\n" +
                "  -h, --help            Show help information\n" +
                "  -e, --email           Send report to specified email address\n" +
                "  -c, --check           Perform specific check(s) by index (0-" + (getNumberOfChecks() - 1) +
                "). Repeat the flag (e.g. -c 1 -c 3) to run multiple checks. " +
                "Default: All checks\n" +
                "  -f, --for             Specify the last N days to consider (positive integer). " +
                "Default: " + configuredForLastNDays + "\n" +
                "  -r, --report          Specify a file to save the report\n\n" +
                "Available checks:\n" + checksNamesToString() + "\n"
        );
    }

    /**
     * Print command line options in a readable format.
     * This method is used to print the options used for the report.
     */
    private String printCommandlineOptions() {
        StringBuilder options = new StringBuilder();
        Set<String> processedOptions = new LinkedHashSet<>();

        for (Option option : commandLine.getOptions()) {
            String key = option.getOpt();
            if (key == null || processedOptions.contains(key)) {
                continue;
            }
            processedOptions.add(key);

            String[] values = commandLine.getOptionValues(key);
            if (values != null && values.length > 0) {
                for (String value : values) {
                    options.append(String.format("  -%s: %s\n", key, value));
                }
            } else {
                options.append(String.format("  -%s\n", key));
            }
        }
        return options.toString();
    }

    /**
     * Convert checks names to string.
     */
    private String checksNamesToString() {
        StringBuilder names = new StringBuilder();
        int pos = 0;
        for (String name : checks.keySet()) {
            names.append(String.format("   %d. %s\n", pos++, name));
        }
        return names.toString();
    }

    /**
     * Get the number of checks. This is used for the `-c` option.
     */
    public static int getNumberOfChecks() {
        return checks.size();
    }

    /**
     * Get the name of a specific check by its index.
     * This is used for the `-c` option.
     *
     * @param specificCheck the index of the check
     * @return the name of the check, or null if the index is invalid
     */
    public static String getCheckName(int specificCheck) {
        if (specificCheck < 0 || specificCheck >= getNumberOfChecks()) {
            return null;
        }
        int pos = 0;
        for (String name : checks.keySet()) {
            if (pos == specificCheck) {
                return name;
            }
            pos++;
        }
        return null; // should not happen
    }

    /**
     * Create check list from configured healthcheck plugins.
     */
    private static LinkedHashMap<String, Check> getChecks() {
        LinkedHashMap<String, Check> loadedChecks = new LinkedHashMap<>();
        String[] checkNames = DSpaceServicesFactory.getInstance().getConfigurationService()
                .getArrayProperty("healthcheck.checks");
        PluginService pluginService = CoreServiceFactory.getInstance().getPluginService();

        for (String checkName : checkNames) {
            Check check = (Check) pluginService.getNamedPlugin(Check.class, checkName);
            if (check != null) {
                loadedChecks.put(checkName, check);
            } else {
                log.warn("Could not find implementation for [{}]", checkName);
            }
        }

        return loadedChecks;
    }
}
