/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.repository;

import java.sql.SQLException;
import java.util.Objects;
import javax.annotation.Nullable;

import jakarta.servlet.http.HttpServletRequest;
import org.dspace.app.rest.exception.RESTAuthorizationException;
import org.dspace.app.rest.model.ClarinLicenseResourceUserAllowanceRest;
import org.dspace.app.rest.model.ClarinUserRegistrationRest;
import org.dspace.app.rest.projection.Projection;
import org.dspace.authorize.AuthorizeException;
import org.dspace.content.clarin.ClarinLicenseResourceUserAllowance;
import org.dspace.content.clarin.ClarinUserRegistration;
import org.dspace.content.service.clarin.ClarinLicenseResourceUserAllowanceService;
import org.dspace.core.Context;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.data.rest.webmvc.ResourceNotFoundException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

/**
 * CLRUA = ClarinLicenseResourceUserAllowance
 */
@Component(ClarinLicenseResourceUserAllowanceRest.CATEGORY + "." + ClarinLicenseResourceUserAllowanceRest.PLURAL_NAME +
        "." + ClarinLicenseResourceUserAllowanceRest.USER_REGISTRATION)
public class CLRUAUUserRegistrationLinkRepository extends AbstractDSpaceRestRepository
        implements LinkRestRepository {

    @Autowired
    ClarinLicenseResourceUserAllowanceService clarinLicenseResourceUserAllowanceService;

    /**
     * The user registration behind a CLARIN license resource user allowance is readable by the user the
     * allowance belongs to and by administrators. The guard has to run <em>before</em> the entity is
     * resolved: {@code ClarinLicenseResourceUserAllowanceService.find} returns {@code null} for a row that
     * does not exist before it ever calls {@code authorizeClruaAction}, so without {@code @PreAuthorize} an
     * anonymous caller got 404 for an unknown id and 401 for an existing one - which tells them whether the
     * allowance exists, while the parent
     * {@code ClarinLicenseResourceUserAllowanceRestRepository.findOne} answers 401 either way. Spring
     * Security intercepts this method before its body runs, so the rel now matches its parent.
     * <p>
     * The checked {@link AuthorizeException} translation below stays: it is what gives a logged-in caller
     * who does not own the allowance a 403 instead of the 500 that
     * {@code RestResourceController.findRelInternal} would produce from a checked exception.
     */
    @PreAuthorize("hasAuthority('AUTHENTICATED')")
    public ClarinUserRegistrationRest getUserRegistration(@Nullable HttpServletRequest request,
                                                     Integer clruaID,
                                                     @Nullable Pageable optionalPageable,
                                                     Projection projection)
            throws SQLException, RESTAuthorizationException {
        Context context = obtainContext();

        ClarinLicenseResourceUserAllowance clarinLicenseResourceUserAllowance;
        try {
            clarinLicenseResourceUserAllowance = clarinLicenseResourceUserAllowanceService.find(context, clruaID);
        } catch (AuthorizeException e) {
            throw new RESTAuthorizationException(e);
        }
        if (Objects.isNull(clarinLicenseResourceUserAllowance)) {
            throw new ResourceNotFoundException("The ClarinLicenseResourceUserAllowance for id: " + clruaID +
                    " couldn't be found");
        }
        ClarinUserRegistration clarinUserRegistration = clarinLicenseResourceUserAllowance.getUserRegistration();

        if (Objects.isNull(clarinUserRegistration)) {
            throw new ResourceNotFoundException("The ClarinUserRegistration for id: " + clruaID +
                    " couldn't be found");
        }
        return converter.toRest(clarinUserRegistration, projection);
    }
}
