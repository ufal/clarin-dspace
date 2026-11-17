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
import org.dspace.app.rest.model.ClarinLicenseRest;
import org.dspace.app.rest.projection.Projection;
import org.dspace.content.clarin.ClarinLicense;
import org.dspace.content.clarin.ClarinLicenseResourceMapping;
import org.dspace.content.service.clarin.ClarinLicenseResourceMappingService;
import org.dspace.core.Context;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.data.rest.webmvc.ResourceNotFoundException;
import org.springframework.stereotype.Component;

@Component(ClarinLicenseResourceMappingRest.CATEGORY + "." + ClarinLicenseResourceMappingRest.PLURAL_NAME +
        "." + ClarinLicenseResourceMappingRest.CLARIN_LICENSE)
public class ClarinResourceMappingCLicenseLinkRepository extends AbstractDSpaceRestRepository
        implements LinkRestRepository {

    @Autowired
    ClarinLicenseResourceMappingService clarinLicenseResourceMappingService;

    /**
     * Deliberately public - do not put a method security guard on this method. Its parent,
     * {@code ClarinLicenseResourceMappingRestRepository.findOne}, permits all callers, and the Angular
     * licence agreement page follows this rel anonymously
     * ({@code clarin-license-agreement-page.component.ts}, {@code followLink('clarinLicense')}), so guarding
     * it would break the anonymous download flow. The 404 an anonymous caller gets for an unknown mapping is
     * parity with the parent, not an information leak - unlike the sibling allowance rels, whose 404 on an
     * unknown id sat next to a 401 on an existing one.
     * <p>
     * {@code ClarinLinkRestRepositoryBeanNameIT.anonymousResourceMappingClarinLicenseRelStaysPublic} fails
     * if a later security cleanup guards it anyway.
     */
    public ClarinLicenseRest getClarinLicense(@Nullable HttpServletRequest request,
                                              Integer mappingID,
                                              @Nullable Pageable optionalPageable,
                                              Projection projection) throws SQLException {
        Context context = obtainContext();

        ClarinLicenseResourceMapping clarinLicenseResourceMapping =
                clarinLicenseResourceMappingService.find(context, mappingID);

        if (Objects.isNull(clarinLicenseResourceMapping)) {
            throw new ResourceNotFoundException("The ClarinLicenseResourceMapping for id: " + mappingID +
                    " couldn't be found");
        }
        ClarinLicense clarinLicense = clarinLicenseResourceMapping.getLicense();
        if (Objects.isNull(clarinLicense)) {
            throw new ResourceNotFoundException("The clarinLicense for " +
                    "ClarinLicenseResourceMapping with id: " + mappingID + " doesn't exists.");
        }
        return converter.toRest(clarinLicense, projection);
    }

}
