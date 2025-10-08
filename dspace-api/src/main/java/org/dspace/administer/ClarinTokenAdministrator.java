/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.administer;

import static org.dspace.administer.ClarinTokenCreator.getExpirationDate;
import static org.dspace.administer.ClarinTokenCreator.getMaskedToken;

import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

import com.nimbusds.jose.EncryptionMethod;
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
import org.dspace.content.service.clarin.ClarinTokenService;
import org.dspace.core.Context;
import org.dspace.eperson.EPerson;
import org.dspace.eperson.factory.EPersonServiceFactory;
import org.dspace.eperson.service.EPersonService;

public class ClarinTokenAdministrator {

    private static final Logger log = LogManager.getLogger(ClarinTokenAdministrator.class);

    private ClarinTokenAdministrator() {
    }

    public static void main(String args[]) throws Exception {
        log.info("Clarin Token administrator started ....");

        Options options = new Options();
        options.addOption("c", "create", false, "create token for ePerson specified by ID or email");
        options.addOption("d", "delete", false,
                "delete specified token, or delete all tokens for given ePerson, " +
                        "or delete all tokens when -t, -u, and -e options are missing)");
        options.addOption("g", "generateEncryptionKey", false,
                "generate encryption/decryption secret key for clarin.token.encryption.secret property");
        options.addOption("u", "ePerson_ID", true, "ePerson UUID");
        options.addOption("e", "email", true, "ePerson email");
        options.addOption("x", "expiration", true,
                "token expiration time in days or hours, (e.g. 3d or 48h), for -c option only [required for create]");
        options.addOption("t", "token", true,
                "token string [optional for delete]");
        options.addOption("h", "help", false, "help");

        CommandLineParser parser = new DefaultParser();
        try {
            CommandLine line = parser.parse(options, args);
            if (line.hasOption('h') || (!line.hasOption('c') && !line.hasOption('d') && !line.hasOption('g')) ) {
                printHelpAndExit(options);
            }
            boolean isCreate = line.hasOption('c');
            boolean isDelete = line.hasOption('d');
            boolean generateEncryptionKey = line.hasOption('g');

            if (isCreate && isDelete || isCreate && generateEncryptionKey || isDelete && generateEncryptionKey) {
                throw new ParseException("Create, delete and generate options are mutually exclusive");
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

            String token = null;
            if (line.hasOption("t")) {
                token = line.getOptionValue("t");
            }

            ClarinTokenService clarinTokenService =
                    ClarinServiceFactory.getInstance().getClarinTokenService();
            EPersonService ePersonService = EPersonServiceFactory.getInstance().getEPersonService();

            try (Context context = new Context()) {
                try {
                    context.turnOffAuthorisationSystem();
                    EPerson ePerson = getEPerson(context, ePersonService, ePersonUUID, email);
                    if (isCreate) {
                        if (ePerson == null) {
                            throw new IllegalArgumentException("Invalid ePerson UUID or email");
                        }
                        Date expirationDate = getExpirationDate(line.getOptionValue("x").toLowerCase());
                        createToken(context, clarinTokenService, ePerson, expirationDate);
                    } else if (isDelete) {
                        deleteToken(context, clarinTokenService, token, ePerson);
                    } else {
                        generateEncryptionKey();
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

        log.info("Clarin Token administrator finished.");
    }

    private static void createToken(Context context,
                                    ClarinTokenService clarinTokenService,
                                    EPerson ePerson,
                                    Date expirationDate) throws SQLException, AuthorizeException {
        String token = clarinTokenService.createToken(context, ePerson, expirationDate);
        log.debug("Clarin Token created: {}", getMaskedToken(token));
        System.out.printf("Clarin Token created: %s\n", token);
        System.out.printf("For user: %s, with ID: %s\n", ePerson.getEmail(), ePerson.getID());
    }

    private static void deleteToken(Context context,
                                    ClarinTokenService clarinTokenService,
                                    String token,
                                    EPerson ePerson) throws SQLException, AuthorizeException {
        if (token != null) {
            clarinTokenService.delete(context, token);
            System.out.println("Clarin Token removed.");
        } else if (ePerson != null) {
            clarinTokenService.delete(context, ePerson);
            System.out.println("Clarin Tokens removed.");
            System.out.printf("For user: %s, with ID: %s\n", ePerson.getEmail(), ePerson.getID());
        } else {
            clarinTokenService.deleteAll(context);
            System.out.println("All Clarin Tokens removed");
        }
    }

    private static void generateEncryptionKey() throws NoSuchAlgorithmException {
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(EncryptionMethod.A256GCM.cekBitLength());
        SecretKey aesKey = keyGen.generateKey();

        String encodedAesKey = Base64.getEncoder().encodeToString(aesKey.getEncoded());
        log.debug("Encryption Key generated: {}", getMaskedToken(encodedAesKey));
        System.out.printf("Encryption Key: %s\n", encodedAesKey);
    }


    private static void printHelpAndExit(Options options) {
        // print the help message
        HelpFormatter myHelp = new HelpFormatter();
        myHelp.printHelp("clarin-token\n", options);
        System.exit(0);
    }

    private static EPerson getEPerson(Context context, EPersonService ePersonService, UUID ePersonID, String email)
            throws SQLException {
        return ePersonID == null ? ePersonService.findByEmail(context, email) : ePersonService.find(context, ePersonID);
    }

}