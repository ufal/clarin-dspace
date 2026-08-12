/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.hf;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.dspace.app.rest.test.AbstractWebClientIntegrationTest;
import org.dspace.builder.BitstreamBuilder;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.content.Collection;
import org.dspace.content.Community;
import org.dspace.content.Item;
import org.dspace.services.ConfigurationService;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Base class for the {@code *ClientIT} integration tests, which drive real third-party
 * HuggingFace clients (llama.cpp, {@code huggingface_hub}, {@code @huggingface/hub}) against a
 * live test server, to verify the {@code /api/hf} facade against the contracts those clients
 * actually implement rather than against our reading of them.
 *
 * <p>These tests are excluded from the normal IT run and only execute under the
 * {@code hf-client-its} Maven profile, because unlike the rest of the suite they need network
 * access and an external toolchain. Each concrete test skips itself (via JUnit {@link Assume})
 * when its client cannot be provisioned, so a missing toolchain reports as "skipped" rather
 * than as a failure.</p>
 *
 * <p>Two fixture Items are published. {@link #singleFileItem} holds a single untagged
 * {@code .gguf}, which is what llama.cpp's "first model file" fallback selects.
 * {@link #multiFileItem} holds the same bytes under three names, one untagged and two carrying
 * quantisation tags, so that client-side model selection is exercised rather than assumed —
 * the facade must work for GGUF repositories in general, not only for a single lucky filename.</p>
 *
 * @author DSpace at UFAL
 */
public abstract class AbstractHfClientIT extends AbstractWebClientIntegrationTest {

    /**
     * llama.cpp's own server test suite downloads this model
     * ({@code tools/server/tests/utils.py}), and the repository holding it exists upstream
     * precisely to exercise the bare {@code -hf <repo>} form with no {@code --hf-file}.
     */
    protected static final String GGUF_URL =
        "https://huggingface.co/ggml-org/test-model-stories260K/resolve/main/stories260K-f32.gguf";

    /** Exact size of the fixture model, asserted after every download. */
    protected static final long GGUF_SIZE = 1185376L;

    /** Untagged model filename; selected by llama.cpp's "first model file" fallback. */
    protected static final String UNTAGGED_GGUF = "stories260K-f32.gguf";

    /** Model filename carrying a Q4_K_M tag; llama.cpp prefers this quantisation first. */
    protected static final String Q4_GGUF = "tiny-Q4_K_M.gguf";

    /** Model filename carrying a Q8_0 tag; llama.cpp's second choice. */
    protected static final String Q8_GGUF = "tiny-Q8_0.gguf";

    private static byte[] ggufBytes;

    protected Item singleFileItem;

    protected Item multiFileItem;

    @Autowired
    private ConfigurationService configurationService;

    @Override
    @Before
    public void setUp() throws Exception {
        // Belt and braces with the failsafe exclude pattern in the root pom: that pattern is
        // silently ignored whenever someone passes -Dit.test=..., so without this flag an
        // ordinary run could pull these network-dependent tests in. The 'hf-client-its' profile
        // sets it; anything else skips.
        Assume.assumeTrue("Not running under the 'hf-client-its' Maven profile",
                Boolean.getBoolean("hf.client.its"));

        super.setUp();

        byte[] model = ggufBytes();

        // The facade is fail-closed and disabled by default. It is enabled through DSpace's own
        // ConfigurationService rather than @TestPropertySource: these keys are read via
        // ConfigurationService, not through Spring's Environment, so a Spring test property would
        // silently have no effect. The web server shares this JVM's DSpace kernel, so setting it
        // here reaches the server too.
        configurationService.setProperty("hf.api.enabled", true);
        configurationService.setProperty("hf.api.filter.metadata-field", "dc.type");
        configurationService.setProperty("hf.api.filter.value", "machineLearningModel");

        context.turnOffAuthorisationSystem();

        Community community = CommunityBuilder.createCommunity(context)
                .withName("HF client test community")
                .build();
        Collection collection = CollectionBuilder.createCollection(context, community)
                .withName("HF client test collection")
                .build();

        singleFileItem = createModelItem(collection, "Single file model", model, UNTAGGED_GGUF);
        multiFileItem = createModelItem(collection, "Multi file model", model,
                UNTAGGED_GGUF, Q4_GGUF, Q8_GGUF);

        context.restoreAuthSystemState();
        // The web server runs against the database, not against this Hibernate session.
        context.commit();

        assertFacadeIsServing(singleFileItem);
        assertFacadeIsServing(multiFileItem);
    }

    /**
     * Verify the facade actually serves an Item before any external client is launched. Without
     * this, a misconfigured fixture surfaces as an opaque third-party error message ("model
     * download failed") many seconds later, instead of as a clear failure here.
     *
     * @param item the fixture item that must be exposed
     */
    private void assertFacadeIsServing(Item item) {
        String path = "/api/hf/api/models/" + item.getHandle();
        ResponseEntity<String> response = getResponseAsString(path);
        Assert.assertEquals("The HF facade is not serving " + path + " — the fixture is not exposed."
                + " Body: " + response.getBody(), HttpStatus.OK, response.getStatusCode());
    }

    private Item createModelItem(Collection collection, String title, byte[] model, String... filenames)
            throws Exception {
        Item item = org.dspace.builder.ItemBuilder.createItem(context, collection)
                .withTitle(title)
                .withType("machineLearningModel")
                .build();
        for (String filename : filenames) {
            try (InputStream is = new ByteArrayInputStream(model)) {
                BitstreamBuilder.createBitstream(context, item, is)
                        .withName(filename)
                        .withMimeType("application/octet-stream")
                        .build();
            }
        }
        return item;
    }

    /**
     * Base URL of the facade on the live test server, e.g. {@code http://localhost:34567/api/hf}.
     *
     * @return the facade endpoint
     */
    protected String hfEndpoint() {
        return getURL("/api/hf");
    }

    /**
     * Port the test web server is listening on. The {@code @LocalServerPort} field of the base
     * class is private, so it is recovered from the URL it builds.
     *
     * @return the server port
     */
    protected int serverPort() {
        return URI.create(getURL("/")).getPort();
    }

    /**
     * Fetch (once per JVM) the fixture model, caching it under {@code target/} so repeated runs
     * do not re-download it. Skips the calling test if it cannot be retrieved.
     *
     * @return the model bytes
     * @throws IOException if the cached copy cannot be read
     */
    protected static synchronized byte[] ggufBytes() throws IOException {
        if (ggufBytes != null) {
            return ggufBytes;
        }
        Path cache = Paths.get("target", "hf-client-its", UNTAGGED_GGUF);
        if (!Files.isRegularFile(cache) || Files.size(cache) != GGUF_SIZE) {
            Files.createDirectories(cache.getParent());
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(GGUF_URL).openConnection();
                connection.setInstanceFollowRedirects(true);
                connection.setConnectTimeout(30_000);
                connection.setReadTimeout(120_000);
                try (InputStream is = connection.getInputStream()) {
                    Files.copy(is, cache, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                Assume.assumeNoException("Cannot download the fixture model from " + GGUF_URL, e);
            }
        }
        Assume.assumeTrue("Fixture model has an unexpected size", Files.size(cache) == GGUF_SIZE);
        ggufBytes = Files.readAllBytes(cache);
        return ggufBytes;
    }

    /**
     * Result of an external client invocation.
     */
    protected static class ProcessResult {
        /** Process exit status, or -1 if it had to be killed after the timeout. */
        public final int exitCode;

        /** Merged stdout and stderr, always captured so failures are readable. */
        public final String output;

        ProcessResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    /**
     * Run an external client and capture its output. Never throws on a non-zero exit; callers
     * assert on the result, because several of these clients report partial failures (notably
     * llama.cpp, which skips a file whose {@code oid} it rejects) while still exiting cleanly.
     *
     * @param workingDir     directory to run in
     * @param env            extra environment variables
     * @param timeoutSeconds how long to wait before killing the process
     * @param command        the command and its arguments
     * @return the exit code and merged output
     * @throws IOException          if the process cannot be started
     * @throws InterruptedException if waiting is interrupted
     */
    protected ProcessResult run(File workingDir, Map<String, String> env, int timeoutSeconds,
            String... command) throws IOException, InterruptedException {
        // Output is redirected to a file rather than read from the pipe: reading the pipe before
        // waitFor() would block indefinitely on a process that hangs without writing, which would
        // defeat the timeout entirely.
        File log = File.createTempFile("hf-client-", ".log", workingDir);
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(workingDir)
                .redirectErrorStream(true)
                .redirectOutput(log);
        builder.environment().putAll(env);

        Process process = builder.start();
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
        }
        String output = new String(Files.readAllBytes(log.toPath()), java.nio.charset.StandardCharsets.UTF_8);
        if (!finished) {
            return new ProcessResult(-1, output + "\n[timed out after " + timeoutSeconds + "s]");
        }
        return new ProcessResult(process.exitValue(), output);
    }

    /**
     * Locate an executable on the PATH.
     *
     * @param name the executable name
     * @return the resolved file, or null when it is not installed
     */
    protected static File which(String name) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String dir : path.split(File.pathSeparator)) {
            File candidate = new File(dir, name);
            if (candidate.isFile() && candidate.canExecute()) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Copy a script bundled as a test resource into a working directory.
     *
     * @param resource   classpath resource name, relative to this package
     * @param workingDir destination directory
     * @return the written file
     * @throws IOException if the resource cannot be read or written
     */
    protected File writeScript(String resource, File workingDir) throws IOException {
        File target = new File(workingDir, resource);
        try (InputStream is = getClass().getResourceAsStream(resource)) {
            if (is == null) {
                throw new IOException("Missing test resource: " + resource);
            }
            Files.copy(is, target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    /**
     * Collect every regular file under a directory, following symlinks — the HuggingFace cache
     * layout stores the payload in {@code blobs/} and links to it from {@code snapshots/}, so a
     * naive walk sees only link entries.
     *
     * @param root directory to scan
     * @return the files found
     * @throws IOException if the directory cannot be walked
     */
    protected List<Path> findFiles(Path root, String suffix) throws IOException {
        List<Path> found = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return found;
        }
        Files.walk(root, java.nio.file.FileVisitOption.FOLLOW_LINKS)
                .filter(p -> p.getFileName().toString().endsWith(suffix))
                .filter(Files::isRegularFile)
                .forEach(found::add);
        return found;
    }
}
