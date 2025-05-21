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

import org.dspace.app.rest.exception.RESTAuthorizationException;
import org.dspace.app.rest.model.MatomoReportRest;
import org.dspace.authorize.AuthorizeException;
import org.dspace.authorize.service.AuthorizeService;
import org.dspace.content.clarin.MatomoReport;
import org.dspace.content.service.clarin.MatomoReportService;
import org.dspace.core.Context;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

/**
 * This is the repository responsible to manage Matomo Report Rest object
 *
 * @author Milan Kuchtiak
 */
@Component(MatomoReportRest.CATEGORY + "." + MatomoReportRest.NAME)
public class MatomoReportRestRepository extends DSpaceRestRepository<MatomoReportRest, Integer> {

    @Autowired
    AuthorizeService authorizeService;

    @Autowired
    MatomoReportService matomoReportService;

    @Override
    @PreAuthorize("hasAuthority('AUTHENTICATED')")
    public MatomoReportRest findOne(Context context, Integer id) {
        MatomoReport matomoReport;
        try {
            matomoReport = matomoReportService.find(context, id);
        } catch (SQLException se) {
            throw new RuntimeException(se.getMessage(), se);
        } catch (AuthorizeException ae) {
            throw new RESTAuthorizationException(ae);
        }
        if (Objects.isNull(matomoReport)) {
            return null;
        }
        return converter.toRest(matomoReport, utils.obtainProjection());
    }

    @Override
    @PreAuthorize("hasAuthority('AUTHENTICATED')")
    public Page<MatomoReportRest> findAll(Context context, Pageable pageable) {
        try {
            List<MatomoReport> matomoReportList = matomoReportService.findAll(context);
            return converter.toRestPage(matomoReportList, pageable, utils.obtainProjection());
        } catch (SQLException se) {
            throw new RuntimeException(se.getMessage(), se);
        } catch (AuthorizeException e) {
            throw new RESTAuthorizationException(e);
        }
    }

    @Override
    public Class<MatomoReportRest> getDomainClass() {
        return MatomoReportRest.class;
    }
}
