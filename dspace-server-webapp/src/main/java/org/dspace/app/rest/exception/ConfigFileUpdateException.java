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
 * Exception thrown when updating a configuration file fails
 *
 * @author Milan Majchrak (dspace at dataquest.sk)
 */
@ResponseStatus(value = HttpStatus.INTERNAL_SERVER_ERROR, reason = "Configuration file update failed")
public class ConfigFileUpdateException extends RuntimeException {

    public ConfigFileUpdateException(String message) {
        super(message);
    }

    public ConfigFileUpdateException(String message, Throwable cause) {
        super(message, cause);
    }
}