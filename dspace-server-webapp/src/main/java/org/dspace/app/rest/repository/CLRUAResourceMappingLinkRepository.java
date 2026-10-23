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
import org.dspace.app.rest.model.ClarinLicenseResourceMappingRest;
import org.dspace.app.rest.model.ClarinLicenseResourceUserAllowanceRest;
import org.dspace.app.rest.projection.Projection;
import org.dspace.authorize.AuthorizeException;
import org.dspace.content.clarin.ClarinLicenseResourceMapping;
import org.dspace.content.clarin.ClarinLicenseResourceUserAllowance;
import org.dspace.content.service.clarin.ClarinLicenseResourceUserAllowanceService;
import org.dspace.core.Context;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.data.rest.webmvc.ResourceNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

/**
 * CLRUA = ClarinLicenseResourceUserAllowance
 */
@Component(ClarinLicenseResourceUserAllowanceRest.CATEGORY + "." + ClarinLicenseResourceUserAllowanceRest.PLURAL_NAME +
        "." + ClarinLicenseResourceUserAllowanceRest.RESOURCE_MAPPING)
public class CLRUAResourceMappingLinkRepository extends AbstractDSpaceRestRepository
        implements LinkRestRepository {

    @Autowired
    ClarinLicenseResourceUserAllowanceService clarinLicenseResourceUserAllowanceService;

    /**
     * The resource mapping behind a CLARIN license resource user allowance is readable by the user the allowance
     * belongs to and by administrators. See
     * {@link ClarinUserRegistrationUserMetadataLinkRepository#getUserMetadata} for why the checked
     * {@link AuthorizeException} must be translated into an unchecked one here.
     */
    @PreAuthorize("hasAuthority('AUTHENTICATED')")
    public ClarinLicenseResourceMappingRest getResourceMapping(@Nullable HttpServletRequest request,
                                                               Integer clruaID,
                                                               @Nullable Pageable optionalPageable,
                                                               Projection projection)
            throws SQLException {
        Context context = obtainContext();

        ClarinLicenseResourceUserAllowance clarinLicenseResourceUserAllowance;
        try {
            clarinLicenseResourceUserAllowance = clarinLicenseResourceUserAllowanceService.find(context, clruaID);
        } catch (AuthorizeException e) {
            throw new AccessDeniedException("The current user is not allowed to read the resource mapping of the "
                    + "CLARIN license resource user allowance with id: " + clruaID, e);
        }
        if (Objects.isNull(clarinLicenseResourceUserAllowance)) {
            throw new ResourceNotFoundException("The ClarinLicenseResourceUserAllowance for id: " + clruaID +
                    " couldn't be found");
        }
        ClarinLicenseResourceMapping clarinLicenseResourceMapping =
                clarinLicenseResourceUserAllowance.getLicenseResourceMapping();

        if (Objects.isNull(clarinLicenseResourceMapping)) {
            throw new ResourceNotFoundException("The ClarinLicenseResourceMapping for " +
                    "ClarinLicenseResourceUserAllowance with id: " + clruaID + " doesn't exists.");
        }
        return converter.toRest(clarinLicenseResourceMapping, projection);
    }
}
