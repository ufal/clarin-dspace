/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest;

import javax.servlet.http.HttpServletRequest;

import org.dspace.app.rest.utils.ContextUtil;
import org.dspace.core.Context;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller for retrieving database connection statistics
 *
 * @author Milan Majchrak (dspace at dataquest.sk)
 */
@PreAuthorize("hasAuthority('ADMIN')")
@RequestMapping(value = "/api/dbstatistics")
@RestController
public class DBConnectionStatisticsController {
    @RequestMapping(method = RequestMethod.GET)
    public ResponseEntity<String> getStatistics(HttpServletRequest request) {

        Context context = ContextUtil.obtainContext(request);
        if (context == null) {
            return ResponseEntity.status(500).build();
        }
        // Return response entity with the statistics
        return ResponseEntity.ok().body(context.getHibernateStatistics());
    }
}
