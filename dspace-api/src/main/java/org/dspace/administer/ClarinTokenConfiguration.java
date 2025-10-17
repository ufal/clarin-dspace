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

public class ClarinTokenConfiguration extends ScriptConfiguration<ClarinTokenCreator> {

    private Class<ClarinTokenCreator> dspaceRunnableClass;

    /**
     * Generic getter for the dspaceRunnableClass
     *
     * @return the dspaceRunnableClass value of this ScriptConfiguration
     */
    @Override
    public Class<ClarinTokenCreator> getDspaceRunnableClass() {
        return dspaceRunnableClass;
    }

    /**
     * Generic setter for the dspaceRunnableClass
     *
     * @param dspaceRunnableClass The dspaceRunnableClass to be set on this IndexDiscoveryScriptConfiguration
     */
    @Override
    public void setDspaceRunnableClass(Class<ClarinTokenCreator> dspaceRunnableClass) {
        this.dspaceRunnableClass = dspaceRunnableClass;
    }

    /**
     * This script is allowed to execute to any authorized user. Further access control mechanism then checks,
     * if the current user is authorized to download a file to the item specified in command line parameters.
     *
     * @param context   The relevant DSpace context
     * @param commandLineParameters the parameters that will be used to start the process if known,
     *        <code>null</code> otherwise
     * @return          A boolean indicating whether the script is allowed to execute or not
     */
    @Override
    public boolean isAllowedToExecute(Context context, List<DSpaceCommandLineParameter> commandLineParameters) {
        return context.getCurrentUser() != null;
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

            options.addOption("c", "create", false, "create new token");
            options.addOption("d", "delete", false, "delete/deactivate token");

            options.addOption("x", "expiration", true,
                    "token expiration in days or hours, e.g. 3d or 48h [required for token create]");
            options.addOption("e", "email", true,
                    "e-mail to send newly created access token [optional for token create]");
            options.addOption("t", "token", true, "token to delete/deactivate [required for token delete]");

            super.options = options;
        }
        return options;
    }
}
