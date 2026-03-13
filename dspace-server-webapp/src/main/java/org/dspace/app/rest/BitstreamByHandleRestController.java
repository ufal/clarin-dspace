/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest;

import static org.dspace.core.Constants.CONTENT_BUNDLE_NAME;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.InternalServerErrorException;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;
import org.dspace.app.rest.model.BitstreamRest;
import org.dspace.app.rest.utils.ContextUtil;
import org.dspace.app.statistics.clarin.ClarinMatomoBitstreamTracker;
import org.dspace.authorize.AuthorizeException;
import org.dspace.authorize.service.AuthorizeService;
import org.dspace.content.Bitstream;
import org.dspace.content.BitstreamFormat;
import org.dspace.content.Bundle;
import org.dspace.content.DSpaceObject;
import org.dspace.content.Item;
import org.dspace.content.service.BitstreamService;
import org.dspace.core.Constants;
import org.dspace.core.Context;
import org.dspace.handle.service.HandleService;
import org.dspace.services.ConfigurationService;
import org.dspace.services.EventService;
import org.dspace.storage.bitstore.S3BitStoreService;
import org.dspace.storage.bitstore.service.S3DirectDownloadService;
import org.dspace.usage.UsageEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * This controller provides a direct download endpoint for bitstreams
 * identified by an Item handle and the bitstream filename.
 *
 * <p>Endpoint: {@code GET /api/core/bitstreams/handle/{prefix}/{suffix}/{filename}}</p>
 *
 * <p>This is used by the command-line download instructions (curl commands)
 * shown on the item page in the UI. Only bitstreams in ORIGINAL bundles are served.</p>
 *
 * <p>Note: {@code @PreAuthorize} is not used because authorization depends on the resolved
 * bitstream (looked up by handle + filename), not on a UUID path variable. Authorization
 * is explicitly checked via {@link AuthorizeService#authorizeAction} after the bitstream
 * is resolved.</p>
 */
@RestController
@RequestMapping("/api/" + BitstreamRest.CATEGORY + "/" + BitstreamRest.PLURAL_NAME + "/handle")
public class BitstreamByHandleRestController {

    private static final Logger log =
            org.apache.logging.log4j.LogManager.getLogger(BitstreamByHandleRestController.class);

    private static final int BUFFER_SIZE = 4096 * 10;

    @Autowired
    private BitstreamService bitstreamService;

    @Autowired
    private HandleService handleService;

    @Autowired
    private AuthorizeService authorizeService;

    @Autowired
    private EventService eventService;

    @Autowired
    private ConfigurationService configurationService;

    @Autowired
    private ClarinMatomoBitstreamTracker matomoBitstreamTracker;

    @Autowired
    private S3DirectDownloadService s3DirectDownloadService;

    @Autowired
    private S3BitStoreService s3BitStoreService;

    /**
     * Download a bitstream by item handle and filename.
     *
     * @param prefix   the handle prefix (e.g. "11234")
     * @param suffix   the handle suffix (e.g. "1-5814")
     * @param filename the bitstream filename (e.g. "pdtvallex-4.5.xml")
     * @param request  the HTTP request
     * @param response the HTTP response
     * @throws IOException if an I/O error occurs during streaming
     */
    @RequestMapping(method = {RequestMethod.GET, RequestMethod.HEAD},
                    value = "/{prefix}/{suffix}/{filename:.+}")
    public void downloadBitstreamByHandle(@PathVariable String prefix,
                                          @PathVariable String suffix,
                                          @PathVariable String filename,
                                          HttpServletRequest request,
                                          HttpServletResponse response) throws IOException {
        String handle = prefix + "/" + suffix;

        Context context = ContextUtil.obtainContext(request);
        if (Objects.isNull(context)) {
            log.error("Cannot obtain the context from the request.");
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Cannot obtain the context from the request.");
            return;
        }

        try {
            // Resolve handle to DSpaceObject
            DSpaceObject dso = handleService.resolveToObject(context, handle);
            if (Objects.isNull(dso) || !(dso instanceof Item)) {
                log.warn("Handle '{}' does not resolve to a valid Item.", handle);
                response.sendError(HttpServletResponse.SC_NOT_FOUND,
                        "Handle '" + handle + "' does not resolve to a valid item.");
                return;
            }

            Item item = (Item) dso;
            Bitstream bitstream = findBitstreamByName(item, filename);

            if (bitstream == null) {
                log.warn("No bitstream with name '{}' found for handle '{}'.", filename, handle);
                response.sendError(HttpServletResponse.SC_NOT_FOUND,
                        "Bitstream '" + filename + "' not found for handle '" + handle + "'.");
                return;
            }

            // Authorization is checked explicitly here (not via @PreAuthorize) because the
            // bitstream identity is resolved from handle+filename, not from a UUID path variable.
            authorizeService.authorizeAction(context, bitstream, Constants.READ);

            // Fire usage event for download statistics
            if (StringUtils.isBlank(request.getHeader("Range"))) {
                eventService.fireEvent(
                    new UsageEvent(
                        UsageEvent.Action.VIEW,
                        request,
                        context,
                        bitstream));
            }

            // Retrieve content metadata
            BitstreamFormat format = bitstream.getFormat(context);
            String mimeType = (format != null) ? format.getMIMEType() : MediaType.APPLICATION_OCTET_STREAM_VALUE;
            String name = StringUtils.isNotBlank(bitstream.getName())
                    ? bitstream.getName() : bitstream.getID().toString();

            response.setContentType(mimeType);
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                    buildContentDisposition(name));
            long size = bitstream.getSizeBytes();
            if (size > 0) {
                response.setHeader(HttpHeaders.CONTENT_LENGTH, String.valueOf(size));
            }

            // Track download in Matomo
            matomoBitstreamTracker.trackBitstreamDownload(context, request, bitstream, false);

            // Check for S3 direct download support
            boolean s3DirectDownload = configurationService
                    .getBooleanProperty("s3.download.direct.enabled");
            boolean s3AssetstoreEnabled = configurationService
                    .getBooleanProperty("assetstore.s3.enabled");
            if (s3DirectDownload && s3AssetstoreEnabled) {
                boolean hasOriginalBundle = bitstream.getBundles().stream()
                        .anyMatch(bundle -> CONTENT_BUNDLE_NAME.equals(bundle.getName()));
                if (hasOriginalBundle) {
                    // Close the DB connection before redirecting
                    context.complete();
                    redirectToS3DownloadUrl(name, bitstream.getInternalId(), response);
                    return;
                }
            }

            if (RequestMethod.HEAD.name().equals(request.getMethod())) {
                // HEAD request — only headers, no body
                context.complete();
                return;
            }

            // Stream the bitstream content. The context must remain open because
            // bitstreamService.retrieve() needs an active DB connection / assetstore session.
            Context downloadContext = null;
            boolean downloadContextCompleted = false;
            try {
                downloadContext = new Context();
                try (InputStream is = bitstreamService.retrieve(downloadContext, bitstream)) {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int bytesRead;
                    while ((bytesRead = is.read(buffer)) != -1) {
                        response.getOutputStream().write(buffer, 0, bytesRead);
                    }
                    response.getOutputStream().flush();
                }
                downloadContext.complete();
                downloadContextCompleted = true;
            } finally {
                if (downloadContext != null && !downloadContextCompleted) {
                    downloadContext.abort();
                }
            }
            // Close DB connection after streaming is complete
            context.complete();
        } catch (AuthorizeException e) {
            log.warn("Unauthorized access to bitstream '{}' for handle '{}'.", filename, handle);
            if (context.getCurrentUser() == null) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED,
                        "You are not authorized to download this file.");
            } else {
                response.sendError(HttpServletResponse.SC_FORBIDDEN,
                        "You are not authorized to download this file.");
            }
        } catch (SQLException e) {
            log.error("Database error while downloading bitstream '{}' for handle '{}': {}",
                    filename, handle, e.getMessage());
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "An internal error occurred.");
        }
    }

    /**
     * Redirect to an S3 presigned URL for direct download.
     *
     * @param bitName       the bitstream filename
     * @param bitInternalId the internal storage ID
     * @param response      the HTTP response to send the redirect on
     */
    private void redirectToS3DownloadUrl(String bitName, String bitInternalId,
                                         HttpServletResponse response) throws IOException {
        try {
            String bucket = configurationService.getProperty("assetstore.s3.bucketName", "");
            if (StringUtils.isBlank(bucket)) {
                throw new InternalServerErrorException("S3 bucket name is not configured");
            }

            String bitstreamPath = s3BitStoreService.getFullKey(bitInternalId);
            if (StringUtils.isBlank(bitstreamPath)) {
                throw new InternalServerErrorException(
                        "Failed to get bitstream path for internal ID: " + bitInternalId);
            }

            int expirationTime = configurationService
                    .getIntProperty("s3.download.direct.expiration", 3600);
            String presignedUrl = s3DirectDownloadService
                    .generatePresignedUrl(bucket, bitstreamPath, expirationTime, bitName);

            if (StringUtils.isBlank(presignedUrl)) {
                throw new InternalServerErrorException(
                        "Failed to generate presigned URL for bitstream: " + bitInternalId);
            }

            response.setStatus(HttpStatus.FOUND.value());
            response.setHeader(HttpHeaders.LOCATION, URI.create(presignedUrl).toString());
        } catch (Exception e) {
            log.error("Error generating S3 presigned URL for bitstream: {}", bitInternalId, e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Error generating download URL.");
        }
    }

    /**
     * Build a Content-Disposition header value using RFC 5987 encoding.
     * Includes both {@code filename} (ASCII fallback) and {@code filename*}
     * (UTF-8 percent-encoded) so that curl -J and browsers can save files
     * with non-ASCII characters in the name correctly.
     *
     * @param name the original filename
     * @return the Content-Disposition header value
     */
    private String buildContentDisposition(String name) {
        // RFC 5987 percent-encoding for filename*
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8)
                .replace("+", "%20");
        // ASCII fallback: replace non-ASCII chars with underscore, escape quotes.
        // Modern clients use filename* (RFC 5987 / RFC 6266) with real UTF-8 name.
        String asciiFallback = name.replaceAll("[^\\x20-\\x7E]", "_")
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
        return String.format("attachment; filename=\"%s\"; filename*=UTF-8''%s",
                asciiFallback, encoded);
    }

    /**
     * Find a bitstream by name in the ORIGINAL bundles of an item.
     * Bitstreams in other bundles (THUMBNAIL, TEXT, LICENSE, etc.) are not returned.
     *
     * @param item     the item to search
     * @param filename the exact filename to match
     * @return the matching Bitstream, or null if not found
     */
    private Bitstream findBitstreamByName(Item item, String filename) {
        List<Bundle> bundles = item.getBundles(CONTENT_BUNDLE_NAME);
        for (Bundle bundle : bundles) {
            for (Bitstream bitstream : bundle.getBitstreams()) {
                if (StringUtils.equals(bitstream.getName(), filename)) {
                    return bitstream;
                }
            }
        }
        return null;
    }
}
