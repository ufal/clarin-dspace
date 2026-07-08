/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.hf;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.InternalServerErrorException;

import org.apache.catalina.connector.ClientAbortException;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tomcat.util.http.FastHttpDateFormat;
import org.dspace.app.rest.utils.ContextUtil;
import org.dspace.app.rest.utils.HttpHeadersInitializer;
import org.dspace.app.statistics.clarin.ClarinMatomoBitstreamTracker;
import org.dspace.authorize.AuthorizeException;
import org.dspace.authorize.service.AuthorizeService;
import org.dspace.content.Bitstream;
import org.dspace.content.BitstreamFormat;
import org.dspace.content.Item;
import org.dspace.core.Constants;
import org.dspace.core.Context;
import org.dspace.eperson.EPerson;
import org.dspace.services.ConfigurationService;
import org.dspace.services.EventService;
import org.dspace.storage.bitstore.S3BitStoreService;
import org.dspace.storage.bitstore.service.S3DirectDownloadService;
import org.dspace.usage.UsageEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriUtils;

/**
 * HuggingFace-Hub-compatible facade controller (read-only).
 *
 * <p>Exposes a subset of the {@code huggingface_hub} REST API shape (model info, tree listing, file
 * download/"resolve" and {@code whoami-v2}) backed by {@link ClarinHuggingFaceService}, so that DSpace
 * Items flagged as "machine learning models" (see the {@code hf.api.*} configuration keys) can be
 * consumed directly by HuggingFace-Hub-aware tooling (e.g. the {@code huggingface_hub} Python client)
 * using the Item handle as the repository id.</p>
 *
 * <p>The file-download ({@code resolve}) route streams (or, when configured for S3 direct download,
 * redirects to) the ORIGINAL bitstreams of an exposed Item.</p>
 *
 * <p>Note: {@code @PreAuthorize} is not used because authorization depends on the resolved Item (looked
 * up by handle), not on a UUID path variable. Authorization is explicitly checked via
 * {@link AuthorizeService#authorizeAction} after the Item is resolved, mirroring
 * {@code BitstreamByHandleRestController}.</p>
 *
 * @author DSpace at UFAL
 */
@RestController
@RequestMapping("/api/hf")
public class ClarinHuggingFaceController {

    private static final Logger log = LogManager.getLogger(ClarinHuggingFaceController.class);

    private static final String ERROR_CODE_HEADER = "X-Error-Code";

    private static final String REPO_COMMIT_HEADER = "X-Repo-Commit";

    private static final String LINKED_ETAG_HEADER = "X-Linked-Etag";

    private static final String LINKED_SIZE_HEADER = "X-Linked-Size";

    private static final String RESOLVE_MARKER = "/resolve/";

    // Most file systems are configured to use block sizes of 4096 or 8192 and our buffer should be a
    // multiple of that (mirrors BitstreamRestController / BitstreamByHandleRestController).
    private static final int BUFFER_SIZE = 4096 * 10;

    @Autowired
    private ClarinHuggingFaceService huggingFaceService;

    @Autowired
    private AuthorizeService authorizeService;

    @Autowired
    private ConfigurationService configurationService;

    @Autowired
    private EventService eventService;

    @Autowired
    private ClarinMatomoBitstreamTracker matomoBitstreamTracker;

    @Autowired
    private S3DirectDownloadService s3DirectDownloadService;

    @Autowired
    private S3BitStoreService s3BitStoreService;

    /**
     * Get the model info of an exposed Item, at the implicit "main" revision.
     *
     * @param prefix  the handle prefix (e.g. "11234")
     * @param suffix  the handle suffix (e.g. "1-5814")
     * @param request the HTTP request
     * @return the model info, or an error response
     * @throws SQLException if a database error occurs
     */
    @GetMapping("/api/models/{prefix}/{suffix}")
    public ResponseEntity<Object> modelInfo(@PathVariable String prefix, @PathVariable String suffix,
            HttpServletRequest request) throws SQLException {
        if (!huggingFaceService.isEnabled()) {
            return ResponseEntity.notFound().build();
        }

        Context context = ContextUtil.obtainContext(request);
        try {
            ResolvedRepository repository = resolveAuthorizedRepository(context, prefix, suffix);
            HuggingFaceModelInfo modelInfo =
                    huggingFaceService.toModelInfo(context, repository.item, repository.sha);
            context.complete();
            return ResponseEntity.ok(modelInfo);
        } catch (HuggingFaceApiException e) {
            return e.toResponse();
        }
    }

    /**
     * Get the model info of an exposed Item, at a specific (validated) revision.
     *
     * @param prefix   the handle prefix (e.g. "11234")
     * @param suffix   the handle suffix (e.g. "1-5814")
     * @param revision the requested revision; must be {@code "main"} or the item's current sha
     * @param request  the HTTP request
     * @return the model info, or an error response
     * @throws SQLException if a database error occurs
     */
    @GetMapping("/api/models/{prefix}/{suffix}/revision/{revision}")
    public ResponseEntity<Object> modelInfoAtRevision(@PathVariable String prefix, @PathVariable String suffix,
            @PathVariable String revision, HttpServletRequest request) throws SQLException {
        if (!huggingFaceService.isEnabled()) {
            return ResponseEntity.notFound().build();
        }

        Context context = ContextUtil.obtainContext(request);
        try {
            ResolvedRepository repository = resolveAuthorizedRepository(context, prefix, suffix);
            requireValidRevision(revision, repository.sha);
            HuggingFaceModelInfo modelInfo =
                    huggingFaceService.toModelInfo(context, repository.item, repository.sha);
            context.complete();
            return ResponseEntity.ok(modelInfo);
        } catch (HuggingFaceApiException e) {
            return e.toResponse();
        }
    }

    /**
     * Get the flat file tree of an exposed Item, at a specific (validated) revision.
     *
     * @param prefix    the handle prefix (e.g. "11234")
     * @param suffix    the handle suffix (e.g. "1-5814")
     * @param revision  the requested revision; must be {@code "main"} or the item's current sha
     * @param recursive accepted and ignored (single-page flat listing regardless of its value)
     * @param request   the HTTP request
     * @return the flat list of tree entries, or an error response
     * @throws SQLException if a database error occurs
     */
    @GetMapping("/api/models/{prefix}/{suffix}/tree/{revision}")
    public ResponseEntity<Object> tree(@PathVariable String prefix, @PathVariable String suffix,
            @PathVariable String revision,
            @RequestParam(required = false) String recursive,
            HttpServletRequest request) throws SQLException {
        if (!huggingFaceService.isEnabled()) {
            return ResponseEntity.notFound().build();
        }

        Context context = ContextUtil.obtainContext(request);
        try {
            ResolvedRepository repository = resolveAuthorizedRepository(context, prefix, suffix);
            requireValidRevision(revision, repository.sha);
            List<HuggingFaceTreeEntry> entries = huggingFaceService.toTreeEntries(context, repository.item);
            context.complete();
            return ResponseEntity.ok(entries);
        } catch (HuggingFaceApiException e) {
            return e.toResponse();
        }
    }

    /**
     * List exposed Items ("models"), HuggingFace-Hub {@code GET /api/models} style.
     *
     * <p>This is a metadata-only listing of publicly exposed items: unlike the model-info/tree/resolve
     * endpoints, no per-item {@code READ} authorization check is performed, since only items that already
     * pass {@link ClarinHuggingFaceService#isExposed(Context, Item)} (fail-closed, public-by-configuration)
     * are ever returned.</p>
     *
     * @param search an optional, case-insensitive substring to match against the item title
     * @param limit  the maximum number of items to return (default 20, capped at 100)
     * @param request the HTTP request
     * @return the matching model-info objects, as a JSON array
     * @throws SQLException if a database error occurs
     */
    @GetMapping("/api/models")
    public ResponseEntity<Object> listModels(@RequestParam(required = false) String search,
            @RequestParam(required = false, defaultValue = "20") int limit,
            HttpServletRequest request) throws SQLException {
        if (!huggingFaceService.isEnabled()) {
            return ResponseEntity.notFound().build();
        }

        Context context = ContextUtil.obtainContext(request);
        List<HuggingFaceModelInfo> models = new ArrayList<>();
        for (Item item : huggingFaceService.findExposedItems(context, search, limit)) {
            String sha = huggingFaceService.computeShaForItem(context, item);
            models.add(huggingFaceService.toModelInfo(context, item, sha));
        }
        context.complete();
        return ResponseEntity.ok(models);
    }

    /**
     * Placeholder for the HuggingFace Hub {@code whoami-v2} endpoint. Always responds with 401, since
     * this facade does not (yet) support Bearer-token authentication.
     *
     * @return a 401 response with a small JSON error body
     */
    @GetMapping("/api/whoami-v2")
    public ResponseEntity<Object> whoAmI() {
        // TODO: phase 2 - map Authorization: Bearer to CLARIN Personal Access Token here
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Collections.singletonMap("error", "Invalid user token"));
    }

    /**
     * Download (GET) or probe (HEAD) a single ORIGINAL bitstream of an exposed Item, HuggingFace-Hub
     * "resolve" style: {@code /{prefix}/{suffix}/resolve/{revision}/{filename}}.
     *
     * <p>The filename is everything after the revision path segment (it may contain slashes and
     * URL-encoded characters, decoded once). HEAD requests are always answered locally with the metadata
     * headers ({@code X-Repo-Commit}, {@code ETag}, {@code X-Linked-Etag}, {@code Content-Length},
     * {@code X-Linked-Size}, {@code Accept-Ranges}) and an empty body; they are never redirected. GET
     * requests are either redirected (302) to an S3 presigned URL when direct S3 download is enabled, or
     * streamed with full Range/conditional-request support.</p>
     *
     * @param prefix   the handle prefix (e.g. "11234")
     * @param suffix   the handle suffix (e.g. "1-5814")
     * @param revision the requested revision; must be {@code "main"} or the item's current sha
     * @param request  the HTTP request
     * @param response the HTTP response
     * @return the response entity (file content, redirect or error), or {@code null} when the response
     *     has already been committed (e.g. 304/412 shortcuts or a client abort)
     * @throws SQLException if a database error occurs
     * @throws IOException  if an I/O error occurs while initialising the response
     */
    @RequestMapping(method = {RequestMethod.GET, RequestMethod.HEAD},
            value = "/{prefix}/{suffix}/resolve/{revision}/**")
    public ResponseEntity<Object> resolveFile(@PathVariable String prefix, @PathVariable String suffix,
            @PathVariable String revision, HttpServletRequest request, HttpServletResponse response)
            throws SQLException, IOException {
        if (!huggingFaceService.isEnabled()) {
            return ResponseEntity.notFound().build();
        }

        Context context = ContextUtil.obtainContext(request);
        try {
            if (Objects.isNull(context)) {
                log.error("Cannot obtain the context from the request.");
                throw new HuggingFaceApiException(HttpStatus.INTERNAL_SERVER_ERROR, null, "Internal error.");
            }

            String handle = prefix + "/" + suffix;
            Item item = huggingFaceService.resolveExposedItem(context, handle);
            if (item == null) {
                throw new HuggingFaceApiException(HttpStatus.NOT_FOUND, "RepoNotFound", "Repository not found");
            }

            String sha = huggingFaceService.computeShaForItem(context, item);
            requireValidRevision(revision, sha);

            String filename = extractResolveFilename(request);
            if (StringUtils.isBlank(filename)) {
                throw new HuggingFaceApiException(HttpStatus.NOT_FOUND, "EntryNotFound", "Entry not found");
            }

            Bitstream bitstream = huggingFaceService.getOriginalFiles(item).get(filename);
            if (bitstream == null) {
                throw new HuggingFaceApiException(HttpStatus.NOT_FOUND, "EntryNotFound", "Entry not found");
            }

            try {
                // Authorization is checked explicitly here (not via @PreAuthorize) because the bitstream
                // identity is resolved from handle + filename, not from a UUID path variable.
                authorizeService.authorizeAction(context, bitstream, Constants.READ);
            } catch (AuthorizeException e) {
                // TODO: phase 2 - Bearer/PAT auth: map Authorization: Bearer to a CLARIN Personal Access
                // Token and re-check authorization with the resolved EPerson before falling back to the
                // anonymous 401/403 distinction below.
                log.warn("Unauthorized access to file '{}' of HuggingFace repository '{}'.", filename, handle);
                HttpStatus status = context.getCurrentUser() == null
                        ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN;
                String landingPage = configurationService.getProperty("dspace.ui.url") + "/handle/" + handle;
                throw new HuggingFaceApiException(status, null,
                        "You are not authorized to download this file. Please visit the item landing page at "
                                + landingPage + " to request access.");
            }

            return serveBitstream(context, request, response, item, bitstream, sha);
        } catch (HuggingFaceApiException e) {
            return e.toResponse();
        }
    }

    /**
     * Serve a resolved and authorized bitstream: HEAD locally, GET via an S3 presigned-URL redirect when
     * direct S3 download is enabled, or by streaming the content otherwise.
     *
     * @param context   the DSpace context
     * @param request   the HTTP request
     * @param response  the HTTP response
     * @param item      the owning item (source of {@code Last-Modified})
     * @param bitstream the bitstream to serve
     * @param sha       the item's current sha (sent as {@code X-Repo-Commit})
     * @return the response entity, or {@code null} when the response has already been committed
     * @throws SQLException if a database error occurs
     * @throws IOException  if an I/O error occurs while initialising the response
     */
    private ResponseEntity<Object> serveBitstream(Context context, HttpServletRequest request,
            HttpServletResponse response, Item item, Bitstream bitstream, String sha)
            throws SQLException, IOException {
        BitstreamFormat format = bitstream.getFormat(context);
        String mimetype = format != null ? format.getMIMEType() : MediaType.APPLICATION_OCTET_STREAM_VALUE;
        String name = StringUtils.isNotBlank(bitstream.getName())
                ? bitstream.getName() : bitstream.getID().toString();
        String quotedEtag = "\"" + bitstream.getChecksum() + "\"";
        long sizeBytes = bitstream.getSizeBytes();
        long lastModifiedMillis = item.getLastModified() != null ? item.getLastModified().getTime() : 0L;

        if (RequestMethod.HEAD.name().equals(request.getMethod())) {
            // HEAD is always answered locally (never redirected to S3), so that HuggingFace clients can
            // read the metadata headers without following a presigned URL.
            HttpHeaders headers = buildCommonHeaders(sha, quotedEtag, sizeBytes, mimetype, lastModifiedMillis);
            context.complete();
            return ResponseEntity.ok().headers(headers).build();
        }

        // We only log a download for requests without a Range header, because a client always sends a
        // regular request first to check for Range support (mirrors BitstreamByHandleRestController).
        if (StringUtils.isBlank(request.getHeader("Range"))) {
            eventService.fireEvent(
                new UsageEvent(
                    UsageEvent.Action.VIEW,
                    request,
                    context,
                    bitstream));

            // Track the download in Matomo - only if the downloading has started (the condition is
            // inside the method)
            matomoBitstreamTracker.trackBitstreamDownload(context, request, bitstream, false);
        }

        try {
            boolean s3DirectDownload = configurationService.getBooleanProperty("s3.download.direct.enabled");
            boolean s3AssetstoreEnabled = configurationService.getBooleanProperty("assetstore.s3.enabled");
            if (s3DirectDownload && s3AssetstoreEnabled) {
                // Download only files which are stored in the `ORIGINAL` bundle, because some specific
                // files are not correctly downloaded and displayed in the UI when using presigned URLs.
                boolean hasOriginalBundle = bitstream.getBundles().stream()
                        .anyMatch(bundle -> Constants.CONTENT_BUNDLE_NAME.equals(bundle.getName()));
                if (hasOriginalBundle) {
                    HttpHeaders headers =
                            buildCommonHeaders(sha, quotedEtag, sizeBytes, mimetype, lastModifiedMillis);
                    // Close the DB connection before redirecting
                    context.complete();
                    return redirectToS3DownloadUrl(headers, name, bitstream.getInternalId());
                }
            }

            EPerson currentUser = context.getCurrentUser();
            org.dspace.app.rest.utils.BitstreamResource bitstreamResource =
                    new org.dspace.app.rest.utils.BitstreamResource(name, bitstream.getID(),
                            currentUser != null ? currentUser.getID() : null,
                            context.getSpecialGroupUuids(), false);

            HttpHeadersInitializer httpHeadersInitializer = new HttpHeadersInitializer()
                    .withBufferSize(BUFFER_SIZE)
                    .withFileName(name)
                    .withChecksum(quotedEtag)
                    .withLength(bitstreamResource.contentLength())
                    .withMimetype(mimetype)
                    .withLastModified(lastModifiedMillis)
                    .withDisposition(HttpHeadersInitializer.CONTENT_DISPOSITION_ATTACHMENT)
                    .with(request)
                    .with(response);

            // We have all the data we need, close the connection to the database so that it doesn't
            // stay open during download/streaming
            context.complete();

            if (httpHeadersInitializer.isValid()) {
                HttpHeaders httpHeaders = httpHeadersInitializer.initialiseHeaders();
                httpHeaders.set(REPO_COMMIT_HEADER, sha);
                httpHeaders.set(LINKED_ETAG_HEADER, quotedEtag);
                httpHeaders.set(LINKED_SIZE_HEADER, String.valueOf(sizeBytes));
                return ResponseEntity.ok().headers(httpHeaders).body(bitstreamResource);
            }
        } catch (ClientAbortException ex) {
            log.debug("Client aborted the request before the download was completed. "
                    + "Client is probably switching to a Range request.", ex);
        }
        return null;
    }

    /**
     * Build the metadata headers shared by every successful {@code resolve} response (HEAD, 302 redirect
     * and streamed GET).
     *
     * @param sha                the item's current sha
     * @param quotedEtag         the bitstream's MD5 checksum, wrapped in double quotes
     * @param sizeBytes          the bitstream size in bytes
     * @param mimetype           the bitstream MIME type
     * @param lastModifiedMillis the item's last-modified timestamp, in epoch milliseconds
     * @return the headers
     */
    private HttpHeaders buildCommonHeaders(String sha, String quotedEtag, long sizeBytes, String mimetype,
            long lastModifiedMillis) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(REPO_COMMIT_HEADER, sha);
        headers.set(HttpHeaders.ETAG, quotedEtag);
        headers.set(LINKED_ETAG_HEADER, quotedEtag);
        headers.set(HttpHeaders.CONTENT_LENGTH, String.valueOf(sizeBytes));
        headers.set(LINKED_SIZE_HEADER, String.valueOf(sizeBytes));
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        headers.set(HttpHeaders.CONTENT_TYPE, mimetype);
        headers.set(HttpHeaders.LAST_MODIFIED, FastHttpDateFormat.formatDate(lastModifiedMillis));
        return headers;
    }

    /**
     * Extract the requested filename from the raw request URI of a {@code resolve} request: everything
     * after the {@code /resolve/} marker and the following (revision) path segment, decoded once. This
     * supports filenames containing slashes, spaces, parentheses and other URL-encoded characters.
     *
     * @param request the HTTP request
     * @return the decoded filename, or {@code null} if the URI contains no filename part
     */
    private String extractResolveFilename(HttpServletRequest request) {
        String uri = request.getRequestURI();
        int markerIndex = uri.indexOf(RESOLVE_MARKER);
        if (markerIndex < 0) {
            return null;
        }
        String rest = uri.substring(markerIndex + RESOLVE_MARKER.length());
        int slashIndex = rest.indexOf('/');
        if (slashIndex < 0) {
            return null;
        }
        String rawFilename = rest.substring(slashIndex + 1);
        return UriUtils.decode(rawFilename, StandardCharsets.UTF_8);
    }

    /**
     * This method will handle the S3 direct download by generating a presigned URL for the bitstream and
     * returning a redirect response to the client (mirrors {@code BitstreamRestController}).
     *
     * @param httpHeaders   headers needed to form a proper response when returning the Bitstream/File
     * @param bitName       name of the bitstream
     * @param bitInternalId internal id of the bitstream
     * @return ResponseEntity with the location header set to the presigned URL
     */
    private ResponseEntity<Object> redirectToS3DownloadUrl(HttpHeaders httpHeaders, String bitName,
            String bitInternalId) {
        try {
            String bucket = configurationService.getProperty("assetstore.s3.bucketName", "");
            if (StringUtils.isBlank(bucket)) {
                throw new InternalServerErrorException("S3 bucket name is not configured");
            }

            // Get the full path to the bitstream in the S3 bucket
            String bitstreamPath = s3BitStoreService.getFullKey(bitInternalId);
            if (StringUtils.isBlank(bitstreamPath)) {
                throw new InternalServerErrorException("Failed to get bitstream path for internal ID: "
                        + bitInternalId);
            }

            // Generate a presigned URL for the bitstream with a configurable expiration time
            int expirationTime = configurationService.getIntProperty("s3.download.direct.expiration", 3600);
            log.debug("Generating presigned URL with expiration time of {} seconds", expirationTime);
            String presignedUrl =
                    s3DirectDownloadService.generatePresignedUrl(bucket, bitstreamPath, expirationTime, bitName);

            if (StringUtils.isBlank(presignedUrl)) {
                throw new InternalServerErrorException("Failed to generate presigned URL for bitstream: "
                        + bitInternalId);
            }

            // Set the Location header to the presigned URL - this will redirect the client to the S3 URL
            httpHeaders.setLocation(URI.create(presignedUrl));
            return ResponseEntity.status(HttpStatus.FOUND).headers(httpHeaders).build();
        } catch (Exception e) {
            throw new InternalServerErrorException("Error generating S3 presigned URL for bitstream: "
                    + bitInternalId, e);
        }
    }

    /**
     * Common flow shared by the model-info, revision and tree endpoints: resolve the handle to an
     * exposed Item, check READ authorization on it, and compute its current sha.
     *
     * @param context the DSpace context
     * @param prefix  the handle prefix
     * @param suffix  the handle suffix
     * @return the resolved item together with its current sha
     * @throws SQLException           if a database error occurs while resolving the handle
     * @throws HuggingFaceApiException if the handle does not resolve to an exposed item, or the
     *                                  current user is not authorized to read it
     */
    private ResolvedRepository resolveAuthorizedRepository(Context context, String prefix, String suffix)
            throws SQLException {
        String handle = prefix + "/" + suffix;

        if (Objects.isNull(context)) {
            log.error("Cannot obtain the context from the request.");
            throw new HuggingFaceApiException(HttpStatus.INTERNAL_SERVER_ERROR, null, "Internal error.");
        }

        Item item = huggingFaceService.resolveExposedItem(context, handle);
        if (item == null) {
            throw new HuggingFaceApiException(HttpStatus.NOT_FOUND, "RepoNotFound", "Repository not found");
        }

        try {
            authorizeService.authorizeAction(context, item, Constants.READ);
        } catch (AuthorizeException e) {
            // TODO: phase 2 - Bearer token auth: map Authorization: Bearer to a CLARIN Personal Access
            // Token and re-check authorization with the resolved EPerson before falling back to the
            // anonymous 401/403 distinction below.
            log.warn("Unauthorized access to HuggingFace repository '{}'.", handle);
            HttpStatus status = context.getCurrentUser() == null ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN;
            throw new HuggingFaceApiException(status, null, "You are not authorized to access this repository.");
        }

        String sha = huggingFaceService.computeShaForItem(context, item);
        return new ResolvedRepository(item, sha);
    }

    /**
     * Validate a requested revision against the resolved item's current sha.
     *
     * @param revision   the requested revision
     * @param currentSha the item's current sha
     * @throws HuggingFaceApiException if the revision is not valid
     */
    private void requireValidRevision(String revision, String currentSha) {
        if (!ClarinHuggingFaceService.isValidRevision(revision, currentSha)) {
            throw new HuggingFaceApiException(HttpStatus.NOT_FOUND, "RevisionNotFound", "Revision not found");
        }
    }

    /**
     * Simple holder for the result of {@link #resolveAuthorizedRepository(Context, String, String)}.
     */
    private static final class ResolvedRepository {

        private final Item item;

        private final String sha;

        private ResolvedRepository(Item item, String sha) {
            this.item = item;
            this.sha = sha;
        }
    }

    /**
     * Internal unchecked exception used to short-circuit the common resolve/authorize/validate-revision
     * flow shared by the model-info, revision and tree endpoints, carrying the HTTP status, optional
     * {@code X-Error-Code} header value and JSON error message to send back to the client.
     */
    private static final class HuggingFaceApiException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final HttpStatus status;

        private final String errorCode;

        private final String message;

        private HuggingFaceApiException(HttpStatus status, String errorCode, String message) {
            this.status = status;
            this.errorCode = errorCode;
            this.message = message;
        }

        /**
         * Build the JSON error {@link ResponseEntity} for this exception.
         *
         * @return the response entity
         */
        private ResponseEntity<Object> toResponse() {
            ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
            if (errorCode != null) {
                builder = builder.header(ERROR_CODE_HEADER, errorCode);
            }
            return builder.body(Collections.singletonMap("error", message));
        }
    }
}
