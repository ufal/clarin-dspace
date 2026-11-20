/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.util;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Builds a `Content-Disposition` header value from a bitstream name.
 *
 * The fork had two independent implementations of this, one of them wrong: it escaped `"` but not `\`,
 * so a bitstream named `evil\` terminated the quoted string early and swallowed the `filename*`
 * parameter; and it used {@link URLEncoder} directly, which encodes a space as `+`. RFC 8187 treats
 * `+` as a literal character, so `my report.pdf` arrived as `my+report.pdf`.
 *
 * @author Milan Majchrak (dspace at dataquest.sk)
 */
public final class ContentDispositionUtils {

    public static final String ATTACHMENT = "attachment";
    public static final String INLINE = "inline";

    private ContentDispositionUtils() {
    }

    /**
     * Build a `Content-Disposition` value carrying both the RFC 6266 ASCII fallback and the RFC 8187
     * percent-encoded UTF-8 name.
     *
     * @param disposition `attachment` or `inline`
     * @param name        the bitstream name; must not be null
     * @return the header value
     */
    public static String build(String disposition, String name) {
        if (name == null) {
            throw new IllegalArgumentException("Bitstream name cannot be null");
        }

        // RFC 8187 percent-encoding for filename*. URLEncoder is form-encoding, so `+` has to be
        // converted back to `%20` - a literal `+` in a filename is already encoded as `%2B` by then.
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8)
                .replace("+", "%20");

        // ASCII fallback for clients that ignore filename*. Non-ASCII becomes `_`; backslash and quote
        // are escaped, in that order, so the quoted-string cannot be terminated early.
        String asciiFallback = name.replaceAll("[^\\x20-\\x7E]", "_")
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");

        return String.format("%s; filename=\"%s\"; filename*=UTF-8''%s", disposition, asciiFallback, encoded);
    }
}
