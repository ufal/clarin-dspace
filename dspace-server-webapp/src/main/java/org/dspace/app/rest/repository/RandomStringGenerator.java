/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.repository;

/**
 * Interface for generating random strings.
 */
public interface RandomStringGenerator {
    /**
     * Generate a random string of the specified length.
     *
     * @param length the length of the random string
     * @return the generated random string
     */
    String generate(int length);
}
