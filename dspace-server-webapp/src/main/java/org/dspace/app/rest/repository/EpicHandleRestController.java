/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.repository;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;

import org.dspace.app.rest.converter.ConverterService;
import org.dspace.app.rest.exception.DSpaceBadRequestException;
import org.dspace.app.rest.model.EpicHandleRest;
import org.dspace.app.rest.model.hateoas.EpicHandleResource;
import org.dspace.app.rest.projection.Projection;
import org.dspace.app.rest.utils.Utils;
import org.dspace.handle.service.EpicHandleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.rest.webmvc.ResourceNotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * Specialized controller created for ePIC handle.
 *
 * @author Milan Kuchtiak
 */
@RestController
@RequestMapping({EpicHandleRest.URI_PREFIX, EpicHandleRest.URI_PREFIX_PLURAL})
public class EpicHandleRestController {
    private static final int DEFAULT_PAGE_SIZE = 10;

    @Autowired
    private ConverterService converter;
    @Autowired
    private EpicHandleService epicHandleService;
    @Autowired
    private Utils utils;

    @PreAuthorize("hasAuthority('ADMIN')")
    @RequestMapping(method = RequestMethod.POST, path = "{prefix}")
    public ResponseEntity<EpicHandleResource> createHandle(@PathVariable String prefix, HttpServletRequest request)
            throws IOException, URISyntaxException {
        if (prefix == null || prefix.isEmpty()) {
            throw new DSpaceBadRequestException("Epic handle prefix cannot be empty string");
        }
        String subPrefix = request.getParameter("subprefix");
        String subSuffix = request.getParameter("subsuffix");
        String url = request.getParameter("url");
        if (url == null) {
            throw new DSpaceBadRequestException("Epic handle URL is required");
        }
        try {
            String pid = epicHandleService.createHandle(prefix, subPrefix, subSuffix, url);
            EpicHandleService.Handle handle = new EpicHandleService.Handle(pid, url);
            EpicHandleResource handleResource = converter.toResource(toRest(handle, utils.obtainProjection()));
            URL urlLocation = new URL(request.getRequestURL().append(pid.substring(pid.indexOf("/"))).toString());
            return ResponseEntity.created(urlLocation.toURI()).body(handleResource);
        } catch (WebApplicationException ex) {
            throw toDSpaceException(ex);
        }
    }

    @PreAuthorize("hasAuthority('ADMIN')")
    @RequestMapping(method = RequestMethod.PUT, path = "{prefix}/{suffix}")
    public ResponseEntity<?> updateHandle(@PathVariable String prefix,
                                           @PathVariable String suffix,
                                           HttpServletRequest request)
            throws IOException {
        if (prefix == null || prefix.isEmpty()) {
            throw new DSpaceBadRequestException("Epic handle prefix cannot be empty string");
        }
        if (suffix == null || suffix.isEmpty()) {
            throw new DSpaceBadRequestException("Epic handle suffix cannot be empty string");
        }
        String url = request.getParameter("url");
        if (url == null) {
            throw new DSpaceBadRequestException("Epic handle URL is required");
        }
        try {
            // check if handle exists
            if (epicHandleService.resolveURLForHandle(prefix, suffix) == null) {
                throw new ResourceNotFoundException("Epic handle not found");
            }
            epicHandleService.updateHandle(prefix, suffix, url);
            return ResponseEntity.noContent().build();
        } catch (WebApplicationException ex) {
            throw toDSpaceException(ex);
        }
    }

    @PreAuthorize("hasAuthority('ADMIN')")
    @RequestMapping(method = RequestMethod.GET, path = "{prefix}/{suffix}")
    public EpicHandleResource getHandle(@PathVariable String prefix, @PathVariable String suffix)
            throws IOException {
        if (prefix == null || prefix.isEmpty()) {
            throw new DSpaceBadRequestException("Epic handle prefix cannot be empty string");
        }
        if (suffix == null || suffix.isEmpty()) {
            throw new DSpaceBadRequestException("Epic handle suffix cannot be empty string");
        }
        try {
            String url = epicHandleService.resolveURLForHandle(prefix, suffix);
            if (url == null) {
                throw new ResourceNotFoundException("Epic handle not found");
            }
            EpicHandleService.Handle handle = new EpicHandleService.Handle(prefix + "/" + suffix, url);
            return converter.toResource(toRest(handle, utils.obtainProjection()));
        } catch (WebApplicationException ex) {
            throw toDSpaceException(ex);
        }
    }

    @PreAuthorize("hasAuthority('ADMIN')")
    @RequestMapping(method = RequestMethod.GET, path = "{prefix}")
    public Page<EpicHandleRest> search(@PathVariable String prefix, HttpServletRequest request)
            throws IOException {
        if (prefix == null || prefix.isEmpty()) {
            throw new DSpaceBadRequestException("Epic handle prefix cannot be empty string");
        }
        String url = request.getParameter("url");
        if (url == null) {
            throw new DSpaceBadRequestException("Epic handle URL is required");
        }
        int page = Optional.ofNullable(request.getParameter("page"))
                .map(Integer::parseInt).orElse(0);
        int size = Optional.ofNullable(request.getParameter("size"))
                .map(Integer::parseInt).orElse(DEFAULT_PAGE_SIZE);

        CompletableFuture<Integer> future1 = CompletableFuture.supplyAsync(() -> {
            try {
                return epicHandleService.count(prefix, url);
            } catch (IOException ex) {
                return -1;
            } catch (WebApplicationException ex) {
                throw toDSpaceException(ex);
            }
        });
        CompletableFuture<List<EpicHandleService.Handle>> future2 = CompletableFuture.supplyAsync(() -> {
            try {
                return epicHandleService.search(prefix, url,page + 1, size);
            } catch (IOException ex) {
                return null;
            } catch (WebApplicationException ex) {
                throw toDSpaceException(ex);
            }
        });
        try {
            // wait for both tasks to complete
            CompletableFuture.allOf(future1, future2).join();
            int count = future1.get();
            List<EpicHandleService.Handle> handles = future2.get();
            Projection proj = utils.obtainProjection();
            List<EpicHandleRest> restObjects = handles.stream()
                    .map(h -> toRest(h, proj))
                    .collect(Collectors.toList());
            return new PageImpl<>(restObjects, PageRequest.of(page, size), count);
        } catch (InterruptedException | ExecutionException ex) {
            throw new RuntimeException(ex);
        }
    }

    @PreAuthorize("hasAuthority('ADMIN')")
    @RequestMapping(method = RequestMethod.DELETE, path = "{prefix}/{suffix}")
    public ResponseEntity<?> deleteHandle(@PathVariable String prefix, @PathVariable String suffix)
            throws IOException {
        if (prefix == null || prefix.isEmpty()) {
            throw new DSpaceBadRequestException("Epic handle prefix cannot be empty string");
        }
        if (suffix == null || suffix.isEmpty()) {
            throw new DSpaceBadRequestException("Epic handle suffix cannot be empty string");
        }
        try {
            epicHandleService.deleteHandle(prefix, suffix);
            return ResponseEntity.noContent().build();
        } catch (WebApplicationException ex) {
            throw toDSpaceException(ex);
        }
    }

    private EpicHandleRest toRest(EpicHandleService.Handle handle, Projection projection) {
        return converter.toRest(handle, projection);
    }

    private static RuntimeException toDSpaceException(WebApplicationException ex) {
        if (ex.getResponse().getStatus() == Response.Status.NOT_FOUND.getStatusCode()) {
            return new ResourceNotFoundException(ex.getMessage());
        } else if (ex.getResponse().getStatus() == Response.Status.BAD_REQUEST.getStatusCode()) {
            return new DSpaceBadRequestException(ex.getMessage());
        } else {
            return ex;
        }
    }
}
