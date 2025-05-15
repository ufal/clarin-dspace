/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.repository;

import static org.apache.commons.lang3.StringUtils.isBlank;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dspace.app.rest.exception.DSpaceBadRequestException;
import org.dspace.app.rest.exception.UnprocessableEntityException;
import org.dspace.app.rest.model.MatomoReportRest;
import org.dspace.authorize.AuthorizeException;
import org.dspace.content.Item;
import org.dspace.content.clarin.MatomoReport;
import org.dspace.content.service.ItemService;
import org.dspace.content.service.clarin.MatomoReportService;
import org.dspace.core.Context;
import org.dspace.eperson.EPerson;
import org.dspace.eperson.service.EPersonService;
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
    MatomoReportService matomoReportService;

    @Autowired
    ItemService itemService;

    @Autowired
    EPersonService epersonService;

    @Override
    @PreAuthorize("hasAuthority('AUTHENTICATED')")
    public MatomoReportRest findOne(Context context, Integer id) {
        MatomoReport matomoReport;
        try {
            matomoReport = matomoReportService.find(context, id);
        } catch (SQLException e) {
            throw new RuntimeException(e.getMessage(), e);
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
        } catch (SQLException | AuthorizeException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    // create
    @Override
    @PreAuthorize("hasAuthority('AUTHENTICATED')")
    protected MatomoReportRest createAndReturn(Context context)
            throws AuthorizeException, SQLException {

        // parse request body
        MatomoReportRest matomoReportRest;
        try {
            matomoReportRest = new ObjectMapper().readValue(
                    getRequestService().getCurrentRequest().getHttpServletRequest().getInputStream(),
                    MatomoReportRest.class
            );
        } catch (IOException excIO) {
            throw new DSpaceBadRequestException("error parsing request body", excIO);
        }

        // validate fields
        if (isBlank(matomoReportRest.getEpersonId()) || isBlank(matomoReportRest.getItemId())) {
            throw new UnprocessableEntityException("MatomoReport epersonId and itemId cannot be null or empty");
        }

        Item item = itemService.find(context, UUID.fromString(matomoReportRest.getItemId()));
        if (item == null) {
            throw new UnprocessableEntityException("MatomoReport item for itemId does not exist");
        }

        EPerson eperson = epersonService.find(context, UUID.fromString(matomoReportRest.getEpersonId()));
        if (eperson == null) {
            throw new UnprocessableEntityException("MatomoReport ePerson for epersonId does not exist");
        }

        // create
        MatomoReport matomoReport = new MatomoReport();
        matomoReport.setEPerson(eperson);
        matomoReport.setItem(item);
        MatomoReport createdMatomoReport = matomoReportService.create(context, matomoReport);

        // return
        return converter.toRest(createdMatomoReport, utils.obtainProjection());
    }

    @Override
    public Class<MatomoReportRest> getDomainClass() {
        return MatomoReportRest.class;
    }
}
