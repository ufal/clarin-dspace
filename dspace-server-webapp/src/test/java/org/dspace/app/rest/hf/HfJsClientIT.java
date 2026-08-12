/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.hf;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

import org.junit.Assume;
import org.junit.Test;

/**
 * Drives the official {@code @huggingface/hub} JavaScript client against the facade.
 *
 * <p>This is an independent implementation of the HuggingFace Hub protocol from
 * {@code huggingface_hub} (Python) and llama.cpp's own client, which is why it is worth testing
 * separately: a facade quirk that happens to satisfy one client's parsing may still break
 * another's. {@code check_hub.mjs} exercises {@code listFiles}, {@code fileDownloadInfo} (whose
 * declared size comes purely from the {@code Content-Range} header of a ranged request, so it is
 * a real contract check) and {@code downloadFile}, verifying both the declared and the actual
 * downloaded size, plus the {@code GGUF} magic bytes of the payload.</p>
 *
 * <p>The package is installed fresh via {@code npm install} rather than pinned, for the same
 * reason {@link HfPythonClientIT} resolves the current PyPI release: these tests exist to catch
 * drift in what the client currently does, not to freeze against a snapshot of it.</p>
 *
 * @author DSpace at UFAL
 */
public class HfJsClientIT extends AbstractHfClientIT {

    private static final int INSTALL_TIMEOUT_SECONDS = 600;

    private static final int RUN_TIMEOUT_SECONDS = 300;

    /**
     * Run {@code check_hub.mjs} against a repository, in a freshly {@code npm install}ed,
     * per-test HOME, and assert it reported success.
     *
     * @param repoId the repository (item handle) to download
     * @throws Exception if the client cannot be provisioned or run
     */
    private void check(String repoId) throws Exception {
        Assume.assumeTrue("npm is not installed", which("npm") != null);
        Assume.assumeTrue("node is not installed", which("node") != null);

        File home = Files.createTempDirectory("hf-js-home").toFile();
        Map<String, String> env = new HashMap<>();
        env.put("HOME", home.getAbsolutePath());

        ProcessResult install = run(home, env, INSTALL_TIMEOUT_SECONDS,
                "npm", "install", "--silent", "--no-audit", "--no-fund", "@huggingface/hub");
        Assume.assumeTrue("Cannot install @huggingface/hub:\n" + install.output, install.exitCode == 0);

        File script = writeScript("check_hub.mjs", home);

        Map<String, String> runEnv = new HashMap<>();
        runEnv.put("HUB_URL", hfEndpoint());
        runEnv.put("HOME", home.getAbsolutePath());

        ProcessResult result = run(home, runEnv, RUN_TIMEOUT_SECONDS,
                "node", script.getAbsolutePath(), repoId, String.valueOf(GGUF_SIZE));

        assertEquals("check_hub.mjs failed for " + repoId + ":\n" + result.output, 0, result.exitCode);
    }

    @Test
    public void jsClientDownloadsSingleFileModel() throws Exception {
        check(singleFileItem.getHandle());
    }

    @Test
    public void jsClientDownloadsEveryFileOfMultiFileModel() throws Exception {
        check(multiFileItem.getHandle());
    }
}
