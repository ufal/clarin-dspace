/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.administer;

import java.util.List;

import org.apache.commons.cli.Options;
import org.dspace.core.Context;
import org.dspace.scripts.DSpaceCommandLineParameter;
import org.dspace.scripts.configuration.ScriptConfiguration;

public class PersonalAccessTokenCliConfiguration extends ScriptConfiguration<PersonalAccessTokenCli> {

    private Class<PersonalAccessTokenCli> dspaceRunnableClass;

    /**
     * Generic getter for the dspaceRunnableClass
     *
     * @return the dspaceRunnableClass value of this ScriptConfiguration
     */
    @Override
    public Class<PersonalAccessTokenCli> getDspaceRunnableClass() {
        return dspaceRunnableClass;
    }

    @Override
    public boolean isAllowedToExecute(Context context, List<DSpaceCommandLineParameter> commandLineParameters) {
        return false;
    }

    /**
     * Generic setter for the dspaceRunnableClass
     *
     * @param dspaceRunnableClass The dspaceRunnableClass to be set on this IndexDiscoveryScriptConfiguration
     */
    @Override
    public void setDspaceRunnableClass(Class<PersonalAccessTokenCli> dspaceRunnableClass) {
        this.dspaceRunnableClass = dspaceRunnableClass;
    }

    /**
     * The getter for the options of the Script
     *
     * @return the options value of this ScriptConfiguration
     */
    @Override
    public Options getOptions() {
        if (options == null) {

            Options options = new Options();

            options.addOption("h", "help", false, "help");

            options.addOption("x", "expiration", true,
                    "token expiration time in days or hours, (e.g. 3d or 48h), for -c option only");

            options.addOption("e", "ePerson uuid", true, "ePerson UUID");

            options.addOption("c", "create token", false, "create token");

            options.addOption("d", "delete token(s)", false,
                    "delete token for given ePerson (or delete all tokens when -e option is missing)");

            super.options = options;
        }
        return options;
    }
}
