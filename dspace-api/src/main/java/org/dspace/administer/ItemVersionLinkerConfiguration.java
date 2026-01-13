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
 * The {@link ScriptConfiguration} for the {@link ItemVersionLinker} script.
 *
 * @author Milan Kuchtiak
 */
public class ItemVersionLinkerConfiguration extends ScriptConfiguration<ItemVersionLinker> {

    private Class<ItemVersionLinker> dspaceRunnableClass;

    /**
     * Generic getter for the dspaceRunnableClass
     *
     * @return the dspaceRunnableClass value of this ScriptConfiguration
     */
    @Override
    public Class<ItemVersionLinker> getDspaceRunnableClass() {
        return dspaceRunnableClass;
    }

    /**
     * Generic setter for the dspaceRunnableClass
     *
     * @param dspaceRunnableClass The dspaceRunnableClass to be set on this IndexDiscoveryScriptConfiguration
     */
    @Override
    public void setDspaceRunnableClass(Class<ItemVersionLinker> dspaceRunnableClass) {
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

            options.addOption("l", "link", false, "link the item with the previous item");

            options.addOption("u", "unlink", false, "unlink the item from the previous item in version history.");

            options.addOption("p", "previous", true,
                    "item handle, or UUID, of the previous (left) item intended to be linked with the item " +
                            "(only required for link option)");

            options.addOption("i", "item", true,
                    "item handle, or UUID, of the (right) item intended to be linked/unlinked with/from the " +
                            "previous item (required for both link and unlink options)");

            super.options = options;
        }
        return options;
    }
}
