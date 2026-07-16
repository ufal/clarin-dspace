/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest;

import static org.dspace.app.rest.utils.ContextUtil.obtainContext;

import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import javax.servlet.http.HttpServletRequest;

import org.dspace.app.rest.exception.DSpaceBadRequestException;
import org.dspace.app.util.Util;
import org.dspace.core.Context;
import org.dspace.eperson.EPerson;
import org.dspace.eperson.service.EPersonService;
import org.dspace.eperson.service.clarin.ClarinIdentityService;
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
 * Admin endpoint for manual identity linking (section 4: "attach netid X as alias of EPerson
 * Y", written to {@code eperson_netid_alias} with {@code source='admin'}). Identity
 * administration happens here, not via the {@code /netid} PATCH operation, which now only
 * edits the denormalized display column.
 *
 * Endpoint: POST /api/clarin/identity/link?eperson={uuid}&amp;value={identityValue}&amp;authority={idpEntityIdOrIssuer}
 *
 * @author Ondrej Kosarko
 */
@RestController
@RequestMapping("/api/clarin/identity")
public class ClarinIdentityLinkController {

    @Autowired
    private EPersonService ePersonService;
    @Autowired
    private ClarinIdentityService identityService;

    @PreAuthorize("hasAuthority('ADMIN')")
    @RequestMapping(method = RequestMethod.POST, path = "/link")
    public ResponseEntity<RepresentationModel<?>> link(HttpServletRequest request)
            throws SQLException {
        Context context = getContext(request);

        UUID epersonUuid = parseUuid(request.getParameter("eperson"), "eperson");
        String value = requireParameter(request, "value");
        String authority = requireParameter(request, "authority");

        EPerson ePerson = ePersonService.find(context, epersonUuid);
        if (ePerson == null) {
            throw new ResourceNotFoundException("No EPerson found for eperson=" + epersonUuid);
        }

        String netid = Util.formatNetId(value, authority);
        try {
            identityService.attach(context, ePerson, netid, ClarinIdentityService.SOURCE_ADMIN,
                    context.getCurrentUser());
        } catch (IllegalStateException e) {
            throw new DSpaceBadRequestException(e.getMessage(), e);
        }
        context.commit();

        return ControllerUtils.toEmptyResponse(HttpStatus.CREATED);
    }

    private static UUID parseUuid(String value, String paramName) {
        if (value == null) {
            throw new DSpaceBadRequestException("Missing required parameter: " + paramName);
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new DSpaceBadRequestException("Invalid UUID for parameter " + paramName + ": " + value);
        }
    }

    private static String requireParameter(HttpServletRequest request, String paramName) {
        String value = request.getParameter(paramName);
        if (value == null || value.isBlank()) {
            throw new DSpaceBadRequestException("Missing required parameter: " + paramName);
        }
        return value;
    }

    private static Context getContext(HttpServletRequest request) {
        Context context = obtainContext(request);
        if (Objects.isNull(context)) {
            throw new RuntimeException("Context is null!");
        }
        return context;
    }
}
