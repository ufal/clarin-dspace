/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.xoai.services.impl.resources;

import javax.xml.transform.TransformerFactory;

import net.sf.saxon.jaxp.SaxonTransformerFactory;
import net.sf.saxon.s9api.Processor;

/**
 * Utility class to provide a shared Saxon Processor and TransformerFactory instance.
 *
 * This class maintains a singleton Processor and SaxonTransformerFactory to avoid
 * configuration incompatibility issues between different Saxon processors.
 *
 * Initialization must be done once by calling {@link #initialize(TransformerFactory)}.
 * The class is thread-safe: the shared instances are lazily initialized with synchronization.
 *
 * @author Michaela Stefancova (dspace at dataquest.sk)
 */
public final class SharedSaxonProcessor {
    private SharedSaxonProcessor() {
        // utility class
    }

    private static volatile SaxonTransformerFactory saxonTransformerFactory;
    private static volatile Processor sharedProcessor;

    /**
     * Initialize the shared processor with the given TransformerFactory.
     * Must be called once before accessing any shared processor or builder.
     *
     * Synchronized to prevent race conditions if multiple threads attempt initialization.
     *
     * @param transformerFactory the Saxon TransformerFactory to use
     * @throws IllegalArgumentException if transformerFactory is null or not a SaxonTransformerFactory
     */
    public static synchronized void initialize(TransformerFactory transformerFactory) {
        if (saxonTransformerFactory == null) {
            if (transformerFactory == null) {
                throw new IllegalArgumentException("TransformerFactory cannot be null");
            }
            if (!(transformerFactory instanceof SaxonTransformerFactory)) {
                throw new IllegalArgumentException("TransformerFactory must be an instance of SaxonTransformerFactory");
            }
            saxonTransformerFactory = (SaxonTransformerFactory) transformerFactory;
            sharedProcessor = saxonTransformerFactory.getProcessor();
        }
    }

    /**
     * Get the shared Saxon Processor instance.
     *
     * @return the shared Processor
     * @throws IllegalStateException if initialize() has not been called yet
     */
    public static Processor getProcessor() {
        if (sharedProcessor == null) {
            throw new IllegalStateException("SharedSaxonProcessor has not been initialized. Call initialize() first.");
        }
        return sharedProcessor;
    }

    /**
     * Get the shared Saxon TransformerFactory instance.
     *
     * @return the shared SaxonTransformerFactory
     * @throws IllegalStateException if initialize() has not been called yet
     */
    public static SaxonTransformerFactory getTransformerFactory() {
        if (saxonTransformerFactory == null) {
            throw new IllegalStateException("SharedSaxonProcessor has not been initialized. Call initialize() first.");
        }
        return saxonTransformerFactory;
    }
}
