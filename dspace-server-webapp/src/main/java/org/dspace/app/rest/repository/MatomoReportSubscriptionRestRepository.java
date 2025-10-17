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
import org.dspace.app.rest.model.MatomoReportSubscriptionRest;
import org.dspace.authorize.AuthorizeException;
import org.dspace.authorize.service.AuthorizeService;
import org.dspace.content.clarin.MatomoReportSubscription;
import org.dspace.content.service.clarin.MatomoReportSubscriptionService;
import org.dspace.core.Context;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

/**
 * This is the repository responsible to manage Matomo Report Subscription Rest object
 *
 * @author Milan Kuchtiak
 */
@Component(MatomoReportSubscriptionRest.CATEGORY + "." + MatomoReportSubscriptionRest.NAME)
public class MatomoReportSubscriptionRestRepository extends
        DSpaceRestRepository<MatomoReportSubscriptionRest, Integer> {

    @Autowired
    AuthorizeService authorizeService;

    @Autowired
    MatomoReportSubscriptionService matomoReportSubscriptionService;

    @Override
    @PreAuthorize("hasAuthority('AUTHENTICATED')")
    public MatomoReportSubscriptionRest findOne(Context context, Integer id) {
        MatomoReportSubscription matomoReportSubscription;
        try {
            matomoReportSubscription = matomoReportSubscriptionService.find(context, id);
        } catch (SQLException se) {
            throw new RuntimeException(se.getMessage(), se);
        } catch (AuthorizeException ae) {
            throw new RESTAuthorizationException(ae);
        }
        if (Objects.isNull(matomoReportSubscription)) {
            return null;
        }
        return converter.toRest(matomoReportSubscription, utils.obtainProjection());
    }

    @Override
    @PreAuthorize("hasAuthority('AUTHENTICATED')")
    public Page<MatomoReportSubscriptionRest> findAll(Context context, Pageable pageable) {
        try {
            List<MatomoReportSubscription> matomoReportSubscriptions = matomoReportSubscriptionService.findAll(context);
            return converter.toRestPage(matomoReportSubscriptions, pageable, utils.obtainProjection());
        } catch (SQLException se) {
            throw new RuntimeException(se.getMessage(), se);
        } catch (AuthorizeException e) {
            throw new RESTAuthorizationException(e);
        }
    }

    @Override
    public Class<MatomoReportSubscriptionRest> getDomainClass() {
        return MatomoReportSubscriptionRest.class;
    }
}
