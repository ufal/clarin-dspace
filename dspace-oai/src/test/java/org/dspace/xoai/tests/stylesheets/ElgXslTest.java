/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.xoai.tests.stylesheets;

import static org.dspace.xoai.tests.support.XmlMatcherBuilder.xml;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsEqual.equalTo;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.apache.commons.io.IOUtils;
import org.dspace.xoai.tests.support.XmlMatcherBuilder;
import org.junit.Test;

/**
 * Tests for the ELG (European Language Grid, ELG-SHARE) OAI-PMH crosswalk.
 *
 * <p>Each group covers one cause of the records the ELG harvest of LINDAT rejected. The values
 * are the ones found in the rejected records, so the crosswalk has to cope with the stored data
 * as it is: it is not cleaned up before the next harvest.</p>
 *
 * @see <a href="https://gitlab.com/european-language-grid/platform/ELG-SHARE-schema">ELG-SHARE schema</a>
 */
public class ElgXslTest extends AbstractXSLTest {

    private static final String XSL = "elg.xsl";
    private static final String MS_NS = "http://w3id.org/meta-share/meta-share/";
    private static final String XML_NS = "http://www.w3.org/XML/1998/namespace";
    private static final String CORPUS = "xoai-elg-corpus-test.xml";
    private static final String TOOL = "xoai-elg-tool-test.xml";

    /** ELG-SHARE: ms:description is a langString restricted to maxLength 10000. */
    private static final int DESCRIPTION_MAX_LENGTH = 10000;
    private static final String ELLIPSIS = "\u2026";

    private static final String DESCRIPTION = "A short description of the test corpus.";
    private static final String SPONSOR = "euFunds;123456;Example Funder;Example Project;";
    private static final String SIZE = "1000;tokens";
    private static final String LANGUAGE = "<field name=\"value\">ces</field>";
    private static final String TOOL_LANGUAGE = "<field name=\"value\">lat</field>";

    // ---------------------------------------------------------------- description (10,000 chars)

    @Test
    public void descriptionAtTheLimitIsKept() throws Exception {
        String description = "a".repeat(DESCRIPTION_MAX_LENGTH);
        String result = apply(XSL).to(withReplaced(CORPUS, DESCRIPTION, description));
        assertThat(result, is(elg().withXPath("//ms:description", equalTo(description))));
    }

    @Test
    public void descriptionOverTheLimitIsTruncatedWithEllipsis() throws Exception {
        String description = "a".repeat(10_400) + "END";
        String result = apply(XSL).to(withReplaced(CORPUS, DESCRIPTION, description));
        assertThat(result, is(elg().withXPath("string-length(//ms:description)",
            equalTo(String.valueOf(DESCRIPTION_MAX_LENGTH)))));
        assertThat(result, is(elg().withXPath("//ms:description",
            equalTo("a".repeat(DESCRIPTION_MAX_LENGTH - 1) + ELLIPSIS))));
    }

    // ---------------------------------------------------------------- fundingType

    @Test
    public void storedFundingTypesMapToElgChoices() throws Exception {
        assertFundingType("euFunds", "euFunds");
        assertFundingType("nationalFunds", "nationalFunds");
        assertFundingType("ownFunds", "ownFunds");
        assertFundingType("Other", "other");
    }

    /** submission-forms metashare_funding displayed values, stored by mistake. */
    @Test
    public void displayedFundingTypesMapToElgChoices() throws Exception {
        assertFundingType("EU", "euFunds");
        assertFundingType("National", "nationalFunds");
        assertFundingType("Own funds", "ownFunds");
        assertFundingType("other", "other");
    }

    /** 11234/1-6120 stores the four components without a trailing ";". */
    @Test
    public void fundingWithoutTrailingSeparatorIsMapped() throws Exception {
        String result = apply(XSL).to(withReplaced(CORPUS, SPONSOR, "National;2;Funder;Project"));
        assertThat(result, is(elg().withXPath("//ms:fundingProject/ms:fundingType",
            equalTo(MS_NS + "nationalFunds"))));
    }

    /** N/A or an unknown type has no ELG choice; ELG requires a project name. */
    @Test
    public void fundingWithoutElgTypeOrProjectNameIsLeftOut() throws Exception {
        for (String sponsor : new String[] {"N/A;1;Funder;Project;", "N/A;-;-;-;",
            "privateFunds;1;Funder;Project;", ";;Funder;;", "euFunds;1;Funder;-;", "euFunds;1;Funder;;",
            "Other;-;-;-;"}) {
            String result = apply(XSL).to(withReplaced(CORPUS, SPONSOR, sponsor));
            assertThat(sponsor, result, is(elg().withXPath("count(//ms:fundingProject)", equalTo("0"))));
        }
    }

    /** 11234/1-1679: a project named n/a still carries a real funder and grant. */
    @Test
    public void projectNamedNotApplicableIsKept() throws Exception {
        String result = apply(XSL).to(withReplaced(CORPUS, SPONSOR,
            "Other;G-42;Example Funder;n/a;"));
        assertThat(result, is(elg().withXPath("//ms:fundingProject/ms:grantNumber", equalTo("G-42"))));
        assertThat(result, is(elg().withXPath("//ms:funder/ms:Organization/ms:organizationName",
            equalTo("Example Funder"))));
    }

    @Test
    public void placeholderGrantNumberIsLeftOut() throws Exception {
        for (String grant : new String[] {"-", "N/A", ""}) {
            String result = apply(XSL).to(withReplaced(CORPUS, SPONSOR, "nationalFunds;" + grant + ";Funder;Project;"));
            assertThat(grant, result, is(elg().withXPath("count(//ms:fundingProject)", equalTo("1"))));
            assertThat(grant, result, is(elg().withXPath("//ms:fundingProject/ms:fundingType",
                equalTo(MS_NS + "nationalFunds"))));
            assertThat(grant, result, is(elg().withXPath("count(//ms:grantNumber)", equalTo("0"))));
        }
    }

    @Test
    public void placeholderFunderIsLeftOut() throws Exception {
        for (String funder : new String[] {"-", "N/A", ""}) {
            String result = apply(XSL).to(withReplaced(CORPUS, SPONSOR, "euFunds;1;" + funder + ";Project;"));
            assertThat(funder, result, is(elg().withXPath("//ms:fundingProject/ms:projectName", equalTo("Project"))));
            assertThat(funder, result, is(elg().withXPath("count(//ms:funder)", equalTo("0"))));
        }
    }

    // ---------------------------------------------------------------- size amount (xs:float)

    @Test
    public void plainAmountsAreKept() throws Exception {
        assertAmount("1000;tokens", "1000");
        assertAmount("12.5;hours", "12.5");
        // live record: ELG accepts it today, xs:float collapses the whitespace
        assertAmount("48 ;scenarios", "48");
        // other xs:float forms ELG accepted before
        assertAmount("+1.5E3;files", "+1.5E3");
    }

    /** A size cannot be negative: the value is a typo, the file size is used instead. */
    @Test
    public void negativeAmountIsLeftOut() throws Exception {
        assertAmount("-5;files", "2048");
    }

    @Test
    public void thousandsSeparatorsAreRemoved() throws Exception {
        assertAmount("30,000,000;sentences", "30000000");
        assertAmount("5 490 000 000;words", "5490000000");
        assertAmount("19,376.84;hours", "19376.84");
    }

    @Test
    public void amountThatIsNotANumberIsLeftOut() throws Exception {
        String result = apply(XSL).to(withReplaced(CORPUS, SIZE,
            "122.62 KB;entries</field><field name=\"value\">500;files"));
        assertThat(result, is(elg().withXPath("count(//ms:size)", equalTo("1"))));
        assertThat(result, is(elg().withXPath("//ms:size/ms:amount", equalTo("500"))));
    }

    /** "1,5" may be a decimal comma or a typo; the crosswalk must not guess. */
    @Test
    public void ambiguousCommaIsNotGuessed() throws Exception {
        String result = apply(XSL).to(withReplaced(CORPUS, SIZE,
            "1,5;hours</field><field name=\"value\">500;files"));
        assertThat(result, is(elg().withXPath("count(//ms:size)", equalTo("1"))));
        assertThat(result, is(elg().withXPath("//ms:size/ms:amount", equalTo("500"))));
    }

    @Test
    public void withoutAnyValidAmountTheFileSizeIsUsed() throws Exception {
        String result = apply(XSL).to(withReplaced(CORPUS, SIZE, "122.62 KB;entries"));
        assertThat(result, is(elg().withXPath("count(//ms:size)", equalTo("1"))));
        assertThat(result, is(elg().withXPath("//ms:size/ms:amount", equalTo("2048"))));
        assertThat(result, is(elg().withXPath("//ms:size/ms:sizeUnit/ms:sizeUnitRecommended",
            equalTo(MS_NS + "byte"))));
    }

    // ---------------------------------------------------------------- languageId

    /** A code with an ISO 639-1 form that the crosswalk did not convert (nbl from 11234/1-6130). */
    @Test
    public void codeWithATwoLetterFormIsConverted() throws Exception {
        assertLanguageIds("nbl", "nr");
        // retired Moldavian, merged into Romanian; ELG has no "mo"
        assertLanguageIds("mol", "ro");
    }

    /** its is Isekiri, not Italian; ISO 639-3 has no two-letter form for it. */
    @Test
    public void codeWithoutTwoLetterFormIsKept() throws Exception {
        assertLanguageIds("its", "its");
        assertLanguageIds("grc", "grc");
    }

    /** "English; Czech" from 11234/1-6153: several languages typed into one value. */
    @Test
    public void severalCodesInOneValueAreSplit() throws Exception {
        String result = apply(XSL).to(withReplaced(CORPUS, LANGUAGE, "<field name=\"value\">eng; ces</field>"));
        assertLanguageIdsInOrder(result, "en", "cs");
        assertThat(result, is(elg().withXPath("//ms:lingualityType", equalTo(MS_NS + "bilingual"))));
    }

    /** Language names stored in dc.language.iso: "English; Czech" from 11234/1-6153, Czech from 11234/1-6232. */
    @Test
    public void languageNamesAreSentAsUncodedLanguages() throws Exception {
        String result = apply(XSL).to(withReplaced(CORPUS, LANGUAGE,
            "<field name=\"value\">English; Czech</field>"));
        assertThat(result, is(elg().withXPath("count(//ms:language)", equalTo("2"))));
        assertThat(result, is(elg().withXPath("(//ms:language)[1]/ms:languageVarietyName", equalTo("English"))));
        assertThat(result, is(elg().withXPath("(//ms:language)[2]/ms:languageVarietyName", equalTo("Czech"))));
        assertThat(result, is(elg().withXPath("count(//ms:language[ms:languageId != 'und'])", equalTo("0"))));
        // ELG validates the tag too, it must never keep the raw value
        assertThat(result, is(elg().withXPath("count(//ms:language[ms:languageTag != ms:languageId])",
            equalTo("0"))));
        assertThat(result, is(elg().withXPath("count(//ms:languageVarietyName[not(@xml:lang = 'en')])",
            equalTo("0"))));
        assertThat(result, is(elg().withXPath("//ms:lingualityType", equalTo(MS_NS + "bilingual"))));
    }

    /** dc.language.iso und with the names in dc.language: one uncoded language per name, as before. */
    @Test
    public void undeterminedCodeTakesTheNamesFromDcLanguage() throws Exception {
        String result = apply(XSL).to(withReplaced(CORPUS, LANGUAGE, "<field name=\"value\">und</field>"
            + "</element></element>"
            + "<element name=\"none\"><field name=\"value\">Variety A</field><field name=\"value\">Variety B</field>"
            + "</element><element name=\"iso\"><element name=\"none\">"));
        assertLanguageIdsInOrder(result, "und", "und");
        assertThat(result, is(elg().withXPath("(//ms:language)[1]/ms:languageVarietyName", equalTo("Variety A"))));
        assertThat(result, is(elg().withXPath("(//ms:language)[2]/ms:languageVarietyName", equalTo("Variety B"))));
    }

    @Test
    public void repeatedLanguagesAreSentOnce() throws Exception {
        String result = apply(XSL).to(withReplaced(CORPUS, LANGUAGE, "<field name=\"value\">ces; ces</field>"));
        assertLanguageIdsInOrder(result, "cs");
        assertThat(result, is(elg().withXPath("//ms:lingualityType", equalTo(MS_NS + "monolingual"))));
    }

    @Test
    public void undeterminedAndUncodedListTheNamesOnce() throws Exception {
        String result = apply(XSL).to(withReplaced(CORPUS, LANGUAGE, "<field name=\"value\">und; mis</field>"
            + "</element></element>"
            + "<element name=\"none\"><field name=\"value\">Variety A</field>"
            + "</element><element name=\"iso\"><element name=\"none\">"));
        assertLanguageIdsInOrder(result, "und");
    }

    /** tok (Toki Pona, 11234/1-6251) entered ISO 639-3 after the ELG vocabulary was frozen. */
    @Test
    public void codeUnknownToElgIsSentAsUncodedLanguage() throws Exception {
        String result = apply(XSL).to(withReplaced(CORPUS, LANGUAGE,
            "<field name=\"value\">tok</field><field name=\"value\">ces</field>"));
        assertLanguageIdsInOrder(result, "und", "cs");
        assertThat(result, is(elg().withXPath("(//ms:language)[1]/ms:languageVarietyName", equalTo("Toki Pona"))));
    }

    // ---------------------------------------------------------------- tool without language

    @Test
    public void languageDependentToolKeepsItsLanguages() throws Exception {
        String result = apply(XSL).to(resource(TOOL));
        assertThat(result, is(elg().withXPath("//ms:languageDependent", equalTo("true"))));
        assertThat(result, is(elg().withXPath("//ms:inputContentResource/ms:language/ms:languageId",
            equalTo("la"))));
        assertThat(result, is(elg().withXPath("//ms:outputResource/ms:language/ms:languageId",
            equalTo("la"))));
    }

    /** 11234/1-6024 and 11372/LRT-6050: language dependent, but no dc.language.iso. */
    @Test
    public void toolWithoutLanguageIsNotLanguageDependent() throws Exception {
        String result = apply(XSL).to(withReplaced(TOOL, TOOL_LANGUAGE, ""));
        assertThat(result, is(elg().withXPath("//ms:languageDependent", equalTo("false"))));
        assertThat(result, is(elg().withXPath("count(//ms:inputContentResource/ms:language)", equalTo("0"))));
        assertThat(result, is(elg().withXPath("count(//ms:outputResource)", equalTo("0"))));
    }

    // ---------------------------------------------------------------- helpers

    private void assertFundingType(String stored, String elgChoice) throws Exception {
        String result = apply(XSL).to(withReplaced(CORPUS, SPONSOR, stored + ";1;Funder;Project;"));
        assertThat(stored, result, is(elg().withXPath("//ms:fundingProject/ms:fundingType",
            equalTo(MS_NS + elgChoice))));
    }

    private void assertAmount(String sizeInfo, String amount) throws Exception {
        String result = apply(XSL).to(withReplaced(CORPUS, SIZE, sizeInfo));
        assertThat(sizeInfo, result, is(elg().withXPath("//ms:size/ms:amount", equalTo(amount))));
    }

    /** The ms:language elements carry exactly these ids, in this order. */
    private void assertLanguageIdsInOrder(String result, String... ids) {
        assertThat(result, is(elg().withXPath("count(//ms:language)", equalTo(String.valueOf(ids.length)))));
        for (int i = 0; i < ids.length; i++) {
            assertThat(result, is(elg().withXPath("(//ms:language)[" + (i + 1) + "]/ms:languageId", equalTo(ids[i]))));
        }
    }

    private void assertLanguageIds(String isoCode, String languageId) throws Exception {
        String result = apply(XSL).to(withReplaced(CORPUS, LANGUAGE, "<field name=\"value\">" + isoCode + "</field>"));
        assertThat(isoCode, result, is(elg().withXPath("//ms:language/ms:languageId", equalTo(languageId))));
        assertThat(isoCode, result, is(elg().withXPath("//ms:language/ms:languageTag", equalTo(languageId))));
    }

    /** The fixture with one piece of text, which must occur exactly once, replaced literally. */
    private InputStream withReplaced(String fixture, String text, String replacement) throws Exception {
        String xml = IOUtils.toString(resource(fixture), StandardCharsets.UTF_8);
        int at = xml.indexOf(text);
        if (at < 0) {
            throw new IllegalArgumentException("'" + text + "' is not in " + fixture);
        }
        if (xml.indexOf(text, at + 1) >= 0) {
            throw new IllegalArgumentException("'" + text + "' is not unique in " + fixture);
        }
        xml = xml.substring(0, at) + replacement + xml.substring(at + text.length());
        return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
    }

    private XmlMatcherBuilder elg() {
        // XmlMatcherBuilder's NamespaceContext is a plain map, so the reserved "xml" prefix has to be registered
        // explicitly or JAXP cannot compile an @xml:lang step.
        return xml().withNamespace("ms", MS_NS).withNamespace("xml", XML_NS);
    }
}
