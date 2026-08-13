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
import java.util.HashMap;
import java.util.Map;

import org.junit.Assume;
import org.junit.Test;

/**
 * Drives the {@code huggingface_hub} Python client against the facade.
 *
 * <p>{@code uv run --with huggingface_hub} is used to provision the client, which resolves and
 * installs the <em>current</em> PyPI release at test time rather than a pinned one. That is
 * deliberate: these tests exist to catch drift in the client's real, currently-shipping
 * behaviour, not to freeze against a snapshot of it.</p>
 *
 * <p>{@code check_hub.py} exercises {@code model_info}, {@code hf_hub_download} (once per
 * sibling file) and {@code snapshot_download}, so a single passing run covers the three
 * download paths client code actually uses.</p>
 *
 * @author DSpace at UFAL
 */
public class HfPythonClientIT extends AbstractHfClientIT {

    private static final int TIMEOUT_SECONDS = 600;

    /**
     * Run {@code check_hub.py} against a repository in an isolated HOME (so the client cache
     * starts empty) and assert it reported success.
     *
     * @param repoId the repository (item handle) to download
     * @throws Exception if the script cannot be provisioned or run
     */
    private void check(String repoId) throws Exception {
        Assume.assumeTrue("uv is not installed", which("uv") != null);

        File home = newWorkDir("python-");
        File script = writeScript("check_hub.py", home);

        Map<String, String> env = new HashMap<>();
        env.put("HF_ENDPOINT", hfEndpoint());
        env.put("HOME", home.getAbsolutePath());
        env.put("HF_HUB_DISABLE_PROGRESS_BARS", "1");

        ProcessResult result = run(home, env, TIMEOUT_SECONDS,
                "uv", "run", "--quiet", "--with", "huggingface_hub",
                "python3", script.getAbsolutePath(), repoId, String.valueOf(GGUF_SIZE));

        assertEquals("check_hub.py failed for " + repoId + ":\n" + result.output, 0, result.exitCode);
    }

    @Test
    public void pythonClientDownloadsSingleFileModel() throws Exception {
        check(singleFileItem.getHandle());
    }

    @Test
    public void pythonClientDownloadsEveryFileOfMultiFileModel() throws Exception {
        check(multiFileItem.getHandle());
    }
}
