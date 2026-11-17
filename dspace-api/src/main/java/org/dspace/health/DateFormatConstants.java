/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.health;

import java.time.format.DateTimeFormatter;

/**
 * Constants class for date format patterns used across health check and report diff functionality.
 *
 * @author Michaela Stefancova (dspace at dataquest.sk)
 */
public final class DateFormatConstants {

    private DateFormatConstants() {
    }

    /**
     * Standard date format pattern: yyyy-MM-dd
     */
    public static final String DATE_FORMAT = "yyyy-MM-dd";

    /**
     * Standard datetime format pattern: yyyy-MM-dd HH:mm:ss
     */
    public static final String DATETIME_FORMAT = "yyyy-MM-dd HH:mm:ss";

    /**
     * Extended datetime format pattern: yyyy-MM-dd HH:mm:ss.SSS
     */
    public static final String DATETIME_WITH_MILLIS_FORMAT = "yyyy-MM-dd HH:mm:ss.SSS";

    /**
     * DateTimeFormatter for simple date format (yyyy-MM-dd).
     */
    public static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern(DATE_FORMAT);

    /**
     * DateTimeFormatter for datetime format (yyyy-MM-dd HH:mm:ss).
     */
    public static final DateTimeFormatter DATETIME_FORMATTER =
            DateTimeFormatter.ofPattern(DATETIME_FORMAT);

    /**
     * DateTimeFormatter for extended datetime format with milliseconds.
     */
    public static final DateTimeFormatter DATETIME_WITH_MILLIS_FORMATTER =
            DateTimeFormatter.ofPattern(DATETIME_WITH_MILLIS_FORMAT);
}