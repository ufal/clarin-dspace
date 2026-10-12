/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.hf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

/**
 * Plain JUnit 4 unit tests (no Spring context) for the pure static helper methods of
 * {@link ClarinHuggingFaceService}: {@link ClarinHuggingFaceService#computeSha(String, long, Map)} and
 * {@link ClarinHuggingFaceService#isValidRevision(String, String)}.
 *
 * @author DSpace at UFAL
 */
public class ClarinHuggingFaceServiceTest {

    private static final String UUID_1 = "11111111-1111-1111-1111-111111111111";

    private static final String UUID_2 = "22222222-2222-2222-2222-222222222222";

    private static final long LAST_MODIFIED_1 = 1_700_000_000_000L;

    private static final long LAST_MODIFIED_2 = 1_800_000_000_000L;

    /**
     * Build a sample filename-to-md5 map.
     *
     * @return a map with two entries
     */
    private Map<String, String> sampleFiles() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("model.bin", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        files.put("config.json", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        return files;
    }

    @Test
    public void testComputeShaReturns40CharLowercaseHex() {
        String sha = ClarinHuggingFaceService.computeSha(UUID_1, LAST_MODIFIED_1, sampleFiles());
        assertTrue("sha should match [0-9a-f]{40} but was: " + sha, sha.matches("[0-9a-f]{40}"));
    }

    @Test
    public void testComputeShaIsDeterministic() {
        String sha1 = ClarinHuggingFaceService.computeSha(UUID_1, LAST_MODIFIED_1, sampleFiles());
        String sha2 = ClarinHuggingFaceService.computeSha(UUID_1, LAST_MODIFIED_1, sampleFiles());
        assertEquals(sha1, sha2);
    }

    @Test
    public void testComputeShaIsInsensitiveToMapInsertionOrder() {
        Map<String, String> hashMapOrder = new HashMap<>();
        hashMapOrder.put("model.bin", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        hashMapOrder.put("config.json", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        hashMapOrder.put("tokenizer.json", "cccccccccccccccccccccccccccccccc");

        Map<String, String> reversedLinkedOrder = new LinkedHashMap<>();
        reversedLinkedOrder.put("tokenizer.json", "cccccccccccccccccccccccccccccccc");
        reversedLinkedOrder.put("config.json", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        reversedLinkedOrder.put("model.bin", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

        String shaFromHashMap = ClarinHuggingFaceService.computeSha(UUID_1, LAST_MODIFIED_1, hashMapOrder);
        String shaFromReversedLinkedMap =
                ClarinHuggingFaceService.computeSha(UUID_1, LAST_MODIFIED_1, reversedLinkedOrder);

        assertEquals(shaFromHashMap, shaFromReversedLinkedMap);
    }

    @Test
    public void testComputeShaChangesWhenUuidChanges() {
        String sha1 = ClarinHuggingFaceService.computeSha(UUID_1, LAST_MODIFIED_1, sampleFiles());
        String sha2 = ClarinHuggingFaceService.computeSha(UUID_2, LAST_MODIFIED_1, sampleFiles());
        assertNotEquals(sha1, sha2);
    }

    @Test
    public void testComputeShaChangesWhenLastModifiedChanges() {
        String sha1 = ClarinHuggingFaceService.computeSha(UUID_1, LAST_MODIFIED_1, sampleFiles());
        String sha2 = ClarinHuggingFaceService.computeSha(UUID_1, LAST_MODIFIED_2, sampleFiles());
        assertNotEquals(sha1, sha2);
    }

    @Test
    public void testComputeShaChangesWhenFilenameChanges() {
        Map<String, String> files = sampleFiles();
        String sha1 = ClarinHuggingFaceService.computeSha(UUID_1, LAST_MODIFIED_1, files);

        Map<String, String> renamed = new LinkedHashMap<>();
        renamed.put("model-renamed.bin", files.get("model.bin"));
        renamed.put("config.json", files.get("config.json"));
        String sha2 = ClarinHuggingFaceService.computeSha(UUID_1, LAST_MODIFIED_1, renamed);

        assertNotEquals(sha1, sha2);
    }

    @Test
    public void testComputeShaChangesWhenMd5Changes() {
        Map<String, String> files = sampleFiles();
        String sha1 = ClarinHuggingFaceService.computeSha(UUID_1, LAST_MODIFIED_1, files);

        Map<String, String> changed = new LinkedHashMap<>(files);
        changed.put("model.bin", "ffffffffffffffffffffffffffffffff");
        String sha2 = ClarinHuggingFaceService.computeSha(UUID_1, LAST_MODIFIED_1, changed);

        assertNotEquals(sha1, sha2);
    }

    @Test
    public void testComputeShaChangesWhenFileAdded() {
        Map<String, String> files = sampleFiles();
        String sha1 = ClarinHuggingFaceService.computeSha(UUID_1, LAST_MODIFIED_1, files);

        Map<String, String> withExtra = new LinkedHashMap<>(files);
        withExtra.put("extra.txt", "dddddddddddddddddddddddddddddddd");
        String sha2 = ClarinHuggingFaceService.computeSha(UUID_1, LAST_MODIFIED_1, withExtra);

        assertNotEquals(sha1, sha2);
    }

    @Test
    public void testIsValidRevisionMainIsAlwaysValid() {
        String sha = ClarinHuggingFaceService.computeSha(UUID_1, LAST_MODIFIED_1, sampleFiles());
        assertTrue(ClarinHuggingFaceService.isValidRevision("main", sha));
    }

    @Test
    public void testIsValidRevisionMatchingShaIsValid() {
        String sha = ClarinHuggingFaceService.computeSha(UUID_1, LAST_MODIFIED_1, sampleFiles());
        assertTrue(ClarinHuggingFaceService.isValidRevision(sha, sha));
    }

    @Test
    public void testIsValidRevisionMatchingShaIsCaseInsensitive() {
        String sha = ClarinHuggingFaceService.computeSha(UUID_1, LAST_MODIFIED_1, sampleFiles());
        assertTrue(ClarinHuggingFaceService.isValidRevision(sha.toUpperCase(), sha));
    }

    @Test
    public void testIsValidRevisionDifferentShaIsInvalid() {
        String sha = ClarinHuggingFaceService.computeSha(UUID_1, LAST_MODIFIED_1, sampleFiles());
        String otherSha = ClarinHuggingFaceService.computeSha(UUID_2, LAST_MODIFIED_2, sampleFiles());
        assertFalse(ClarinHuggingFaceService.isValidRevision(otherSha, sha));
    }

    @Test
    public void testIsValidRevisionNullIsInvalid() {
        String sha = ClarinHuggingFaceService.computeSha(UUID_1, LAST_MODIFIED_1, sampleFiles());
        assertFalse(ClarinHuggingFaceService.isValidRevision(null, sha));
    }

    @Test
    public void testIsValidRevisionEmptyIsInvalid() {
        String sha = ClarinHuggingFaceService.computeSha(UUID_1, LAST_MODIFIED_1, sampleFiles());
        assertFalse(ClarinHuggingFaceService.isValidRevision("", sha));
    }
}
