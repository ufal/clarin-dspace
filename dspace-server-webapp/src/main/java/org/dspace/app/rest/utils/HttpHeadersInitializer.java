/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.utils;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Collections;
import java.util.Objects;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.tomcat.util.http.FastHttpDateFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;

/**
 * This class takes data from the Bitstream/File that has to be send. It'll then digest this input and save it in
 * its local variables.
 * When calling {{@link #initialiseHeaders()}}, the input and information will be used to set the proper headers
 * with this info and return an Object of {@link HttpHeaders} to be used in the response that'll be generated
 */
public class HttpHeadersInitializer {

    protected final Logger log = LoggerFactory.getLogger(this.getClass());

    private static final String MULTIPART_BOUNDARY = "MULTIPART_BYTERANGES";
    private static final String CONTENT_TYPE_MULTITYPE_WITH_BOUNDARY = "multipart/byteranges; boundary=" +
        MULTIPART_BOUNDARY;
    public static final String CONTENT_DISPOSITION_INLINE = "inline";
    /**
     * Set on the request when If-Range no longer matches, so that the filter honouring it hides the
     * Range header from the rest of the chain.
     */
    public static final String IGNORE_RANGE = HttpHeadersInitializer.class.getName() + ".ignoreRange";
    public static final String CONTENT_DISPOSITION_ATTACHMENT = "attachment";
    private static final String IF_NONE_MATCH = "If-None-Match";
    private static final String IF_MODIFIED_SINCE = "If-Modified-Since";
    private static final String ETAG = "ETag";
    private static final String IF_MATCH = "If-Match";
    private static final String ANY_ETAG = "*";
    private static final String WEAK_ETAG_PREFIX = "W/";
    private static final String ETAG_QUOTE = "\"";
    private static final String IF_UNMODIFIED_SINCE = "If-Unmodified-Since";
    private static final String CONTENT_TYPE = "Content-Type";
    private static final String ACCEPT_RANGES = "Accept-Ranges";
    private static final String BYTES = "bytes";
    private static final String LAST_MODIFIED = "Last-Modified";
    private static final String EXPIRES = "Expires";
    private static final String APPLICATION_OCTET_STREAM = "application/octet-stream";
    private static final String IMAGE = "image";
    private static final String ACCEPT = "Accept";
    private static final String CONTENT_DISPOSITION = "Content-Disposition";
    private static final String CONTENT_DISPOSITION_FORMAT = "%s;filename=\"%s\"";
    private static final String CACHE_CONTROL = "Cache-Control";

    private int bufferSize = 1000000;

    private static final long DEFAULT_EXPIRE_TIME = 60L * 60L * 1000L;

    //no-cache so request is always performed for logging
    private static final String CACHE_CONTROL_SETTING = "private,no-cache";

    private HttpServletRequest request;
    private HttpServletResponse response;
    private String contentType;
    private String disposition;
    private long lastModified;
    private long length;
    private String fileName;
    private String checksum;

    public HttpHeadersInitializer() {
        //Convert to BufferedInputStream so we can re-read the stream
    }

    public HttpHeadersInitializer with(HttpServletRequest httpRequest) {
        request = httpRequest;
        return this;
    }

    public HttpHeadersInitializer with(HttpServletResponse httpResponse) {
        response = httpResponse;
        return this;
    }

    public HttpHeadersInitializer withLength(long length) {
        this.length = length;
        return this;
    }

    public HttpHeadersInitializer withFileName(String fileName) {
        this.fileName = fileName;
        return this;
    }

    public HttpHeadersInitializer withChecksum(String checksum) {
        this.checksum = checksum;
        return this;
    }

    public HttpHeadersInitializer withMimetype(String mimetype) {
        this.contentType = mimetype;
        return this;
    }

    public HttpHeadersInitializer withLastModified(long lastModified) {
        this.lastModified = lastModified;
        return this;
    }

    public HttpHeadersInitializer withBufferSize(int bufferSize) {
        if (bufferSize > 0) {
            this.bufferSize = bufferSize;
        }
        return this;
    }
    public HttpHeadersInitializer withDisposition(String contentDisposition) {
        this.disposition = contentDisposition;
        return this;
    }

    /**
     * This method will be called to create a {@link HttpHeaders} object which will contain the headers needed
     * to form a proper response when returning the Bitstream/File
     * @return  A {@link HttpHeaders} object containing the information for the Bitstream/File to be sent
     * @throws IOException If something goes wrong
     */
    public HttpHeaders initialiseHeaders() throws IOException {

        HttpHeaders httpHeaders = new HttpHeaders();
        // Validate and process range -------------------------------------------------------------

        log.debug("Content-Type : {}", contentType);
        //TODO response.reset() => Can be re-instated/investigated once we upgrade to Spring 5.2.9, see issue #3056
        // Initialize response.
        response.setBufferSize(bufferSize);
        if (contentType != null) {
            httpHeaders.put(CONTENT_TYPE, Collections.singletonList(contentType));
        }
        httpHeaders.put(ACCEPT_RANGES, Collections.singletonList(BYTES));
        if (checksum != null) {
            httpHeaders.put(ETAG, Collections.singletonList(quotedChecksum()));
        }
        if (Objects.nonNull((Long.valueOf(this.length)))) {
            httpHeaders.put(HttpHeaders.CONTENT_LENGTH, Collections.singletonList(String.valueOf(this.length)));
        }
        httpHeaders.put(LAST_MODIFIED, Collections.singletonList(FastHttpDateFormat.formatDate(lastModified)));
        httpHeaders.put(EXPIRES, Collections.singletonList(expires()));

        //No-cache so that we can log every download
        httpHeaders.put(CACHE_CONTROL, Collections.singletonList(CACHE_CONTROL_SETTING));

        if (isNullOrEmpty(disposition)) {
            if (contentType == null) {
                contentType = APPLICATION_OCTET_STREAM;
            } else if (!contentType.startsWith(IMAGE)) {
                String accept = request.getHeader(ACCEPT);
                disposition = accept != null && accepts(accept,
                                                        contentType) ? CONTENT_DISPOSITION_INLINE :
                    CONTENT_DISPOSITION_ATTACHMENT;
            }

        }

        httpHeaders.put(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                        Collections.singletonList(HttpHeaders.ACCEPT_RANGES));

        // distposition may be null here if contentType is null
        if (!isNullOrEmpty(disposition)) {
            String fallbackAsciiName = createFallbackAsciiName(this.fileName);
            String encodedUtf8Name = createEncodedUtf8Name(this.fileName);

            String headerValue = String.format(
                "%s; filename=\"%s\"; filename*=UTF-8''%s",
                disposition,
                fallbackAsciiName,
                encodedUtf8Name
            );
            httpHeaders.put(CONTENT_DISPOSITION, Collections.singletonList(headerValue));
        }
        log.debug("Content-Disposition : {}", disposition);

        return httpHeaders;

    }

    /**
     * This method will validate whether or not the given Response/Request/Information/Variables are valid.
     * If they're invalid, the Response shouldn't be given.
     * This will do null checks on the response, request, inputstream and filename.
     * Other than this, it'll check Request headers to see if their information is correct.
     * @return
     * @throws IOException
     */
    public boolean isValid() throws IOException {
        if (response == null || request == null) {
            return false;
        }

        if (StringUtils.isEmpty(fileName)) {
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return false;
        }

        // Validate request headers, in the order RFC 9110 section 13.2.2 lays down ----------------

        // If-Match header should contain "*" or ETag. If not, then return 412.
        String ifMatch = request.getHeader(IF_MATCH);
        if (nonNull(ifMatch) && !matchesChecksum(ifMatch, false)) {
            log.debug("If-Match header should contain \"*\" or ETag. If not, then return 412.");
            response.sendError(HttpServletResponse.SC_PRECONDITION_FAILED);
            return false;
        }

        // If-Unmodified-Since header should be greater than LastModified. If not, then return 412.
        long ifUnmodifiedSince = readDateHeader(IF_UNMODIFIED_SINCE);
        if (ifUnmodifiedSince != -1 && ifUnmodifiedSince + 1000 <= lastModified) {
            log.debug("If-Unmodified-Since header should be greater than LastModified. If not, then return 412.");
            response.sendError(HttpServletResponse.SC_PRECONDITION_FAILED);
            return false;
        }

        // If-None-Match header should contain "*" or ETag. If so, then return 304.
        String ifNoneMatch = request.getHeader(IF_NONE_MATCH);
        if (nonNull(ifNoneMatch) && matchesChecksum(ifNoneMatch, true)) {
            log.debug("If-None-Match header should contain \"*\" or ETag. If so, then return 304.");
            sendNotModified();
            return false;
        }

        // If-Modified-Since header should be greater than LastModified. If so, then return 304.
        // This header is ignored if any If-None-Match header is specified.
        long ifModifiedSince = readDateHeader(IF_MODIFIED_SINCE);
        if (isNull(ifNoneMatch) && ifModifiedSince != -1 && ifModifiedSince + 1000 > lastModified) {
            log.debug("If-Modified-Since header should be greater than LastModified. If so, then return 304.");
            sendNotModified();
            return false;
        }

        // A Range only holds while the client is still resuming the same file.
        String ifRange = request.getHeader(HttpHeaders.IF_RANGE);
        if (nonNull(ifRange) && nonNull(request.getHeader(HttpHeaders.RANGE)) && !ifRangeStillHolds(ifRange)) {
            log.debug("If-Range no longer matches, sending the whole file instead of the requested range.");
            request.setAttribute(IGNORE_RANGE, Boolean.TRUE);
        }

        return true;
    }


    private static boolean isNullOrEmpty(String disposition) {
        return StringUtils.isBlank(disposition);
    }


    private static boolean accepts(String acceptHeader, String toAccept) {
        String[] acceptValues = acceptHeader.split("\\s*(,|;)\\s*");
        Arrays.sort(acceptValues);

        return Arrays.binarySearch(acceptValues, toAccept) > -1
            || Arrays.binarySearch(acceptValues, toAccept.replaceAll("/.*$", "/*")) > -1
            || Arrays.binarySearch(acceptValues, "*/*") > -1;
    }

    /**
     * The ETag as it goes out on the wire. RFC 9110 requires the quotes, and that is what a client
     * sends back in If-Match, If-None-Match and If-Range.
     */
    private String quotedChecksum() {
        return ETAG_QUOTE + checksum + ETAG_QUOTE;
    }

    /**
     * Compare an If-Match, If-None-Match or If-Range header against our ETag, as described in
     * RFC 9110 section 8.8.3.2. Missing quotes are tolerated so that clients which drop them keep
     * working.
     *
     * @param matchHeader the header value, which may list several entity tags
     * @param weakAllowed true to also accept a weak tag, which only If-None-Match may do
     */
    private boolean matchesChecksum(String matchHeader, boolean weakAllowed) {
        if (ANY_ETAG.equals(matchHeader.trim())) {
            return true;
        }
        for (String value : matchHeader.split(",")) {
            String entityTag = value.trim();
            if (entityTag.startsWith(WEAK_ETAG_PREFIX)) {
                if (!weakAllowed) {
                    continue;
                }
                entityTag = entityTag.substring(WEAK_ETAG_PREFIX.length());
            }
            if (StringUtils.unwrap(entityTag, ETAG_QUOTE).equals(checksum)) {
                return true;
            }
        }
        return false;
    }

    /**
     * If-Range carries the ETag or the Last-Modified date the client already has, and the range
     * only applies while that still matches (RFC 9110 section 13.1.5).
     */
    private boolean ifRangeStillHolds(String ifRange) {
        long ifRangeDate = FastHttpDateFormat.parseDate(ifRange);
        if (ifRangeDate == -1) {
            return matchesChecksum(ifRange, false);
        }
        // an HTTP date carries whole seconds only, so compare against the date we sent
        return lastModified > 0 && ifRangeDate == lastModified / 1000 * 1000;
    }

    /**
     * Read a date header, treating one we cannot parse as absent. The container answers 400 instead,
     * and RFC 9110 sections 13.1.3 and 13.1.4 both say to ignore it.
     */
    private long readDateHeader(String name) {
        String value = request.getHeader(name);
        return isNull(value) ? -1 : FastHttpDateFormat.parseDate(value);
    }

    private String expires() {
        return FastHttpDateFormat.formatDate(System.currentTimeMillis() + DEFAULT_EXPIRE_TIME);
    }

    /**
     * Answer 304, repeating the header fields the client would have got with the file itself
     * (RFC 9110 section 15.4.5).
     */
    private void sendNotModified() throws IOException {
        if (checksum != null) {
            response.setHeader(ETAG, quotedChecksum());
        }
        response.setHeader(LAST_MODIFIED, FastHttpDateFormat.formatDate(lastModified));
        response.setHeader(CACHE_CONTROL, CACHE_CONTROL_SETTING);
        response.setHeader(EXPIRES, expires());
        response.sendError(HttpServletResponse.SC_NOT_MODIFIED);
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
        try {
            String encoded = URLEncoder.encode(originalFilename, StandardCharsets.UTF_8.toString());
            return encoded.replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            // Fallback to a simple ASCII name if encoding fails.
            log.error("UTF-8 encoding not supported, which should not happen.", e);
            return createFallbackAsciiName(originalFilename);
        }
    }

}
