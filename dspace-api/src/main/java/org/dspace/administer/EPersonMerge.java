/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.administer;

import java.util.UUID;

import org.apache.commons.cli.ParseException;
import org.dspace.core.Context;
import org.dspace.eperson.EPerson;
import org.dspace.eperson.clarin.EPersonMergeAudit;
import org.dspace.eperson.factory.EPersonServiceFactory;
import org.dspace.eperson.service.EPersonService;
import org.dspace.eperson.service.clarin.EPersonMergeService;
import org.dspace.scripts.DSpaceRunnable;
import org.dspace.utils.DSpace;

/**
 * Merges two EPerson accounts that turned out to be duplicates of the same human: re-points
 * foreign keys from {@code --from} to {@code --to}, unions/dedupes memberships, tombstones the
 * {@code from} account (disabled, never deleted) and records an audit trail. Does not rewrite
 * item metadata/provenance.
 *
 * @author Ondrej Kosarko
 */
public class EPersonMerge extends DSpaceRunnable<EPersonMergeConfiguration<EPersonMerge>> {

    private EPersonService ePersonService;
    private EPersonMergeService ePersonMergeService;

    private UUID fromUuid;
    private UUID toUuid;
    private boolean help = false;

    @Override
    public void setup() throws ParseException {
        this.ePersonService = EPersonServiceFactory.getInstance().getEPersonService();
        this.ePersonMergeService = EPersonServiceFactory.getInstance().getEPersonMergeService();

        this.help = commandLine.hasOption('h');
        if (this.help) {
            return;
        }

        try {
            this.fromUuid = UUID.fromString(commandLine.getOptionValue('f'));
            this.toUuid = UUID.fromString(commandLine.getOptionValue('t'));
        } catch (IllegalArgumentException e) {
            throw new ParseException("--from and --to must both be valid EPerson UUIDs");
        }
    }

    @Override
    public void internalRun() throws Exception {
        if (help) {
            printHelp();
            return;
        }

        Context context = new Context();
        context.setCurrentUser(ePersonService.find(context, getEpersonIdentifier()));

        try {
            context.turnOffAuthorisationSystem();

            EPerson from = ePersonService.find(context, fromUuid);
            if (from == null) {
                throw new IllegalArgumentException("No EPerson found for --from " + fromUuid);
            }
            EPerson to = ePersonService.find(context, toUuid);
            if (to == null) {
                throw new IllegalArgumentException("No EPerson found for --to " + toUuid);
            }

            EPersonMergeAudit audit = ePersonMergeService.merge(context, from, to, context.getCurrentUser());
            handler.logInfo("Merged EPerson " + fromUuid + " into " + toUuid + ": " + audit.getDetail());
        } finally {
            context.restoreAuthSystemState();
            context.complete();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public EPersonMergeConfiguration<EPersonMerge> getScriptConfiguration() {
        return new DSpace().getServiceManager()
            .getServiceByName("eperson-merge", EPersonMergeConfiguration.class);
    }
}
