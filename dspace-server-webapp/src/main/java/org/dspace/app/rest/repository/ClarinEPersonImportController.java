/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.repository;

import static org.dspace.app.rest.utils.ContextUtil.obtainContext;

import java.io.IOException;
import java.sql.SQLException;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.lang3.StringUtils;
import org.dspace.app.rest.converter.ConverterService;
import org.dspace.app.rest.exception.UnprocessableEntityException;
import org.dspace.app.rest.model.ClarinUserRegistrationRest;
import org.dspace.app.rest.model.EPersonRest;
import org.dspace.app.rest.utils.Utils;
import org.dspace.authorize.AuthorizeException;
import org.dspace.content.clarin.ClarinUserRegistration;
import org.dspace.content.service.clarin.ClarinUserRegistrationService;
import org.dspace.core.Context;
import org.dspace.eperson.EPerson;
import org.dspace.eperson.PasswordHash;
import org.dspace.eperson.service.EPersonService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * Specialized controller created for Clarin-Dspace eperson and user registration import.
 *
 * @author Michaela Paurikova (michaela.paurikova at dataquest.sk)
 */
@RestController
@RequestMapping("/api/clarin/import")
public class ClarinEPersonImportController {
    private static final Logger log = LoggerFactory.getLogger(ClarinEPersonImportController.class);

    @Autowired
    private EPersonRestRepository ePersonRestRepository;
    @Autowired
    private ConverterService converter;
    @Autowired
    private EPersonService ePersonService;
    @Autowired
    private ClarinUserRegistrationService clarinUserRegistrationService;
    @Autowired
    private Utils utils;

    /**
     * Endpoint for import eperson.
     * The mapping for requested endpoint, for example
     * <pre>
     * {@code
     * https://<dspace.server.url>/api/clarin/import/eperson
     * }
     * </pre>
     * @param request request
     * @return created eperson converted to rest
     * @throws AuthorizeException if authorization error
     * @throws SQLException       if database error
     */
    @PreAuthorize("hasAuthority('ADMIN')")
    @RequestMapping(method = RequestMethod.POST, value = "/eperson")
    public EPersonRest importEPerson(HttpServletRequest request)
            throws AuthorizeException, SQLException, DecoderException {
        Context context = obtainContext(request);
        if (Objects.isNull(context)) {
            throw new RuntimeException("Context is null!");
        }

        //get eperson attribute values
        String selfRegisteredString = request.getParameter("selfRegistered");
        boolean selfRegistered = getBooleanFromString(selfRegisteredString);
        String lastActiveString = request.getParameter("lastActive");
        String passwordHashStr = request.getParameter("passwordHashStr");
        String algorithm = request.getParameter("digestAlgorithm");
        String salt = request.getParameter("salt");
        Date lastActive;
        try {
            lastActive = getDateFromString(lastActiveString);
        } catch (ParseException e) {
            throw new RuntimeException("Cannot import eperson, because the last_active is entered incorrectly!");
        }
        //salt and digest_algorithm are changing with password
        EPersonRest epersonRest = ePersonRestRepository.createAndReturn(context);
        EPerson eperson = ePersonService.find(context, UUID.fromString(epersonRest.getUuid()));
        eperson.setSelfRegistered(selfRegistered);
        eperson.setLastActive(lastActive);
        //the password is already hashed in the request
        PasswordHash passwordHash = new PasswordHash(algorithm, salt, passwordHashStr);
        ePersonService.setPasswordHash(eperson, passwordHash);
        ePersonService.update(context, eperson);

        epersonRest = converter.toRest(eperson, utils.obtainProjection());
        context.complete();

        return epersonRest;
    }

    /**
     * Endpoint for import clarin user registration.
     * The mapping for requested endpoint, for example
     * <pre>
     * {@code
     * https://<dspace.server.url>/api/clarin/import/userregistration
     * }
     * </pre>
     * @param request request
     * @return created clarin user registration converted to rest
     * @throws AuthorizeException if authorization error
     * @throws SQLException       if database error
     */
    @PreAuthorize("hasAuthority('ADMIN')")
    @RequestMapping(method = RequestMethod.POST, value = "/userregistration")
    public ClarinUserRegistrationRest importUserRegistration(HttpServletRequest request)
            throws SQLException, AuthorizeException {
        Context context = obtainContext(request);
        if (Objects.isNull(context)) {
            throw new RuntimeException("Context is null!");
        }
        //get user registration from request
        ObjectMapper mapper = new ObjectMapper();
        ClarinUserRegistrationRest userRegistrationRest = null;
        try {
            ServletInputStream input = request.getInputStream();
            userRegistrationRest = mapper.readValue(input, ClarinUserRegistrationRest.class);
        } catch (IOException e1) {
            throw new UnprocessableEntityException("Error parsing request body", e1);
        }

        ClarinUserRegistration clarinUserRegistration = null;
        boolean foundByEmail = false;

        // Check if user registration already exists by email or ePersonID
        if (StringUtils.isNotBlank(userRegistrationRest.getEmail())) {
            List<ClarinUserRegistration> existingRegistrations = clarinUserRegistrationService.findByEmail(context,
                    userRegistrationRest.getEmail());
            if (!existingRegistrations.isEmpty()) {
                clarinUserRegistration = existingRegistrations.get(0);
                foundByEmail = true;
            }
        }

        // If not found by email, try by ePersonID
        if (Objects.isNull(clarinUserRegistration) && Objects.nonNull(userRegistrationRest.getePersonID())) {
            List<ClarinUserRegistration> existingRegistrationsByEPerson =
                    clarinUserRegistrationService.findByEPersonUUID(context,
                    userRegistrationRest.getePersonID());
            if (!existingRegistrationsByEPerson.isEmpty()) {
                clarinUserRegistration = existingRegistrationsByEPerson.get(0);
            }
        }

        if (Objects.nonNull(clarinUserRegistration)) {
            // Update existing registration if values are different
            boolean needsUpdate = false;

            if (!Objects.equals(clarinUserRegistration.getOrganization(), userRegistrationRest.getOrganization())) {
                clarinUserRegistration.setOrganization(userRegistrationRest.getOrganization());
                needsUpdate = true;
            }

            if (!Objects.equals(clarinUserRegistration.isConfirmation(), userRegistrationRest.isConfirmation())) {
                clarinUserRegistration.setConfirmation(userRegistrationRest.isConfirmation());
                needsUpdate = true;
            }

            // Do not update email if registration was matched by ePersonID instead of email
            // to prevent data inconsistency
            if (!Objects.equals(clarinUserRegistration.getEmail(), userRegistrationRest.getEmail())) {
                if (foundByEmail) {
                    clarinUserRegistration.setEmail(userRegistrationRest.getEmail());
                    needsUpdate = true;
                } else {
                    // Registration found by ePersonID but email differs - potential data corruption
                    log.warn("User registration found by ePersonID={} has different email. " +
                            "Existing email='{}', incoming email='{}'. " +
                            "Email will NOT be updated to prevent data inconsistency.",
                            userRegistrationRest.getePersonID(),
                            clarinUserRegistration.getEmail(),
                            userRegistrationRest.getEmail());
                }
            }

            if (!Objects.equals(clarinUserRegistration.getPersonID(), userRegistrationRest.getePersonID())) {
                boolean canUpdate = true;

                // Check for ePersonID conflicts if the incoming value is non-null
                if (Objects.nonNull(userRegistrationRest.getePersonID())) {
                    List<ClarinUserRegistration> conflictingRegs = clarinUserRegistrationService
                            .findByEPersonUUID(context, userRegistrationRest.getePersonID());
                    if (!conflictingRegs.isEmpty() &&
                            !conflictingRegs.get(0).getID().equals(clarinUserRegistration.getID())) {
                        // Conflict detected - log appropriate message based on how registration was found
                        if (foundByEmail) {
                            log.warn("User registration found by email='{}' has different ePersonID. " +
                                    "Incoming ePersonID={} is already associated with another registration ID={}. " +
                                    "ePersonID will NOT be updated to prevent data inconsistency.",
                                    clarinUserRegistration.getEmail(),
                                    userRegistrationRest.getePersonID(),
                                    conflictingRegs.get(0).getID());
                        } else {
                            log.warn("User registration found by ePersonID={} has different incoming ePersonID. " +
                                    "Incoming ePersonID={} is already associated with another registration ID={}. " +
                                    "ePersonID will NOT be updated to prevent data inconsistency.",
                                    clarinUserRegistration.getPersonID(),
                                    userRegistrationRest.getePersonID(),
                                    conflictingRegs.get(0).getID());
                        }
                        canUpdate = false;
                    }
                }

                // Update ePersonID if no conflict was detected
                if (canUpdate) {
                    clarinUserRegistration.setPersonID(userRegistrationRest.getePersonID());
                    needsUpdate = true;
                }
            }

            if (needsUpdate) {
                clarinUserRegistrationService.update(context, clarinUserRegistration);
            }
        } else {
            // Create new user registration
            clarinUserRegistration = new ClarinUserRegistration();
            clarinUserRegistration.setOrganization(userRegistrationRest.getOrganization());
            clarinUserRegistration.setConfirmation(userRegistrationRest.isConfirmation());
            clarinUserRegistration.setEmail(userRegistrationRest.getEmail());
            clarinUserRegistration.setPersonID(userRegistrationRest.getePersonID());
            clarinUserRegistration = clarinUserRegistrationService.create(context, clarinUserRegistration);
        }
        userRegistrationRest = converter.toRest(clarinUserRegistration, utils.obtainProjection());
        context.commit();
        return userRegistrationRest;
    }


    /**
     * Convert String value to boolean.
     * @param value input value
     * @return converted input value to boolean
     */
    private boolean getBooleanFromString(String value) {
        boolean output = false;
        if (StringUtils.isNotBlank(value)) {
            output = Boolean.parseBoolean(value);
        }
        return output;
    }

    /**
     * Convert String value to Date.
     * Expects two possible date formats, but more can be added.
     * @param value
     * @return converted input value to Date
     * @throws ParseException if parse error
     */
    private Date getDateFromString(String value) throws ParseException {
        Date output = null;
        if (StringUtils.isBlank(value)) {
            return null;
        }

        DateFormat df;
        df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS");
        try {
            output = df.parse(value);
        } catch (ParseException e) {
            df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
            output = df.parse(value);
        }
        return output;
    }
}
