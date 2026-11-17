/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.exception;

import jakarta.ws.rs.NotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Exception thrown when Clarin License Label not found
 *
 * @author Milan Kuchtiak
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class ClarinLicenseLabelNotFoundException extends NotFoundException {

    public ClarinLicenseLabelNotFoundException(String message) {
        super(message);
    }

}
