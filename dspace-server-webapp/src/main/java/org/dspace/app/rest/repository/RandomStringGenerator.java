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
