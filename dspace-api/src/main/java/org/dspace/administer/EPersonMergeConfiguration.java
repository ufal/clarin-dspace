/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.administer;

import org.apache.commons.cli.Options;
import org.dspace.scripts.configuration.ScriptConfiguration;

/**
 * The {@link ScriptConfiguration} for the {@link EPersonMerge} script. Restricted to admins by
 * the {@link ScriptConfiguration} default {@code isAllowedToExecute}.
 *
 * @author Ondrej Kosarko
 */
public class EPersonMergeConfiguration<T extends EPersonMerge> extends ScriptConfiguration<T> {

    private Class<T> dspaceRunnableClass;

    @Override
    public Options getOptions() {
        if (options == null) {
            Options options = new Options();

            options.addOption("h", "help", false, "help");

            options.addOption("f", "from", true,
                "UUID of the EPerson to merge away (tombstoned - disabled, not deleted)");
            options.getOption("f").setRequired(true);

            options.addOption("t", "to", true, "UUID of the EPerson to merge into (the surviving account)");
            options.getOption("t").setRequired(true);

            options.addOption("e", "eperson", true,
                "email of the admin EPerson performing the merge (recorded in the audit trail)");

            super.options = options;
        }
        return options;
    }

    @Override
    public Class<T> getDspaceRunnableClass() {
        return dspaceRunnableClass;
    }

    @Override
    public void setDspaceRunnableClass(Class<T> dspaceRunnableClass) {
        this.dspaceRunnableClass = dspaceRunnableClass;
    }

}
