/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.bulkaccesscontrol;

import java.io.InputStream;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.dspace.app.util.DSpaceObjectUtilsImpl;
import org.dspace.app.util.service.DSpaceObjectUtils;
import org.dspace.content.DSpaceObject;
import org.dspace.core.Context;
import org.dspace.scripts.DSpaceCommandLineParameter;
import org.dspace.scripts.configuration.ScriptConfiguration;
import org.dspace.utils.DSpace;

/**
 * Script configuration for {@link BulkAccessControl}.
 *
 * @author Mohamed Eskander (mohamed.eskander at 4science.it)
 *
 * @param  <T> the {@link BulkAccessControl} type
 */
public class BulkAccessControlScriptConfiguration<T extends BulkAccessControl> extends ScriptConfiguration<T> {

    private Class<T> dspaceRunnableClass;

    @Override
    public boolean isAllowedToExecute(Context context, List<DSpaceCommandLineParameter> commandLineParameters) {

        try {
            if (Objects.isNull(commandLineParameters)) {
                return authorizeService.isAdmin(context) || authorizeService.isComColAdmin(context)
                    || authorizeService.isItemAdmin(context);
            }

            String[] dspaceObjectIDs = getTargetUuids(commandLineParameters);
            if (Objects.isNull(dspaceObjectIDs)) {
                // Without target uuids there is nothing to check per object, so fail closed: only a site admin
                // may go on. The run would fail before modifying anything, but this check must not rely on that.
                return authorizeService.isAdmin(context);
            } else {
                DSpaceObjectUtils dSpaceObjectUtils = new DSpace().getServiceManager().getServiceByName(
                    DSpaceObjectUtilsImpl.class.getName(), DSpaceObjectUtilsImpl.class);

                for (String dspaceObjectID : dspaceObjectIDs) {

                    DSpaceObject dso = dSpaceObjectUtils.findDSpaceObject(context, UUID.fromString(dspaceObjectID));

                    if (Objects.isNull(dso)) {
                        throw new IllegalArgumentException();
                    }

                    if (!authorizeService.isAdmin(context, dso)) {
                        return false;
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        return true;
    }

    /**
     * Resolve the target uuids exactly as the runnable will, by parsing the parameters with the same parser and
     * options. Matching parameter names (e.g. only "-u") is not enough: "--uuid", "--uuid=X", "-uX" or an
     * abbreviated "--uu" are all accepted by the parser and would otherwise skip the per-object check.
     *
     * @return the target uuids, or null when there is no uuid or the parameters cannot be parsed
     */
    private String[] getTargetUuids(List<DSpaceCommandLineParameter> commandLineParameters) {
        List<String> args = new ArrayList<>();
        for (DSpaceCommandLineParameter parameter : commandLineParameters) {
            args.add(parameter.getName());
            if (parameter.getValue() != null) {
                args.add(parameter.getValue());
            }
        }

        try {
            return new DefaultParser().parse(getOptions(), args.toArray(new String[0])).getOptionValues('u');
        } catch (ParseException e) {
            return null;
        }
    }

    @Override
    public Options getOptions() {
        if (options == null) {
            Options options = new Options();

            options.addOption("u", "uuid", true, "target uuids of communities/collections/items");
            options.getOption("u").setType(String.class);
            options.getOption("u").setRequired(true);

            options.addOption("f", "file", true, "source json file");
            options.getOption("f").setType(InputStream.class);
            options.getOption("f").setRequired(true);

            options.addOption("h", "help", false, "help");

            super.options = options;
        }
        return options;
    }

    @Override
    public Class<T> getDspaceRunnableClass() {
        return dspaceRunnableClass;
    }

    /**
     * Generic setter for the dspaceRunnableClass
     *
     * @param dspaceRunnableClass The dspaceRunnableClass to be set on this
     *                            BulkImportScriptConfiguration
     */
    @Override
    public void setDspaceRunnableClass(Class<T> dspaceRunnableClass) {
        this.dspaceRunnableClass = dspaceRunnableClass;
    }

}
