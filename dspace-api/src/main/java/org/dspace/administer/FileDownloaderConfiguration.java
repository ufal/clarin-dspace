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

public class FileDownloaderConfiguration extends ScriptConfiguration<FileDownloader> {

    private Class<FileDownloader> dspaceRunnableClass;

    /**
     * Generic getter for the dspaceRunnableClass
     *
     * @return the dspaceRunnableClass value of this ScriptConfiguration
     */
    @Override
    public Class<FileDownloader> getDspaceRunnableClass() {
        return dspaceRunnableClass;
    }

    /**
     * Generic setter for the dspaceRunnableClass
     *
     * @param dspaceRunnableClass The dspaceRunnableClass to be set on this IndexDiscoveryScriptConfiguration
     */
    @Override
    public void setDspaceRunnableClass(Class<FileDownloader> dspaceRunnableClass) {
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

            options.addOption("u", "url", true, "source url");
            options.getOption("u").setRequired(true);

            options.addOption("i", "item", true, "item uuid");
            options.getOption("i").setRequired(true);

            options.addOption("e", "eperson", true, "eperson email");
            options.getOption("e").setRequired(false);

            super.options = options;
        }
        return options;
    }
}
