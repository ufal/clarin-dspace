/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.hf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Assume;
import org.junit.Test;

/**
 * Drives the llama.cpp HuggingFace client against the facade.
 *
 * <p>This is the client whose contract the facade originally failed: it needs
 * {@code GET /api/models/{repo}/refs}, and it rejects an {@code oid} that is not 40 or 64 hex
 * characters. Its flow is exactly three calls and never touches model info
 * ({@code common/hf-cache.cpp}): {@code /refs} yields the {@code targetCommit} of the
 * {@code main} branch, which is then used verbatim as the revision of
 * {@code /tree/{commit}?recursive=true} and of {@code {repo}/resolve/{commit}/{path}}.</p>
 *
 * <p>{@code llama download} is used rather than {@code llama serve}: it exercises exactly that
 * flow and then exits, so the test has a clean pass/fail instead of a server to poll and kill.
 * Assertions are on the downloaded bytes, never on the exit status — llama.cpp <em>skips</em> a
 * file whose {@code oid} it rejects and still exits cleanly, which is precisely how the original
 * MD5-valued {@code oid} went unnoticed.</p>
 *
 * @author DSpace at UFAL
 */
public class HfLlamaCppClientIT extends AbstractHfClientIT {

    /**
     * Pinned llama.cpp release used when no binary is supplied. Override with the
     * {@code LLAMA_RELEASE} environment variable; supply an existing binary with
     * {@code LLAMA_BIN} to skip the download entirely.
     */
    private static final String DEFAULT_RELEASE = "b10375";

    private static File llamaBinary;

    /**
     * Locate the {@code llama} CLI: an explicit {@code LLAMA_BIN}, else one on the PATH, else
     * download the pinned Linux x64 release into {@code target/}. Skips the test if none can be
     * obtained.
     *
     * @return the executable
     * @throws IOException if the release cannot be unpacked
     */
    private static synchronized File llama() throws IOException, InterruptedException {
        if (llamaBinary != null) {
            return llamaBinary;
        }
        String explicit = System.getenv("LLAMA_BIN");
        if (explicit != null && new File(explicit).canExecute()) {
            llamaBinary = new File(explicit);
            return llamaBinary;
        }
        File onPath = which("llama");
        if (onPath != null) {
            llamaBinary = onPath;
            return llamaBinary;
        }

        String release = System.getenv().getOrDefault("LLAMA_RELEASE", DEFAULT_RELEASE);
        Path dir = Paths.get("target", "hf-client-its", "llama-" + release);
        File candidate = dir.resolve("llama-" + release).resolve("llama").toFile();
        if (!candidate.canExecute()) {
            String url = "https://github.com/ggml-org/llama.cpp/releases/download/" + release
                    + "/llama-" + release + "-bin-ubuntu-x64.tar.gz";
            Path archive = dir.resolve("llama.tar.gz");
            try {
                Files.createDirectories(dir);
                HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setInstanceFollowRedirects(true);
                connection.setConnectTimeout(30_000);
                connection.setReadTimeout(300_000);
                try (InputStream is = connection.getInputStream()) {
                    Files.copy(is, archive, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                Assume.assumeNoException("Cannot download the llama.cpp release from " + url, e);
            }
            Process tar = new ProcessBuilder("tar", "xzf", archive.toAbsolutePath().toString())
                    .directory(dir.toFile())
                    .redirectErrorStream(true)
                    .start();
            tar.waitFor();
        }
        Assume.assumeTrue("No llama.cpp binary available", candidate.canExecute());
        llamaBinary = candidate;
        return llamaBinary;
    }

    /**
     * Run {@code llama download -hf <repo>} in an isolated HOME so each test starts with an
     * empty client cache, and return every {@code .gguf} it actually materialised.
     */
    private List<Path> download(String repoId) throws Exception {
        File llama = llama();
        File home = Files.createTempDirectory("hf-llama-home").toFile();

        Map<String, String> env = new HashMap<>();
        env.put("HOME", home.getAbsolutePath());
        // llama.cpp reads MODEL_ENDPOINT first, falling back to HF_ENDPOINT; the endpoint is
        // slash-normalised by common_get_model_endpoint().
        env.put("MODEL_ENDPOINT", hfEndpoint());
        env.put("LD_LIBRARY_PATH", llama.getParentFile().getAbsolutePath());

        ProcessResult result = run(home, env, 300, llama.getAbsolutePath(), "download", "-hf", repoId);

        List<Path> models = findFiles(home.toPath(), ".gguf");
        assertTrue("llama download produced no .gguf for " + repoId
                + " (exit " + result.exitCode + ")\n" + result.output, !models.isEmpty());
        return models;
    }

    @Test
    public void bareHfDownloadsUntaggedModel() throws Exception {
        List<Path> models = download(singleFileItem.getHandle());

        Path model = models.get(0);
        assertEquals("llama.cpp downloaded a truncated model", GGUF_SIZE, Files.size(model));
        assertTrue("Expected the untagged model, got " + model,
                model.getFileName().toString().equals(UNTAGGED_GGUF));
    }

    @Test
    public void bareHfPrefersQ4FromMultiFileRepository() throws Exception {
        List<Path> models = download(multiFileItem.getHandle());

        assertTrue("Expected " + Q4_GGUF + " to be selected, got " + models,
                models.stream().anyMatch(p -> p.getFileName().toString().equals(Q4_GGUF)));
        for (Path model : models) {
            assertEquals("Downloaded model is truncated: " + model, GGUF_SIZE, Files.size(model));
        }
    }

    @Test
    public void taggedHfSelectsRequestedQuantisation() throws Exception {
        List<Path> models = download(multiFileItem.getHandle() + ":Q8_0");

        assertTrue("Expected " + Q8_GGUF + " to be selected, got " + models,
                models.stream().anyMatch(p -> p.getFileName().toString().equals(Q8_GGUF)));
        for (Path model : models) {
            assertEquals("Downloaded model is truncated: " + model, GGUF_SIZE, Files.size(model));
        }
    }
}
