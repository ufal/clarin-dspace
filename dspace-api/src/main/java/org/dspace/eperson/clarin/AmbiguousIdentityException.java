/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.eperson.clarin;

/**
 * Thrown by {@link org.dspace.eperson.service.clarin.ClarinIdentityService#autoLink} when the
 * identities released by a proxy match more than one existing EPerson. Picking one of the
 * candidates would be a guess, and silently creating yet another account would deepen the
 * duplication — the existing accounts need an admin merge/link first, so callers are expected
 * to block auto-registration for the current login when they catch this.
 *
 * @author Ondrej Kosarko
 */
public class AmbiguousIdentityException extends RuntimeException {

    public AmbiguousIdentityException(String message) {
        super(message);
    }
}
