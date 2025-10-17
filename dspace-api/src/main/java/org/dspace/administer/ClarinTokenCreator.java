/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.administer;

import static org.dspace.content.clarin.ClarinToken.MASKED_TOKEN_SIZE;
import static org.dspace.content.clarin.ClarinToken.UNMASKED_TOKEN_SIZE;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import javax.mail.MessagingException;

import org.apache.commons.cli.ParseException;
import org.dspace.content.clarin.ClarinToken;
import org.dspace.content.factory.ClarinServiceFactory;
import org.dspace.content.service.clarin.ClarinTokenService;
import org.dspace.core.Context;
import org.dspace.core.Email;
import org.dspace.core.I18nUtil;
import org.dspace.eperson.EPerson;
import org.dspace.eperson.factory.EPersonServiceFactory;
import org.dspace.eperson.service.EPersonService;
import org.dspace.scripts.DSpaceRunnable;
import org.dspace.scripts.configuration.ScriptConfiguration;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.dspace.utils.DSpace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClarinTokenCreator extends DSpaceRunnable<ClarinTokenConfiguration> {

    private final static int HOURS_TO_MILLIS = 60 * 60 * 1000;
    private final static int DAYS_TO_MILLIS = 24 * HOURS_TO_MILLIS;

    private static final Logger log = LoggerFactory.getLogger(ClarinTokenCreator.class);
    private boolean help = false;
    private String email;
    private Date expirationDate;
    private String token;
    private ClarinTokenService clarinTokenService;
    private EPersonService ePersonService;

    /**
     * This method will return the Configuration that the implementing DSpaceRunnable uses
     *
     * @return The {@link ScriptConfiguration} that this implementing DspaceRunnable uses
     */
    @Override
    public ClarinTokenConfiguration getScriptConfiguration() {
        return new DSpace().getServiceManager().getServiceByName("clarin-token",
                ClarinTokenConfiguration.class);
    }

    /**
     * This method has to be included in every script and handles the setup of the script by parsing the CommandLine
     * and setting the variables
     *
     * @throws ParseException If something goes wrong
     */
    @Override
    public void setup() throws ParseException {
        log.debug("Setting up {}", ClarinTokenCreator.class.getName());
        if (commandLine.hasOption("h") || (!commandLine.hasOption("c") && !commandLine.hasOption("d"))) {
            help = true;
            return;
        }

        if (commandLine.hasOption("c") && commandLine.hasOption("d")) {
            throw new ParseException("Select either create or delete option, not both");
        }

        if (commandLine.hasOption("c") && !commandLine.hasOption("x")) {
            throw new ParseException("No token expiration time specified");
        }

        if (commandLine.hasOption("d") && !commandLine.hasOption("t")) {
            throw new ParseException("No token specified");
        }

        if (commandLine.hasOption("c")) {
            expirationDate = getExpirationDate(commandLine.getOptionValue("x").toLowerCase());
            if (commandLine.hasOption("e")) {
                email = commandLine.getOptionValue("e");
            }
        } else if (commandLine.hasOption("d")) {
            token = commandLine.getOptionValue("t");
        }

        clarinTokenService = ClarinServiceFactory.getInstance().getClarinTokenService();
        ePersonService = EPersonServiceFactory.getInstance().getEPersonService();

    }

    /**
     * This method has to be included in every script and this will be the main execution block for the script that'll
     * contain all the logic needed
     *
     * @throws Exception If something goes wrong
     */
    @Override
    public void internalRun() throws Exception {
        log.debug("Running {}", ClarinTokenCreator.class.getName());
        if (help) {
            printHelp();
            return;
        }

        Context context = new Context();
        EPerson ePerson = getEperson(context);
        if (ePerson == null) {
            throw new RuntimeException("Only authenticated user can run the script");
        }
        context.setCurrentUser(ePerson);
        try {
            if (expirationDate != null) {
                performCreate(context, ePerson);
            } else {
                performDelete(context, token);
            }
        } finally {
            context.complete();
        }

    }

    protected void performCreate(Context context, EPerson ePerson) throws Exception {
        String token = clarinTokenService.createToken(context, ePerson, expirationDate);

        log.debug("Clarin Token created: {}", getMaskedToken(token));

        String emailToSend = email != null ? email : ePerson.getEmail();

        sendEmail(ePerson, emailToSend, token, expirationDate);

        handler.logInfo("Clarin Token created: " + getMaskedToken(token));
        handler.logInfo("Exact token string has been sent to: " + emailToSend);
    }

    protected void performDelete(Context context, String token) throws Exception {
        clarinTokenService.delete(context, token);

        log.debug("Clarin Token deleted: {}", getMaskedToken(token));

        handler.logInfo("Clarin Token deleted");
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

        long maxExpirationTimeInDays = getMaxExpirationTimeInDays();
        if ((inDays && expirationTime > maxExpirationTimeInDays) ||
                (inHours && expirationTime > maxExpirationTimeInDays * 24)) {
            throw new ParseException("The maximum expiration time is " + maxExpirationTimeInDays + " days");
        }

        long currentDate = new Date().getTime();

        if (inDays) {
            return new Date(currentDate + DAYS_TO_MILLIS * expirationTime);
        } else {
            return new Date(currentDate + HOURS_TO_MILLIS * expirationTime);
        }
    }

    static long getMaxExpirationTimeInDays() {
        ConfigurationService configurationService = DSpaceServicesFactory.getInstance().getConfigurationService();
        return configurationService.getLongProperty(ClarinToken.PROPERTY_MAX_EXPIRATION_TIME_IN_DAYS, 90);
    }

    static String getMaskedToken(String token) {
        String maskedTokenPart = "*".repeat(MASKED_TOKEN_SIZE);
        String unmaskedTokenPart = token.substring(token.length() - UNMASKED_TOKEN_SIZE);
        return maskedTokenPart + unmaskedTokenPart;
    }

    private static void sendEmail(EPerson ePerson, String to, String token, Date validUntil)
            throws IOException, MessagingException {

        // Get a resource bundle according to the ePerson language preferences
        Locale supportedLocale = I18nUtil.getEPersonLocale(ePerson);

        Email email = Email.getEmail(I18nUtil.getEmailFilename(supportedLocale, "clarin_token"));
        email.addArgument(token);
        email.addArgument(ePerson.getFullName());
        email.addArgument(validUntil.toString());

        email.addRecipient(to);
        email.send();
    }
}

