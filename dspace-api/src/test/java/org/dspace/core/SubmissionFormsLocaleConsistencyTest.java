/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Checks that structural (non-localized) properties are identical between
 * submission-forms.xml and EVERY submission-forms_&lt;locale&gt;.xml found
 * in the config directory (submission-forms_cs.xml, submission-forms_es.xml,
 * submission-forms_it.xml, ...), while ignoring properties that are
 * *expected* to differ because they hold translated text (label, hint,
 * description, placeholder).
 * <p>
 * Locale files are discovered automatically at test-collection time by
 * scanning CONFIG_DIR, so adding a new submission-forms_xx.xml file gets
 * covered without touching this test. Each locale runs as its own
 * JUnit Parameterized test case, so a failure in submission-forms_it.xml,
 * say, is reported separately from submission-forms_cs.xml rather than
 * being lumped into one giant failure.
 */
@RunWith(Parameterized.class)
public class SubmissionFormsLocaleConsistencyTest {

    private static final String CONFIG_DIR = "../dspace/config";
    private static final String BASE_FILE_NAME = "submission-forms.xml";

    // Matches submission-forms_<locale>.xml, e.g. submission-forms_cs.xml, submission-forms_pl.xml, etc.
    private static final Pattern LOCALE_FILE_PATTERN =
            Pattern.compile("^submission-forms_(.+)\\.xml$");

    /** {@code <field>} children whose *text* may legitimately differ between locales. */
    private static final Set<String> FIELD_LOCALIZED_TAGS = Set.of("label", "hint", "required");

    /** {@code <relation-field>} children whose *text* may legitimately differ between locales. */
    private static final Set<String> RELATION_FIELD_LOCALIZED_TAGS = Set.of("label", "hint");

    /** {@code <pair>} children whose *text* may legitimately differ between locales. */
    private static final Set<String> PAIR_LOCALIZED_TAGS = Set.of("displayed-value");

    /** {@code <input>} attributes (inside form-complex-definitions) that may differ. */
    private static final Map<String, Set<String>> INPUT_LOCALIZED_ATTRS =
            Map.of("input", Set.of("label", "hint", "placeholder"));

    private final String locale;
    private final File localeFile;

    private static Element originalRoot;
    private Element localizedRoot;

    @BeforeClass
    public static void checkBaseFileExists() {
        File baseFile = new File(CONFIG_DIR, BASE_FILE_NAME);
        if (!baseFile.isFile()) {
            throw new IllegalStateException(
                    "Base submission-forms.xml file not found under " + baseFile.getAbsolutePath()
                            + " - check CONFIG_DIR is correct and submission-forms.xml exists.");
        }
        try {
            Document baseDoc = parse(baseFile);
            originalRoot = baseDoc.getDocumentElement();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to parse base submission-forms.xml file under " + baseFile.getAbsolutePath()
                            + " - check CONFIG_DIR is correct and submission-forms.xml is valid XML.",
                    e);
        }
    }

    public SubmissionFormsLocaleConsistencyTest(String locale, File localeFile) {
        this.locale = locale;
        this.localeFile = localeFile;
    }

    /**
     * Discovers all submission-forms_<locale>.xml files in CONFIG_DIR.
     * Each becomes one parameterized test case: {locale, file}.
     */
    @Parameterized.Parameters(name = "locale={0}")
    public static Collection<Object[]> discoverLocaleFiles() {
        File dir = new File(CONFIG_DIR);
        File[] files = dir.listFiles();
        List<Object[]> params = new ArrayList<>();

        if (files != null) {
            for (File f : files) {
                if (!f.isFile()) {
                    continue;
                }
                Matcher m = LOCALE_FILE_PATTERN.matcher(f.getName());
                if (m.matches()) {
                    params.add(new Object[]{m.group(1), f});
                }
            }
        }

        // Sort by locale name so test output/order is stable and predictable
        params.sort(Comparator.comparing(p -> (String) p[0]));

        if (params.isEmpty()) {
            throw new IllegalStateException(
                    "No submission-forms_<locale>.xml files found under " + dir.getAbsolutePath()
                            + " - check CONFIG_DIR is correct and locale files exist.");
        }

        return params;
    }

    @Before
    public void setUp() {
        try {
            Document localeDoc = parse(localeFile);
            localizedRoot = localeDoc.getDocumentElement();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to parse locale submission-forms file " + localeFile.getAbsolutePath()
                            + " - check the file is valid XML.",
                    e);
        }
    }

    // ------------------------------------------------------------------
    // 1) same overall element vocabulary
    // ------------------------------------------------------------------
    @Test
    public void sameElementVocabulary() {
        Set<String> enTags = allTagNames(originalRoot);
        Set<String> localeTags = allTagNames(localizedRoot);
        Set<String> onlyOriginal = new TreeSet<>(enTags);
        onlyOriginal.removeAll(localeTags);
        Set<String> onlyLocalized = new TreeSet<>(localeTags);
        onlyLocalized.removeAll(enTags);

        assertEquals("element vocabulary differs\n"
                        + "  original only : " + onlyOriginal + "\n"
                        + "  localized only: " + onlyLocalized,
                enTags, localeTags);
    }

    // ------------------------------------------------------------------
    // 2) form-definitions -> form -> row -> field
    // ------------------------------------------------------------------
    @Test
    public void formDefinitionsConsistency() {
        Element enDefs = firstChildByTag(originalRoot, "form-definitions");
        Element localeDefs = firstChildByTag(localizedRoot, "form-definitions");
        assertNotNull("form-definitions missing in original", enDefs);
        assertNotNull("form-definitions missing in localized", localeDefs);

        Map<String, Element> enForms = byNameAttr(childElementsByTag(enDefs, "form"));
        Map<String, Element> localeForms = byNameAttr(childElementsByTag(localeDefs, "form"));
        assertEquals("form name mismatch", enForms.keySet(), localeForms.keySet());

        for (String name : enForms.keySet()) {
            Element enForm = enForms.get(name);
            Element localeForm = localeForms.get(name);

            List<Element> enRows = childElementsByTag(enForm, "row");
            List<Element> localeRows = childElementsByTag(localeForm, "row");
            assertEquals("form '" + name + "': row count mismatch", enRows.size(), localeRows.size());

            for (int r = 0; r < enRows.size(); r++) {
                List<Element> enFields = childElementsByTag(enRows.get(r), "field");
                List<Element> localeFields = childElementsByTag(localeRows.get(r), "field");
                assertEquals("form '" + name + "' row " + r + ": field count mismatch",
                        enFields.size(), localeFields.size());

                for (int f = 0; f < enFields.size(); f++) {
                    Element enField = enFields.get(f);
                    Element localeField = localeFields.get(f);
                    String fid = fieldIdentity(enField);
                    assertStructurallyEqual(enField, localeField,
                            "form[" + name + "]/row[" + r + "]/field[" + fid + "]",
                            FIELD_LOCALIZED_TAGS, Map.of());
                }

                List<Element> enRelationFields = childElementsByTag(enRows.get(r), "relation-field");
                List<Element> localeRelationFields = childElementsByTag(localeRows.get(r), "relation-field");
                assertEquals("form '" + name + "' row " + r + ": relation-field count mismatch",
                        enRelationFields.size(), localeRelationFields.size());

                for (int f = 0; f < enRelationFields.size(); f++) {
                    Element enRelationField = enRelationFields.get(f);
                    Element localeRelationField = localeRelationFields.get(f);
                    String fid = relationFieldIdentity(enRelationField);
                    assertStructurallyEqual(enRelationField, localeRelationField,
                            "form[" + name + "]/row[" + r + "]/relation-field[" + fid + "]",
                            RELATION_FIELD_LOCALIZED_TAGS, Map.of());
                }


            }
        }
    }

    // ------------------------------------------------------------------
    // 3) form-value-pairs -> value-pairs -> pair
    // ------------------------------------------------------------------
    @Test
    public void formValuePairsConsistency() {
        Element enVp = firstChildByTag(originalRoot, "form-value-pairs");
        Element localeVp = firstChildByTag(localizedRoot, "form-value-pairs");
        assertNotNull("form-value-pairs missing in original", enVp);
        assertNotNull("form-value-pairs missing in localized", localeVp);

        Map<String, Element> enGroups = byValuePairsKey(childElementsByTag(enVp, "value-pairs"));
        Map<String, Element> localeGroups = byValuePairsKey(childElementsByTag(localeVp, "value-pairs"));
        assertEquals("value-pairs name mismatch", enGroups.keySet(), localeGroups.keySet());

        for (String name : enGroups.keySet()) {
            Element enGroup = enGroups.get(name);
            Element localeGroup = localeGroups.get(name);

            List<Element> enPairs = childElementsByTag(enGroup, "pair");
            List<Element> localePairs = childElementsByTag(localeGroup, "pair");
            assertEquals("value-pairs '" + name + "': pair count mismatch",
                    enPairs.size(), localePairs.size());

            for (int p = 0; p < enPairs.size(); p++) {
                assertStructurallyEqual(enPairs.get(p), localePairs.get(p),
                        "value-pairs[" + name + "]/pair[" + p + "]",
                        PAIR_LOCALIZED_TAGS, Map.of());
            }
        }
    }

    // ------------------------------------------------------------------
    // 4) form-complex-definitions -> definition -> input
    // ------------------------------------------------------------------
    @Test
    public void formComplexDefinitionsConsistency() {
        Element enRoot = firstChildByTag(originalRoot, "form-complex-definitions");
        Element localeRoot = firstChildByTag(localizedRoot, "form-complex-definitions");

        Assume.assumeTrue("no form-complex-definitions section in either file",
                !(enRoot == null && localeRoot == null));
        assertNotNull("form-complex-definitions present only in localized file", enRoot);
        assertNotNull("form-complex-definitions present only in original file", localeRoot);

        Map<String, Element> enDefsMap = byNameAttr(childElementsByTag(enRoot, "definition"));
        Map<String, Element> localeDefsMap = byNameAttr(childElementsByTag(localeRoot, "definition"));
        assertEquals("definition name mismatch", enDefsMap.keySet(), localeDefsMap.keySet());

        for (String name : enDefsMap.keySet()) {
            Element enDef = enDefsMap.get(name);
            Element localeDef = localeDefsMap.get(name);

            List<Element> enInputs = childElementsByTag(enDef, "input");
            List<Element> localeInputs = childElementsByTag(localeDef, "input");
            assertEquals("definition '" + name + "': input count mismatch",
                    enInputs.size(), localeInputs.size());

            for (int i = 0; i < enInputs.size(); i++) {
                assertStructurallyEqual(enInputs.get(i), localeInputs.get(i),
                        "definition[" + name + "]/input[" + i + "]",
                        Set.of(), INPUT_LOCALIZED_ATTRS);
            }
        }
    }

    private static Document parse(File file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);

        // Allow DOCTYPE - submission-forms.xml legitimately uses one - but keep
        // external entity/DTD resolution locked down to avoid XXE exposure.
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(true);

        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(file);
    }

    // ------------------------------------------------------------------
    // generic DOM helpers
    // ------------------------------------------------------------------

    private static List<Element> childElements(Element parent) {
        List<Element> out = new ArrayList<>();
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                out.add((Element) n);
            }
        }
        return out;
    }

    private static List<Element> childElementsByTag(Element parent, String tag) {
        List<Element> out = new ArrayList<>();
        for (Element c : childElements(parent)) {
            if (c.getTagName().equals(tag)) {
                out.add(c);
            }
        }
        return out;
    }

    private static Element firstChildByTag(Element parent, String tag) {
        List<Element> matches = childElementsByTag(parent, tag);
        return matches.isEmpty() ? null : matches.get(0);
    }

    private static String text(Element e) {
        String t = e.getTextContent();
        return t == null ? "" : t.trim();
    }

    private static List<String> tagsOf(List<Element> elements) {
        List<String> out = new ArrayList<>();
        for (Element e : elements) {
            out.add(e.getTagName());
        }
        return out;
    }

    private static Set<String> allTagNames(Element root) {
        Set<String> tags = new HashSet<>();
        collectTags(root, tags);
        return tags;
    }

    private static void collectTags(Element e, Set<String> tags) {
        tags.add(e.getTagName());
        for (Element c : childElements(e)) {
            collectTags(c, tags);
        }
    }

    private static Map<String, String> attrMap(Element e, Set<String> ignore) {
        Map<String, String> m = new TreeMap<>();
        NamedNodeMap attrs = e.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Node a = attrs.item(i);
            if (!ignore.contains(a.getNodeName())) {
                m.put(a.getNodeName(), a.getNodeValue());
            }
        }
        return m;
    }

    private static String fieldIdentity(Element field) {
        List<String> parts = new ArrayList<>();
        for (String tag : new String[] {"dc-schema", "dc-element", "dc-qualifier"}) {
            Element c = firstChildByTag(field, tag);
            if (c != null && !text(c).isEmpty()) {
                parts.add(text(c));
            }
        }
        return parts.isEmpty() ? "<field>" : String.join(".", parts);
    }

    private static String relationFieldIdentity(Element field) {
        List<String> parts = new ArrayList<>();
        for (String tag : new String[] {"relationship-type", "search-configuration"}) {
            Element c = firstChildByTag(field, tag);
            if (c != null && !text(c).isEmpty()) {
                parts.add(text(c));
            }
        }
        return parts.isEmpty() ? "<relation-field>" : String.join(".", parts);
    }

    private static Map<String, Element> byNameAttr(List<Element> elements) {
        Map<String, Element> m = new LinkedHashMap<>();
        for (Element e : elements) {
            if (!e.hasAttribute("name")) {
                throw new IllegalStateException(
                        "The 'name' attribute missing in element " + e.getTagName());
            }
            m.put(e.getAttribute("name"), e);
        }
        return m;
    }

    private static Map<String, Element> byValuePairsKey(List<Element> elements) {
        Map<String, Element> m = new LinkedHashMap<>();
        for (Element e : elements) {
            if (!e.hasAttribute("value-pairs-name")) {
                throw new IllegalStateException(
                        "The 'value-pairs-name' attribute missing in value-pairs element");
            }
            m.put(e.getAttribute("value-pairs-name"), e);
        }
        return m;
    }

    // ------------------------------------------------------------------
    // core recursive structural comparison
    // ------------------------------------------------------------------
    /**
     * Recursively asserts that {@code enEl} (original) and {@code localeEl} (localized)
     * have the same structure: same tag, same attributes/values (except
     * attributes listed in {@code localizedAttrsByTag} for that tag, which
     * only need to be present/absent consistently), and the same children
     * (same tags, same order, same count). Leaf text must match exactly,
     * UNLESS the tag is listed in {@code localizedTags}, in which case both
     * sides just need to have text, or both be empty (i.e. a translation
     * wasn't simply dropped).
     */
    private void assertStructurallyEqual(Element enEl, Element localeEl, String path,
                                         Set<String> localizedTags,
                                         Map<String, Set<String>> localizedAttrsByTag) {
        assertEquals(path + ": tag mismatch", enEl.getTagName(), localeEl.getTagName());

        Set<String> ignoreAttrs = localizedAttrsByTag.containsKey(enEl.getTagName())
                ? localizedAttrsByTag.get(enEl.getTagName())
                : Set.of();
        Map<String, String> enAttrs = attrMap(enEl, ignoreAttrs);
        Map<String, String> localeAttrs = attrMap(localeEl, ignoreAttrs);
        assertEquals(path + ": attribute mismatch\n"
                        + "  original : " + attrMap(enEl, Set.of()) + "\n"
                        + "  localized: " + attrMap(localeEl, Set.of()),
                enAttrs, localeAttrs);

        for (String attr : ignoreAttrs) {
            assertEquals(path + ": localized attribute '" + attr + "' present in only one file",
                    enEl.hasAttribute(attr), localeEl.hasAttribute(attr));
        }

        List<Element> enChildren = childElements(enEl);
        List<Element> localeChildren = childElements(localeEl);
        List<String> enTags = tagsOf(enChildren);
        List<String> localeTags = tagsOf(localeChildren);
        assertEquals(path + ": child element mismatch\n"
                        + "  original : " + enTags + "\n"
                        + "  localized: " + localeTags,
                enTags, localeTags);

        if (enChildren.isEmpty()) {
            String enText = text(enEl);
            String localeText = text(localeEl);
            if (localizedTags.contains(enEl.getTagName())) {
                assertEquals(path + "/" + enEl.getTagName() + ": localized text present in only one file "
                                + "(original=" + enText + ", localized=" + localeText + ")",
                        enText.isEmpty(), localeText.isEmpty());
            } else {
                assertEquals(path + "/" + enEl.getTagName() + ": text mismatch", enText, localeText);
            }
            return;
        }

        for (int i = 0; i < enChildren.size(); i++) {
            Element enChild = enChildren.get(i);
            Element localeChild = localeChildren.get(i);
            assertStructurallyEqual(enChild, localeChild, path + "/" + enChild.getTagName() + "[" + i + "]",
                    localizedTags, localizedAttrsByTag);
        }
    }

}