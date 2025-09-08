/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.administer;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import javax.mail.MessagingException;

import org.apache.commons.cli.ParseException;
import org.dspace.content.clarin.PersonalAccessToken;
import org.dspace.content.factory.ClarinServiceFactory;
import org.dspace.content.service.clarin.PersonalAccessTokenService;
import org.dspace.core.Context;
import org.dspace.core.Email;
import org.dspace.core.I18nUtil;
import org.dspace.eperson.EPerson;
import org.dspace.eperson.factory.EPersonServiceFactory;
import org.dspace.eperson.service.EPersonService;
import org.dspace.scripts.DSpaceRunnable;
import org.dspace.scripts.configuration.ScriptConfiguration;
import org.dspace.utils.DSpace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PersonalAccessTokenCreator extends DSpaceRunnable<PersonalAccessTokenConfiguration> {

    private static final Logger log = LoggerFactory.getLogger(PersonalAccessTokenCreator.class);
    private static final int TOKEN_END_LENGTH = 3;
    private boolean help = false;
    private UUID epersonUUID;
    private String email;
    private Date expirationDate;
    private PersonalAccessTokenService personalAccessTokenService;
    private EPersonService ePersonService;

    /**
     * This method will return the Configuration that the implementing DSpaceRunnable uses
     *
     * @return The {@link ScriptConfiguration} that this implementing DspaceRunnable uses
     */
    @Override
    public PersonalAccessTokenConfiguration getScriptConfiguration() {
        return new DSpace().getServiceManager().getServiceByName("personal-access-token-creator",
                PersonalAccessTokenConfiguration.class);
    }

    /**
     * This method has to be included in every script and handles the setup of the script by parsing the CommandLine
     * and setting the variables
     *
     * @throws ParseException If something goes wrong
     */
    @Override
    public void setup() throws ParseException {
        log.debug("Setting up {}", PersonalAccessTokenCreator.class.getName());
        if (commandLine.hasOption("h")) {
            help = true;
            return;
        }

        if (!commandLine.hasOption("x")) {
            throw new ParseException("No token expiration specified");
        }

        personalAccessTokenService = ClarinServiceFactory.getInstance().getPersonalAccessTokenService();
        ePersonService = EPersonServiceFactory.getInstance().getEPersonService();

        if (commandLine.hasOption("u")) {
            epersonUUID = UUID.fromString(commandLine.getOptionValue("u"));
        }

        expirationDate = getExpirationDate(commandLine.getOptionValue("x").toLowerCase());

        if (commandLine.hasOption("e")) {
            email = commandLine.getOptionValue("e");
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
        log.debug("Running {}", PersonalAccessTokenCreator.class.getName());
        if (help) {
            printHelp();
            return;
        }

        Context context = new Context();
        EPerson ePerson = getEperson(context);

        if (ePerson == null) {
            throw new IllegalArgumentException("Cannot find ePerson for this UUID");
        }
        context.setCurrentUser(ePerson);

        String token = personalAccessTokenService.createToken(context, ePerson.getID(), expirationDate);

        log.debug("Personal Access Token created: {}", token);

        String emailToSend = email != null ? email : ePerson.getEmail();

        sendEmail(ePerson, emailToSend, token, expirationDate);

        context.commit();

        handler.logInfo("Personal Access Token was created: " + getSecureToken(token));
        handler.logInfo("Exact token string has been sent to: " + emailToSend);
    }

    private EPerson getEperson(Context context) throws SQLException {
        if (epersonUUID != null) {
            return ePersonService.find(context, epersonUUID);
        } else if (getEpersonIdentifier() != null) {
            return ePersonService.find(context, getEpersonIdentifier());
        } else {
            return null;
        }
    }

    private Date getExpirationDate(String expiration) throws ParseException {
        if (expiration.length() < 2 || (!expiration.endsWith("d") && !expiration.endsWith("h"))) {
            throw new ParseException("Invalid expiration value");
        }
        long expirationTime;
        try {
            expirationTime = Integer.parseInt(expiration.substring(0, expiration.length() - 1));
        } catch (NumberFormatException e) {
            throw new ParseException("Invalid expiration value");
        }
        if (expirationTime < 0) {
            throw new ParseException("Invalid expiration value");
        }
        boolean inDays = expiration.endsWith("d");
        boolean inHours = !inDays;

        if ((inDays && expirationTime > 90) || (inHours && expirationTime > 90 * 24)) {
            throw new ParseException("Maximal expiration time is 90 days");
        }

        long currentDate = new Date().getTime();
        if (inDays) {
            return new Date(currentDate + 24 * 60 * 60 * 1000 * expirationTime);
        } else {
            return new Date(currentDate +  60 * 60 * 1000 * expirationTime);
        }
    }

    private static String getSecureToken(String token) {
        String hiddenTokenPart = "*".repeat(token.length() - PersonalAccessToken.PREFIX.length() - TOKEN_END_LENGTH);
        return PersonalAccessToken.PREFIX + hiddenTokenPart + token.substring(token.length() - TOKEN_END_LENGTH);
    }

    private static void sendEmail(EPerson ePerson, String to, String token, Date validUntil)
            throws IOException, MessagingException {

        // Get a resource bundle according to the ePerson language preferences
        Locale supportedLocale = I18nUtil.getEPersonLocale(ePerson);

        Email email = Email.getEmail(I18nUtil.getEmailFilename(supportedLocale, "personal_access_token"));
        email.addArgument(token);
        email.addArgument(ePerson.getFullName());
        email.addArgument(validUntil.toString());


        email.addRecipient(to);
        email.send();
    }
}

