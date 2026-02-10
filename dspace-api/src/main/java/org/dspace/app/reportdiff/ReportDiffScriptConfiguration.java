/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.reportdiff;

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
            options.addOption("i", "info", false,
                    "Show help information.");
            options.addOption("e", "email", true,
                    "Send report to this email address.");
            options.getOption("e").setType(String.class);
            options.addOption("c", "check", true,
                    String.format("Perform only specific check (use index from 0 to %d, " +
                            "otherwise perform default checks).", HealthReport.getNumberOfChecks() - 1));
            options.getOption("c").setType(String.class);

            options.addOption("d", "dates", false, "Show all report dates");

            options.addOption("l", "limit", true,
                    "Limit the number of entries (use only with -d). If omitted, all entries are shown.");
            options.getOption("l").setType(String.class);

            options.addOption("f", "from", true,"Report from specific date [YYYY-MM-DD HH:mm:ss.SSS].");
            options.getOption("f").setType(String.class);

            options.addOption("t", "to", true,"Report to specific date [YYYY-MM-DD HH:mm:ss.SSS].");
            options.getOption("t").setType(String.class);

            super.options =  options;
        }
        return options;
    }
}
