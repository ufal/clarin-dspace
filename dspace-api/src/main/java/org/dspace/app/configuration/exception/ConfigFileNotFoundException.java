/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.configuration.exception;

/**
 * Exception thrown when a requested configuration file is not found
 *
 * @author Milan Majchrak (dspace at dataquest.sk)
 */
public class ConfigFileNotFoundException extends Exception {

    public ConfigFileNotFoundException(String message) {
        super(message);
    }

    public ConfigFileNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}