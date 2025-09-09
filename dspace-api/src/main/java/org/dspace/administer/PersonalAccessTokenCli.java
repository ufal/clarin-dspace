/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.administer;

import java.util.Date;
import java.util.UUID;

import org.apache.commons.cli.ParseException;
import org.dspace.content.factory.ClarinServiceFactory;
import org.dspace.content.service.clarin.PersonalAccessTokenService;
import org.dspace.core.Context;
import org.dspace.eperson.factory.EPersonServiceFactory;
import org.dspace.eperson.service.EPersonService;
import org.dspace.scripts.DSpaceRunnable;
import org.dspace.scripts.configuration.ScriptConfiguration;
import org.dspace.utils.DSpace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PersonalAccessTokenCli extends DSpaceRunnable<PersonalAccessTokenCliConfiguration> {

    private static final Logger log = LoggerFactory.getLogger(PersonalAccessTokenCli.class);
    private boolean help = false;
    private UUID ePersonUUID;
    private boolean isCreate;
    private boolean isDelete;
    private Date expirationDate;
    private PersonalAccessTokenService personalAccessTokenService;
    private EPersonService ePersonService;

    /**
     * This method will return the Configuration that the implementing DSpaceRunnable uses
     *
     * @return The {@link ScriptConfiguration} that this implementing DspaceRunnable uses
     */
    @Override
    public PersonalAccessTokenCliConfiguration getScriptConfiguration() {
        return new DSpace().getServiceManager().getServiceByName("personal-access-token-manager",
                PersonalAccessTokenCliConfiguration.class);
    }

    /**
     * This method has to be included in every script and handles the setup of the script by parsing the CommandLine
     * and setting the variables
     *
     * @throws ParseException If something goes wrong
     */
    @Override
    public void setup() throws ParseException {
        log.debug("Setting up {}", PersonalAccessTokenCli.class.getName());
        if (commandLine.hasOption("h")) {
            help = true;
            return;
        }

        isCreate = commandLine.hasOption("c");
        isDelete = commandLine.hasOption("d");

        if (!isCreate && !isDelete) {
            throw new ParseException("Either create or delete option is required");
        }

        if (isCreate && !commandLine.hasOption("e")) {
            throw new ParseException("ePerson UUID option is missing");
        }

        if (isCreate && !commandLine.hasOption("x")) {
            throw new ParseException("Token expiration time option is missing");
        }

        personalAccessTokenService = ClarinServiceFactory.getInstance().getPersonalAccessTokenService();

        if (commandLine.hasOption("e")) {
            ePersonUUID = UUID.fromString(commandLine.getOptionValue("e"));
        }

        if (isCreate) {
            expirationDate = PersonalAccessTokenCreator.getExpirationDate(
                    commandLine.getOptionValue("x").toLowerCase());
            ePersonService = EPersonServiceFactory.getInstance().getEPersonService();
        }
    }

    /**
     * This method has to be included in every script and this will be the main execution block for the script that'll
     * contain all the logic needed
     *
     * @throws Exception If something goes wrong
     */
    @Override
    public void internalRun() throws Exception {
        log.debug("Running {}", PersonalAccessTokenCli.class.getName());
        if (help) {
            printHelp();
            return;
        }

        Context context = new Context();
        try {
            context.turnOffAuthorisationSystem();
            performScript(context);
        } finally {
            context.restoreAuthSystemState();
            context.complete();
        }
    }

    protected void performScript(Context context) throws Exception {
        if (isCreate) {
            String token = personalAccessTokenService.createToken(context, ePersonUUID, expirationDate);
            log.debug("Personal Access Token created: {}", PersonalAccessTokenCreator.getSecureToken(token));
            System.out.printf("Personal Access Token created: %s\n", token);
            System.out.printf("For user: %s, with ID: %s\n",
                    ePersonService.find(context, ePersonUUID).getEmail(), ePersonUUID);
        } else if (isDelete) {
            if (ePersonUUID != null) {
                personalAccessTokenService.delete(context, ePersonUUID);
                System.out.printf("Personal Access Token removed for user with ID: %s\n", ePersonUUID);
            } else {
                personalAccessTokenService.deleteAll(context);
                System.out.println("All Personal Access Tokens removed");
            }
        }
    }

}

