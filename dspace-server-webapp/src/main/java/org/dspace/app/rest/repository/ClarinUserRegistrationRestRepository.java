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
import java.util.UUID;

import org.apache.commons.collections4.CollectionUtils;
import org.dspace.app.rest.Parameter;
import org.dspace.app.rest.SearchRestMethod;
import org.dspace.app.rest.exception.RESTAuthorizationException;
import org.dspace.app.rest.model.ClarinUserRegistrationRest;
import org.dspace.authorize.AuthorizeException;
import org.dspace.content.clarin.ClarinUserRegistration;
import org.dspace.content.service.clarin.ClarinUserRegistrationService;
import org.dspace.core.Context;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

/**
 * This is the repository responsible to manage Clarin User Registration Rest object
 *
 * @author Milan Majchrak (milan.majchrak at dataquest.sk)
 */
@Component(ClarinUserRegistrationRest.CATEGORY + "." + ClarinUserRegistrationRest.NAME)
public class ClarinUserRegistrationRestRepository extends DSpaceRestRepository<ClarinUserRegistrationRest, Integer> {

    @Autowired
    ClarinUserRegistrationService clarinUserRegistrationService;

    @Override
    @PreAuthorize("hasAuthority('AUTHENTICATED')")
    public ClarinUserRegistrationRest findOne(Context context, Integer idValue) {
        ClarinUserRegistration clarinUserRegistration;
        try {
            clarinUserRegistration = clarinUserRegistrationService.find(context, idValue);
        } catch (SQLException e) {
            throw new RuntimeException(e.getMessage(), e);
        } catch (AuthorizeException e) {
            throw new RESTAuthorizationException(e);
        }

        if (Objects.isNull(clarinUserRegistration)) {
            return null;
        }
        return converter.toRest(clarinUserRegistration, utils.obtainProjection());
    }

    @Override
    @PreAuthorize("hasAuthority('ADMIN')")
    public Page<ClarinUserRegistrationRest> findAll(Context context, Pageable pageable) {
        try {
            List<ClarinUserRegistration> clarinUserRegistrationList = clarinUserRegistrationService.findAll(context);
            return converter.toRestPage(clarinUserRegistrationList, pageable, utils.obtainProjection());
        } catch (SQLException | AuthorizeException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    @PreAuthorize("hasAuthority('ADMIN')")
    public void delete(Context context, Integer idValue) throws AuthorizeException {
        super.delete(context, idValue);
    }

    @Override
    @PreAuthorize("hasAuthority('ADMIN')")
    protected ClarinUserRegistrationRest createAndReturn(Context context) throws SQLException, AuthorizeException {
        return super.createAndReturn(context);
    }

    @SearchRestMethod(name = "byEPerson")
    public Page<ClarinUserRegistrationRest> findByEPerson(@Parameter(value = "userUUID", required = true) UUID
                                                                      userUUID,
                                                              Pageable pageable)
            throws SQLException, AuthorizeException {
        Context context = obtainContext();

        List<ClarinUserRegistration> clarinUserRegistrationList =
                clarinUserRegistrationService.findByEPersonUUID(context, userUUID);
        if (CollectionUtils.isEmpty(clarinUserRegistrationList)) {
            return null;
        }

        return converter.toRestPage(clarinUserRegistrationList, pageable, utils.obtainProjection());
    }

    @Override
    public Class<ClarinUserRegistrationRest> getDomainClass() {
        return ClarinUserRegistrationRest.class;
    }
}
