/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.administer;

import java.sql.SQLException;
import java.util.Date;
import java.util.UUID;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.dspace.authorize.AuthorizeException;
import org.dspace.content.factory.ClarinServiceFactory;
import org.dspace.content.service.clarin.PersonalAccessTokenService;
import org.dspace.core.Context;
import org.dspace.eperson.EPerson;
import org.dspace.eperson.factory.EPersonServiceFactory;
import org.dspace.eperson.service.EPersonService;

public class PersonalAccessTokenAdministrator {

    private static final Logger log = LogManager.getLogger(PersonalAccessTokenAdministrator.class);

    private PersonalAccessTokenAdministrator() {
    }

    public static void main(String args[]) throws Exception {
        log.info("Personal Access Token manager started ....");

        Options options = new Options();
        options.addOption("c", "create", false, "create token for ePerson specified by ID or email");
        options.addOption("d", "delete", false,
                "delete token for given ePerson (or delete all tokens when -u and -e options are missing)");
        options.addOption("u", "ePerson_ID", true, "ePerson UUID");
        options.addOption("e", "email", true, "ePerson email");
        options.addOption("x", "expiration", true,
                "token expiration time in days or hours, (e.g. 3d or 48h), for -c option only");
        options.addOption("h", "help", false, "help");

        CommandLineParser parser = new DefaultParser();
        try {
            CommandLine line = parser.parse(options, args);
            if (line.hasOption('h') || (!line.hasOption('c') && !line.hasOption('d')) ) {
                printHelpAndExit(options);
            }
            boolean isCreate = line.hasOption('c');
            boolean isDelete = line.hasOption('d');

            if (isCreate && isDelete) {
                throw new ParseException("Create and delete options are mutually exclusive");
            }

            if (isCreate && !line.hasOption("u") && !line.hasOption("e")) {
                throw new ParseException("either ePerson UUID or ePerson e-mail option is needed to create token");
            }

            if (isCreate && !line.hasOption("x")) {
                throw new ParseException("Token expiration time option is missing");
            }

            UUID ePersonUUID = null;
            if (line.hasOption("u")) {
                ePersonUUID = UUID.fromString(line.getOptionValue("u"));
            }

            String email = null;
            if (line.hasOption("e")) {
                email = line.getOptionValue("e");
            }

            PersonalAccessTokenService personalAccessTokenService =
                    ClarinServiceFactory.getInstance().getPersonalAccessTokenService();
            EPersonService ePersonService = EPersonServiceFactory.getInstance().getEPersonService();

            try (Context context = new Context()) {
                try {
                    context.turnOffAuthorisationSystem();
                    EPerson ePerson = getEPerson(context, ePersonService, ePersonUUID, email);
                    if (isCreate) {
                        if (ePerson == null) {
                            throw new IllegalArgumentException("Invalid ePerson UUID or email");
                        }
                        Date expirationDate = PersonalAccessTokenCreator.getExpirationDate(
                                line.getOptionValue("x").toLowerCase());
                        createToken(context, personalAccessTokenService, ePerson, expirationDate);
                    } else {
                        deleteToken(context, personalAccessTokenService, ePerson);
                    }
                } finally {
                    context.restoreAuthSystemState();
                    context.complete();
                }
            }

        } catch (ParseException e) {
            System.out.printf("Invalid command options: %s\n", e.getMessage());
            printHelpAndExit(options);
        }

        log.info("MATOMO pdf reports generation finished.");
    }

    private static void createToken(Context context,
                                    PersonalAccessTokenService personalAccessTokenService,
                                    EPerson ePerson,
                                    Date expirationDate) throws SQLException, AuthorizeException {
        String token = personalAccessTokenService.createToken(context, ePerson.getID(), expirationDate);
        log.debug("Personal Access Token created: {}", PersonalAccessTokenCreator.getSecureToken(token));
        System.out.printf("Personal Access Token created: %s\n", token);
        System.out.printf("For user: %s, with ID: %s\n", ePerson.getEmail(), ePerson.getID());
    }

    private static void deleteToken(Context context,
                                    PersonalAccessTokenService personalAccessTokenService,
                                    EPerson ePerson) throws SQLException, AuthorizeException {
        if (ePerson != null) {
            personalAccessTokenService.delete(context, ePerson.getID());
            System.out.println("Personal Access Token removed.");
            System.out.printf("For user: %s, with ID: %s\n", ePerson.getEmail(), ePerson.getID());
        } else {
            personalAccessTokenService.deleteAll(context);
            System.out.println("All Personal Access Tokens removed");
        }
    }

    private static void printHelpAndExit(Options options) {
        // print the help message
        HelpFormatter myHelp = new HelpFormatter();
        myHelp.printHelp("personal-access-token\n", options);
        System.exit(0);
    }

    private static EPerson getEPerson(Context context, EPersonService ePersonService, UUID ePersonID, String email)
            throws SQLException {
        return ePersonID == null ? ePersonService.findByEmail(context, email) : ePersonService.find(context, ePersonID);
    }

}