/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.administer;

import org.apache.commons.cli.OptionGroup;
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
            OptionGroup ids = new OptionGroup();

            options.addOption("h", "help", false, "help");

            options.addOption("u", "url", true, "source url");
            options.getOption("u").setRequired(true);

            options.addOption("i", "uuid", true, "item uuid");
            options.addOption("w", "wsid", true, "workspace id");
            options.addOption("p", "pid", true, "item pid (e.g. handle or doi)");
            ids.addOption(options.getOption("i"));
            ids.addOption(options.getOption("w"));
            ids.addOption(options.getOption("p"));
            ids.setRequired(true);

            options.addOption("e", "eperson", true, "eperson email");
            options.getOption("e").setRequired(false);

            options.addOption("n", "name", true, "name of the file/bitstream");
            options.getOption("n").setRequired(false);

            super.options = options;
        }
        return options;
    }
}
