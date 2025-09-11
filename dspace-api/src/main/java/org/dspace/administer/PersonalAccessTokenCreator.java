/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.administer;

import static org.dspace.content.clarin.PersonalAccessToken.UNMASKED_TOKEN_SIZE;

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
    private static final int MAX_EXPIRATION_TIME_IN_DAYS = 90;
    private boolean help = false;
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
        return new DSpace().getServiceManager().getServiceByName("personal-access-token",
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
        try {
            performScript(context);
        } finally {
            context.complete();
        }

    }

    protected void performScript(Context context) throws Exception {
        EPerson ePerson = getEperson(context);

        if (ePerson == null) {
            throw new RuntimeException("Only authenticated user can run the script");
        }
        context.setCurrentUser(ePerson);

        String token = personalAccessTokenService.createToken(context, ePerson.getID(), expirationDate);

        log.debug("Personal Access Token created: {}", getMaskedToken(token));

        String emailToSend = email != null ? email : ePerson.getEmail();

        sendEmail(ePerson, emailToSend, token, expirationDate);

        handler.logInfo("Personal Access Token created: " + getMaskedToken(token));
        handler.logInfo("Exact token string has been sent to: " + emailToSend);
    }

    private EPerson getEperson(Context context) throws SQLException {
        UUID ePersonIdentifier = getEpersonIdentifier();
        return ePersonIdentifier == null ? null : ePersonService.find(context, ePersonIdentifier);
    }

    static Date getExpirationDate(String expiration) throws ParseException {
        if (expiration.length() < 2 || (!expiration.endsWith("d") && !expiration.endsWith("h"))) {
            throw new ParseException("Invalid expiration time value");
        }
        long expirationTime;
        try {
            expirationTime = Integer.parseInt(expiration.substring(0, expiration.length() - 1));
        } catch (NumberFormatException e) {
            throw new ParseException("Invalid expiration time value");
        }
        if (expirationTime < 0) {
            throw new ParseException("Invalid expiration time value");
        }
        boolean inDays = expiration.endsWith("d");
        boolean inHours = !inDays;

        if ((inDays && expirationTime > MAX_EXPIRATION_TIME_IN_DAYS) ||
                (inHours && expirationTime > MAX_EXPIRATION_TIME_IN_DAYS * 24)) {
            throw new ParseException("The maximum expiration time is " + MAX_EXPIRATION_TIME_IN_DAYS + " days");
        }

        long currentDate = new Date().getTime();
        if (inDays) {
            return new Date(currentDate + 24 * 60 * 60 * 1000 * expirationTime);
        } else {
            return new Date(currentDate +  60 * 60 * 1000 * expirationTime);
        }
    }

    static String getMaskedToken(String token) {
        String maskedTokenPart = "*".repeat(token.length() - PersonalAccessToken.PREFIX.length() - UNMASKED_TOKEN_SIZE);
        String unmaskedTokenPart = token.substring(token.length() - UNMASKED_TOKEN_SIZE);
        return PersonalAccessToken.PREFIX + maskedTokenPart + unmaskedTokenPart;
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

