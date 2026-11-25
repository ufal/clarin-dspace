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
import org.dspace.app.rest.model.ClarinUserMetadataRest;
import org.dspace.app.rest.model.ClarinUserRegistrationRest;
import org.dspace.app.rest.projection.Projection;
import org.dspace.authorize.AuthorizeException;
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
        ClarinUserRegistrationRest.USER_METADATA)
public class ClarinUserRegistrationUserMetadataLinkRepository extends AbstractDSpaceRestRepository
        implements LinkRestRepository {

    @Autowired
    ClarinUserRegistrationService clarinUserRegistrationService;

    /**
     * The user metadata of a CLARIN user registration is readable by the owner of the registration and by
     * administrators. Anonymous callers are rejected by {@code @PreAuthorize}; a logged-in caller who does not
     * own the registration is rejected by the service. The service reports that with a checked
     * {@link AuthorizeException}, which must be translated here: {@code RestResourceController.findRelInternal}
     * wraps a checked exception thrown by a link method into a {@code RuntimeException} and the client sees an
     * HTTP 500 instead of 401/403.
     */
    @PreAuthorize("hasAuthority('AUTHENTICATED')")
    public Page<ClarinUserMetadataRest> getUserMetadata(@Nullable HttpServletRequest request,
                                                        Integer userRegistrationID,
                                                        @Nullable Pageable optionalPageable,
                                                        Projection projection) throws SQLException {
        Context context = obtainContext();

        ClarinUserRegistration clarinUserRegistration;
        try {
            clarinUserRegistration = clarinUserRegistrationService.find(context, userRegistrationID);
        } catch (AuthorizeException e) {
            throw new AccessDeniedException("The current user is not allowed to read the user metadata of the "
                    + "CLARIN user registration with id: " + userRegistrationID, e);
        }
        if (Objects.isNull(clarinUserRegistration)) {
            throw new ResourceNotFoundException("The ClarinUserRegistration for id: " + userRegistrationID +
                    " couldn't be found");
        }

        return converter.toRestPage(clarinUserRegistration.getUserMetadata(), optionalPageable, projection);
    }
}
