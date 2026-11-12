/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.xoai.tests.stylesheets;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import net.sf.saxon.jaxp.SaxonTransformerFactory;
import org.apache.commons.io.IOUtils;
import org.dspace.xoai.services.impl.resources.functions.StringXSLFunction;

public abstract class AbstractXSLTest {
    /**
     * The backend URL fn:getProperty('dspace.server.url') answers with. It is deliberately on another host
     * than the repository url of the test fixtures, so a stylesheet that derives the server from the UI
     * url instead of asking the configuration fails its tests.
     */
    protected static final String TEST_SERVER_URL = "https://api.example.org/server";

    private static final Map<String, String> DEFAULT_PROPERTIES = Map.of("dspace.server.url", TEST_SERVER_URL);

    /** what the stub getProperty answers during the current transformation */
    private static volatile Map<String, String> properties = DEFAULT_PROPERTIES;

    // Requires usage of Saxon as OAI-PMH uses some XSLT 2 functions
    private static final TransformerFactory factory = TransformerFactory
            .newInstance("net.sf.saxon.TransformerFactoryImpl", null);

    static {
        // In production DSpaceResourceResolver registers the real extension functions. getProperty reads
        // the DSpace configuration, which a unit test does not start, so it is replaced by a stub.
        ((SaxonTransformerFactory) factory).getProcessor().registerExtensionFunction(new StubGetPropertyFn());
    }

    protected TransformBuilder apply(String xslLocation) throws Exception {
        return new TransformBuilder(xslLocation);
    }

    protected InputStream resource(String location) throws Exception {
        return print(this.getClass().getClassLoader().getResourceAsStream(location));
    }

    private InputStream print(InputStream resourceAsStream) throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        IOUtils.copy(resourceAsStream, outputStream);

//        System.out.println(outputStream.toString());

        return new ByteArrayInputStream(outputStream.toByteArray());
    }

    public static class TransformBuilder {
        private final Transformer transformer;
        private final Map<String, String> overrides = new HashMap<>();

        public TransformBuilder(String xslLocation) throws Exception {
            this.transformer = factory.newTransformer(
                new StreamSource(new File("../dspace/config/crosswalks/oai/metadataFormats", xslLocation)));
        }

        /**
         * Set a configuration property for this transformation only; null leaves it unset.
         */
        public TransformBuilder withProperty(String key, String value) {
            this.overrides.put(key, value);
            return this;
        }

        public String to(InputStream input) throws Exception {
            Map<String, String> merged = new HashMap<>(DEFAULT_PROPERTIES);
            merged.putAll(overrides);
            properties = merged;
            try {
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                this.transformer.transform(new StreamSource(input), new StreamResult(outputStream));
                outputStream.close();
                return outputStream.toString();
            } finally {
                properties = DEFAULT_PROPERTIES;
            }
        }
    }

    /**
     * fn:getProperty for the tests: same name and namespace as GetPropertyFn, answers from
     * {@link #properties} instead of the DSpace configuration.
     */
    private static class StubGetPropertyFn extends StringXSLFunction {
        @Override
        protected String getFnName() {
            return "getProperty";
        }

        @Override
        protected String getStringResult(String param) {
            return properties.get(param);
        }
    }
}
