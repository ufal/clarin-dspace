/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.xoai.tests.stylesheets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Source;
import javax.xml.transform.Templates;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;

import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.dspace.utils.SpecialItemService;
import org.dspace.xoai.services.impl.resources.DSpaceResourceResolver;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

/**
 * Validates generated CMDI dates against both supported profiles.
 *
 * @author Arnis Voitkans
 */
public class LindatCMDIXslTest {
    private static final String CMD = "http://www.clarin.eu/cmd/";
    private static final String XOAI = "http://www.lyncode.com/xoai";
    private static Schema newProfileSchema;
    private static Schema oldProfileSchema;
    private MockedStatic<DSpaceServicesFactory> services;
    private MockedStatic<SpecialItemService> items;
    private Templates templates;
    private Document uploadedMetadata;

    /** Loads profile schemas without external schema access. */
    @BeforeClass
    public static void loadSchemas() throws Exception {
        SchemaFactory factory = SchemaFactory.newDefaultInstance();
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        newProfileSchema = loadSchema(factory, "p_1403526079380.xsd");
        oldProfileSchema = loadSchema(factory, "p_1349361150622.xsd");
    }

    private static Schema loadSchema(SchemaFactory factory, String profile) throws Exception {
        // Load the imported namespace first so validation never needs the registry or W3C host.
        return factory.newSchema(new Source[] {
            new StreamSource(LindatCMDIXslTest.class.getResource("/cmdi/xml.xsd").toExternalForm()),
            new StreamSource(LindatCMDIXslTest.class.getResource("/cmdi/" + profile).toExternalForm())
        });
    }

    /** Uses the production crosswalk with synthetic repository inputs. */
    @Before
    public void setUp() throws Exception {
        ConfigurationService configuration = mock(ConfigurationService.class);
        when(configuration.getProperty("oai.config.dir"))
            .thenReturn("../dspace/config/crosswalks/oai");
        when(configuration.getProperty("dspace.server.url")).thenReturn("https://repository.example/server");
        DSpaceServicesFactory factory = mock(DSpaceServicesFactory.class);
        when(factory.getConfigurationService()).thenReturn(configuration);
        services = mockStatic(DSpaceServicesFactory.class);
        services.when(DSpaceServicesFactory::getInstance).thenReturn(factory);
        // Only uploaded metadata needs a repository; retain the real XML extension functions.
        items = mockStatic(SpecialItemService.class, invocation -> {
            if ("getUploadedMetadata".equals(invocation.getMethod().getName())) {
                return uploadedMetadata;
            }
            return CALLS_REAL_METHODS.answer(invocation);
        });
        templates = new DSpaceResourceResolver().getTemplates("metadataFormats/lindat_cmdi.xsl");
    }

    /** Releases static mocks between tests. */
    @After
    public void tearDown() {
        if (items != null) {
            items.close();
        }
        if (services != null) {
            services.close();
        }
    }

    /** Covers SQL timestamp precision variants. */
    @Test
    public void sqlTimestampsProduceSchemaDates() throws Exception {
        assertGeneratedDate(Timestamp.valueOf("2026-09-30 20:17:11.508").toString(), "2026-09-30");
        assertGeneratedDate("2026-09-30 20:17:11.508123456", "2026-09-30");
        assertGeneratedDate("2026-09-30 20:17:11", "2026-09-30");
    }

    /** Retains the calendar date expressed in ISO timestamps. */
    @Test
    public void isoTimestampsProduceSchemaDates() throws Exception {
        assertGeneratedDate("2026-09-30T20:17:11.508Z", "2026-09-30");
        assertGeneratedDate("2026-09-30T00:17:11+03:00", "2026-09-30");
    }

    /** Covers actual Java date serialization for every month. */
    @Test
    public void javaDatesProduceSchemaDates() throws Exception {
        for (int month = 1; month <= 12; month++) {
            LocalDate day = LocalDate.of(2026, month, 1);
            Date modified = Date.from(day.atStartOfDay(ZoneId.systemDefault()).toInstant());
            assertGeneratedDate(modified.toString(), day.toString());
        }
    }

    /** Prevents incomplete or unrecognized Java dates from becoming valid CMDI dates. */
    @Test
    public void malformedJavaDatesDoNotBecomeSchemaDates() throws Exception {
        String[] timestamps = {
            "Xyz Abc 12 ",
            "Xyz Sep 30 20:17:11 UTC 2026",
            "Wed Abc 30 20:17:11 UTC 2026",
            "Wed Sep 30 2026",
            "Wed Sep 30 20:17:11 2026",
            "Wed Sep 30 invalid UTC 2026",
            "Wed Sep 30 20:17:11 UTC 2026 trailing 2026"
        };
        for (String timestamp : timestamps) {
            for (boolean newProfile : new boolean[] {true, false}) {
                Document result = transform(timestamp, newProfile);
                Schema schema = newProfile ? newProfileSchema : oldProfileSchema;
                assertThrows(timestamp, SAXException.class,
                             () -> schema.newValidator().validate(new DOMSource(result)));
            }
        }
    }

    /** Guards against dropping date-only input when no timestamp separator exists. */
    @Test
    public void dateOnlyDoesNotBecomeEmpty() throws Exception {
        assertGeneratedDate("2024-02-29", "2024-02-29");
    }

    /** Preserves the complete uploaded CMDI document. */
    @Test
    public void uploadedMetadataIsNotRewritten() throws Exception {
        uploadedMetadata = transform("2024-02-29", true);
        Document result = transform("2026-09-30 20:17:11.508", true);
        assertTrue(uploadedMetadata.getDocumentElement().isEqualNode(result.getDocumentElement()));
        assertEquals("2024-02-29", text(result, "MdCreationDate"));
    }

    private void assertGeneratedDate(String timestamp, String expected) throws Exception {
        for (boolean newProfile : new boolean[] {true, false}) {
            Document result = transform(timestamp, newProfile);
            assertEquals(newProfile ? "clarin.eu:cr1:p_1403526079380" : "clarin.eu:cr1:p_1349361150622",
                         text(result, "MdProfile"));
            String date = text(result, "MdCreationDate");
            assertEquals(expected, date);
            Schema schema = newProfile ? newProfileSchema : oldProfileSchema;
            schema.newValidator().validate(new DOMSource(result));
        }
    }

    private Document transform(String timestamp, boolean newProfile) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document input;
        try (InputStream stream = getClass().getResourceAsStream("/cmdi-item.xml")) {
            input = factory.newDocumentBuilder().parse(stream);
        }
        for (int i = 0; i < input.getElementsByTagNameNS(XOAI, "field").getLength(); i++) {
            Node field = input.getElementsByTagNameNS(XOAI, "field").item(i);
            if ("lastModifyDate".equals(field.getAttributes().getNamedItem("name").getNodeValue())) {
                field.setTextContent(timestamp);
            }
        }
        if (!newProfile) {
            for (int i = 0; i < input.getElementsByTagNameNS(XOAI, "element").getLength(); i++) {
                Node element = input.getElementsByTagNameNS(XOAI, "element").item(i);
                if ("local".equals(element.getAttributes().getNamedItem("name").getNodeValue())) {
                    element.getParentNode().removeChild(element);
                    break;
                }
            }
        }
        DOMResult result = new DOMResult();
        templates.newTransformer().transform(new DOMSource(input), result);
        return (Document) result.getNode();
    }

    private String text(Document document, String localName) {
        assertEquals(1, document.getElementsByTagNameNS(CMD, localName).getLength());
        return document.getElementsByTagNameNS(CMD, localName).item(0).getTextContent();
    }
}
