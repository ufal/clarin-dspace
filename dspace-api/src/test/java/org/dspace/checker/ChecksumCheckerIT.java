/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.checker;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.dspace.AbstractIntegrationTestWithDatabase;
import org.dspace.builder.BitstreamBuilder;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.ItemBuilder;
import org.dspace.checker.factory.CheckerServiceFactory;
import org.dspace.checker.service.ChecksumHistoryService;
import org.dspace.checker.service.MostRecentChecksumService;
import org.dspace.content.Bitstream;
import org.dspace.content.Collection;
import org.dspace.content.Community;
import org.dspace.content.Item;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.BitstreamService;
import org.dspace.core.Context;
import org.dspace.storage.bitstore.BitStoreService;
import org.dspace.storage.bitstore.DSBitStoreService;
import org.dspace.storage.bitstore.SyncBitstreamStorageServiceImpl;
import org.dspace.storage.bitstore.factory.StorageServiceFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ChecksumCheckerIT extends AbstractIntegrationTestWithDatabase {
    /** Free key in bitstore.xml, used by the test as the mirror of the synchronized store. */
    private static final int MIRROR_STORE_NUMBER = 3;

    protected List<Bitstream> bitstreams;
    protected MostRecentChecksumService checksumService =
        CheckerServiceFactory.getInstance().getMostRecentChecksumService();

    @Before
    public void setup() throws Exception {
        context.turnOffAuthorisationSystem();

        Community parentCommunity = CommunityBuilder.createCommunity(context).build();
        Collection collection = CollectionBuilder.createCollection(context, parentCommunity)
            .build();
        Item item = ItemBuilder.createItem(context, collection).withTitle("Test item")
            .build();

        int numBitstreams = 3;
        bitstreams = new ArrayList<>();
        for (int i = 0; i < numBitstreams; i++) {
            String content = "Test bitstream " + i;
            bitstreams.add(
                BitstreamBuilder.createBitstream(
                    context, item, IOUtils.toInputStream(content, UTF_8)
                ).build()
            );
        }

        context.restoreAuthSystemState();

        // Call the "updateMissingBitstreams" method so that the test bitstreams
        // already have checksums in the past when CheckerCommand runs.
        // Otherwise, the CheckerCommand will simply update the test
        // bitstreams without going through the BitstreamDispatcher.
        checksumService = CheckerServiceFactory.getInstance().getMostRecentChecksumService();
        checksumService.updateMissingBitstreams(context);

        // The "updateMissingBitstreams" method updates the test bitstreams in
        // a random order. To verify that the expected bitstreams were
        // processed, reset the timestamps so that the bitstreams are
        // checked in a specific order (oldest first).
        Instant checksumInstant = Instant.ofEpochMilli(0);
        for (Bitstream bitstream: bitstreams) {
            MostRecentChecksum mrc = checksumService.findByBitstream(context, bitstream);
            mrc.setProcessStartDate(checksumInstant);
            mrc.setProcessEndDate(checksumInstant);
            checksumInstant = checksumInstant.plusSeconds(10);
        }
        context.commit();
    }

    @After
    public void cleanUp() throws SQLException {
        // Need to clean up ChecksumHistory because of a referential integrity
        // constraint violation between the most_recent_checksum table and
        // bitstream tables
        ChecksumHistoryService checksumHistoryService = CheckerServiceFactory.getInstance().getChecksumHistoryService();

        for (Bitstream bitstream: bitstreams) {
            checksumHistoryService.deleteByBitstream(context, bitstream);
        }
    }

    @Test
    public void testChecksumsRecordedWhenProcesingIsInterrupted() throws SQLException {
        CheckerCommand checker = new CheckerCommand(context);

        // The start date to use for the checker process
        Instant checkerStartDate = Instant.now();

        // Verify that all checksums are before the checker start date
        for (Bitstream bitstream: bitstreams) {
            MostRecentChecksum checksum = checksumService.findByBitstream(context, bitstream);
            Instant lastChecksumDate = checksum.getProcessStartDate();
            assertTrue("lastChecksumDate (" + lastChecksumDate + ") <= checkerStartDate (" + checkerStartDate + ")",
                lastChecksumDate.isBefore(checkerStartDate));
        }

        // Dispatcher that throws an exception when a third bitstream is
        // retrieved.
        BitstreamDispatcher dispatcher = new ExpectionThrowingDispatcher(
            context, checkerStartDate, false, 2);
        checker.setDispatcher(dispatcher);


        // Run the checksum checker
        checker.setProcessStartDate(checkerStartDate);
        try {
            checker.process();
            fail("SQLException should have been thrown");
        } catch (SQLException sqle) {
            // Rollback any pending transaction
            context.rollback();
        }

        // Verify that the checksums of the first two bitstreams (that were
        // processed before the exception) have been successfully recorded in
        // the database, while the third bitstream was not updated.
        int bitstreamCount = 0;
        for (Bitstream bitstream: bitstreams) {
            MostRecentChecksum checksum = checksumService.findByBitstream(context, bitstream);
            Instant lastChecksumDate = checksum.getProcessStartDate();

            bitstreamCount = bitstreamCount + 1;
            if (bitstreamCount <= 2) {
                assertTrue("lastChecksumDate (" + lastChecksumDate + ") <= checkerStartDate (" + checkerStartDate + ")",
                    lastChecksumDate.isAfter(checkerStartDate));
            } else {
                assertTrue("lastChecksumDate (" + lastChecksumDate + ") >= checkerStartDate (" + checkerStartDate + ")",
                    lastChecksumDate.isBefore(checkerStartDate));
            }
        }
    }

    /**
     * A bitstream in the synchronized store is held in the incoming store and in a mirror at the
     * same time. When the two copies drift apart the checker's second pass has to report it, and
     * the first pass on its own cannot: it only ever reads the incoming store.
     */
    @Test
    public void testDivergingSynchronizedCopyIsReported() throws Exception {
        SyncBitstreamStorageServiceImpl storage =
            StorageServiceFactory.getInstance().getSyncBitstreamStorageService();
        BitstreamService bitstreamService = ContentServiceFactory.getInstance().getBitstreamService();

        File mirrorDir = new File("target/testing/sync-mirror-assetstore");
        FileUtils.forceMkdir(mirrorDir);
        DSBitStoreService mirror = new DSBitStoreService();
        mirror.setBaseDir(mirrorDir);
        mirror.init();

        // The checker reaches the bitstream through the checksum record, so the test has to work on
        // that instance; the one the builder handed back is a different object for the same row.
        MostRecentChecksum info = checksumService.findByBitstream(context, bitstreams.get(0));
        Bitstream bitstream = info.getBitstream();
        int originalStoreNumber = bitstream.getStoreNumber();
        Map<Integer, BitStoreService> stores = storage.getStores();
        stores.put(MIRROR_STORE_NUMBER, mirror);

        try {
            context.turnOffAuthorisationSystem();
            bitstream.setStoreNumber(SyncBitstreamStorageServiceImpl.SYNCHRONIZED_STORES_NUMBER);
            bitstreamService.update(context, bitstream);
            context.restoreAuthSystemState();

            assertEquals(MIRROR_STORE_NUMBER, storage.getSynchronizedStoreNumber(bitstream));

            File mirroredFile = mirror.getFile(bitstream);
            FileUtils.forceMkdirParent(mirroredFile);
            FileUtils.writeStringToFile(mirroredFile, "Not what the incoming store holds", UTF_8);
            assertTrue("the mirrored copy was not written", mirroredFile.exists());
            assertEquals("the mirrored copy has to differ from the incoming one",
                ChecksumResultCode.CHECKSUM_NO_MATCH,
                new CheckerCommand(context).compareChecksums(
                    bitstream.getChecksum(),
                    storage.computeChecksumSpecStore(context, bitstream, MIRROR_STORE_NUMBER)
                           .get("checksum").toString()).getResultCode());

            new CheckerCommand(context).processBitstream(info);

            assertEquals(ChecksumResultCode.CHECKSUM_SYNC_NO_MATCH,
                info.getChecksumResult().getResultCode());
        } finally {
            stores.remove(MIRROR_STORE_NUMBER);
            FileUtils.deleteQuietly(mirrorDir);
            context.turnOffAuthorisationSystem();
            bitstream.setStoreNumber(originalStoreNumber);
            bitstreamService.update(context, bitstream);
            context.restoreAuthSystemState();
        }
    }

    /**
     * Subclass of SimpleDispatcher that only allows a limited number of "next"
     * class before throwing a SQLException.
     */
    class ExpectionThrowingDispatcher extends SimpleDispatcher {
        // The number of "next" calls to allow before throwing a SQLException
        protected int maxNextCalls;

        // The number of "next" method calls seen so far.
        protected int numNextCalls = 0;

        /**
         * Constructor.
         *
         * @param context   Context
         * @param startTime timestamp for beginning of checker process
         * @param looping   indicates whether checker should loop infinitely
         *                  through most_recent_checksum table
         * @param maxNextCalls the number of "next" method calls to allow before
         * throwing a SQLException.
         */
        public ExpectionThrowingDispatcher(Context context, Instant startTime, boolean looping, int maxNextCalls) {
            super(context, startTime, looping);
            this.maxNextCalls = maxNextCalls;
        }

        /**
         * Selects the next candidate bitstream.
         *
         * After "maxNextClass" number of calls, this method throws a
         * SQLException.
         *
         * @throws SQLException if database error
         */
        @Override
        public synchronized Bitstream next() throws SQLException {
            numNextCalls = numNextCalls + 1;
            if (numNextCalls > maxNextCalls) {
                throw new SQLException("Max 'next' method calls exceeded");
            }
            return super.next();
        }
    }
}
