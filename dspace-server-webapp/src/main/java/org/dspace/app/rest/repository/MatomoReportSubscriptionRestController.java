/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.repository;

import static org.dspace.app.rest.utils.ContextUtil.obtainContext;

import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.BadRequestException;

import org.dspace.app.rest.converter.ConverterService;
import org.dspace.app.rest.exception.DSpaceBadRequestException;
import org.dspace.app.rest.model.MatomoReportSubscriptionRest;
import org.dspace.app.rest.utils.Utils;
import org.dspace.authorize.AuthorizeException;
import org.dspace.content.Item;
import org.dspace.content.clarin.MatomoReportSubscription;
import org.dspace.content.service.ItemService;
import org.dspace.content.service.clarin.MatomoReportSubscriptionService;
import org.dspace.core.Context;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.rest.webmvc.ControllerUtils;
import org.springframework.data.rest.webmvc.ResourceNotFoundException;
import org.springframework.hateoas.RepresentationModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * Specialized controller created for Matomo Report.
 *
 * @author Milan Kuchtiak
 */
@RestController
@RequestMapping("/api/" + MatomoReportSubscriptionRest.CATEGORY + "/" +
        MatomoReportSubscriptionRest.NAME + "/item/{itemId}")
public class MatomoReportSubscriptionRestController {

    @Autowired
    private ConverterService converter;
    @Autowired
    private ItemService itemService;
    @Autowired
    private MatomoReportSubscriptionService matomoReportSubscriptionService;
    @Autowired
    private Utils utils;

    @PreAuthorize("hasAuthority('AUTHENTICATED')")
    @RequestMapping(method = RequestMethod.POST, path = "subscribe")
    public MatomoReportSubscriptionRest itemSubscribe(@PathVariable UUID itemId, HttpServletRequest request)
            throws AuthorizeException, SQLException {
        Context context = getContext(request);

        Item item = getItem(context, itemId);
        MatomoReportSubscription matomoReport = matomoReportSubscriptionService.subscribe(context, item);
        context.commit();
        return converter.toRest(matomoReport, utils.obtainProjection());
    }

    @PreAuthorize("hasAuthority('AUTHENTICATED')")
    @RequestMapping(method = RequestMethod.POST, path = "unsubscribe")
    public ResponseEntity<RepresentationModel<?>> itemUnsubscribe(@PathVariable UUID itemId, HttpServletRequest request)
            throws AuthorizeException, SQLException {
        Context context = getContext(request);

        Item item = getItem(context, itemId);
        try {
            matomoReportSubscriptionService.unsubscribe(context, item);
            context.commit();
        } catch (BadRequestException ex) {
            throw new DSpaceBadRequestException(ex.getMessage(), ex);
        }
        return ControllerUtils.toEmptyResponse(HttpStatus.NO_CONTENT);
    }

    @PreAuthorize("hasAuthority('AUTHENTICATED')")
    @RequestMapping(method = RequestMethod.GET)
    public MatomoReportSubscriptionRest getReportForItem(@PathVariable UUID itemId, HttpServletRequest request)
            throws AuthorizeException, SQLException {
        Context context = getContext(request);

        Item item = getItem(context, itemId);
        MatomoReportSubscription matomoReport = matomoReportSubscriptionService.findByItem(context, item);
        if (matomoReport == null) {
            throw new ResourceNotFoundException("Current user is not subscribed for this item");
        }
        return converter.toRest(matomoReport, utils.obtainProjection());
    }

    private Item getItem(Context context, UUID itemId) {
        try {
            Item item = itemService.find(context, itemId);
            if (item == null) {
                throw new DSpaceBadRequestException("Item for this item ID does not exist");
            }
            return item;
        } catch (SQLException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    private static Context getContext(HttpServletRequest request) {
        Context context = obtainContext(request);
        if (Objects.isNull(context)) {
            throw new RuntimeException("Context is null!");
        }
        return context;
    }

}
