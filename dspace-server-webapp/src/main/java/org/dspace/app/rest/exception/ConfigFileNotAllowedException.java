/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Exception thrown when a configuration file is not allowed for API access
 *
 * @author Milan Majchrak (dspace at dataquest.sk)
 */
@ResponseStatus(value = HttpStatus.FORBIDDEN, reason = "Configuration file access not allowed")
public class ConfigFileNotAllowedException extends RuntimeException {

    public ConfigFileNotAllowedException(String message) {
        super(message);
    }

    public ConfigFileNotAllowedException(String message, Throwable cause) {
        super(message, cause);
    }
}