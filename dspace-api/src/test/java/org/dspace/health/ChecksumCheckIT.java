/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.health;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.dspace.AbstractIntegrationTestWithDatabase;
import org.dspace.builder.BitstreamBuilder;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.ItemBuilder;
import org.dspace.checker.MostRecentChecksum;
import org.dspace.checker.factory.CheckerServiceFactory;
import org.dspace.checker.service.ChecksumHistoryService;
import org.dspace.checker.service.MostRecentChecksumService;
import org.dspace.content.Bitstream;
import org.dspace.content.Collection;
import org.dspace.content.Community;
import org.dspace.content.Item;
import org.junit.Before;
import org.junit.Test;

/**
 * Covers the situation the Checksum health check exists for: a bitstream whose stored checksum no longer
 * matches, so the report has to name it.
 */
public class ChecksumCheckIT extends AbstractIntegrationTestWithDatabase {

    private static final String BITSTREAM_NAME = "checksum-check-target.txt";
    private static final String BITSTREAM_CONTENT = "checksum check content";
    /** A checksum no real file can have, so the checker must report a mismatch. */
    private static final String IMPOSSIBLE_CHECKSUM = "00000000000000000000000000000000";

    private final MostRecentChecksumService mostRecentChecksumService =
            CheckerServiceFactory.getInstance().getMostRecentChecksumService();
    private final ChecksumHistoryService checksumHistoryService =
            CheckerServiceFactory.getInstance().getChecksumHistoryService();

    private Bitstream bitstream;

    @Before
    public void setUpBitstream() throws Exception {
        context.turnOffAuthorisationSystem();
        Community community = CommunityBuilder.createCommunity(context)
                .withName("Checksum check community")
                .build();
        Collection collection = CollectionBuilder.createCollection(context, community)
                .withName("Checksum check collection")
                .build();
        Item item = ItemBuilder.createItem(context, collection)
                .withTitle("Checksum check item")
                .withIssueDate("2026-09-10")
                .build();
        try (InputStream is = new ByteArrayInputStream(BITSTREAM_CONTENT.getBytes(StandardCharsets.UTF_8))) {
            bitstream = BitstreamBuilder.createBitstream(context, item, is)
                    .withName(BITSTREAM_NAME)
                    .withMimeType("text/plain")
                    .build();
        }
        context.restoreAuthSystemState();
        context.commit();
    }

    /**
     * Drops the checker rows before the builder teardown deletes the bitstream they reference.
     */
    @Override
    public void destroy() throws Exception {
        if (bitstream != null) {
            context.turnOffAuthorisationSystem();
            Bitstream reloaded = context.reloadEntity(bitstream);
            if (reloaded != null) {
                checksumHistoryService.deleteByBitstream(context, reloaded);
                mostRecentChecksumService.deleteByBitstream(context, reloaded);
            }
            context.restoreAuthSystemState();
            context.commit();
            bitstream = null;
        }
        super.destroy();
    }

    /**
     * Registers the fixture bitstream with the checker and back-dates its process dates, because the
     * dispatcher only picks up rows strictly older than the instant the check started.
     */
    private void registerForChecking(String expectedChecksum) throws Exception {
        context.turnOffAuthorisationSystem();
        mostRecentChecksumService.updateMissingBitstreams(context);
        MostRecentChecksum mostRecent = mostRecentChecksumService.findByBitstream(context, bitstream);
        assertNotNull("The checker did not register the fixture bitstream", mostRecent);
        Instant yesterday = Instant.now().minus(1, ChronoUnit.DAYS);
        mostRecent.setProcessStartDate(yesterday);
        mostRecent.setProcessEndDate(yesterday);
        mostRecent.setToBeProcessed(true);
        if (expectedChecksum != null) {
            mostRecent.setExpectedChecksum(expectedChecksum);
        }
        mostRecentChecksumService.update(context, mostRecent);
        context.restoreAuthSystemState();
        context.commit();

        MostRecentChecksum stored = mostRecentChecksumService.findByBitstream(context, bitstream);
        assertEquals("The checker record was not stored as set up, so this test would measure nothing",
                expectedChecksum == null ? stored.getExpectedChecksum() : expectedChecksum,
                stored.getExpectedChecksum());
    }

    /**
     * With a mismatching checksum the check must name the offending bitstream.
     */
    @Test
    public void checksumMismatchIsReportedWithBitstreamName() throws Exception {
        registerForChecking(IMPOSSIBLE_CHECKSUM);

        String report = new ChecksumCheck().run(new ReportInfo(1));

        assertTrue("The checksum check did not report the mismatch at all:\n" + report,
                report.contains("md5 checksum FAILED"));
        assertTrue("The checksum check reported a mismatch without naming the bitstream - the report is"
                        + " built from a detached entity:\n" + report,
                report.contains(BITSTREAM_NAME));
    }

    /**
     * Guards the healthy path, so that a fix which simply stops reporting cannot pass.
     */
    @Test
    public void healthyBitstreamIsReportedAsOk() throws Exception {
        registerForChecking(null);

        String report = new ChecksumCheck().run(new ReportInfo(1));

        assertTrue("The checksum check did not report the healthy bitstream:\n" + report,
                report.contains("checksum OK for"));
    }
}
