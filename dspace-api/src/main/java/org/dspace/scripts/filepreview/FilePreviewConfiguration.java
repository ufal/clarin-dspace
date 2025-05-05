/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.scripts.filepreview;

import org.apache.commons.cli.Options;
import org.dspace.scripts.configuration.ScriptConfiguration;

/**
 * This class represents a FilePreview that is used in the CLI.
 * @author Milan Majchrak at (dspace at dataquest.sk)
 */
public class FilePreviewConfiguration<T extends FilePreview> extends ScriptConfiguration<T> {

    private Class<T> dspaceRunnableclass;

    @Override
    public Class<T> getDspaceRunnableClass() {
        return dspaceRunnableclass;
    }

    @Override
    public void setDspaceRunnableClass(Class<T> dspaceRunnableClass) {
        this.dspaceRunnableclass = dspaceRunnableClass;
    }

    @Override
    public Options getOptions() {
        if (options == null) {
            Options options = new Options();
            options.addOption("i", "info", false, "Show help information.");

            options.addOption("u", "uuid", true,
                    "The UUID of the ITEM for which to create a preview of its bitstreams.");
            options.getOption("u").setType(String.class);
            options.getOption("u").setRequired(false);

            options.addOption("e", "email", true,
                    "Email for authentication.");
            options.getOption("e").setType(String.class);
            options.getOption("e").setRequired(true);

            options.addOption("p", "password", true,
                    "Password for authentication.");
            options.getOption("p").setType(String.class);
            options.getOption("p").setRequired(true);

            super.options = options;
        }
        return options;
    }
}
