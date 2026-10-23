/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.FilenameFilter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.dspace.AbstractDSpaceTest;
import org.junit.Test;

/**
 * Asserts that every localized variant of {@code submission-forms.xml} defines the same forms, with the
 * same field structure, as the default file.
 * <P>
 * A localized variant is a translation, not a second configuration: the REST endpoint
 * {@code /api/config/submissionforms/{name}} serves it verbatim for a request carrying that
 * {@code Accept-Language}, so a form that exists only in the default file answers HTTP 500 in the other
 * language, and a field that differs makes the submission form itself differ between languages. Both
 * happened here: {@code submission-forms_cs.xml} was never adapted for DSpace 9 while
 * {@code submission-forms.xml} took the vanilla 9.3 shape, so the five forms vanilla renamed from
 * {@code openAIRE*} to {@code openaire*} were unreachable in Czech, and eight rows had drifted apart.
 * <P>
 * The files are read with {@link DCInputsReader} -- the same parser the REST endpoint uses -- from the
 * shipped {@code dspace/config} directory rather than from {@code dspace.dir}, because the test
 * environment overlays its own {@code submission-forms.xml} from
 * {@code dspace-api/src/test/data/dspaceFolder/config} and comparing that fixture against the shipped
 * translation would prove nothing.
 * <P>
 * Only structure is compared. {@code <label>}, {@code <hint>} and the text of {@code <required>} are
 * translations and are expected to differ.
 */
public class SubmissionFormsLocaleParityIT extends AbstractDSpaceTest {

    private static final String CONFIG_DIR = "dspace" + File.separator + "config";
    private static final String DEFAULT_FORMS_FILE = "submission-forms.xml";
    private static final String LOCALIZED_FORMS_PREFIX = "submission-forms_";
    private static final String FORMS_FILE_SUFFIX = ".xml";
    /** How far above the working directory the repository root may sit. */
    private static final int MAX_LEVELS_UP = 4;

    /**
     * Locates the shipped configuration directory by walking up from the working directory until a
     * directory holds both a Maven {@code pom.xml} and {@code dspace/config/submission-forms.xml}. The
     * {@code pom.xml} condition is what keeps the search from stopping at a {@code target/testing}
     * copy of the same tree.
     *
     * @return the shipped {@code dspace/config} directory
     */
    private Path shippedConfigDir() {
        Path candidate = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (int level = 0; level <= MAX_LEVELS_UP && candidate != null; level++) {
            if (candidate.resolve("pom.xml").toFile().isFile()
                    && candidate.resolve(CONFIG_DIR).resolve(DEFAULT_FORMS_FILE).toFile().isFile()) {
                return candidate.resolve(CONFIG_DIR);
            }
            candidate = candidate.getParent();
        }
        fail("Could not locate dspace/config above the working directory " + System.getProperty("user.dir"));
        return null;
    }

    /**
     * @return the localized submission form files shipped next to the default one, never empty
     */
    private List<File> localizedFormFiles() {
        File configDir = shippedConfigDir().toFile();
        FilenameFilter localized = (dir, name) ->
                name.startsWith(LOCALIZED_FORMS_PREFIX) && name.endsWith(FORMS_FILE_SUFFIX);
        File[] files = configDir.listFiles(localized);
        assertTrue("No localized submission-forms file found in " + configDir,
                files != null && files.length > 0);
        List<File> sorted = new ArrayList<>(Arrays.asList(files));
        sorted.sort((left, right) -> left.getName().compareTo(right.getName()));
        return sorted;
    }

    /**
     * @param formsFile a submission form definition file
     * @return every form it defines, by form name
     */
    private Map<String, DCInputSet> readForms(File formsFile) throws DCInputsReaderException {
        DCInputsReader reader = new DCInputsReader(formsFile.getAbsolutePath());
        Map<String, DCInputSet> byName = new LinkedHashMap<>();
        for (DCInputSet inputSet : reader.getAllInputs(reader.countInputs(), 0)) {
            byName.put(inputSet.getFormName(), inputSet);
        }
        return byName;
    }

    /**
     * The structural identity of one input: everything the submission UI drives off, minus the
     * translated label, hint and required message.
     *
     * @param input one field of one form
     * @return a comparable description of the field
     */
    private String structureOf(DCInput input) {
        return "type=" + input.getInputType()
                + " field=" + input.getFieldName()
                + " mandatory=" + input.isRequired()
                + " repeatable=" + input.isRepeatable()
                + " style=" + input.getStyle()
                + " valuePairs=" + input.getPairsType()
                + " vocabulary=" + input.getVocabulary()
                + " typeBind=" + input.getTypeBindList()
                + " relationshipType=" + input.getRelationshipType()
                + " searchConfiguration=" + input.getSearchConfiguration()
                + " externalSources=" + input.getExternalSources();
    }

    /**
     * @param inputSet one form
     * @return one entry per field, in the order the form declares them
     */
    private List<String> structureOf(DCInputSet inputSet) {
        List<String> structure = new ArrayList<>();
        DCInput[][] rows = inputSet.getFields();
        for (int row = 0; row < rows.length; row++) {
            for (DCInput input : rows[row]) {
                structure.add("row " + row + ": " + structureOf(input));
            }
        }
        return structure;
    }

    @Test
    public void everyLocaleDefinesTheSameForms() throws Exception {
        Set<String> defaultForms =
                new TreeSet<>(readForms(shippedConfigDir().resolve(DEFAULT_FORMS_FILE).toFile()).keySet());

        for (File localizedFile : localizedFormFiles()) {
            Set<String> localizedForms = new TreeSet<>(readForms(localizedFile).keySet());
            assertEquals(localizedFile.getName() + " must define exactly the forms " + DEFAULT_FORMS_FILE
                            + " defines: a form missing from a translation makes"
                            + " GET /api/config/submissionforms/{name} answer HTTP 500 in that language",
                    defaultForms, localizedForms);
        }
    }

    @Test
    public void everyLocaleDefinesTheSameFieldStructure() throws Exception {
        Map<String, DCInputSet> defaultForms = readForms(shippedConfigDir().resolve(DEFAULT_FORMS_FILE).toFile());

        for (File localizedFile : localizedFormFiles()) {
            Map<String, DCInputSet> localizedForms = readForms(localizedFile);
            for (Map.Entry<String, DCInputSet> entry : defaultForms.entrySet()) {
                DCInputSet localized = localizedForms.get(entry.getKey());
                if (localized == null) {
                    // reported by everyLocaleDefinesTheSameForms
                    continue;
                }
                assertEquals("Form '" + entry.getKey() + "' has a different field structure in "
                                + localizedFile.getName() + " than in " + DEFAULT_FORMS_FILE
                                + ". A translation may change labels, hints and required messages,"
                                + " never the fields themselves.",
                        structureOf(entry.getValue()), structureOf(localized));
            }
        }
    }
}
