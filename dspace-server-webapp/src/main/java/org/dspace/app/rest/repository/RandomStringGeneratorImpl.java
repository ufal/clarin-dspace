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
