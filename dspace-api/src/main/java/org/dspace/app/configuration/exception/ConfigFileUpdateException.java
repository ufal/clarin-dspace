/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.configuration.exception;

/**
 * Exception thrown when a configuration file update operation fails
 *
 * @author Milan Majchrak (dspace at dataquest.sk)
 */
public class ConfigFileUpdateException extends Exception {

    public ConfigFileUpdateException(String message) {
        super(message);
    }

    public ConfigFileUpdateException(String message, Throwable cause) {
        super(message, cause);
    }
}