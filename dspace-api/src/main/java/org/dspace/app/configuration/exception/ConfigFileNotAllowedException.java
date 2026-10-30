/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.configuration.exception;

/**
 * Exception thrown when access to a configuration file is not allowed
 *
 * @author Milan Majchrak (dspace at dataquest.sk)
 */
public class ConfigFileNotAllowedException extends Exception {

    public ConfigFileNotAllowedException(String message) {
        super(message);
    }

    public ConfigFileNotAllowedException(String message, Throwable cause) {
        super(message, cause);
    }
}