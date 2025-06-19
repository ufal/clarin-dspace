/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.repository;

import static org.dspace.handle.service.EpicHandleService.Handle;

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
import org.dspace.app.rest.exception.RepositoryMethodNotImplementedException;
import org.dspace.app.rest.model.EpicHandleRest;
import org.dspace.app.rest.model.hateoas.EpicHandleResource;
import org.dspace.app.rest.projection.Projection;
import org.dspace.app.rest.utils.Utils;
import org.dspace.core.Context;
import org.dspace.handle.service.EpicHandleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.rest.webmvc.ResourceNotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
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
@Component(EpicHandleRest.CATEGORY + "." + EpicHandleRest.NAME)
public class EpicHandleRestController extends DSpaceRestRepository<EpicHandleRest, String> {
    private static final int DEFAULT_PAGE_SIZE = 10;

    @Autowired
    private ConverterService converter;
    @Autowired
    private EpicHandleService epicHandleService;
    @Autowired
    private Utils utils;

    @Override
    public EpicHandleRest findOne(Context context, String s) {
        throw new RepositoryMethodNotImplementedException("Method not allowed!", "findOne");
    }

    @Override
    public Page<EpicHandleRest> findAll(Context context, Pageable pageable) {
        throw new RepositoryMethodNotImplementedException("Method not allowed!", "findAll");
    }

    @Override
    public Class<EpicHandleRest> getDomainClass() {
        return EpicHandleRest.class;
    }

    @PreAuthorize("hasAuthority('ADMIN')")
    @RequestMapping(method = RequestMethod.POST, path = "{prefix}")
    public ResponseEntity<EpicHandleResource> createHandle(@PathVariable String prefix, HttpServletRequest request)
            throws IOException, URISyntaxException {
        if (prefix == null || prefix.isEmpty()) {
            throw new DSpaceBadRequestException("Epic handle prefix cannot be empty string");
        }
        String subPrefix = request.getParameter("prefix");
        String subSuffix = request.getParameter("suffix");
        String url = request.getParameter("url");
        if (url == null) {
            throw new DSpaceBadRequestException("Epic handle URL is required");
        }
        try {
            String pid = epicHandleService.createHandle(prefix, subPrefix, subSuffix, url);
            Handle handle = new Handle(pid, url);
            EpicHandleResource handleResource = converter.toResource(toRest(handle, utils.obtainProjection()));
            // compute URL of created handle
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
                                          HttpServletRequest request) throws IOException {
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
    public EpicHandleResource getHandle(@PathVariable String prefix, @PathVariable String suffix) throws IOException {
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
            Handle handle = new Handle(prefix + "/" + suffix, url);
            return converter.toResource(toRest(handle, utils.obtainProjection()));
        } catch (WebApplicationException ex) {
            throw toDSpaceException(ex);
        }
    }

    @PreAuthorize("hasAuthority('ADMIN')")
    @RequestMapping(method = RequestMethod.GET, path = "{prefix}")
    public Page<EpicHandleRest> search(@PathVariable String prefix, HttpServletRequest request) throws IOException {
        if (prefix == null || prefix.isEmpty()) {
            throw new DSpaceBadRequestException("Epic handle prefix cannot be empty string");
        }
        String urlQuery = request.getParameter("url");
        int page = Optional.ofNullable(request.getParameter("page"))
                .map(Integer::parseInt).orElse(0);
        int size = Optional.ofNullable(request.getParameter("size"))
                .map(Integer::parseInt).orElse(DEFAULT_PAGE_SIZE);
        int totalElements = Optional.ofNullable(request.getParameter("totalElements"))
                .map(Integer::parseInt).orElse(-1);
        boolean runSynchronously = Optional.ofNullable(request.getParameter("runSynchronously"))
                .map(Boolean::parseBoolean).orElse(false);
        try {
            if (totalElements >= 0) {
                // counting elements is not needed
                List<Handle> handles = epicHandleService.searchHandles(prefix, urlQuery, page + 1, size);
                return getPage(handles, page, size, totalElements);
            } else if (runSynchronously) {
                // first count elements, then search handles
                int count = epicHandleService.countHandles(prefix, urlQuery);
                List<Handle> handles = epicHandleService.searchHandles(prefix, urlQuery, page + 1, size);
                return getPage(handles, page, size, count);
            } else {
                // running elements count and search asynchronously in 2 different threads
                CompletableFuture<ValueStorage<Integer>> future1 = CompletableFuture.supplyAsync(() -> {
                    try {
                        return new ValueStorage<>(epicHandleService.countHandles(prefix, urlQuery));
                    } catch (IOException ex) {
                        return new ValueStorage<>(ex);
                    } catch (WebApplicationException ex) {
                        return new ValueStorage<>(ex);
                    }
                });
                CompletableFuture<ValueStorage<List<Handle>>> future2 = CompletableFuture.supplyAsync(() -> {
                    try {
                        return new ValueStorage<>(epicHandleService.searchHandles(prefix, urlQuery, page + 1, size));
                    } catch (IOException ex) {
                        return new ValueStorage<>(ex);
                    } catch (WebApplicationException ex) {
                        return new ValueStorage<>(ex);
                    }
                });
                // wait for both threads to complete
                CompletableFuture.allOf(future1, future2).join();
                try {
                    int count = future1.get().getValue();
                    List<Handle> handles = future2.get().getValue();
                    return getPage(handles, page, size, count);
                } catch (InterruptedException | ExecutionException ex) {
                    throw new RuntimeException(ex);
                }
            }
        } catch (WebApplicationException ex) {
            throw toDSpaceException(ex);
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

    private EpicHandleRest toRest(Handle handle, Projection projection) {
        return converter.toRest(handle, projection);
    }

    private Page<EpicHandleRest> getPage(List<Handle> handles, int page, int size, int totalElements) {
        Projection proj = utils.obtainProjection();
        List<EpicHandleRest> restObjects = handles.stream()
                .map(h -> toRest(h, proj))
                .collect(Collectors.toList());
        return new PageImpl<>(restObjects, PageRequest.of(page, size), totalElements);
    }

    private static RuntimeException toDSpaceException(WebApplicationException ex) {
        int responseStatus = ex.getResponse().getStatus();
        if (responseStatus == Response.Status.NOT_FOUND.getStatusCode()) {
            return new ResourceNotFoundException(ex.getMessage());
        } else if (responseStatus == Response.Status.BAD_REQUEST.getStatusCode()) {
            return new DSpaceBadRequestException(ex.getMessage());
        } else {
            return ex;
        }
    }

    /**
     * This class is used to store the response value, as well as possible exception, when requests are called
     * in a separate thread.
     *
     * @param <T> value to store
     */
    private static class ValueStorage<T> {
        T value;
        WebApplicationException ex;
        IOException ie;

        ValueStorage(T value) {
            this.value = value;
        }

        ValueStorage(WebApplicationException ex) {
            this.ex = ex;
        }

        ValueStorage(IOException ie) {
            this.ie = ie;
        }

        T getValue() throws WebApplicationException, IOException {
            if (ex != null) {
                throw ex;
            }
            if (ie != null) {
                throw ie;
            }
            return value;
        }
    }
}
