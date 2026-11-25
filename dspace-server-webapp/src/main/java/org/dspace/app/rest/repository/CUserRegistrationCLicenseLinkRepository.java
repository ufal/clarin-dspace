/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.repository;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;

import jakarta.servlet.http.HttpServletRequest;
import org.dspace.app.rest.model.ClarinLicenseRest;
import org.dspace.app.rest.model.ClarinUserRegistrationRest;
import org.dspace.app.rest.projection.Projection;
import org.dspace.authorize.AuthorizeException;
import org.dspace.content.clarin.ClarinLicense;
import org.dspace.content.clarin.ClarinUserRegistration;
import org.dspace.content.service.clarin.ClarinUserRegistrationService;
import org.dspace.core.Context;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.rest.webmvc.ResourceNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

@Component(ClarinUserRegistrationRest.CATEGORY + "." + ClarinUserRegistrationRest.PLURAL_NAME + "." +
        ClarinUserRegistrationRest.CLARIN_LICENSES)
public class CUserRegistrationCLicenseLinkRepository extends AbstractDSpaceRestRepository
        implements LinkRestRepository {

    @Autowired
    ClarinUserRegistrationService clarinUserRegistrationService;

    /**
     * The CLARIN licenses a user registration has agreed to are readable by the owner of the registration and by
     * administrators. See {@link ClarinUserRegistrationUserMetadataLinkRepository#getUserMetadata} for why the
     * checked {@link AuthorizeException} must be translated into an unchecked one here.
     */
    @PreAuthorize("hasAuthority('AUTHENTICATED')")
    public Page<ClarinLicenseRest> getClarinLicenses(@Nullable HttpServletRequest request,
                                                     Integer userRegistrationID,
                                                     @Nullable Pageable optionalPageable,
                                                     Projection projection) throws SQLException {
        Context context = obtainContext();
        ClarinUserRegistration clarinUserRegistration;
        try {
            clarinUserRegistration = clarinUserRegistrationService.find(context, userRegistrationID);
        } catch (AuthorizeException e) {
            throw new AccessDeniedException("The current user is not allowed to read the CLARIN licenses of the "
                    + "CLARIN user registration with id: " + userRegistrationID, e);
        }
        if (Objects.isNull(clarinUserRegistration)) {
            throw new ResourceNotFoundException("The CLARIN User Registration for id: " + userRegistrationID +
                    " couldn't be found");
        }
        Pageable pageable = utils.getPageable(optionalPageable);

        List<ClarinLicense> clarinLicenseList = clarinUserRegistration.getClarinLicenses();
        return converter.toRestPage(clarinLicenseList, pageable, utils.obtainProjection());
    }
}
