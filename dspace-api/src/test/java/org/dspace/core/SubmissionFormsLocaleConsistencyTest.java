/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.core;

import static org.junit.Assert.fail;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.w3c.dom.Attr;
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
 * <p>
 * For every &lt;definition name="..."&gt; element, and recursively for every
 * child element inside it (matched across the two files by its "name"
 * attribute, or by dc-schema/dc-element/dc-qualifier for DSpace
 * &lt;field&gt; elements), all remaining attributes (input-type, required,
 * regex, repeatable, vocabulary, etc.) are compared.
 */
@RunWith(Parameterized.class)
public class SubmissionFormsLocaleConsistencyTest {

    private static final String CONFIG_DIR = "../dspace/config";
    private static final String BASE_FILE_NAME = "submission-forms.xml";

    // Matches submission-forms_<locale>.xml, e.g. submission-forms_cs.xml, submission-forms_pl.xml, etc.
    private static final Pattern LOCALE_FILE_PATTERN =
            Pattern.compile("^submission-forms_(.+)\\.xml$");

    private static final Set<String> IGNORED_ATTRS = new HashSet<>(Arrays.asList(
            "label", "hint", "description", "placeholder"));

    private static final Set<String> IGNORED_TEXT_TAGS = new HashSet<>(Arrays.asList(
            "label", "hint", "description"));

    private final String locale;
    private final File localeFile;

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

    @Test
    public void localeMatchesBaseStructure() throws Exception {
        File baseFile = new File(CONFIG_DIR, BASE_FILE_NAME);
        Document baseDoc = parse(baseFile);
        Document localeDoc = parse(localeFile);

        Map<String, Element> baseDefs = indexDefinitions(baseDoc);
        Map<String, Element> localeDefs = indexDefinitions(localeDoc);

        List<String> errors = new ArrayList<>();

        for (String name : baseDefs.keySet()) {
            if (!localeDefs.containsKey(name)) {
                errors.add("definition '" + name + "': present in base but missing in " + locale);
            }
        }
        for (String name : localeDefs.keySet()) {
            if (!baseDefs.containsKey(name)) {
                errors.add("definition '" + name + "': present in " + locale + " but missing in base");
            }
        }

        for (String name : baseDefs.keySet()) {
            if (localeDefs.containsKey(name)) {
                compareElements(baseDefs.get(name), localeDefs.get(name), "definition[" + name + "]", errors);
            }
        }

        if (!errors.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append(errors.size()).append(" inconsistency(ies) found between ")
                    .append(BASE_FILE_NAME).append(" and ").append(localeFile.getName()).append(":\n");
            for (String e : errors) {
                sb.append(" - ").append(e).append("\n");
            }
            fail(sb.toString());
        }
    }

    private Document parse(File file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(file);
    }

    private Map<String, Element> indexDefinitions(Document doc) {
        Map<String, Element> result = new LinkedHashMap<>();
        NodeList defs = doc.getElementsByTagName("definition");
        for (int i = 0; i < defs.getLength(); i++) {
            Element def = (Element) defs.item(i);
            result.put(def.getAttribute("name"), def);
        }
        return result;
    }

    /** Best-effort identity for aligning an element across the two files. */
    private String nodeKey(Element el) {
        if ("field".equals(el.getTagName())) {
            return "field:" + el.getAttribute("dc-schema") + "/"
                    + el.getAttribute("dc-element") + "/" + el.getAttribute("dc-qualifier");
        }
        return el.getTagName() + ":" + el.getAttribute("name");
    }

    private Map<String, String> attrsOf(Element el) {
        Map<String, String> attrs = new TreeMap<>();
        NamedNodeMap map = el.getAttributes();
        for (int i = 0; i < map.getLength(); i++) {
            Attr attr = (Attr) map.item(i);
            if (!IGNORED_ATTRS.contains(attr.getName())) {
                attrs.put(attr.getName(), attr.getValue());
            }
        }
        return attrs;
    }

    private List<Element> childElements(Element el) {
        List<Element> children = new ArrayList<>();
        NodeList nodes = el.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                Element child = (Element) n;
                if (!IGNORED_TEXT_TAGS.contains(child.getTagName())) {
                    children.add(child);
                }
            }
        }
        return children;
    }

    private void compareElements(Element a, Element b, String path, List<String> errors) {
        if (!a.getTagName().equals(b.getTagName())) {
            errors.add(path + ": tag mismatch <" + a.getTagName() + "> vs <" + b.getTagName() + ">");
            return;
        }

        Map<String, String> aAttrs = attrsOf(a);
        Map<String, String> bAttrs = attrsOf(b);
        if (!aAttrs.equals(bAttrs)) {
            errors.add(path + ": attribute mismatch\n    base: " + aAttrs + "\n    " + locale + ": " + bAttrs);
        }

        Map<String, List<Element>> aChildren = groupByKey(childElements(a));
        Map<String, List<Element>> bChildren = groupByKey(childElements(b));

        for (String key : aChildren.keySet()) {
            if (!bChildren.containsKey(key)) {
                errors.add(path + ": element [" + key + "] present in base but missing in " + locale);
            }
        }
        for (String key : bChildren.keySet()) {
            if (!aChildren.containsKey(key)) {
                errors.add(path + ": element [" + key + "] present in " + locale + " but missing in base");
            }
        }

        for (String key : aChildren.keySet()) {
            if (bChildren.containsKey(key)) {
                List<Element> aList = aChildren.get(key);
                List<Element> bList = bChildren.get(key);
                int n = Math.min(aList.size(), bList.size());
                for (int i = 0; i < n; i++) {
                    compareElements(aList.get(i), bList.get(i), path + "/" + key + "[" + i + "]", errors);
                }
            }
        }
    }

    private Map<String, List<Element>> groupByKey(List<Element> elements) {
        Map<String, List<Element>> grouped = new LinkedHashMap<>();
        for (Element el : elements) {
            grouped.computeIfAbsent(nodeKey(el), k -> new ArrayList<>()).add(el);
        }
        return grouped;
    }
}