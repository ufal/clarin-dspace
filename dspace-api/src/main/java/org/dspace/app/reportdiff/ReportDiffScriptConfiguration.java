/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.reportdiff;

import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.dspace.app.healthreport.HealthReport;
import org.dspace.scripts.configuration.ScriptConfiguration;

/**
 * This class represents a ReportDiff script configuration that is used in the CLI.
 * @author Matus Kasak (dspace at dataquest.sk)
 */
public class ReportDiffScriptConfiguration<T extends ReportDiff> extends ScriptConfiguration<T> {

    private Class<T> dspaceRunnableClass;

    @Override
    public Class<T> getDspaceRunnableClass() {
        return dspaceRunnableClass;
    }

    @Override
    public void setDspaceRunnableClass(Class<T> dspaceRunnableClass) {
        this.dspaceRunnableClass = dspaceRunnableClass;
    }

    @Override
    public Options getOptions() {
        if (options == null) {
            Options options = new Options();
            options.addOption("h", "help", false,
                    "Show help information.");
            options.addOption("e", "email", true,
                    "Send report to this email address.");
            options.getOption("e").setType(String.class);
            Option checkOption = Option.builder("c").longOpt("check").hasArgs()
                    .desc(String.format("Filter comparison to one or more specific checks by index (0 to %d). " +
                            "Repeat the flag (e.g. -c 1 -c 3) to compare multiple checks from both reports.",
                            HealthReport.getNumberOfChecks() - 1))
                    .type(String.class)
                    .build();
            options.addOption(checkOption);

            options.addOption("l", "list", false,
                    "List available reports (ID, timestamp, args). Use to find report IDs.");

            options.addOption("m", "max", true,
                    "Limit the number of entries (use only with -l). If omitted, all entries are shown.");
            options.getOption("m").setType(String.class);

            options.addOption("s", "source", true,
                    "Source report ID to compare from.");
            options.getOption("s").setType(String.class);

            options.addOption("t", "target", true,
                    "Target report ID to compare against.");
            options.getOption("t").setType(String.class);

            super.options =  options;
        }
        return options;
    }
}
