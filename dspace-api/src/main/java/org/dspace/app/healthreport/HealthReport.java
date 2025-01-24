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
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import javax.mail.MessagingException;

import org.apache.commons.cli.ParseException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.dspace.core.Context;
import org.dspace.core.Email;
import org.dspace.core.I18nUtil;
import org.dspace.eperson.factory.EPersonServiceFactory;
import org.dspace.eperson.service.EPersonService;
import org.dspace.health.Check;
import org.dspace.health.Report;
import org.dspace.health.ReportInfo;
import org.dspace.scripts.DSpaceRunnable;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.dspace.utils.DSpace;

/**
 * This class is used to generate a health report of the DSpace instance.
 * @author Matus Kasak (dspace at dataquest.sk)
 * @author Milan Majchrak (dspace at dataquest.sk)
 */
public class HealthReport extends DSpaceRunnable<HealthReportScriptConfiguration> {
    ConfigurationService configurationService = DSpaceServicesFactory.getInstance().getConfigurationService();
    private static final Logger log = LogManager.getLogger(HealthReport.class);
    private EPersonService ePersonService;

    /**
     * Checks to be performed.
     */
    private static final LinkedHashMap<String, Check> checks = Report.checks();

    /**
     * `-i`: Info, show help information.
     */
    private boolean info = false;

    /**
     * `-e`: Email, send report to specified email address.
     */
    private String email;

    /**
     * `-c`: Check, perform only specific check by index (0-`getNumberOfChecks()`).
     */
    private int specificCheck = -1;

    /**
     * `-f`: For, specify the last N days to consider.
     * Default value is set in dspace.cfg.
     */
    private int forLastNDays = configurationService.getIntProperty("healthcheck.last_n_days");

    /**
     * `-o`: Output, specify a file to save the report.
     */
    private String fileName;

    @Override
    public HealthReportScriptConfiguration getScriptConfiguration() {
        return new DSpace().getServiceManager()
                .getServiceByName("health-report", HealthReportScriptConfiguration.class);
    }

    @Override
    public void setup() throws ParseException {
        ePersonService = EPersonServiceFactory.getInstance().getEPersonService();
        // `-i`: Info, show help information.
        if (commandLine.hasOption('i')) {
            info = true;
            return;
        }

        // `-e`: Email, send report to specified email address.
        if (commandLine.hasOption('e')) {
            email = commandLine.getOptionValue('e');
            handler.logInfo("\nReport sent to this email address: " + email);
        }

        // `-c`: Check, perform only specific check by index (0-`getNumberOfChecks()`).
        if (commandLine.hasOption('c')) {
            String checkOption = commandLine.getOptionValue('c');
            try {
                specificCheck = Integer.parseInt(checkOption);
                if (specificCheck < 0 || specificCheck >= getNumberOfChecks()) {
                    specificCheck = -1;
                }
            } catch (NumberFormatException e) {
                log.info("Invalid value for check. It has to be a number from the displayed range.");
                return;
            }
        }

        // `-f`: For, specify the last N days to consider.
        if (commandLine.hasOption('f')) {
            String daysOption = commandLine.getOptionValue('f');
            try {
                forLastNDays = Integer.parseInt(daysOption);
            } catch (NumberFormatException e) {
                log.info("Invalid value for last N days. Argument f has to be a number.");
                return;
            }
        }

        // `-o`: Output, specify a file to save the report.
        if (commandLine.hasOption('o')) {
            fileName = commandLine.getOptionValue('o');
        }
    }

    @Override
    public void internalRun() throws Exception {
        if (info) {
            printHelp();
            return;
        }

        ReportInfo ri = new ReportInfo(this.forLastNDays);

        StringBuilder sbReport = new StringBuilder();
        sbReport.append("\n\nHEALTH REPORT:\n");

        int position = -1;
        for (Map.Entry<String, Check> check_entry : Report.checks().entrySet()) {
            ++position;
            if (specificCheck != -1 && specificCheck != position) {
                continue;
            }

            String name = check_entry.getKey();
            Check check = check_entry.getValue();

            log.info("#{}. Processing [{}] at [{}]", position, name, new SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss.SSS").format(new Date()));

            sbReport.append("\n######################\n\n").append(name).append(":\n");
            check.report(ri);
            sbReport.append(check.getReport());
        }

        // save output to file
        if (fileName != null) {
            Context context = new Context();
            context.setCurrentUser(ePersonService.find(context, this.getEpersonIdentifier()));

            InputStream inputStream = toInputStream(sbReport.toString(), StandardCharsets.UTF_8);
            handler.writeFilestream(context, fileName, inputStream, "export");

            context.restoreAuthSystemState();
            context.complete();
        }

        // send email to email address from argument
        if (email != null) {
            try {
                Email e = Email.getEmail(I18nUtil.getEmailFilename(Locale.getDefault(), "healthcheck"));
                e.addRecipient(email);
                e.addArgument(sbReport.toString());
                e.send();
            } catch (IOException | MessagingException e) {
                log.error("Error sending email:", e);
            }
        }

        handler.logInfo(sbReport.toString());
    }

    @Override
    public void printHelp() {
        handler.logInfo("\n\nINFORMATION\nThis process creates a health report of your DSpace.\n" +
                "You can choose from these available options:\n" +
                "  -i, --info            Show help information\n" +
                "  -e, --email           Send report to specified email address\n" +
                "  -c, --check           Perform only specific check by index (0-" + (getNumberOfChecks() - 1) + ")\n" +
                "  -f, --for             Specify the last N days to consider\n" +
                "  -o, --output          Specify a file to save the report\n\n" +
                "If you want to execute only one check using -c, use check index:\n" + checksNamesToString() + "\n"
        );
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
}
