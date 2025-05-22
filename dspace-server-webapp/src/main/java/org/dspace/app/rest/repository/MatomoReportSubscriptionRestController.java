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
        MatomoReportSubscriptionRest.NAME + "/forItem")
public class MatomoReportSubscriptionRestController {

    private static final String ITEM_QUERY_PARAMETER = "item";

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
    public MatomoReportSubscriptionRest itemSubscribe(HttpServletRequest request)
            throws AuthorizeException, SQLException {
        Context context = getContext(request);

        Item item = getItem(context, request);
        MatomoReportSubscription matomoReport = matomoReportSubscriptionService.subscribe(context, item);
        context.commit();
        return converter.toRest(matomoReport, utils.obtainProjection());
    }

    @PreAuthorize("hasAuthority('AUTHENTICATED')")
    @RequestMapping(method = RequestMethod.POST, path = "unsubscribe")
    public ResponseEntity<RepresentationModel<?>> itemUnsubscribe(HttpServletRequest request)
            throws AuthorizeException, SQLException {
        Context context = getContext(request);

        Item item = getItem(context, request);
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
    public MatomoReportSubscriptionRest getReportForItem(HttpServletRequest request)
            throws AuthorizeException, SQLException {
        Context context = getContext(request);

        Item item = getItem(context, request);
        MatomoReportSubscription matomoReport = matomoReportSubscriptionService.findByItem(context, item);
        if (matomoReport == null) {
            throw new ResourceNotFoundException("Current user is not subscribed for this item");
        }
        return converter.toRest(matomoReport, utils.obtainProjection());
    }

    private Item getItem(Context context, HttpServletRequest request) {
        String itemId = request.getParameter(ITEM_QUERY_PARAMETER);
        if (itemId == null || itemId.isEmpty()) {
            throw new DSpaceBadRequestException("missing " + ITEM_QUERY_PARAMETER + " query parameter");
        }
        UUID itemUuid;
        try {
            itemUuid =  UUID.fromString(itemId);
        } catch (IllegalArgumentException ex) {
            throw new DSpaceBadRequestException(ex.getMessage(), ex);
        }
        try {
            Item item = itemService.find(context, itemUuid);
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
