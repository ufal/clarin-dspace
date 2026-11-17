 /**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest;

import static org.dspace.app.rest.utils.RegexUtils.REGEX_REQUESTMAPPING_IDENTIFIER_AS_UUID;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.text.Normalizer;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.zip.Deflater;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.utils.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;
import org.dspace.app.rest.exception.DSpaceBadRequestException;
import org.dspace.app.rest.exception.UnprocessableEntityException;
import org.dspace.app.rest.model.ItemRest;
import org.dspace.app.rest.utils.ContextUtil;
import org.dspace.authorize.AuthorizationBitstreamUtils;
import org.dspace.authorize.AuthorizeException;
import org.dspace.authorize.service.AuthorizeService;
import org.dspace.content.Bitstream;
import org.dspace.content.Bundle;
import org.dspace.content.DSpaceObject;
import org.dspace.content.Item;
import org.dspace.content.service.BitstreamService;
import org.dspace.core.Context;
import org.dspace.handle.service.HandleService;
import org.dspace.services.ConfigurationService;
import org.dspace.services.EventService;
import org.dspace.services.RequestService;
import org.dspace.usage.UsageEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

 /**
 * This CLARIN Controller download a single file or a ZIP file from the Item's bitstream.
 */
@RestController
@RequestMapping("/api/" + ItemRest.CATEGORY + "/" + ItemRest.PLURAL_NAME + REGEX_REQUESTMAPPING_IDENTIFIER_AS_UUID)
public class MetadataBitstreamController {

    private static Logger log = org.apache.logging.log4j.LogManager.getLogger(MetadataBitstreamController.class);

    @Autowired
    private BitstreamService bitstreamService;

    @Autowired
    private HandleService handleService;
    @Autowired
    private AuthorizeService authorizeService;
    @Autowired
    private ConfigurationService configurationService;
    @Autowired
    AuthorizationBitstreamUtils authorizationBitstreamUtils;
    @Autowired
    private RequestService requestService;
    @Autowired
    EventService eventService;

    /**
     * Download all Item's bitstreams as single ZIP file.
     */
    @PreAuthorize("hasPermission(#uuid, 'ITEM', 'READ')")
    @RequestMapping( method = {RequestMethod.GET, RequestMethod.HEAD}, value = "allzip")
    public void downloadFileZip(@PathVariable UUID uuid, @RequestParam("handleId") String handleId,
                                HttpServletResponse response,
                                HttpServletRequest request) throws IOException, SQLException, AuthorizeException {
        if (StringUtils.isBlank(handleId)) {
            log.error("Handle cannot be null!");
            throw new DSpaceBadRequestException("Handle cannot be null!");
        }
        Context context = ContextUtil.obtainContext(request);
        if (Objects.isNull(context)) {
            log.error("Cannot obtain the context from the request.");
            throw new RuntimeException("Cannot obtain the context from the request.");
        }

        DSpaceObject dso = null;
        String name = "";
        try {
            dso = handleService.resolveToObject(context, handleId);
        } catch (Exception e) {
            log.error("Cannot resolve handle: " + handleId);
            throw new RuntimeException("Cannot resolve handle: " + handleId);
        }

        if (Objects.isNull(dso)) {
            log.error("DSO is null");
            throw new UnprocessableEntityException("Retrieved DSO is null, handle: " + handleId);
        }

        if (!(dso instanceof Item)) {
            log.info("DSO is not instance of Item");
        }

        Item item = (Item) dso;
        name = item.getName() + ".zip";
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, buildContentDisposition(name));
        response.setContentType("application/zip");
        List<Bundle> bundles = item.getBundles("ORIGINAL");

        ZipArchiveOutputStream zip = new ZipArchiveOutputStream(response.getOutputStream());
        zip.setCreateUnicodeExtraFields(ZipArchiveOutputStream.UnicodeExtraFieldPolicy.ALWAYS);
        zip.setLevel(Deflater.NO_COMPRESSION);
        for (Bundle original : bundles) {
            List<Bitstream> bss = original.getBitstreams();
            for (Bitstream bitstream : bss) {
                String filename = bitstream.getName();
                ZipArchiveEntry ze = new ZipArchiveEntry(filename);
                zip.putArchiveEntry(ze);
                // Get content of the bitstream
                // Retrieve method authorize bitstream download action.
                InputStream is = bitstreamService.retrieve(context, bitstream);
                IOUtils.copy(is, zip);
                zip.closeArchiveEntry();
                is.close();
                // Track each downloaded file through the standard DSpace usage-event pipeline
                // (Solr statistics + the vanilla Matomo MatomoEventListener when matomo.enabled=true),
                // instead of a CLARIN-specific tracker.
                eventService.fireEvent(new UsageEvent(UsageEvent.Action.VIEW, request, context, bitstream));
            }
        }
        zip.close();
        response.getOutputStream().flush();
    }

    /**
     * Build the Content-Disposition value the way vanilla's HttpHeadersInitializer does: an ASCII
     * fallback in {@code filename} for clients that predate RFC 5987, plus the real UTF-8 name in
     * {@code filename*} for everyone else. This endpoint has no upstream counterpart, so the logic
     * is copied from vanilla rather than shared, to keep it tracking upstream's behaviour.
     */
    private String buildContentDisposition(String name) {
        return String.format("attachment; filename=\"%s\"; filename*=UTF-8''%s",
                createFallbackAsciiName(name), createEncodedUtf8Name(name));
    }

    /**
     * Creates a safe ASCII-only fallback filename by removing diacritics (accents)
     * and replacing any remaining non-ASCII characters.
     * E.g., "ä-ö-é.pdf" becomes "a-o-e.pdf".
     * @param originalFilename The original filename.
     * @return A string containing only ASCII characters.
     */
    private String createFallbackAsciiName(String originalFilename) {
        if (originalFilename == null) {
            return "";
        }
        String normalized = Normalizer.normalize(originalFilename, Normalizer.Form.NFD);
        String withoutAccents = normalized.replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        // Deviates from vanilla by escaping \ and ": the value is a quoted-string, and an item name
        // containing a quote closes it early. Vanilla still has that bug.
        return withoutAccents.replaceAll("[^\\x00-\\x7F]", "")
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    /**
     * Creates a percent-encoded UTF-8 filename according to RFC 5987.
     * This is for the `filename*` parameter.
     * E.g., "ä ö é.pdf" becomes "%C3%A4%20%C3%B6%20%C3%A9.pdf".
     * @param originalFilename The original filename.
     * @return A percent-encoded string.
     */
    private String createEncodedUtf8Name(String originalFilename) {
        if (originalFilename == null) {
            return "";
        }
        return URLEncoder.encode(originalFilename, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
