/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.statistics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.servlet.http.HttpServletRequest;

import org.dspace.AbstractDSpaceTest;
import org.dspace.app.statistics.clarin.ClarinMatomoBitstreamTracker;
import org.dspace.content.Bitstream;
import org.dspace.content.Item;
import org.dspace.content.MetadataValue;
import org.dspace.content.service.BitstreamService;
import org.dspace.content.service.ItemService;
import org.dspace.content.service.clarin.ClarinItemService;
import org.dspace.core.Context;
import org.dspace.services.ConfigurationService;
import org.junit.Before;
import org.junit.Test;
import org.matomo.java.tracking.MatomoRequest;
import org.matomo.java.tracking.MatomoTracker;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;

/**
 * Test class for the ClarinMatomoBitstreamTracker. Test the tracking of bitstream downloads.
 *
 * @author Milan Majchrak (milan.majchrak at dataquest.sk)
 */
public class ClarinMatomoBitstreamTrackerTest extends AbstractDSpaceTest {

    private static final String HANDLE = "123456789/1";
    private static final String BASE_URL = "http://example.com";
    private static final String LOCALHOST_URL = "http://localhost:4000";
    private static final UUID ITEM_UUID = UUID.randomUUID();

    @Mock
    private ConfigurationService configurationService;

    @Mock
    private MatomoTracker matomoTracker;

    @Mock
    private HttpServletRequest request;

    @Mock
    private ClarinItemService clarinItemService;

    @Mock
    private ItemService itemService;

    @Mock
    private BitstreamService bitstreamService;

    @Mock
    private Bitstream bitstream;

    @InjectMocks
    private ClarinMatomoBitstreamTracker clarinMatomoBitstreamTracker;

    Context context;

    @Before
    public void setUp() {
        context = new Context();
        when(bitstream.getName()).thenReturn("Test bitstream");
    }

    @Test
    public void testTrackBitstreamDownload() throws SQLException {
        UUID bitstreamId = UUID.randomUUID();
        mockRequest("/bitstreams/" + bitstreamId + "/download");
        mockBitstreamAndItem(bitstreamId);
        when(bitstreamService.find(context, bitstreamId)).thenReturn(bitstream);
        when(matomoTracker.sendRequestAsync(any(MatomoRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        clarinMatomoBitstreamTracker.trackBitstreamDownload(context, request, bitstream, false);

        String expectedUrl = LOCALHOST_URL + "/bitstream/handle/" + HANDLE + "/" +
                URLEncoder.encode(bitstream.getName(), StandardCharsets.UTF_8);
        verifyMatomoRequest(expectedUrl, "Bitstream Download / Single File");
    }

    @Test
    public void testTrackBitstreamDownloadWrongUrl() throws SQLException {
        UUID bitstreamId = UUID.randomUUID();
        mockRequest("/bitstreams/NOT_EXISTING_UUID/download");
        mockBitstreamAndItem(bitstreamId);
        when(matomoTracker.sendRequestAsync(any(MatomoRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        clarinMatomoBitstreamTracker.trackBitstreamDownload(context, request, bitstream, false);

        String expectedUrl = BASE_URL + "/bitstreams/NOT_EXISTING_UUID/download";
        verifyMatomoRequest(expectedUrl, "Bitstream Download / Single File");
    }

    @Test
    public void testTrackZipDownload() throws SQLException {
        UUID bitstreamId = UUID.randomUUID();
        mockRequest("/bitstreams/" + bitstreamId + "/download");
        mockBitstreamAndItem(bitstreamId);
        when(bitstreamService.find(context, bitstreamId)).thenReturn(bitstream);
        when(matomoTracker.sendRequestAsync(any(MatomoRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        clarinMatomoBitstreamTracker.trackBitstreamDownload(context, request, bitstream, true);

        String expectedUrl = LOCALHOST_URL + "/bitstream/handle/" + HANDLE + "/" +
                URLEncoder.encode(bitstream.getName(), StandardCharsets.UTF_8);
        verifyMatomoRequest(expectedUrl, "Bitstream Download / Zip Archive");
    }

    @Test
    public void testSkipTrackingWithRangeHeader() throws SQLException {
        UUID bitstreamId = UUID.randomUUID();
        mockRequest("/bitstreams/" + bitstreamId + "/download");
        // Override Range header to be non-null
        when(request.getHeader("Range")).thenReturn("bytes=0-1000");
        mockBitstreamAndItem(bitstreamId);

        clarinMatomoBitstreamTracker.trackBitstreamDownload(context, request, bitstream, false);

        // Verify that no tracking request was sent
        verify(matomoTracker, times(0)).sendRequestAsync(any(MatomoRequest.class));
    }

    @Test
    public void testNoItemFound() throws SQLException {
        UUID bitstreamId = UUID.randomUUID();
        mockRequest("/bitstreams/" + bitstreamId + "/download");
        when(bitstream.getID()).thenReturn(bitstreamId);
        // Return empty list to simulate no items found
        when(clarinItemService.findByBitstreamUUID(context, bitstreamId)).thenReturn(Collections.emptyList());

        clarinMatomoBitstreamTracker.trackBitstreamDownload(context, request, bitstream, false);

        // Verify that no tracking request was sent
        verify(matomoTracker, times(0)).sendRequestAsync(any(MatomoRequest.class));
    }

    @Test
    public void testTrackBitstreamDownloadWithNullBitstream() throws SQLException {
        UUID bitstreamId = UUID.randomUUID();
        mockRequest("/bitstreams/" + bitstreamId + "/download");
        mockBitstreamAndItem(bitstreamId);
        when(bitstreamService.find(context, bitstreamId)).thenReturn(null);
        when(matomoTracker.sendRequestAsync(any(MatomoRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        clarinMatomoBitstreamTracker.trackBitstreamDownload(context, request, bitstream, false);

        String expectedUrl = BASE_URL + "/bitstreams/" + bitstreamId + "/download";
        verifyMatomoRequest(expectedUrl, "Bitstream Download / Single File");
    }

    private void mockRequest(String requestURI) {
        when(request.getRequestURI()).thenReturn(requestURI);
        when(request.getScheme()).thenReturn("http");
        when(request.getServerName()).thenReturn("example.com");
        when(request.getServerPort()).thenReturn(80);
        when(request.getHeader("Range")).thenReturn(null);
    }

    private void mockBitstreamAndItem(UUID bitstreamId) throws SQLException {
        when(bitstream.getID()).thenReturn(bitstreamId);
        Item item = mock(Item.class);
        when(item.getHandle()).thenReturn(HANDLE);
        when(clarinItemService.findByBitstreamUUID(context, bitstreamId)).thenReturn(Collections.singletonList(item));
        when(item.getID()).thenReturn(ITEM_UUID);

        MetadataValue metadataValue = mock(MetadataValue.class);
        when(metadataValue.getValue()).thenReturn("http://hdl.handle.net/" + HANDLE);
        List<MetadataValue> metadataValues = Collections.singletonList(metadataValue);
        when(itemService.getMetadata(item, "dc", "identifier", "uri",
                Item.ANY, false)).thenReturn(metadataValues);
    }

    private void verifyMatomoRequest(String expectedUrl, String pageName) {
        ArgumentCaptor<MatomoRequest> captor = ArgumentCaptor.forClass(MatomoRequest.class);
        verify(matomoTracker, times(1)).sendRequestAsync(captor.capture());

        MatomoRequest sentRequest = captor.getValue();
        assertNotNull(sentRequest);
        assertEquals(pageName, sentRequest.getActionName());
        assertEquals("Action URL should match the request URL", expectedUrl, sentRequest.getActionUrl());
        assertEquals("Item handle should be set as a dimension", HANDLE, sentRequest.getDimensions().get(1L));
    }
}