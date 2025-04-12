/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.repository;

import org.apache.commons.lang.RandomStringUtils;
import org.springframework.stereotype.Component;

/**
 * Default implementation of RandomStringGenerator using RandomStringUtils.
 */
@Component
public class RandomStringGeneratorImpl implements RandomStringGenerator {

    @Override
    public String generate(int length) {
        return RandomStringUtils.random(length, true, true);
    }
}
