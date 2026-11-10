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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.IOUtils;
import org.dspace.xoai.tests.support.XmlMatcherBuilder;
import org.junit.Test;

/**
 * Tests for the CCMM (Czech Common Metadata Model) 1.1.0 OAI-PMH crosswalk.
 *
 * <p>The CCMM schema is an xs:sequence, so element order is part of validity, and every
 * controlled value has to come from the register CCMM names for it.  Validating against the
 * schema itself would pull xml.xsd and GML over the network at build time, so the invariants
 * that matter are asserted here instead: the order of the dataset children, and closed-set
 * guards that fail if any off-register string or unresolvable IRI reappears.</p>
 *
 * @see <a href="https://github.com/techlib/CCMM">CCMM Schema</a>
 * @see <a href="https://github.com/ufal/clarin-dspace/issues/1145">Issue #1145</a>
 */
public class CcmmXslTest extends AbstractXSLTest {

    private static final String CCMM_NS = "https://schema.ccmm.cz/research-data/1.1";
    private static final String XML_NS = "http://www.w3.org/XML/1998/namespace";
    private static final String MAIN = "xoai-ccmm-test.xml";
    private static final String MINIMAL = "xoai-ccmm-minimal-test.xml";
    private static final String APPROX = "xoai-ccmm-approximate-date-test.xml";
    private static final String STOCK = "xoai-ccmm-stock-dspace-test.xml";
    private static final String DATES = "xoai-ccmm-date-shapes-test.xml";
    private static final String CLARIN = "xoai-ccmm-clarin-access-test.xml";
    private static final String ENUM = "xoai-ccmm-enumerated-date-test.xml";
    private static final String ACA = "xoai-ccmm-academic-licence-test.xml";
    private static final String BARE = "xoai-ccmm-no-identifier-test.xml";
    private static final String FLAG = "xoai-ccmm-restricted-flag-test.xml";
    private static final String EMBARGO = "xoai-ccmm-embargo-test.xml";
    private static final String CLIP = "xoai-ccmm-newsreel-clip-test.xml";
    private static final String TOOL = "xoai-ccmm-tool-test.xml";
    private static final String FUNDING = "xoai-ccmm-funding-test.xml";
    private static final String BAD_REPO = "xoai-ccmm-bad-repo-test.xml";

    @Test
    public void ccmmCanTransformInput() throws Exception {
        String result = apply("ccmm.xsl").to(resource(MAIN));
        assertThat(result, is(ccmm().withXPath("//ccmm:title", equalTo("Czech NLP Dataset v2.0"))));
    }

    @Test
    public void ccmmContainsPublicationYear() throws Exception {
        String result = apply("ccmm.xsl").to(resource(MAIN));
        assertThat(result, is(ccmm().withXPath("//ccmm:publication_year", equalTo("2025"))));
    }

    @Test
    public void ccmmContainsIdentifier() throws Exception {
        String result = apply("ccmm.xsl").to(resource(MAIN));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:dataset/ccmm:identifier[1]/ccmm:value", equalTo("123456789/1-5678"))));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:dataset/ccmm:identifier[1]/ccmm:iri",
            equalTo("http://hdl.handle.net/123456789/1-5678"))));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:dataset/ccmm:identifier[1]/ccmm:scheme/ccmm:label", equalTo("Handle"))));
    }

    @Test
    public void ccmmKeepsIdentifiersOtherThanTheItemUri() throws Exception {
        // dc.identifier.other is an external identifier and must not be dropped
        String result = apply("ccmm.xsl").to(resource(MAIN));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:dataset/ccmm:identifier[ccmm:value='ISLRN-123-456-789'])", equalTo("1"))));
    }

    @Test
    public void ccmmContainsCreator() throws Exception {
        String result = apply("ccmm.xsl").to(resource(MAIN));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:dataset/ccmm:qualified_relation[1]/ccmm:relation/ccmm:person/ccmm:name",
            equalTo("Novak, Jan"))));
    }

    @Test
    public void ccmmSplitsPersonNamesIntoGivenAndFamily() throws Exception {
        String result = apply("ccmm.xsl").to(resource(MAIN));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:person[ccmm:name='Novak, Jan']/ccmm:family_name", equalTo("Novak"))));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:person[ccmm:name='Novak, Jan']/ccmm:given_name", equalTo("Jan"))));
    }

    @Test
    public void ccmmCommasNeverSplitOneAgentValue() throws Exception {
        // DSpace stores each agent as its own value, so a long organization name with three or
        // more commas is still one agent
        String name = "Example University, Faculty of Sciences,"
            + " Institute of Example Linguistics, Prague";
        String result = apply("ccmm.xsl").to(withReplaced(MAIN,
            "Institute of Example Linguistics</field>",
            "Institute of Example Linguistics, Prague</field>"));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:qualified_relation/ccmm:relation/ccmm:organization[ccmm:name='" + name + "'])",
            equalTo("1"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:qualified_relation/ccmm:relation/*[ccmm:name='Prague'])", equalTo("0"))));
    }

    @Test
    public void ccmmAGivenNameContainingUstavIsStillAPerson() throws Exception {
        // "ustav" (Czech "institute") is matched only at the start of a word, so the
        // G-ustav-o inside a given name does not make its bearer an organization
        String result = ccmmOf(add(fixture(MAIN), "dc", field("contributor.other", "none", "Exemplo, Gustavo")));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:person[ccmm:name='Exemplo, Gustavo']/ccmm:given_name", equalTo("Gustavo"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:organization[ccmm:name='Exemplo, Gustavo'])", equalTo("0"))));
    }

    @Test
    public void ccmmAnUstavAtTheStartOfAWordIsStillAnOrganization() throws Exception {
        // the type is read off the name for every field but dc.contributor.author and dc.creator
        String result = ccmmOf(add(fixture(MAIN), "dc",
            field("contributor.other", "none", "Ústav ukázkové lingvistiky")));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:qualified_relation/ccmm:relation/ccmm:organization"
                + "[ccmm:name='Ústav ukázkové lingvistiky'])", equalTo("1"))));
        result = ccmmOf(add(fixture(MAIN), "dc", field("contributor.other", "none", "Ukázkový ústav")));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:organization[ccmm:name='Ukázkový ústav'])", equalTo("1"))));
    }

    @Test
    public void ccmmSemicolonsStillSplitAPackedAgentValue() throws Exception {
        // a legacy value packing several people is split on semicolons only; each part keeps
        // its "Family, Given" comma
        String result = apply("ccmm.xsl").to(withReplaced(MAIN, ">Svobodova, Marie<",
            ">Svobodova, Marie; Dvorak, Petr<"));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:person[ccmm:name='Svobodova, Marie']/ccmm:family_name", equalTo("Svobodova"))));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:person[ccmm:name='Dvorak, Petr']/ccmm:given_name", equalTo("Petr"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:person[contains(ccmm:name, ';')])", equalTo("0"))));
    }

    @Test
    public void ccmmAuthorIsAlwaysAPerson() throws Exception {
        // dc.contributor.author and dc.creator name people; a corporate name there is an error
        // the repository has to fix, so the crosswalk does not guess the type from the name
        String result = ccmmOf(add(fixture(MAIN), "dc", field("creator", "none", "Example Film Studio")));
        for (String name : new String[] {"Example Film Co.", "Zorvex", "Example Film Studio"}) {
            assertThat(name, result, is(ccmm().withXPath(
                "count(//ccmm:qualified_relation[ccmm:role/ccmm:label='Creator']"
                    + "/ccmm:relation/ccmm:person[ccmm:name='" + name + "'])", equalTo("1"))));
            assertThat(name, result, is(ccmm().withXPath(
                "count(//ccmm:organization[ccmm:name='" + name + "'])", equalTo("0"))));
        }
    }

    @Test
    public void ccmmCorporateNameOutsideTheAuthorsIsAnOrganization() throws Exception {
        String result = ccmmOf(add(fixture(MAIN), "dc", field("contributor.other", "none", "Example Film Studio")));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:qualified_relation[ccmm:role/ccmm:label!='Creator']"
                + "/ccmm:relation/ccmm:organization[ccmm:name='Example Film Studio'])", equalTo("1"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:person[ccmm:name='Example Film Studio'])", equalTo("0"))));
    }

    @Test
    public void ccmmDropsAnvlPlaceholderAgents() throws Exception {
        // "(:unav) Unknown author" names nobody and must not become an agent
        String result = apply("ccmm.xsl").to(resource(MAIN));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:person[contains(ccmm:name, '(:un')])", equalTo("0"))));
    }

    @Test
    public void ccmmEmitsCreatorAndPublisherEvenWhenTheRecordNamesNeither() throws Exception {
        String result = apply("ccmm.xsl").to(resource(MINIMAL));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:qualified_relation[ccmm:role/ccmm:label='Creator'])", equalTo("1"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:dataset/ccmm:qualified_relation[ccmm:role/ccmm:label='Publisher'])",
            equalTo("1"))));
    }

    @Test
    public void ccmmContainsSubjects() throws Exception {
        String result = apply("ccmm.xsl").to(resource(MAIN));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:dataset/ccmm:subject/ccmm:title[.='linguistics'])", equalTo("1"))));
    }

    @Test
    public void ccmmHierarchicalSubjectKeepsThePathAsAClassificationCode() throws Exception {
        // "People::Example ..." is a path through a thesaurus, not a subject heading
        String result = apply("ccmm.xsl").to(resource(MAIN));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:subject[ccmm:classification_code="
                + "'People::Example Person (1801-1870)']/ccmm:title",
            equalTo("Example Person (1801-1870)"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:subject/ccmm:classification_code"
                + "[.='People::Example Person (1801-1870)'])",
            equalTo("1"))));
    }

    @Test
    public void ccmmContainsResourceType() throws Exception {
        // META-SHARE "corpus" maps to the COAR resource type "dataset"
        String result = apply("ccmm.xsl").to(resource(MAIN));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:dataset/ccmm:resource_type/ccmm:iri",
            equalTo("http://purl.org/coar/resource_type/c_ddb1"))));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:dataset/ccmm:resource_type/ccmm:label[1]", equalTo("dataset"))));
    }

    @Test
    public void ccmmResourceTypeUnderstandsTheStockDspaceTypeList() throws Exception {
        String result = apply("ccmm.xsl").to(resource(STOCK));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:dataset/ccmm:resource_type/ccmm:iri",
            equalTo("http://purl.org/coar/resource_type/c_6501"))));
    }

    @Test
    public void ccmmContainsDescription() throws Exception {
        String result = apply("ccmm.xsl").to(resource(MAIN));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:description[ccmm:description_type/ccmm:label='Abstract']/ccmm:description_text",
            equalTo("A sample dataset for testing CCMM crosswalk output in the OAI-PMH protocol."))));
    }

    @Test
    public void ccmmUnqualifiedDescriptionIsNotAssertedToBeAnAbstract() throws Exception {
        // dc.description without a qualifier says nothing about what kind of description it is
        String result = apply("ccmm.xsl").to(resource(MAIN));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:description[ccmm:description_text='Built from newspaper text collected in 2024.']"
                + "/ccmm:description_type/ccmm:iri",
            equalTo("https://vocabs.ccmm.cz/registry/codelist/DescriptionType/Other"))));
    }

    @Test
    public void ccmmLicenseCarriesTheLicenceNameNotTheAccessCategory() throws Exception {
        // dc.rights.label is PUB/ACA/RES, which is a download gate, not the name of a licence
        String result = apply("ccmm.xsl").to(resource(MAIN));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:dataset/ccmm:terms_of_use/ccmm:license/ccmm:iri",
            equalTo("https://creativecommons.org/licenses/by/4.0/"))));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:dataset/ccmm:terms_of_use/ccmm:license/ccmm:label",
            equalTo("Creative Commons - Attribution 4.0 International"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:license/ccmm:label[.='PUB' or .='ACA' or .='RES'])", equalTo("0"))));
    }

    @Test
    public void ccmmContainsAccessRights() throws Exception {
        String result = apply("ccmm.xsl").to(resource(MAIN));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:dataset/ccmm:terms_of_use/ccmm:access_rights/ccmm:iri",
            equalTo("http://purl.org/coar/access_right/c_abf2"))));
    }

    @Test
    public void ccmmAccessRightsFollowTheComputedAnonymousAccess() throws Exception {
        // ItemUtils computed others/anonymousAccess=open from the READ policies and the licence
        String result = apply("ccmm.xsl").to(resource(MAIN));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:dataset/ccmm:terms_of_use/ccmm:access_rights/ccmm:label",
            equalTo("open access"))));
    }

    @Test
    public void ccmmPubLabelIsNotOpenWhenAnonymousCannotDownload() throws Exception {
        // dc.rights.label says PUB, but the files are not downloadable anonymously (no Anonymous
        // READ policy, or a licence that requires sign-in): the real gate wins over the label
        String result = apply("ccmm.xsl").to(withAnonymousAccess(CLARIN, "restricted"));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:terms_of_use/ccmm:access_rights/ccmm:iri",
            equalTo("http://purl.org/coar/access_right/c_16ec"))));
    }

    @Test
    public void ccmmPubLabelWithAnEmbargoIsEmbargoedNotOpen() throws Exception {
        String result = apply("ccmm.xsl").to(withAnonymousAccess(CLARIN, "embargo"));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:terms_of_use/ccmm:access_rights/ccmm:iri",
            equalTo("http://purl.org/coar/access_right/c_f1cf"))));
    }

    @Test
    public void ccmmAcademicLabelIsOpenWhenAnonymousCanDownload() throws Exception {
        // the other direction: the label says ACA, the policies and the licence let anyone in
        String result = apply("ccmm.xsl").to(withAnonymousAccess(ACA, "open"));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:terms_of_use/ccmm:access_rights/ccmm:iri",
            equalTo("http://purl.org/coar/access_right/c_abf2"))));
    }

    @Test
    public void ccmmMissingAnonymousAccessIsNeverOpen() throws Exception {
        // a record cached before ItemUtils computed the field: PUB label, files and
        // access-status=open.access are all present, and still nothing proves the download works
        String result = apply("ccmm.xsl").to(withAnonymousAccess(MAIN, null));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:terms_of_use/ccmm:access_rights/ccmm:iri",
            equalTo("http://purl.org/coar/access_right/c_16ec"))));
    }

    @Test
    public void ccmmMetadataOnlyAnonymousAccessIsMetadataOnly() throws Exception {
        String result = apply("ccmm.xsl").to(withAnonymousAccess(MAIN, "metadata.only"));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:terms_of_use/ccmm:access_rights/ccmm:iri",
            equalTo("http://purl.org/coar/access_right/c_14cb"))));
    }

    @Test
    public void ccmmAnItemWithNoFilesIsMetadataOnlyWhateverItsLabel() throws Exception {
        // ACA with no files used to come out as restricted; there is nothing to restrict
        String result = apply("ccmm.xsl").to(withAnonymousAccess(MINIMAL, "restricted"));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:terms_of_use/ccmm:access_rights/ccmm:iri",
            equalTo("http://purl.org/coar/access_right/c_14cb"))));
    }

    @Test
    public void ccmmAnItemWithNoFilesIsMetadataOnlyNotRestricted() throws Exception {
        String result = apply("ccmm.xsl").to(resource(MINIMAL));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:dataset/ccmm:terms_of_use/ccmm:access_rights/ccmm:iri",
            equalTo("http://purl.org/coar/access_right/c_14cb"))));
    }

    @Test
    public void ccmmContainsPrimaryLanguage() throws Exception {
        // CCMM: "Use IRI identifier from the register
        // http://publications.europa.eu/resource/authority/language"
        String result = apply("ccmm.xsl").to(resource(MAIN));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:dataset/ccmm:primary_language/ccmm:iri",
            equalTo("http://publications.europa.eu/resource/authority/language/CES"))));
    }

    @Test
    public void ccmmOtherLanguageKeepsItsRegisterIri() throws Exception {
        // document order is preserved and duplicates removed without reordering, so the second
        // distinct code is the first other_language
        String result = apply("ccmm.xsl").to(resource(MAIN));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:dataset/ccmm:other_language/ccmm:iri",
            equalTo("http://publications.europa.eu/resource/authority/language/ENG"))));
    }

    @Test
    public void ccmmAcceptsIso6391AndRegionSubtags() throws Exception {
        // stock DSpace stores "en_US"; the register keys on the uppercased 639-3 code
        String result = apply("ccmm.xsl").to(resource(STOCK));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:dataset/ccmm:primary_language/ccmm:iri",
            equalTo("http://publications.europa.eu/resource/authority/language/ENG"))));
    }

    @Test
    public void ccmmContainsAlternateTitle() throws Exception {
        String result = apply("ccmm.xsl").to(resource(MAIN));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:dataset/ccmm:alternate_title/ccmm:title", equalTo("CND 2.0"))));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:dataset/ccmm:alternate_title/ccmm:alternate_title_type/ccmm:iri",
            equalTo("https://vocabs.ccmm.cz/registry/codelist/AlternateTitle/TranslatedTitle"))));
    }

    @Test
    public void ccmmPublisherUsesPublisherRole() throws Exception {
        String result = apply("ccmm.xsl").to(resource(MAIN));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:dataset/ccmm:qualified_relation[ccmm:role/ccmm:label='Publisher']"
                + "/ccmm:relation/ccmm:organization/ccmm:name",
            equalTo("Example University, Faculty of Sciences, "
                + "Institute of Example Linguistics"))));
    }

    @Test
    public void ccmmContributorOtherKeepsItsOwnRole() throws Exception {
        String result = apply("ccmm.xsl").to(resource(MAIN));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:qualified_relation[ccmm:role/ccmm:label='Other']/ccmm:role/ccmm:iri",
            equalTo("https://vocabs.ccmm.cz/registry/codelist/AgentRole/Contributor/Other"))));
    }

    @Test
    public void ccmmContainsMetadataIdentification() throws Exception {
        String result = apply("ccmm.xsl").to(resource(MAIN));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:dataset/ccmm:metadata_identification/ccmm:conforms_to_standard/ccmm:iri",
            equalTo("https://model.ccmm.cz/research-data/1.1.0"))));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:dataset/ccmm:metadata_identification/ccmm:conforms_to_standard/ccmm:label",
            equalTo("CCMM RD 1.1.0"))));
    }

    @Test
    public void ccmmMetadataRecordCarriesItsOwnOaiIdentifier() throws Exception {
        String result = apply("ccmm.xsl").to(resource(MAIN));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:dataset/ccmm:metadata_identification/ccmm:iri",
            equalTo("oai:repository.example.org:123456789/1-5678"))));
    }

    @Test
    public void ccmmMetadataRecordHasADataManager() throws Exception {
        // dsv.ttl: the relationship shall contain at least one agent with role "Data Manager"
        String result = apply("ccmm.xsl").to(resource(MAIN));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:metadata_identification/ccmm:qualified_relation/ccmm:role/ccmm:iri",
            equalTo("https://vocabs.ccmm.cz/registry/codelist/AgentRole/Contributor/DataManager"))));
    }

    @Test
    public void ccmmCanTransformBasicXoaiInput() throws Exception {
        // the default xoai-test1.xml exercises the paths where almost every field is missing
        String result = apply("ccmm.xsl").to(resource("xoai-test1.xml"));
        assertThat(result, is(ccmm().withXPath("//ccmm:title", equalTo("Test Webpage"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:dataset/ccmm:identifier)", equalTo("2"))));
        // dc.identifier.issn names its own scheme; it used to be dropped entirely
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:identifier[ccmm:value='123456789']/ccmm:scheme/ccmm:label", equalTo("ISSN"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:dataset/ccmm:terms_of_use)", equalTo("1"))));
    }

    // ---- Fallback scenario tests ----

    @Test
    public void ccmmFallbackTitleIsUntitled() throws Exception {
        String result = apply("ccmm.xsl").to(resource(MINIMAL));
        assertThat(result, is(ccmm().withXPath("//ccmm:dataset/ccmm:title", equalTo("Untitled"))));
    }

    @Test
    public void ccmmFallbackPublicationYearIs9999() throws Exception {
        String result = apply("ccmm.xsl").to(resource(MINIMAL));
        assertThat(result, is(ccmm().withXPath("//ccmm:dataset/ccmm:publication_year", equalTo("9999"))));
    }

    @Test
    public void ccmmRecordWithNoSubjectGetsTheConfiguredDefaultSubject() throws Exception {
        // subject is 1..n; dc.subject is required at submission, this is only the safety net
        String result = apply("ccmm.xsl").withProperty("oai.ccmm.subject.default", "Research data")
            .to(resource(MINIMAL));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:dataset/ccmm:subject)", equalTo("1"))));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:dataset/ccmm:subject/ccmm:title", equalTo("Research data"))));
    }

    @Test
    public void ccmmRecordWithNoSubjectAndNoDefaultDeclaresTheSubjectUnavailable() throws Exception {
        String result = apply("ccmm.xsl").to(resource(MINIMAL));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:dataset/ccmm:subject)", equalTo("1"))));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:dataset/ccmm:subject/ccmm:title", equalTo(":unav"))));
    }

    @Test
    public void ccmmDefaultSubjectIsNotAddedWhenTheItemHasSubjects() throws Exception {
        String result = apply("ccmm.xsl").withProperty("oai.ccmm.subject.default", "Research data")
            .to(resource(MAIN));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:subject/ccmm:title[.='Research data' or .=':unav'])", equalTo("0"))));
    }

    @Test
    public void ccmmNoFordFieldIsDerivedFromTheResourceType() throws Exception {
        // DSpace records no discipline; deriving one from dc.type would publish one the depositor
        // never stated, e.g. "Linguistics" for every dataset of a non-linguistic repository
        for (String fixture : new String[] {MAIN, MINIMAL, CLIP, TOOL, STOCK}) {
            String result = apply("ccmm.xsl").to(resource(fixture));
            assertThat(fixture, result, is(ccmm().withXPath(
                "count(//ccmm:subject_scheme[contains(ccmm:iri, 'SubjectCategory')])", equalTo("0"))));
            assertThat(fixture, result, is(ccmm().withXPath(
                "count(//ccmm:subject/ccmm:iri[contains(., 'SubjectCategory')])", equalTo("0"))));
            assertThat(fixture, result, is(ccmm().withXPath(
                "count(//ccmm:subject/ccmm:title[.='Linguistics' or .='Lingvistika'"
                    + " or .='History' or .='Historie'])", equalTo("0"))));
        }
    }

    @Test
    public void ccmmFallbackIdentifierUsesOthersHandle() throws Exception {
        String result = apply("ccmm.xsl").to(resource(MINIMAL));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:dataset/ccmm:identifier/ccmm:value", equalTo("99999/test-1"))));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:dataset/ccmm:identifier/ccmm:iri",
            equalTo("http://hdl.handle.net/99999/test-1"))));
    }

    @Test
    public void ccmmFallbackRepositoryDeclaresItselfUnavailable() throws Exception {
        // an absent repository name is stated as the ANVL ":unav", not invented
        String result = apply("ccmm.xsl").to(resource(MINIMAL));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:dataset/ccmm:metadata_identification/ccmm:qualified_relation"
                + "/ccmm:relation/ccmm:organization/ccmm:name",
            equalTo(":unav"))));
    }

    @Test
    public void ccmmFallbackLicenseIsEmptyAndRightsTextBecomesTheLabel() throws Exception {
        // With no licence URI, ccmm:license carries the wording as its label rather than a
        // made-up IRI; license_document allows a label without one.
        String result = apply("ccmm.xsl").to(resource(MINIMAL));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:dataset/ccmm:terms_of_use/ccmm:license/ccmm:iri)", equalTo("0"))));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:dataset/ccmm:terms_of_use/ccmm:license/ccmm:label",
            equalTo("All rights reserved"))));
    }

    @Test
    public void ccmmOriginalRepositoryIsTheRepositoryUrl() throws Exception {
        String result = apply("ccmm.xsl").to(resource(MAIN));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:metadata_identification/ccmm:original_repository/ccmm:iri",
            equalTo("https://repository.example.org/repository/"))));
    }

    @Test
    public void ccmmOriginalRepositoryIsNeverTheItemItself() throws Exception {
        // without repository/url there is no repository to name, and a handle URL is not one
        String result = apply("ccmm.xsl").to(resource(STOCK));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:metadata_identification/ccmm:original_repository/ccmm:iri",
            equalTo("urn:x-ccmm:repository-unknown"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:original_repository[contains(ccmm:iri, '123/456')])", equalTo("0"))));
    }

    @Test
    public void ccmmXmlLangFollowsTheXoaiLanguageWrapper() throws Exception {
        String result = apply("ccmm.xsl").to(resource(MAIN));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:subject/ccmm:title[.='korpus']/@xml:lang", equalTo("cs"))));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:subject/ccmm:title[.='Korpus']/@xml:lang", equalTo("de"))));
        // "none" means DSpace does not know the language: xml:lang is "und" (BCP 47 "undetermined")
        // instead of claiming English, and the text itself is copied unchanged
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:subject/ccmm:title[.='linguistics'][@xml:lang='und'])", equalTo("1"))));
    }

    @Test
    public void ccmmTextWithoutALanguageIsNotLabelledEnglish() throws Exception {
        String result = apply("ccmm.xsl").to(withReplaced(MAIN, ">linguistics<", ">Ukázková kategorie<"));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:subject/ccmm:title[.='Ukázková kategorie'][@xml:lang='und'])", equalTo("1"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:subject/ccmm:title[.='Ukázková kategorie'][@xml:lang='en'])", equalTo("0"))));
    }

    @Test
    public void ccmmSchemaLocationPointsAtTheVersionedOneDotOneSchema() throws Exception {
        // techlib.github.io/CCMM/ always serves the latest major (2.0 by now), whose target
        // namespace is not the one these records use
        String result = apply("ccmm.xsl").to(resource(MAIN));
        assertThat(result, is(ccmm().withNamespace("xsi", "http://www.w3.org/2001/XMLSchema-instance")
            .withXPath("/ccmm:dataset/@xsi:schemaLocation", equalTo("https://schema.ccmm.cz/research-data/1.1"
                + " https://model.ccmm.cz/research-data/1.1.0/dataset/schema.xsd"))));
    }

    // ---- Contact, distribution, relations ----

    @Test
    public void ccmmContainsANamedContactPoint() throws Exception {
        String result = apply("ccmm.xsl").to(resource(MAIN));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:terms_of_use/ccmm:contact_point/ccmm:person/ccmm:name", equalTo("Kovar, Tomas"))));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:terms_of_use/ccmm:contact_point/ccmm:person/ccmm:contact_point/ccmm:email",
            equalTo("kovar@example.org"))));
    }

    @Test
    public void ccmmContainsTheRepositoryContactAsAnOrganization() throws Exception {
        String result = apply("ccmm.xsl").to(resource(MAIN));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:contact_point/ccmm:organization/ccmm:contact_point/ccmm:email",
            equalTo("help@repository.example.org"))));
    }

    @Test
    public void ccmmBitstreamsBecomeDownloadableDistributions() throws Exception {
        String result = apply("ccmm.xsl").to(resource(MAIN));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:distribution/ccmm:distribution_downloadable_file/ccmm:title",
            equalTo("corpus.txt"))));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:distribution_downloadable_file/ccmm:byte_size", equalTo("2048"))));
        // format carries the EU file-type codelist value, media_type carries IANA
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:distribution_downloadable_file/ccmm:format/ccmm:iri",
            equalTo("http://publications.europa.eu/resource/authority/file-type/TXT"))));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:distribution_downloadable_file/ccmm:format/ccmm:label", equalTo("Plain text"))));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:distribution_downloadable_file/ccmm:media_type/ccmm:iri",
            equalTo("https://www.iana.org/assignments/media-types/text/plain"))));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:distribution_downloadable_file/ccmm:media_type/ccmm:label", equalTo("text/plain"))));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:distribution_downloadable_file/ccmm:checksum/ccmm:checksum_value",
            equalTo("ee1c4e448a8f9f838df348dfee1fc11f"))));
    }

    @Test
    public void ccmmRelationQualifiersBecomeTypedRelatedResources() throws Exception {
        // the register has no "Replaces"; superseding is Obsoletes
        String result = apply("ccmm.xsl").to(resource(MAIN));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:related_resource[ccmm:iri='http://hdl.handle.net/123456789/1-1111']"
                + "/ccmm:resource_relation_type/ccmm:iri",
            equalTo("https://vocabs.ccmm.cz/registry/codelist/RelationType/Obsoletes"))));
    }

    @Test
    public void ccmmOwningCollectionBecomesAPartOfRelation() throws Exception {
        String result = apply("ccmm.xsl").to(resource(MAIN));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:related_resource[ccmm:title='Test Collection']"
                + "/ccmm:resource_relation_type/ccmm:iri",
            equalTo("https://vocabs.ccmm.cz/registry/codelist/RelationType/IsPartOf"))));
    }

    @Test
    public void ccmmProjectPageAndDemoBecomeRelatedResources() throws Exception {
        String result = apply("ccmm.xsl").to(resource(MAIN));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:related_resource[ccmm:resource_url='https://example.org/czech-nlp-dataset'])",
            equalTo("1"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:related_resource[ccmm:resource_url='https://example.org/demo'])",
            equalTo("1"))));
    }

    @Test
    public void ccmmContainsLocationForACoveredPlace() throws Exception {
        String result = apply("ccmm.xsl").to(resource(MAIN));
        assertThat(result, is(ccmm().withXPath("//ccmm:location/ccmm:name", equalTo("Praha"))));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:location/ccmm:relation_type/ccmm:iri",
            equalTo("https://vocabs.ccmm.cz/registry/codelist/LocationRelation/Refers"))));
    }

    @Test
    public void ccmmContainsValidationResult() throws Exception {
        String result = apply("ccmm.xsl").to(resource(MAIN));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:validation_result/ccmm:label", equalTo("Validated by the repository"))));
    }

    @Test
    public void ccmmFundingReferenceSpellsOutTheFunderButNoProgrammeCode() throws Exception {
        // funding_program is a reference (xsd:anyURI) to the programme; "H2020" from the
        // info:eu-repo path is only a code, so the optional element is left out
        String result = apply("ccmm.xsl").to(resource(MAIN));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:funding_reference/ccmm:local_identifier", equalTo("900002"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:funding_reference/ccmm:funding_program)", equalTo("0"))));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:funding_reference/ccmm:funder/ccmm:organization/ccmm:name",
            equalTo("European Commission"))));
    }

    @Test
    public void ccmmNonGrantInfoUrisNeverInventAFunder() throws Exception {
        // info:eu-repo/semantics/... is not a grant agreement
        String result = apply("ccmm.xsl").to(resource(STOCK));
        assertThat(result, is(ccmm().withXPath("count(//ccmm:funding_reference)", equalTo("0"))));
    }

    // ---- Controlled vocabularies ----

    @Test
    public void ccmmCreatorRoleUsesCcmmAgentRoleCodelist() throws Exception {
        String result = apply("ccmm.xsl").to(resource(MAIN));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:dataset/ccmm:qualified_relation[1]/ccmm:role/ccmm:iri",
            equalTo("https://vocabs.ccmm.cz/registry/codelist/AgentRole/Creator"))));
    }

    @Test
    public void ccmmDateTypeUsesTheRegistersOwnLabels() throws Exception {
        // the register's English prefLabel is "Date Issued", not the concept id "Issued"
        String result = apply("ccmm.xsl").to(resource(MAIN));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:dataset/ccmm:time_reference[1]/ccmm:date_type/ccmm:iri",
            equalTo("https://vocabs.ccmm.cz/registry/codelist/TimeReference/Issued"))));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:dataset/ccmm:time_reference[1]/ccmm:date_type/ccmm:label[1]",
            equalTo("Date Issued"))));
    }

    @Test
    public void ccmmEmitsNoOffRegisterVocabularyStrings() throws Exception {
        // a closed-set guard: these are the strings and hosts that were wrong before, and no
        // future edit may bring them back
        String result = apply("ccmm.xsl").to(resource(MAIN));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:date_type/ccmm:label[.='Issued' or .='Created'"
                + " or .='Accepted' or .='Available'])", equalTo("0"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:iri[contains(., 'model.ccmm.cz/vocabulary')])", equalTo("0"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:iri[contains(., 'w3.org/ns/iana')])", equalTo("0"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:iri[contains(., 'RelationType/Replaces')"
                + " or contains(., 'RelationType/IsReplacedBy')])", equalTo("0"))));
    }

    // ---- Element order: the CCMM dataset is an xs:sequence ----

    @Test
    public void ccmmEmitsTheDatasetChildrenInSchemaOrder() throws Exception {
        String result = apply("ccmm.xsl").to(resource(MAIN));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:dataset/ccmm:identifier[preceding-sibling::ccmm:title])", equalTo("0"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:dataset/ccmm:title[preceding-sibling::ccmm:publication_year])",
            equalTo("0"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:dataset/ccmm:publication_year[preceding-sibling::ccmm:time_reference])",
            equalTo("0"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:dataset/ccmm:resource_type[preceding-sibling::ccmm:terms_of_use])",
            equalTo("0"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:dataset/ccmm:terms_of_use[preceding-sibling::ccmm:subject])",
            equalTo("0"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:dataset/ccmm:subject[preceding-sibling::ccmm:location])", equalTo("0"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:dataset/ccmm:related_resource[preceding-sibling::ccmm:distribution])",
            equalTo("0"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:dataset/ccmm:distribution[preceding-sibling::ccmm:validation_result])",
            equalTo("0"))));
    }

    @Test
    public void ccmmEmitsNestedElementsInSchemaOrder() throws Exception {
        String result = apply("ccmm.xsl").to(resource(MAIN));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:person/ccmm:name[preceding-sibling::ccmm:given_name])", equalTo("0"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:subject/ccmm:title[preceding-sibling::ccmm:classification_code])",
            equalTo("0"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:terms_of_use/ccmm:access_rights[preceding-sibling::ccmm:license])",
            equalTo("0"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:terms_of_use/ccmm:license[preceding-sibling::ccmm:contact_point])",
            equalTo("0"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:time_reference/ccmm:temporal_representation"
                + "[preceding-sibling::ccmm:date_type])", equalTo("0"))));
    }

    // ---- CLARIN approximate dates (dc.date.issued = "0000") ----

    @Test
    public void ccmmApproximateDateSetsPublicationYearToTheStartOfTheRange() throws Exception {
        String result = apply("ccmm.xsl").to(resource(APPROX));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:dataset/ccmm:publication_year", equalTo("1930"))));
    }

    @Test
    public void ccmmApproximateDateRangeBecomesTimeInterval() throws Exception {
        String result = apply("ccmm.xsl").to(resource(APPROX));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:time_reference/ccmm:temporal_representation/ccmm:time_interval"
                + "/ccmm:beginning/ccmm:date", equalTo("1930-01-01"))));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:time_reference/ccmm:temporal_representation/ccmm:time_interval"
                + "/ccmm:end/ccmm:date", equalTo("1950-12-31"))));
    }

    @Test
    public void ccmmApproximateDateKeepsOriginalTextVerbatim() throws Exception {
        String result = apply("ccmm.xsl").to(resource(APPROX));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:time_reference/ccmm:date_information", equalTo("cca 1930-1950"))));
    }

    @Test
    public void ccmmEmitsExactlyOneIssuedAndOneCreatedTimeReference() throws Exception {
        // CCMM requires a Created time reference, and requires publication_year to equal the
        // year of the Issued date - which several Issued references could not satisfy.
        String result = apply("ccmm.xsl").to(resource(MAIN));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:time_reference[ccmm:date_type/ccmm:label='Date Issued'])", equalTo("1"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:time_reference[ccmm:date_type/ccmm:label='Date Created'])", equalTo("1"))));
    }

    @Test
    public void ccmmApproximateDateNeverEmitsYearZero() throws Exception {
        String result = apply("ccmm.xsl").to(resource(APPROX));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:time_instant[starts-with(ccmm:date, '0000')])", equalTo("0"))));
    }

    // ---- Dates that no format string can parse ----

    @Test
    public void ccmmEveryEmittedDateIsAValidXsDate() throws Exception {
        String result = apply("ccmm.xsl").to(resource(DATES));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:date[string-length(normalize-space(.)) != 10])", equalTo("0"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:date[normalize-space(.) = ''])", equalTo("0"))));
    }

    @Test
    public void ccmmUnparsableDateProducesNoTimeReferenceAtAll() throws Exception {
        // "n.d." is one of two dc.date.available values; only the parsable one survives
        String result = apply("ccmm.xsl").to(resource(DATES));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:time_reference[ccmm:date_type/ccmm:label='Date Available'])",
            equalTo("1"))));
    }

    @Test
    public void ccmmYearOnlyAndYearMonthDatesArePadded() throws Exception {
        String result = apply("ccmm.xsl").to(resource(DATES));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:time_reference[ccmm:date_type/ccmm:label='Date Issued']"
                + "/ccmm:temporal_representation/ccmm:time_instant/ccmm:date",
            equalTo("2014-01-01"))));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:time_reference[ccmm:date_type/ccmm:label='Date Accepted']"
                + "/ccmm:temporal_representation/ccmm:time_instant/ccmm:date",
            equalTo("2011-12-01"))));
    }

    @Test
    public void ccmmDateTimeValuesKeepTheirTimeAndUseTheDateTimeElement() throws Exception {
        // time_instant is a choice of date_time or date; truncating a timestamp into date
        // would silently drop the time
        String result = apply("ccmm.xsl").to(resource(MAIN));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:time_reference[ccmm:date_type/ccmm:label='Date Accepted']"
                + "/ccmm:temporal_representation/ccmm:time_instant/ccmm:date_time",
            equalTo("2025-06-15T10:30:00Z"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:time_reference[ccmm:date_type/ccmm:label='Date Accepted']"
                + "/ccmm:temporal_representation/ccmm:time_instant/ccmm:date)",
            equalTo("0"))));
    }

    @Test
    public void ccmmPublicationYearAlwaysMatchesTheIssuedReference() throws Exception {
        // dsv.ttl requires the two to agree; the fixtures carry different years so this can fail
        for (String fixture : new String[] { MAIN, MINIMAL, APPROX, STOCK, DATES }) {
            String result = apply("ccmm.xsl").to(resource(fixture));
            assertThat(fixture, result, is(ccmm().withXPath(
                "substring((//ccmm:time_reference[ccmm:date_type/ccmm:label='Date Issued']"
                    + "//ccmm:date | //ccmm:time_reference[ccmm:date_type/ccmm:label='Date Issued']"
                    + "//ccmm:date_time)[1], 1, 4) = string(//ccmm:dataset/ccmm:publication_year)",
                equalTo("true"))));
        }
    }

    @Test
    public void ccmmAccessStatusOfThePrimaryBitstreamDoesNotDecide() throws Exception {
        // access-status says restricted (it only looks at the primary bitstream); the computed
        // value over all files says open
        String result = apply("ccmm.xsl").to(resource(CLARIN));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:terms_of_use/ccmm:access_rights/ccmm:iri",
            equalTo("http://purl.org/coar/access_right/c_abf2"))));
    }

    @Test
    public void ccmmEnumeratedYearsNeverBecomeAContinuousInterval() throws Exception {
        // "1920, 1932" names two years; the source never claims the years between them
        String result = apply("ccmm.xsl").to(resource(ENUM));
        assertThat(result, is(ccmm().withXPath("count(//ccmm:time_interval)", equalTo("0"))));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:dataset/ccmm:publication_year", equalTo("1932"))));
    }

    @Test
    public void ccmmCreatedUsesTheStatedYearOfCreation() throws Exception {
        // local.additional.metadata states field_year; the deposit year is not a creation date
        String result = apply("ccmm.xsl").to(resource(ENUM));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:time_reference[ccmm:date_type/ccmm:label='Date Created']"
                + "/ccmm:temporal_representation/ccmm:time_instant/ccmm:date",
            equalTo("1801-01-01"))));
    }

    @Test
    public void ccmmTitleIsNeverAConcatenationOfSeveralValues() throws Exception {
        String result = apply("ccmm.xsl").to(resource(STOCK));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:dataset/ccmm:title", equalTo("A Stock DSpace Item"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:dataset/ccmm:title)", equalTo("1"))));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:dataset/ccmm:alternate_title/ccmm:title",
            equalTo("Second Title Nobody Entered As One"))));
    }

    @Test
    public void ccmmUnparsableAccessionDateProducesNoTimeReference() throws Exception {
        String result = apply("ccmm.xsl").to(resource(DATES));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:time_reference[ccmm:date_type/ccmm:label='Date Accepted'])",
            equalTo("1"))));
    }

    @Test
    public void ccmmUriIdentifierSchemeUsesTheIanaRegistry() throws Exception {
        String result = apply("ccmm.xsl").to(resource(MAIN));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:related_resource[ccmm:resource_url='https://example.org/czech-nlp-dataset']"
                + "/ccmm:identifier/ccmm:scheme/ccmm:iri",
            equalTo("https://www.iana.org/assignments/uri-schemes"))));
    }

    @Test
    public void ccmmAcademicLicenceIsRestrictedEvenWhenAccessStatusSaysOpen() throws Exception {
        // the licence requires sign-in, so anonymousAccess is restricted; open.access on the
        // primary bitstream does not override it
        String result = apply("ccmm.xsl").to(resource(ACA));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:terms_of_use/ccmm:access_rights/ccmm:iri",
            equalTo("http://purl.org/coar/access_right/c_16ec"))));
    }

    @Test
    public void ccmmRestrictedAnonymousAccessOutranksAPubLicence() throws Exception {
        // the PUB label is a description; anonymousAccess=restricted is what the download does
        String result = apply("ccmm.xsl").to(resource(FLAG));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:terms_of_use/ccmm:access_rights/ccmm:iri",
            equalTo("http://purl.org/coar/access_right/c_16ec"))));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:terms_of_use/ccmm:access_rights/ccmm:label",
            equalTo("restricted access"))));
    }

    @Test
    public void ccmmEmbargoedAnonymousAccessMapsToTheCoarEmbargoTerm() throws Exception {
        String result = apply("ccmm.xsl").to(resource(EMBARGO));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:terms_of_use/ccmm:access_rights/ccmm:iri",
            equalTo("http://purl.org/coar/access_right/c_f1cf"))));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:terms_of_use/ccmm:access_rights/ccmm:label",
            equalTo("embargoes access"))));
    }

    @Test
    public void ccmmSubjectKeepsTheSchemaElementOrder() throws Exception {
        // subject is a sequence: iri, title+, definition*, classification_code?, subject_scheme?
        String result = apply("ccmm.xsl").to(resource(MAIN));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:subject/ccmm:iri[preceding-sibling::ccmm:title])", equalTo("0"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:subject/ccmm:subject_scheme[following-sibling::ccmm:classification_code])",
            equalTo("0"))));
    }

    @Test
    public void ccmmDownloadUrlIsTheByHandleEndpoint() throws Exception {
        // the file is addressed by the item's Handle and its name, the same endpoint the CMDI
        // crosswalk publishes, so the link outlives the bitstream's uuid
        String result = apply("ccmm.xsl").to(resource(MAIN));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:distribution_downloadable_file/ccmm:download_url/ccmm:iri",
            equalTo(TEST_SERVER_URL + "/api/core/bitstreams/handle/123456789/1-5678/corpus.txt"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:download_url/ccmm:iri[contains(., '/bitstream/')])", equalTo("0"))));
    }

    @Test
    public void ccmmDownloadUrlDoesNotDependOnTheBitstreamUuid() throws Exception {
        // this bitstream carries no usable uuid; the Handle and the name are all the endpoint
        // needs, so the link is built anyway
        String result = apply("ccmm.xsl").to(resource(TOOL));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:distribution_downloadable_file[ccmm:title='exampletagger.zip']"
                + "/ccmm:download_url/ccmm:iri",
            equalTo(TEST_SERVER_URL + "/api/core/bitstreams/handle/123456789/1-2000/exampletagger.zip"))));
    }

    @Test
    public void ccmmDatasetCarriesItsOwnResolvableIri() throws Exception {
        // dataset/iri is optional in the XSD but it is the only resolvable identity the
        // published record has
        String result = apply("ccmm.xsl").to(resource(MAIN));
        assertThat(result, is(ccmm().withXPath(
            "/ccmm:dataset/ccmm:iri", equalTo("http://hdl.handle.net/123456789/1-5678"))));
    }

    @Test
    public void ccmmDatasetIriFallsBackToTheOthersHandle() throws Exception {
        // a record with no dc.identifier.uri still has a Handle, and that is its landing page
        String result = apply("ccmm.xsl").to(resource(MINIMAL));
        assertThat(result, is(ccmm().withXPath(
            "/ccmm:dataset/ccmm:iri", equalTo("http://hdl.handle.net/99999/test-1"))));
    }

    @Test
    public void ccmmIdentifierValueIsTheNotationAndIriIsTheResolvableForm() throws Exception {
        // CCMM types value as skos:notation - the identifier WITHIN its scheme - and keeps the
        // resolvable form in iri, which is the split CCMM's own samples use
        String result = apply("ccmm.xsl").to(resource(MAIN));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:dataset/ccmm:identifier[ccmm:scheme/ccmm:label='Handle']/ccmm:value",
            equalTo("123456789/1-5678"))));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:dataset/ccmm:identifier[ccmm:scheme/ccmm:label='Handle']/ccmm:iri",
            equalTo("http://hdl.handle.net/123456789/1-5678"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:dataset/ccmm:identifier/ccmm:value[starts-with(., 'http')"
                + " and ../ccmm:scheme/ccmm:label='Handle'])",
            equalTo("0"))));
    }

    @Test
    public void ccmmMandatoryLicenceIsNeverAnEmptyElement() throws Exception {
        // license is 1..1; a record that states no rights gets the standard statement for
        // exactly that case rather than a void element
        String result = apply("ccmm.xsl").to(resource(STOCK));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:terms_of_use/ccmm:license/ccmm:iri",
            equalTo("http://rightsstatements.org/vocab/UND/1.0/"))));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:terms_of_use/ccmm:license/ccmm:label", equalTo("Copyright Undetermined"))));
    }

    @Test
    public void ccmmLanguageCarriesTheRegisterIriAndNoInventedLabel() throws Exception {
        // the scope note asks for the register's label "and/or" its code; DSpace's own string is
        // not the register's, so only the IRI is published
        String result = apply("ccmm.xsl").to(resource(MAIN));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:primary_language/ccmm:iri",
            equalTo("http://publications.europa.eu/resource/authority/language/CES"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:primary_language/ccmm:label)", equalTo("0"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:other_language/ccmm:label)", equalTo("0"))));
    }

    @Test
    public void ccmmUnregisteredMediaTypeGetsNoIanaIriButKeepsItsEuFormat() throws Exception {
        // RFC 6838 reserves the "x-" subtype prefix for types that are NOT registered, so an
        // IANA IRI for one would not resolve; the mandatory EU format carries it instead
        String result = apply("ccmm.xsl").to(resource(TOOL));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:distribution_downloadable_file[ccmm:title='models.tar.gz']/ccmm:format/ccmm:iri",
            equalTo("http://publications.europa.eu/resource/authority/file-type/GZIP"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:distribution_downloadable_file[ccmm:title='models.tar.gz']/ccmm:media_type)",
            equalTo("0"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:distribution_downloadable_file[ccmm:title='exampletagger.zip']/ccmm:media_type)",
            equalTo("1"))));
    }

    @Test
    public void ccmmPackedMultiValuePlaceBecomesOneLocationEach() throws Exception {
        // DSpace packs repeated values as "A||B"; that is two places, not one name
        String result = apply("ccmm.xsl").to(resource(CLIP));
        assertThat(result, is(ccmm().withXPath("count(//ccmm:location)", equalTo("2"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:location/ccmm:name[.='Germany'])", equalTo("1"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:location/ccmm:name[.='Austria'])", equalTo("1"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:location/ccmm:name[contains(., '||')])", equalTo("0"))));
    }

    @Test
    public void ccmmUnqualifiedRelationUriHasNoRelationType() throws Exception {
        // dc.relation.uri asserts a relation without saying which, and the CCMM register has no
        // term for that ("RelationType/Other" does not exist); resource_relation_type is optional
        String result = apply("ccmm.xsl").to(resource(CLIP));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:related_resource[ccmm:iri='http://hdl.handle.net/123456789/1-7001'])",
            equalTo("1"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:related_resource[ccmm:iri='http://hdl.handle.net/123456789/1-7001']"
                + "/ccmm:resource_relation_type)", equalTo("0"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:resource_relation_type/ccmm:iri[contains(., 'RelationType/Other')])",
            equalTo("0"))));
    }

    @Test
    public void ccmmAlternateTitleAlwaysStatesWhatKindItIs() throws Exception {
        // dc.title.alternative says it is a translation; a plain second dc.title only says the
        // resource has another name, which the register calls AlternativeTitle
        String result = apply("ccmm.xsl").to(resource(MAIN));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:alternate_title[not(ccmm:alternate_title_type)])", equalTo("0"))));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:alternate_title[ccmm:title='CND 2.0']/ccmm:alternate_title_type/ccmm:iri",
            equalTo("https://vocabs.ccmm.cz/registry/codelist/AlternateTitle/TranslatedTitle"))));
    }

    @Test
    public void ccmmSecondPlainTitleBecomesAnAlternativeTitle() throws Exception {
        // the two titles sit in different XOAI language wrappers, which is how DSpace stores
        // them whenever they differ in language
        String result = apply("ccmm.xsl").to(resource(TOOL));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:alternate_title[ccmm:title='ExampleTagger']/ccmm:alternate_title_type/ccmm:iri",
            equalTo("https://vocabs.ccmm.cz/registry/codelist/AlternateTitle/AlternativeTitle"))));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:alternate_title[ccmm:title='ExampleTagger']/ccmm:alternate_title_type/ccmm:label",
            equalTo("Alternative Title"))));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:dataset/ccmm:title", equalTo("ExampleTagger Morphological Tagger"))));
    }

    @Test
    public void ccmmDoiWrittenAsAUrlStillYieldsTheBareDoiAsItsNotation() throws Exception {
        String result = apply("ccmm.xsl").to(resource(TOOL));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:dataset/ccmm:identifier[ccmm:scheme/ccmm:label='DOI']/ccmm:value",
            equalTo("10.5555/exampletagger"))));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:dataset/ccmm:identifier[ccmm:scheme/ccmm:label='DOI']/ccmm:iri",
            equalTo("https://doi.org/10.5555/exampletagger"))));
    }

    @Test
    public void ccmmAffiliationInTheEmailSlotIsNotPublishedAsAnEmail() throws Exception {
        // local.contact.person is positional "Given;Family;email;affiliation"; a record that
        // omits the email shifts the affiliation into its place
        String result = apply("ccmm.xsl").to(resource(TOOL));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:contact_point/ccmm:person/ccmm:name", equalTo("Benesova, Lucie"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:contact_point/ccmm:person/ccmm:contact_point/ccmm:email)",
            equalTo("0"))));
    }

    /** ENUM carries local.additional.metadata and no local.contact.person; add a migrated contact. */
    private String withMigratedContact(String contact) throws Exception {
        return apply("ccmm.xsl").to(withReplaced(ENUM, "Nid:1",
            "Nid:1\nContact Person (field_contact_person):" + contact));
    }

    private static final String MIGRATED_PERSON = "//ccmm:terms_of_use/ccmm:contact_point/ccmm:person";

    @Test
    public void ccmmMigratedContactOfTwoPeopleIsTwoPersons() throws Exception {
        // a ccmm:person is one physical person
        String result = withMigratedContact("Pavel Cerny; Jana Kralova");
        assertThat(result, is(ccmm().withXPath("count(" + MIGRATED_PERSON + ")", equalTo("2"))));
        assertThat(result, is(ccmm().withXPath("(" + MIGRATED_PERSON + ")[1]/ccmm:name", equalTo("Pavel Cerny"))));
        assertThat(result, is(ccmm().withXPath("(" + MIGRATED_PERSON + ")[2]/ccmm:name", equalTo("Jana Kralova"))));
    }

    @Test
    public void ccmmSemicolonInsideANoteDoesNotSplitTheContact() throws Exception {
        String result = withMigratedContact("Prof. Dr. Karel Ukázka (licensing); "
            + "Prof. Dr. Anna Vzorová (licensing; project history)");
        assertThat(result, is(ccmm().withXPath("count(" + MIGRATED_PERSON + ")", equalTo("2"))));
        assertThat(result, is(ccmm().withXPath(
            "(" + MIGRATED_PERSON + ")[1]/ccmm:name", equalTo("Prof. Dr. Karel Ukázka"))));
        assertThat(result, is(ccmm().withXPath(
            "(" + MIGRATED_PERSON + ")[2]/ccmm:name", equalTo("Prof. Dr. Anna Vzorová"))));
    }

    @Test
    public void ccmmAddressAfterTheSemicolonBelongsToThePersonBeforeIt() throws Exception {
        String result = withMigratedContact("Jan Novák; jan.novak@example.org");
        assertThat(result, is(ccmm().withXPath("count(" + MIGRATED_PERSON + ")", equalTo("1"))));
        assertThat(result, is(ccmm().withXPath(MIGRATED_PERSON + "/ccmm:name", equalTo("Jan Novák"))));
        assertThat(result, is(ccmm().withXPath(
            MIGRATED_PERSON + "/ccmm:contact_point/ccmm:email", equalTo("jan.novak@example.org"))));
    }

    @Test
    public void ccmmAddressAfterASpaceIsNotPartOfTheName() throws Exception {
        String result = withMigratedContact("Eva Svobodová eva.svobodova@example.org");
        assertThat(result, is(ccmm().withXPath(MIGRATED_PERSON + "/ccmm:name", equalTo("Eva Svobodová"))));
        assertThat(result, is(ccmm().withXPath(
            MIGRATED_PERSON + "/ccmm:contact_point/ccmm:email", equalTo("eva.svobodova@example.org"))));
    }

    @Test
    public void ccmmBareMigratedAddressIsStillAContact() throws Exception {
        // nothing but an address: kept, rather than dropping the only way to reach anyone
        String result = withMigratedContact("office@example.org");
        assertThat(result, is(ccmm().withXPath("count(" + MIGRATED_PERSON + ")", equalTo("1"))));
        assertThat(result, is(ccmm().withXPath(
            MIGRATED_PERSON + "/ccmm:contact_point/ccmm:email", equalTo("office@example.org"))));
    }

    @Test
    public void ccmmSameValueUnderTwoLanguageWrappersIsOneElement() throws Exception {
        // DSpace stores one value under several wrappers; two byte-identical siblings are one
        // value in the CCMM model and would be displayed twice by any consumer
        String result = apply("ccmm.xsl").to(resource(TOOL));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:dataset/ccmm:subject/ccmm:title[.='tagger'])", equalTo("1"))));
        // of the "none" and "en_US" copies, the one whose language is known is kept
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:dataset/ccmm:subject/ccmm:title[.='tagger']/@xml:lang", equalTo("en"))));
    }

    @Test
    public void ccmmSameValueInTwoKnownLanguagesStaysTwoElements() throws Exception {
        // "korpus" tagged cs and a German "korpus" are two statements, not one
        String result = apply("ccmm.xsl").to(withReplaced(MAIN, ">Korpus<", ">korpus<"));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:dataset/ccmm:subject/ccmm:title[.='korpus'])", equalTo("2"))));
    }

    @Test
    public void ccmmUrlFollowedByProseIsATitleNotAnIdentifier() throws Exception {
        String result = apply("ccmm.xsl").to(resource(TOOL));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:related_resource/ccmm:iri[contains(., ' ')])", equalTo("0"))));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:related_resource/ccmm:title",
            equalTo("https://tools.example.org/exampletagger Project page"))));
    }

    @Test
    public void ccmmAccessUrlIsTheLandingPageNotTheDownloadRoute() throws Exception {
        // CCMM: access URL "shall be the web page (not a document file)"; the bitstream belongs
        // in download_url
        String result = apply("ccmm.xsl").to(resource(MAIN));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:distribution_downloadable_file/ccmm:access_url/ccmm:iri",
            equalTo("http://hdl.handle.net/123456789/1-5678"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:access_url/ccmm:iri[contains(., '/bitstream/')])", equalTo("0"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:access_url/ccmm:iri[. = //ccmm:download_url/ccmm:iri])", equalTo("0"))));
    }

    @Test
    public void ccmmQuickTimeVideoMapsToTheMovEuFileType() throws Exception {
        String result = apply("ccmm.xsl").to(resource(CLIP));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:distribution_downloadable_file/ccmm:format/ccmm:iri",
            equalTo("http://publications.europa.eu/resource/authority/file-type/MOV"))));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:distribution_downloadable_file/ccmm:media_type/ccmm:label",
            equalTo("video/quicktime"))));
    }

    @Test
    public void ccmmZipArchiveMapsToTheZipEuFileType() throws Exception {
        String result = apply("ccmm.xsl").to(resource(TOOL));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:distribution_downloadable_file/ccmm:format/ccmm:iri",
            equalTo("http://publications.europa.eu/resource/authority/file-type/ZIP"))));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:distribution_downloadable_file/ccmm:format/ccmm:label", equalTo("ZIP"))));
    }

    @Test
    public void ccmmAlwaysEmitsAtLeastOneIdentifier() throws Exception {
        // identifier is 1..unbounded and this record carries none of the usual sources
        String result = apply("ccmm.xsl").to(resource(BARE));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:dataset/ccmm:identifier)", equalTo("1"))));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:dataset/ccmm:identifier/ccmm:value",
            equalTo("oai:repo.example.org:no-handle-1"))));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:dataset/ccmm:identifier/ccmm:scheme/ccmm:iri",
            equalTo("https://www.iana.org/assignments/uri-schemes"))));
    }

    @Test
    public void ccmmAwardTitleAndFunderComeFromLocalSponsor() throws Exception {
        // the grant number in the info: URI is looked up in local.sponsor, which holds the
        // project name and the funder's own name
        String result = apply("ccmm.xsl").to(resource(FUNDING));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:funding_reference[ccmm:local_identifier='900001']/ccmm:award_title",
            equalTo("ExampleAlpha"))));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:funding_reference[ccmm:local_identifier='900001']/ccmm:funder/ccmm:organization/ccmm:name",
            equalTo("Example Funding Agency"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:funding_reference[ccmm:local_identifier][not(ccmm:award_title)])",
            equalTo("0"))));
    }

    @Test
    public void ccmmOneGrantRecordedTwiceIsPublishedOnce() throws Exception {
        // dc.relation spells it 900001 and local.sponsor spells it FP7-TEST-0000-0-900001
        String result = apply("ccmm.xsl").to(resource(FUNDING));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:funding_reference[ccmm:award_title='ExampleAlpha'])", equalTo("1"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:funding_reference)", equalTo("2"))));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:funding_reference[ccmm:local_identifier='GA00-00002S']/ccmm:award_title",
            equalTo("A separately funded project"))));
    }

    @Test
    public void ccmmDescriptionsDifferingOnlyInWhitespaceAreOneDescription() throws Exception {
        String result = apply("ccmm.xsl").to(resource(FUNDING));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:description[starts-with(ccmm:description_text, 'The source stores')])",
            equalTo("1"))));
    }

    @Test
    public void ccmmEndpointUrlIsBuiltEvenWhenXoaiCouldNotBuildOne() throws Exception {
        // XOAI emitted a bare file name for this bitstream, but the Handle and the name are all
        // the endpoint needs, so the file is published rather than dropped
        String result = apply("ccmm.xsl").to(resource(FUNDING));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:distribution_downloadable_file)", equalTo("3"))));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:distribution_downloadable_file[ccmm:byte_size='128']/ccmm:download_url/ccmm:iri",
            equalTo(TEST_SERVER_URL + "/api/core/bitstreams/handle/"
                + "123456789/1-9001/zpr%C3%A1va%20fin%C3%A1ln%C3%AD.txt"))));
    }

    @Test
    public void ccmmFileNameInTheEndpointUrlIsPercentEncoded() throws Exception {
        // a space or a diacritic in a file name has to survive as %20 and %C3%A1, or the endpoint
        // is handed a URL it cannot parse
        String result = apply("ccmm.xsl").to(resource(FUNDING));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:download_url/ccmm:iri[contains(., ' ')])", equalTo("0"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:download_url/ccmm:iri[contains(., '%20') and contains(., '%C3%A1')])",
            equalTo("1"))));
    }

    @Test
    public void ccmmOriginalNameIsNeverPutInTheEndpointUrl() throws Exception {
        // the endpoint compares the bitstream name, so originalName cannot stand in for it: with
        // no name there is nothing to address the file by and the url XOAI supplied is kept
        String result = apply("ccmm.xsl").to(resource(FUNDING));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:distribution_downloadable_file[ccmm:byte_size='256']/ccmm:download_url/ccmm:iri",
            equalTo("https://example.org/repository/bitstream/123456789/1-9001/2/report-final.pdf"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:download_url/ccmm:iri[contains(., 'report-final.pdf') "
                + "and contains(., '/handle/')])", equalTo("0"))));
    }

    @Test
    public void ccmmDistributionTitleIsAFileNameNeverAUrl() throws Exception {
        String result = apply("ccmm.xsl").to(resource(FUNDING));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:distribution_downloadable_file[ccmm:byte_size='128']/ccmm:title",
            equalTo("zpr\u00e1va fin\u00e1ln\u00ed.txt"))));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:distribution_downloadable_file[ccmm:byte_size='256']/ccmm:title",
            equalTo("report-final.pdf"))));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:distribution_downloadable_file[ccmm:byte_size='512']/ccmm:title",
            equalTo("data-table.csv"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:distribution_downloadable_file/ccmm:title[contains(., '://')])",
            equalTo("0"))));
    }

    @Test
    public void ccmmServerUrlThatIsNotAbsoluteBuildsNoDownloadUrl() throws Exception {
        // guessing a route from "localhost:8080" would put an unresolvable IRI in a
        // mandatory slot, so the url XOAI supplied is kept instead
        String result = apply("ccmm.xsl").withProperty("dspace.server.url", "localhost:8080/server")
            .to(resource(BAD_REPO));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:distribution_downloadable_file/ccmm:download_url/ccmm:iri",
            equalTo("https://files.example.org/data.csv"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:iri[starts-with(., 'localhost')])", equalTo("0"))));
    }

    @Test
    public void ccmmUnsetServerUrlBuildsNoDownloadUrl() throws Exception {
        String result = apply("ccmm.xsl").withProperty("dspace.server.url", null).to(resource(MAIN));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:download_url/ccmm:iri[contains(., '/api/core/bitstreams/handle/')])",
            equalTo("0"))));
    }

    @Test
    public void ccmmDownloadUrlUsesTheConfiguredServerNotTheUiUrl() throws Exception {
        // the fixture's repository url is the UI on repository.example.org; the backend is configured
        // on another host, as lindat_cmdi.xsl reads it, and the UI url plus "/server" is not it
        String result = apply("ccmm.xsl").to(resource(MAIN));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:download_url/ccmm:iri[starts-with(., '" + TEST_SERVER_URL + "/api/')])",
            equalTo("1"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:download_url/ccmm:iri[contains(., 'repository.example.org/repository/server')])",
            equalTo("0"))));
    }

    @Test
    public void ccmmTrailingSlashOnTheServerUrlIsNotDoubled() throws Exception {
        String result = apply("ccmm.xsl").withProperty("dspace.server.url", TEST_SERVER_URL + "/")
            .to(resource(MAIN));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:distribution_downloadable_file/ccmm:download_url/ccmm:iri",
            equalTo(TEST_SERVER_URL + "/api/core/bitstreams/handle/123456789/1-5678/corpus.txt"))));
    }

    /**
     * The fixture with others/anonymousAccess set to the given value, or removed when it is null, so
     * one record can be checked against every value ItemUtils may compute for it.
     */
    private InputStream withAnonymousAccess(String fixture, String value) throws Exception {
        String xml = IOUtils.toString(resource(fixture), StandardCharsets.UTF_8)
            .replaceAll("\\s*<field name=\"anonymousAccess\">[^<]*</field>", "");
        if (value != null) {
            xml = xml.replaceFirst("<element name=\"others\">",
                "<element name=\"others\"><field name=\"anonymousAccess\">" + value + "</field>");
        }
        return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
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

    // ---- Findings of a real-data validation run ----

    @Test
    public void ccmmRefboxFormatStringIsNotPublished() throws Exception {
        // local.refbox.format is a template for the citation box, "{title}, ..." with nothing filled in
        String result = ccmmOf(add(fixture(MAIN), "local",
            field("refbox.format", "none", "{title}, {publisher}, {repository}, {pid}.")));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:description_text[contains(., '{')])", equalTo("0"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:description_text[starts-with(., 'How to cite')])", equalTo("0"))));
    }

    @Test
    public void ccmmMetashareGrantsAreLeftOutWhenLocalSponsorHoldsTheGrants() throws Exception {
        // local.sponsor carries the grants in structured form; the META-SHARE tree repeats them
        // with a position mark ("#1-") and a funding category where the funder belongs
        // FUNDING has no metashare element; the tree goes in front of local
        String result = ccmmOf(once(fixture(FUNDING), "<element name=\"local\">",
            "<element name=\"metashare\">" + metashareProjects(
                new String[] {"#1-Example infrastructure project (LM0000001)"}, new String[] {"#1-euFunds"})
                + "</element><element name=\"local\">"));
        assertThat(result, is(ccmm().withXPath("count(//ccmm:funding_reference)",
            equalTo(String.valueOf(FUNDING_GRANTS)))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:funding_reference[contains(., 'LM0000001') or contains(., 'euFunds')])",
            equalTo("0"))));
    }

    @Test
    public void ccmmMetashareProjectNameIsPublishedWithoutThePositionMark() throws Exception {
        // "#1-" is how the migrated tree numbers repeated values, not part of the name; a value
        // that is wrong in itself ("N/A") is published as stored
        String result = ccmmOf(add(fixture(MAIN), "metashare", metashareProjects(
            new String[] {"#1-Example Project Gamma", "#2-N/A"}, new String[] {"#1-euFunds", "#2-ownFunds"})));
        for (String title : new String[] {"Example Project Gamma", "N/A"}) {
            assertThat(title, result, is(ccmm().withXPath(
                "count(//ccmm:funding_reference[ccmm:award_title='" + title + "'])", equalTo("1"))));
        }
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:funding_reference[contains(., '#1-') or contains(., '#2-')])", equalTo("0"))));
    }

    @Test
    public void ccmmFundingCategoryIsNotAFunderName() throws Exception {
        // "euFunds" says which kind of money it was, not who gave it; the name is unknown
        String result = ccmmOf(add(fixture(MAIN), "metashare", metashareProjects(
            new String[] {"Example Project Delta"}, new String[] {"euFunds"})));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:funding_reference[ccmm:award_title='Example Project Delta']"
                + "/ccmm:funder/ccmm:organization/ccmm:name",
            equalTo(":unkn"))));
    }

    @Test
    public void ccmmPlaceholderSponsorPartsArePublishedAsStored() throws Exception {
        // "-", "None", "unknown" in local.sponsor are data errors the repository fixes
        String result = ccmmOf(add(fixture(FUNDING), "local", field("sponsor", "none",
            "nationalFunds;-;Some Agency;A project without a number;", "ownFunds;unknown;None;unknown;")));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:funding_reference[ccmm:local_identifier='-'][ccmm:award_title='A project without a number']"
                + "[ccmm:funder/ccmm:organization/ccmm:name='Some Agency'])", equalTo("1"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:funding_reference[ccmm:local_identifier='unknown'][ccmm:award_title='unknown']"
                + "[ccmm:funder/ccmm:organization/ccmm:name='None'])", equalTo("1"))));
        assertThat(result, is(ccmm().withXPath("count(//ccmm:funding_reference)",
            equalTo(String.valueOf(FUNDING_GRANTS + 2)))));
    }

    @Test
    public void ccmmSponsorKnownOnlyByItsFunderIsAGrantOfThatFunder() throws Exception {
        String result = ccmmOf(add(fixture(FUNDING), "local", field("sponsor", "none", ";;Example Lexicon Ltd.;;")));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:funding_reference[ccmm:funder/ccmm:organization/ccmm:name='Example Lexicon Ltd.']"
                + "[not(ccmm:local_identifier)][not(ccmm:award_title)])", equalTo("1"))));
    }

    @Test
    public void ccmmSponsorFundingTypeIsNeverTheFunder() throws Exception {
        // local.sponsor is "type;code;funder;project;uri": the first part is a category like the
        // META-SHARE fundingType, so a missing funder name stays unknown
        String result = ccmmOf(add(fixture(FUNDING), "local", field("sponsor", "none",
            "ownFunds;GA99-00001S;;Self-funded project;")));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:funding_reference[ccmm:local_identifier='GA99-00001S']/ccmm:funder/ccmm:organization/ccmm:name",
            equalTo(":unkn"))));
    }

    @Test
    public void ccmmPlaceholderSponsorStillStandsForTheMetashareGrants() throws Exception {
        // "ownFunds;unknown;;;" is a local.sponsor grant like any other, so the META-SHARE tree is not used
        String result = ccmmOf(add(add(fixture(MAIN), "metashare", metashareProjects(
                new String[] {"Example Project Delta"}, new String[] {"euFunds"})),
            "local", field("sponsor", "none", "ownFunds;unknown;;;")));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:funding_reference[ccmm:award_title='Example Project Delta'])", equalTo("0"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:funding_reference[ccmm:local_identifier='unknown'])", equalTo("1"))));
    }

    @Test
    public void ccmmInfoEuRepoGrantTakesTheSponsorPartsAsStored() throws Exception {
        String result = ccmmOf(once(fixture(FUNDING),
            "euFunds;FP7-TEST-0000-0-900001;Example Funding Agency;ExampleAlpha;",
            "euFunds;FP7-TEST-0000-0-900001;None;unknown;"));
        String grant = "//ccmm:funding_reference[ccmm:local_identifier='900001']";
        assertThat(result, is(ccmm().withXPath(grant + "/ccmm:funder/ccmm:organization/ccmm:name", equalTo("None"))));
        assertThat(result, is(ccmm().withXPath(grant + "/ccmm:award_title", equalTo("unknown"))));
    }

    @Test
    public void ccmmNoTextHasAnEmptyLanguage() throws Exception {
        // CCMM wants language-tagged text; an unknown language is "und", never ""
        String result = apply("ccmm.xsl").to(resource(MAIN));
        assertThat(result, is(ccmm().withXPath("count(//*[@xml:lang=''])", equalTo("0"))));
        assertThat(result, is(ccmm().withXPath("count(//*[@xml:lang='und']) > 0", equalTo("true"))));
    }

    @Test
    public void ccmmCrosswalkInternalsNeverReachTheOutput() throws Exception {
        // MAIN has dc.source.uri and local.demo.uri, the related resources marked internally
        String result = apply("ccmm.xsl").to(resource(MAIN));
        assertThat(result.contains("urn:dspace:oai:ccmm-crosswalk"), is(false));
        assertThat(result, is(ccmm().withXPath("count(//@*[local-name()='assumed'])", equalTo("0"))));
    }

    @Test
    public void ccmmSourceUriAlsoRelatedExplicitlyIsOneRelatedResource() throws Exception {
        // dc.source.uri carries no relation of its own; an explicit dc.relation.* for the same
        // resource says which relation it is
        String result = ccmmOf(add(fixture(MAIN), "dc",
            field("relation.isreferencedby", "none", SOURCE_URI)));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:related_resource[ccmm:iri='" + SOURCE_URI + "'])", equalTo("1"))));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:related_resource[ccmm:iri='" + SOURCE_URI + "']/ccmm:resource_relation_type/ccmm:iri",
            equalTo("https://vocabs.ccmm.cz/registry/codelist/RelationType/IsReferencedBy"))));
    }

    @Test
    public void ccmmSourceUriAlsoListedAsAPublicationIsOneRelatedResource() throws Exception {
        String result = ccmmOf(add(fixture(MAIN), "local", field("additional.metadata", "none",
            "Publications (field_publications):" + SOURCE_URI)));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:related_resource[ccmm:iri='" + SOURCE_URI + "'])", equalTo("1"))));
        assertThat(result, is(ccmm().withXPath(relationTypeOf(SOURCE_URI), equalTo(RELATION + "IsReferencedBy"))));
    }

    @Test
    public void ccmmSourceUriAlsoCitedIsOneRelatedResource() throws Exception {
        String result = ccmmOf(add(fixture(MAIN), "dc", field("identifier.citation", "none", SOURCE_URI)));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:related_resource[ccmm:iri='" + SOURCE_URI + "'])", equalTo("1"))));
        assertThat(result, is(ccmm().withXPath(relationTypeOf(SOURCE_URI), equalTo(RELATION + "IsReferencedBy"))));
    }

    @Test
    public void ccmmDemoUriAlsoRelatedExplicitlyIsOneRelatedResource() throws Exception {
        String demo = "https://example.org/demo";
        String result = ccmmOf(add(fixture(MAIN), "dc", field("relation.ispartof", "none", demo)));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:related_resource[ccmm:iri='" + demo + "'])", equalTo("1"))));
        assertThat(result, is(ccmm().withXPath(relationTypeOf(demo), equalTo(RELATION + "IsPartOf"))));
    }

    @Test
    public void ccmmUntypedRelationDoesNotTakeTheTypeAwayFromSourceUri() throws Exception {
        // dc.relation.uri states no relation; the source.uri reference is the only type there is
        String result = ccmmOf(add(fixture(MAIN), "dc", field("relation.uri", "none", SOURCE_URI)));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:related_resource[ccmm:iri='" + SOURCE_URI + "'])", equalTo("1"))));
        assertThat(result, is(ccmm().withXPath(relationTypeOf(SOURCE_URI), equalTo(RELATION + "References"))));
    }

    @Test
    public void ccmmTypedRelationWinsOverAnUntypedOneToTheSameResource() throws Exception {
        String result = ccmmOf(add(fixture(MAIN), "dc", field("relation.uri", "none", REPLACED)));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:related_resource[ccmm:iri='" + REPLACED + "'])", equalTo("1"))));
        assertThat(result, is(ccmm().withXPath(relationTypeOf(REPLACED), equalTo(RELATION + "Obsoletes"))));
    }

    @Test
    public void ccmmSourceUriAloneStillReferencesTheResource() throws Exception {
        String result = apply("ccmm.xsl").to(resource(MAIN));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:related_resource[ccmm:iri='" + SOURCE_URI + "']/ccmm:resource_relation_type/ccmm:iri",
            equalTo("https://vocabs.ccmm.cz/registry/codelist/RelationType/References"))));
    }

    @Test
    public void ccmmTwoStatedRelationsToOneResourceAreBothPublished() throws Exception {
        // the record states isbasedon and replaces for one Handle: both are published as stated,
        // choosing one would be correcting the record
        String result = ccmmOf(add(fixture(MAIN), "dc", field("relation.isbasedon", "none", REPLACED)));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:related_resource[ccmm:iri='" + REPLACED + "'])", equalTo("2"))));
        for (String type : new String[] {"Obsoletes", "IsDerivedFrom"}) {
            assertThat(type, result, is(ccmm().withXPath(
                "count(//ccmm:related_resource[ccmm:iri='" + REPLACED + "']"
                    + "[ccmm:resource_relation_type/ccmm:iri='" + RELATION + type + "'])", equalTo("1"))));
        }
    }

    @Test
    public void ccmmDemoUriThatIsAFeaturedServiceIsOnlyADistribution() throws Exception {
        // CCMM: distributions of the dataset SHALL NOT be modeled as related resource
        String demo = "https://example.org/demo";
        String result = ccmmOf(add(fixture(MAIN), "local", field("featuredService.kontext", "none", "Search|" + demo)));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:distribution_data_service/ccmm:access_service[ccmm:iri='" + demo + "'])", equalTo("1"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:related_resource[ccmm:iri='" + demo + "'])", equalTo("0"))));
    }

    @Test
    public void ccmmSourceUriThatIsTheWebServiceIsOnlyADistribution() throws Exception {
        String result = ccmmOf(add(fixture(MAIN), "local", field("additional.metadata", "none",
            "Webservice link (field_tool_webservice_link):" + SOURCE_URI)));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:distribution_data_service/ccmm:access_service[ccmm:iri='" + SOURCE_URI + "'])",
            equalTo("1"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:related_resource[ccmm:iri='" + SOURCE_URI + "'])", equalTo("0"))));
    }

    @Test
    public void ccmmStatedRelationToADownloadableFileIsOnlyADistribution() throws Exception {
        Matcher download = Pattern.compile("<ccmm:download_url>\\s*<ccmm:iri>([^<]+)</ccmm:iri>")
            .matcher(ccmmOf(fixture(MAIN)));
        assertThat(download.find(), is(true));
        String file = download.group(1);
        String result = ccmmOf(add(fixture(MAIN), "dc", field("relation.haspart", "none", file)));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:download_url[ccmm:iri='" + file + "'])", equalTo("1"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:related_resource[ccmm:iri='" + file + "'])", equalTo("0"))));
    }

    @Test
    public void ccmmLandingPageAndDownloadUrlAreValidIris() throws Exception {
        // the Handle ends up in the dataset iri, access_url and download_url alike
        String xml = once(fixture(MAIN), "http://hdl.handle.net/123456789/1-5678<",
            "http://hdl.handle.net/123456789/1-5678|a<");
        xml = once(xml, "<field name=\"handle\">123456789/1-5678<", "<field name=\"handle\">123456789/1-5678|a<");
        String result = ccmmOf(xml);
        assertThat(result, is(ccmm().withXPath("count(//ccmm:iri[contains(., '|')])", equalTo("0"))));
        assertThat(result, is(ccmm().withXPath(
            "/ccmm:dataset/ccmm:iri", equalTo("http://hdl.handle.net/123456789/1-5678%7Ca"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:access_url/ccmm:iri[. = 'http://hdl.handle.net/123456789/1-5678%7Ca']) > 0",
            equalTo("true"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:download_url/ccmm:iri[contains(., '/123456789/1-5678%7Ca/')]) > 0", equalTo("true"))));
    }

    @Test
    public void ccmmOneRelationStatedTwiceIsOneRelatedResource() throws Exception {
        // the same publication in dc.relation.isreferencedby and in the migrated catalogue
        String paper = "https://doi.org/10.1234/example-paper";
        String result = ccmmOf(add(add(fixture(MAIN), "dc", field("relation.isreferencedby", "none", paper)),
            "local", field("additional.metadata", "none", "Publications (field_publications):" + paper)));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:related_resource[ccmm:iri='" + paper + "'])", equalTo("1"))));
    }

    @Test
    public void ccmmIssueYearIsPublishedAsStored() throws Exception {
        // "0194" is most likely a typo for 194x, but only the repository can correct it
        String result = ccmmOf(once(fixture(MAIN), "2025-03-20", "0194"));
        assertThat(result, is(ccmm().withXPath("//ccmm:dataset/ccmm:publication_year", equalTo("0194"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:date_information[starts-with(., 'Issue date unknown')])", equalTo("0"))));
    }

    @Test
    public void ccmmRoleContactIsPublishedAsStored() throws Exception {
        // "Technical;Contact;..." in the person field is a data error the repository fixes; the
        // value comes out as the person it claims to be
        String result = ccmmOf(once(fixture(MAIN), "Tomas;Kovar;kovar@example.org;Example University",
            "Technical;Contact;archive@example.org;Ukázkový filmový archiv"));
        assertThat(result, is(ccmm().withXPath(
            "//ccmm:terms_of_use/ccmm:contact_point/ccmm:person/ccmm:name", equalTo("Contact, Technical"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:terms_of_use/ccmm:contact_point/ccmm:organization[ccmm:name='Ukázkový filmový archiv'])",
            equalTo("0"))));
    }

    @Test
    public void ccmmCharacterAUriForbidsIsEscapedInTheIri() throws Exception {
        String badUrl = "https://catalogue.example.org/search?q=r:(19|2)&amp;type=thesaurus";
        String escaped = "https://catalogue.example.org/search?q=r:(19%7C2)&type=thesaurus";
        String result = ccmmOf(once(fixture(MAIN), SOURCE_URI, badUrl));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:related_resource[ccmm:iri='" + escaped + "'][ccmm:resource_url='" + escaped + "'])",
            equalTo("1"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:iri[contains(., '|')] | //ccmm:resource_url[contains(., '|')])", equalTo("0"))));
    }

    @Test
    public void ccmmEveryCharacterAUriForbidsIsEscapedAndNothingTwice() throws Exception {
        // the value is XML-escaped by field(); %7C is already encoded and stays as it is
        String raw = "https://example.org/a|b{c}d^e\\f`g<h>i\"j%7Ck";
        String escaped = "https://example.org/a%7Cb%7Bc%7Dd%5Ee%5Cf%60g%3Ch%3Ei%22j%7Ck";
        String result = ccmmOf(add(fixture(MAIN), "dc", field("relation.ispartof", "none", raw)));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:related_resource[ccmm:iri='" + escaped + "'][ccmm:resource_url='" + escaped + "'])",
            equalTo("1"))));
    }

    @Test
    public void ccmmLicenceIriIsEscapedToo() throws Exception {
        String result = ccmmOf(once(fixture(MAIN), "https://creativecommons.org/licenses/by/4.0/",
            "https://example.org/licence|1.html"));
        assertThat(result, is(ccmm().withXPath("//ccmm:terms_of_use/ccmm:license/ccmm:iri",
            equalTo("https://example.org/licence%7C1.html"))));
    }

    @Test
    public void ccmmMigratedServiceAndDocumentationLinksAreEscapedToo() throws Exception {
        String result = ccmmOf(add(fixture(MAIN), "local", field("additional.metadata", "none",
            "Webservice (field_tool_webservice_link):https://example.org/run?a=1|2\n"
                + "Documentation (field_tool_document_link):https://example.org/doc{1}")));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:iri[contains(., '|') or contains(., '{')]"
                + " | //ccmm:resource_url[contains(., '|') or contains(., '{')])", equalTo("0"))));
        assertThat(result, is(ccmm().withXPath(
            "count(//*[contains(., 'https://example.org/run?a=1%7C2')]) > 0", equalTo("true"))));
    }

    @Test
    public void ccmmBracesInAPublicationUrlAreEscapedInTheIri() throws Exception {
        String result = ccmmOf(add(fixture(MAIN), "local", field("additional.metadata", "none",
            "Publications (field_publications):https://example.org/paper{2}")));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:related_resource[ccmm:iri='https://example.org/paper%7B2%7D']"
                + "[ccmm:resource_url='https://example.org/paper%7B2%7D'])", equalTo("1"))));
    }

    @Test
    public void ccmmPlaceholderSubjectsArePublishedAsStored() throws Exception {
        // "?", "undefined", "-" ... are data errors the repository fixes, not values to hide
        String result = ccmmOf(add(fixture(MAIN), "dc", field("subject", "none", "?", "undefined", "-", "N/A")));
        for (String subject : new String[] {"?", "undefined", "-", "N/A"}) {
            assertThat(subject, result, is(ccmm().withXPath(
                "count(//ccmm:subject[ccmm:title='" + subject + "'])", equalTo("1"))));
        }
        result = ccmmOf(add(fixture(MAIN), "local", field("additional.metadata", "none",
            "Languages (other) (field_languages_other):undefined")));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:subject[ccmm:title='undefined'])", equalTo("1"))));
    }

    @Test
    public void ccmmLicenceIriIsPublishedAsStored() throws Exception {
        // "/XXX/" in place of the repository's address is a data error the repository fixes
        String result = ccmmOf(once(fixture(MAIN), "https://creativecommons.org/licenses/by/4.0/", XXX_LICENCE));
        assertThat(result, is(ccmm().withXPath("//ccmm:terms_of_use/ccmm:license/ccmm:iri",
            equalTo(XXX_LICENCE))));
    }

    @Test
    public void ccmmBlankLocalSponsorLeavesTheMetashareGrants() throws Exception {
        String result = ccmmOf(add(add(fixture(MAIN), "local", field("sponsor", "none", "   ")),
            "metashare", metashareProjects(new String[] {"Example Project Delta"}, new String[] {"euFunds"})));
        assertThat(result, is(ccmm().withXPath(
            "count(//ccmm:funding_reference[ccmm:award_title='Example Project Delta'])", equalTo("1"))));
    }

    @Test
    public void ccmmFundingFixtureHasTwoGrants() throws Exception {
        // the baseline the funding tests compare against
        assertThat(apply("ccmm.xsl").to(resource(FUNDING)), is(ccmm().withXPath(
            "count(//ccmm:funding_reference)", equalTo(String.valueOf(FUNDING_GRANTS)))));
    }

    private static final String RELATION = "https://vocabs.ccmm.cz/registry/codelist/RelationType/";
    private static final String XXX_LICENCE = "http://copy.example.org:85/XXX/static/licence-example-1.0.html";
    /** funding_reference elements FUNDING yields on its own */
    private static final int FUNDING_GRANTS = 2;

    private static String relationTypeOf(String iri) {
        return "//ccmm:related_resource[ccmm:iri='" + iri + "']/ccmm:resource_relation_type/ccmm:iri";
    }
    /** dc.source.uri of MAIN */
    private static final String SOURCE_URI = "https://example.org/czech-nlp-dataset";
    /** dc.relation.replaces of MAIN */
    private static final String REPLACED = "http://hdl.handle.net/123456789/1-1111";

    /** A fixture as text, to edit with {@link #once} or {@link #add} and transform with {@link #ccmmOf}. */
    private String fixture(String name) throws Exception {
        return IOUtils.toString(resource(name), StandardCharsets.UTF_8);
    }

    private String ccmmOf(String xoai) throws Exception {
        return apply("ccmm.xsl").to(new ByteArrayInputStream(xoai.getBytes(StandardCharsets.UTF_8)));
    }

    /** text, which must occur exactly once in xml, replaced literally */
    private static String once(String xml, String text, String replacement) {
        int at = xml.indexOf(text);
        if (at < 0 || xml.indexOf(text, at + 1) >= 0) {
            throw new IllegalArgumentException("'" + text + "' does not occur exactly once");
        }
        return xml.substring(0, at) + replacement + xml.substring(at + text.length());
    }

    /** xml with the XOAI elements added as the first children of the element named parent */
    private static String add(String xml, String parent, String... elements) {
        String open = "<element name=\"" + parent + "\">";
        return once(xml, open, open + String.join("", elements));
    }

    /** One XOAI field: an element per dot-separated name, the language wrapper, then the values. */
    private static String field(String path, String language, String... values) {
        String[] names = path.split("\\.");
        StringBuilder xml = new StringBuilder();
        for (String name : names) {
            xml.append("<element name=\"").append(name).append("\">");
        }
        xml.append("<element name=\"").append(language).append("\">");
        for (String value : values) {
            xml.append("<field name=\"value\">")
                .append(value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))
                .append("</field>");
        }
        xml.append("</element>");
        xml.append("</element>".repeat(names.length));
        return xml.toString();
    }

    /** The migrated META-SHARE project tree, as the repository holds it. */
    private static String metashareProjects(String[] projectNames, String[] fundingTypes) {
        String base = "ResourceInfo#ResourceCreationInfo#FundingInfo#ProjectInfo";
        return "<element name=\"" + base + "\">"
            + field("projectName", "none", projectNames)
            + field("fundingType", "none", fundingTypes)
            + "</element>";
    }

    private XmlMatcherBuilder ccmm() {
        // XmlMatcherBuilder's NamespaceContext is a plain map, so the reserved "xml" prefix has
        // to be registered explicitly or JAXP cannot compile an @xml:lang step.
        return xml()
            .withNamespace("ccmm", CCMM_NS)
            .withNamespace("xml", XML_NS);
    }
}
