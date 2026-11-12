/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.oai;

import static com.lyncode.xoai.dataprovider.core.Granularity.Second;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import javax.xml.parsers.DocumentBuilderFactory;

import com.lyncode.xoai.dataprovider.xml.XmlOutputContext;
import com.lyncode.xoai.dataprovider.xml.xoai.Metadata;
import org.apache.commons.lang3.StringUtils;
import org.dspace.app.rest.test.AbstractControllerIntegrationTest;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.ItemBuilder;
import org.dspace.content.Collection;
import org.dspace.content.Community;
import org.dspace.content.Item;
import org.dspace.xoai.util.ItemUtils;
import org.junit.Before;
import org.junit.Test;
import org.xml.sax.InputSource;

/**
 * `ItemUtils.sanitize()` decides what reaches VLO and OLAC, and had no test at all.
 *
 * Two failure modes matter. Escaping the value before the XOAI writer escapes it again produces
 * double-escaped output - a title containing `<b>` reaches a harvester as `&amp;lt;b&amp;gt;`, which is
 * what 7.6.6's `escapeXml10` did. Leaving XML-1.0-illegal characters in place makes the StAX writer
 * throw inside `XOAI.index()`, which catches per item, so the record is silently dropped from the OAI
 * index instead of failing loudly.
 *
 * The assertions run over the real serialisation path used by `DSpaceXOAIItemCacheService.put()`, and
 * re-parse the result rather than only writing it - `U+FFFE` writes without complaint and only blows up
 * on the harvester's parser.
 *
 * @author Milan Majchrak (dspace at dataquest.sk)
 */
public class OAIPMHSanitizeIT extends AbstractControllerIntegrationTest {

    private Collection collection;

    @Before
    public void setupStructure() {
        context.turnOffAuthorisationSystem();
        Community community = CommunityBuilder.createCommunity(context)
                .withName("Sanitize Test Community")
                .build();
        collection = CollectionBuilder.createCollection(context, community)
                .withName("Sanitize Test Collection")
                .build();
        context.restoreAuthSystemState();
    }

    /**
     * The regression 7.6.6 introduced: metacharacters must survive one round trip, not two escapes.
     */
    @Test
    public void metacharactersRoundTripExactlyOnce() throws Exception {
        String title = "Corpus of <b>Czech</b> & \"spoken\" 'texts' > 2000";

        String xml = serialize(buildItem(title));

        // written form is escaped once - if it were escaped twice this would contain `&amp;lt;`
        assertThat(xml, not(org.hamcrest.Matchers.containsString("&amp;lt;")));
        assertThat(xml, not(org.hamcrest.Matchers.containsString("&amp;amp;")));
        // and parsing it back yields the original characters
        assertThat(parsedTitle(xml), is(title));
    }

    /**
     * Control characters and unpaired surrogates are what actually drop records.
     */
    @Test
    public void illegalCharactersAreRemovedInsteadOfDroppingTheRecord() throws Exception {
        // built from explicit code points so the source file carries no raw control bytes.
        // U+0000 is deliberately absent: PostgreSQL cannot store a NUL in a text column, so it never
        // reaches the sanitiser and asserting on it would be testing the persistence layer instead.
        char backspace = (char) 0x08;
        char verticalTab = (char) 0x0B;
        char loneHigh = '\uD800';
        char loneLow = '\uDC00';
        char[] illegal = {backspace, verticalTab, loneHigh, loneLow};

        String title = "corpus with " + backspace + " controls " + verticalTab
                + " and " + loneHigh + " a lone high and " + loneLow + " a lone low surrogate";

        String parsed = parsedTitle(serialize(buildItem(title)));

        assertThat("the record must still carry a title", StringUtils.isNotBlank(parsed), is(true));
        for (char c : illegal) {
            assertThat("illegal code unit " + (int) c + " leaked into the OAI output",
                    parsed.indexOf(c), is(-1));
        }

        // No visible text may be lost. Whitespace is normalised out of the comparison on purpose:
        // DSpace replaces C0 controls with a space on the way into the database, so by the time the
        // value reaches sanitize() the spacing is already not ours to predict - asserting on it would
        // be testing the persistence layer. What is ours is that nothing else disappears.
        String expected = title;
        for (char c : illegal) {
            expected = expected.replace(String.valueOf(c), "");
        }
        assertThat(parsed.replaceAll("\\s+", " ").trim(),
                is(expected.replaceAll("\\s+", " ").trim()));
    }

    /**
     * Supplementary characters are legal and must not be mistaken for unpaired surrogates - CLARIN
     * metadata carries emoji and non-BMP scripts. `U+1FFFE` is legal XML 1.0, only discouraged, and its
     * BMP sibling `U+FFFE` is not - removing the wrong one of the two would be invisible until a
     * harvester re-parses the page.
     */
    @Test
    public void supplementaryCharactersSurviveButNoncharactersInTheBmpDoNot() throws Exception {
        // written as surrogate pairs so the source file stays plain ASCII
        String emoji = "😀";          // U+1F600 grinning face
        String linearB = "𐀀";        // U+10000, the lowest supplementary code point
        String legalNoncharacter = "🿾";  // U+1FFFE - legal XML 1.0, merely discouraged
        char illegalNoncharacter = (char) 0xFFFE;   // U+FFFE - illegal, breaks a re-parse

        String title = "emoji " + emoji + " rare " + linearB + " legal " + legalNoncharacter
                + " illegal " + illegalNoncharacter + " end";

        String parsed = parsedTitle(serialize(buildItem(title)));

        assertTrue("emoji must survive", parsed.contains(emoji));
        assertTrue("valid surrogate pair must survive", parsed.contains(linearB));
        assertTrue("U+1FFFE is legal XML 1.0 and must survive", parsed.contains(legalNoncharacter));
        assertThat("U+FFFE is illegal and would break the harvester's parser",
                parsed.indexOf(illegalNoncharacter), is(-1));
    }

    /**
     * Whatever we emit has to be re-parseable; writing alone proves nothing.
     */
    @Test
    public void outputIsAlwaysWellFormed() throws Exception {
        String title = "]]> " + (char) 0x0C + " " + (char) 0xFFFF + " <![CDATA[ & < > \" '";

        String xml = serialize(buildItem(title));

        DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new InputSource(new java.io.StringReader(xml)));
    }

    private Item buildItem(String title) {
        context.turnOffAuthorisationSystem();
        try {
            return ItemBuilder.createItem(context, collection).withTitle(title).build();
        } finally {
            context.restoreAuthSystemState();
        }
    }

    /**
     * Exactly what DSpaceXOAIItemCacheService.put() does.
     */
    private String serialize(Item item) throws Exception {
        Metadata metadata = ItemUtils.retrieveMetadata(context, item);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        XmlOutputContext outputContext = XmlOutputContext.emptyContext(output, Second);
        metadata.write(outputContext);
        outputContext.getWriter().flush();
        outputContext.getWriter().close();
        return output.toString(StandardCharsets.UTF_8);
    }

    /**
     * Pull dc.title back out of the serialised document by parsing it, so the assertions are about what
     * a harvester sees rather than about our own in-memory objects.
     */
    private String parsedTitle(String xml) throws Exception {
        org.w3c.dom.Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new InputSource(new java.io.StringReader(xml)));
        // <element name="dc"><element name="title"><element name="none"><field name="value">…
        org.w3c.dom.NodeList elements = doc.getElementsByTagName("element");
        for (int i = 0; i < elements.getLength(); i++) {
            org.w3c.dom.Element element = (org.w3c.dom.Element) elements.item(i);
            if (!"title".equals(element.getAttribute("name"))) {
                continue;
            }
            org.w3c.dom.NodeList fields = element.getElementsByTagName("field");
            for (int j = 0; j < fields.getLength(); j++) {
                org.w3c.dom.Element field = (org.w3c.dom.Element) fields.item(j);
                if ("value".equals(field.getAttribute("name"))) {
                    return field.getTextContent();
                }
            }
        }
        throw new AssertionError("no dc.title in the serialised XOAI document:\n" + xml);
    }
}
