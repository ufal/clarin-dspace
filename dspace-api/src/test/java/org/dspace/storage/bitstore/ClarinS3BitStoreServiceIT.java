/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.storage.bitstore;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.commons.io.IOUtils.toInputStream;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.sql.SQLException;

import org.apache.commons.io.FileUtils;
import org.dspace.AbstractIntegrationTestWithDatabase;
import org.dspace.authorize.AuthorizeException;
import org.dspace.builder.BitstreamBuilder;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.ItemBuilder;
import org.dspace.content.Bitstream;
import org.dspace.content.Collection;
import org.dspace.content.Item;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;

/**
 * Covers the CLARIN-only parts of the S3 asset store, which upstream's {@link S3BitStoreServiceIT}
 * does not touch: {@link S3BitStoreService#getFile(Bitstream)} and {@link SyncS3BitStoreService}.
 *
 * @author Milan Majchrak (dspace at dataquest.sk)
 */
public class ClarinS3BitStoreServiceIT extends AbstractIntegrationTestWithDatabase {

    // Pinned to the same image upstream's S3BitStoreServiceIT uses.
    private static DockerImageName localstackName = DockerImageName.parse("localstack/localstack:4.14.0");

    @SuppressWarnings("resource")
    private static LocalStackContainer localstackContainer = new LocalStackContainer(localstackName).withServices("s3");

    private static S3AsyncClient s3AsyncClient;

    private static final String BUCKET_NAME = "clarin-testbucket";

    private Collection collection;

    private File assetstoreDir;

    private DSBitStoreService dsBitStoreService;

    private ConfigurationService configurationService = DSpaceServicesFactory.getInstance().getConfigurationService();

    @BeforeClass
    public static void setupS3() {
        localstackContainer.start();

        s3AsyncClient = S3AsyncClient.crtBuilder()
                .endpointOverride(localstackContainer.getEndpoint())
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(localstackContainer.getAccessKey(),
                                localstackContainer.getSecretKey())
                        ))
                .region(Region.of(localstackContainer.getRegion()))
                .build();
    }

    @AfterClass
    public static void cleanupS3() {
        localstackContainer.close();
        s3AsyncClient.close();
    }

    @Before
    public void setup() throws Exception {
        configurationService.setProperty("assetstore.s3.enabled", "true");
        // clarin-dspace.cfg turns both of these on; the tests opt in explicitly instead
        configurationService.setProperty("sync.storage.service.enabled", "false");
        configurationService.setProperty("s3.upload.by.parts.enabled", "false");

        assetstoreDir = Files.createTempDirectory("clarin-assetstore").toFile();
        dsBitStoreService = new DSBitStoreService();
        dsBitStoreService.setBaseDir(assetstoreDir);
        dsBitStoreService.init();

        context.turnOffAuthorisationSystem();
        parentCommunity = CommunityBuilder.createCommunity(context).build();
        collection = CollectionBuilder.createCollection(context, parentCommunity).build();
        context.restoreAuthSystemState();
    }

    /**
     * {@link S3BitStoreService#getFile(Bitstream)} is declared by the fork's BitStoreService and has no
     * upstream counterpart, so nothing else exercises it.
     */
    @Test
    public void getFileReturnsStoredContent() throws Exception {
        String content = "CLARIN getFile content";
        S3BitStoreService store = initStore(new S3BitStoreService(s3AsyncClient));

        Bitstream bitstream = createBitstream(content);
        store.put(bitstream, toInputStream(content, UTF_8));

        File file = store.getFile(bitstream);

        assertTrue(file.exists());
        assertThat(FileUtils.readFileToString(file, UTF_8), is(content));
        assertThat(file.length(), is((long) content.length()));
    }

    /**
     * Default path: a single putObject, the CRT client splits large files on its own.
     */
    @Test
    public void syncStoreUploadsFluently() throws Exception {
        String content = "CLARIN fluent upload";
        SyncS3BitStoreService store = initSyncStore(false, false);

        Bitstream bitstream = createBitstream(content);
        store.put(bitstream, toInputStream(content, UTF_8));

        assertThat(readS3(store, bitstream), is(content));
        assertThat(bitstream.getSizeBytes(), is((long) content.length()));
        // the local assetstore must stay untouched while syncEnabled is false
        assertFalse(dsBitStoreService.getFile(bitstream).exists());
    }

    /**
     * `s3.upload.by.parts.enabled` path: explicit createMultipartUpload / uploadPart /
     * completeMultipartUpload, with the per-part ETag compared against a locally computed MD5.
     * That comparison is what regressed in the v1 -> v2 port, because v2 returns the ETag quoted.
     */
    @Test
    public void syncStoreUploadsByParts() throws Exception {
        String content = "CLARIN multipart upload";
        SyncS3BitStoreService store = initSyncStore(false, true);

        Bitstream bitstream = createBitstream(content);
        store.put(bitstream, toInputStream(content, UTF_8));

        assertThat(readS3(store, bitstream), is(content));
        assertThat(bitstream.getSizeBytes(), is((long) content.length()));
    }

    /**
     * The same path across a real part boundary. With the 50 MB production part size and a short fixture
     * the loop only ever ran once, so the offset arithmetic, the ranged request body and the ordering of
     * CompletedParts were never executed - a review found the loop untested even though it had just been
     * rewritten. S3 requires every part but the last to be at least 5 MB, so the part size is lowered
     * rather than the fixture made enormous.
     */
    @Test
    public void syncStoreUploadsInMultipleParts() throws Exception {
        int partSize = 5 * 1024 * 1024;
        byte[] content = new byte[partSize + 4096];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) (i % 251);
        }

        SyncS3BitStoreService store = initSyncStore(false, true);
        store.setUploadPartSizeBytes(partSize);

        Bitstream bitstream = createBitstream(content);
        store.put(bitstream, new ByteArrayInputStream(content));

        assertThat(bitstream.getSizeBytes(), is((long) content.length));
        // read the object back byte for byte - a wrong offset would corrupt the second part silently
        assertArrayEquals(content, FileUtils.readFileToByteArray(store.getFile(bitstream)));
    }

    /**
     * The endpoint override and path-style flag are the fork's headline S3 delta, and every other test
     * injects a ready-made client - so `amazonClientBuilderBy` never ran and a tripwire planted in it
     * left all 21 S3 tests green. This builds a client through that method and uses it for real.
     */
    @Test
    public void clientBuilderAppliesEndpointAndPathStyle() {
        S3AsyncClient client = S3BitStoreService.amazonClientBuilderBy(
                Region.of(localstackContainer.getRegion()),
                StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        localstackContainer.getAccessKey(), localstackContainer.getSecretKey())),
                localstackContainer.getEndpoint().toString(),
                10.0,
                8 * 1024 * 1024L,
                null,
                true).get();

        try {
            // Only reachable if endpointOverride was applied - the default endpoint is real AWS.
            client.createBucket(r -> r.bucket("clarin-builder-probe")).join();
            assertTrue(new S3BitStoreService(client).doesBucketExist("clarin-builder-probe"));
        } finally {
            client.close();
        }
    }

    /**
     * The reason SyncS3BitStoreService exists: every asset is also written to the local assetstore.
     */
    @Test
    public void syncStoreWritesLocalCopy() throws Exception {
        String content = "CLARIN synced to local assetstore";
        SyncS3BitStoreService store = initSyncStore(true, false);

        Bitstream bitstream = createBitstream(content);
        store.put(bitstream, toInputStream(content, UTF_8));

        assertThat(readS3(store, bitstream), is(content));

        File localFile = dsBitStoreService.getFile(bitstream);
        assertTrue("asset was not mirrored into the local assetstore", localFile.exists());
        assertThat(FileUtils.readFileToString(localFile, UTF_8), is(content));
    }

    /**
     * remove() has to clear both stores when syncing is on.
     */
    @Test
    public void syncStoreRemovesFromBothStores() throws Exception {
        String content = "CLARIN removed from both";
        SyncS3BitStoreService store = initSyncStore(true, false);

        Bitstream bitstream = createBitstream(content);
        store.put(bitstream, toInputStream(content, UTF_8));
        assertTrue(dsBitStoreService.getFile(bitstream).exists());

        store.remove(bitstream);

        assertFalse(dsBitStoreService.getFile(bitstream).exists());
        assertFalse(objectExists(store, bitstream));
    }

    /**
     * bitstore.xml is the only place the store is configured in production and `s3Store` is lazy-init,
     * so nothing otherwise forces Spring to bind its properties. Two of them are easy to get wrong:
     * `maxConcurrency` is a blank config value bound to an Integer, and `s3ChecksumAlgorithm` is a
     * String bound to an SDK enum.
     */
    @Test
    public void springWiringBindsStoreProperties() {
        SyncS3BitStoreService store = DSpaceServicesFactory.getInstance().getServiceManager()
                .getServiceByName("s3Store", SyncS3BitStoreService.class);

        assertNotNull("s3Store bean could not be created from bitstore.xml", store);
        assertThat(store.getS3ChecksumAlgorithm(), is(ChecksumAlgorithm.CRC32));
        assertThat(store.getTargetThroughputGbps(), is(10.0));
        assertThat(store.getMinPartSizeBytes(), is(8 * 1024 * 1024L));
        assertNull("blank assetstore.s3.maxConcurrency has to bind to null", store.getMaxConcurrency());
        assertFalse(store.getPathStyleAccessEnabled());
    }

    private <T extends S3BitStoreService> T initStore(T store) throws IOException {
        ReflectionTestUtils.setField(store, "s3AsyncClient", s3AsyncClient);
        store.setEnabled(true);
        store.setBucketName(BUCKET_NAME);
        store.init();
        return store;
    }

    private SyncS3BitStoreService initSyncStore(boolean syncEnabled, boolean uploadByParts) throws IOException {
        SyncS3BitStoreService store = new SyncS3BitStoreService(syncEnabled, uploadByParts);
        ReflectionTestUtils.setField(store, "configurationService", configurationService);
        ReflectionTestUtils.setField(store, "dsBitStoreService", dsBitStoreService);
        return initStore(store);
    }

    private String readS3(S3BitStoreService store, Bitstream bitstream) throws IOException {
        return FileUtils.readFileToString(store.getFile(bitstream), UTF_8);
    }

    private boolean objectExists(S3BitStoreService store, Bitstream bitstream) {
        try {
            String key = store.getFullKey(bitstream.getInternalId());
            s3AsyncClient.headObject(r -> r.bucket(BUCKET_NAME).key(key)).join();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private Bitstream createBitstream(String content) {
        return createBitstream(toInputStream(content, UTF_8));
    }

    private Bitstream createBitstream(byte[] content) {
        return createBitstream(new ByteArrayInputStream(content));
    }

    private Bitstream createBitstream(InputStream content) {
        context.turnOffAuthorisationSystem();
        try {
            Item item = ItemBuilder.createItem(context, collection).build();
            return BitstreamBuilder
                .createBitstream(context, item, content)
                .build();
        } catch (SQLException | AuthorizeException | IOException e) {
            throw new RuntimeException(e);
        } finally {
            context.restoreAuthSystemState();
        }
    }
}
