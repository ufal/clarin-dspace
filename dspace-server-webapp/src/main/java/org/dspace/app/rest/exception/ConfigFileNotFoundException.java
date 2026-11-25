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
 * Exception thrown when a configuration file is not found
 *
 * @author Milan Majchrak (dspace at dataquest.sk)
 */
@ResponseStatus(value = HttpStatus.NOT_FOUND, reason = "Configuration file not found")
public class ConfigFileNotFoundException extends RuntimeException {

    public ConfigFileNotFoundException(String message) {
        super(message);
    }

    public ConfigFileNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}