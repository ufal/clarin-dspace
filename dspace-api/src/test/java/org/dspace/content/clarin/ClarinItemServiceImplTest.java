/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.content.clarin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.dspace.content.Item;
import org.dspace.content.MetadataValue;
import org.dspace.content.service.ItemService;
import org.dspace.core.Context;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Pure unit tests for {@link ClarinItemServiceImpl}: the {@code dc.date.issued} derivation
 * from {@code local.approximateDate.issued}, and the normalization guard in
 * {@code updateItemDatesMetadata}. Fully mocked — no DSpace kernel or database.
 *
 * @author dataquest
 */
public class ClarinItemServiceImplTest {

    private ItemService itemService;
    private ClarinItemServiceImpl clarinItemService;
    private Item item;
    private Context context;

    @Before
    public void setUp() {
        itemService = mock(ItemService.class);
        item = mock(Item.class);
        context = mock(Context.class);
        clarinItemService = new ClarinItemServiceImpl();
        ReflectionTestUtils.setField(clarinItemService, "itemService", itemService);
    }

    private MetadataValue mv(String value) {
        MetadataValue metadataValue = mock(MetadataValue.class);
        when(metadataValue.getValue()).thenReturn(value);
        return metadataValue;
    }

    private void mockApproximateDate(String value) {
        List<MetadataValue> values = value == null ? Collections.emptyList() : Collections.singletonList(mv(value));
        when(itemService.getMetadata(item, "local", "approximateDate", "issued", Item.ANY, false))
                .thenReturn(values);
    }

    private void mockCurrentDateIssued(List<MetadataValue> values) {
        when(itemService.getMetadata(item, "dc", "date", "issued", Item.ANY, false)).thenReturn(values);
    }

    // ---- deriveDateIssuedFromApproximateDate (pure, no DB) ----

    @Test
    public void derive_returnsNull_whenNoApproximateDate() {
        mockApproximateDate(null);
        assertNull(clarinItemService.deriveDateIssuedFromApproximateDate(item));
    }

    @Test
    public void derive_returnsNull_whenApproximateDateBlank() {
        mockApproximateDate("   ");
        assertNull(clarinItemService.deriveDateIssuedFromApproximateDate(item));
    }

    @Test
    public void derive_returnsNoYear_whenNonNumeric() {
        mockApproximateDate("spring 1945");
        assertEquals("0000", clarinItemService.deriveDateIssuedFromApproximateDate(item));
    }

    @Test
    public void derive_returnsLastYear_whenNumericSequence() {
        mockApproximateDate("1938, 1945, 2022");
        assertEquals("2022", clarinItemService.deriveDateIssuedFromApproximateDate(item));
    }

    @Test
    public void derive_returnsSingleYear() {
        mockApproximateDate("1990");
        assertEquals("1990", clarinItemService.deriveDateIssuedFromApproximateDate(item));
    }

    // ---- updateItemDatesMetadata: skip-write / normalization guard ----

    @Test
    public void update_skipsWrite_whenSingleValueAlreadyDerived() throws SQLException {
        mockApproximateDate("2022");
        mockCurrentDateIssued(Collections.singletonList(mv("2022")));

        clarinItemService.updateItemDatesMetadata(context, item);

        verify(itemService, never()).clearMetadata(context, item, "dc", "date", "issued", Item.ANY);
        verify(itemService, never()).addMetadata(context, item, "dc", "date", "issued", Item.ANY, "2022");
    }

    @Test
    public void update_normalizesMultiValue_evenWhenFirstMatchesDerived() throws SQLException {
        // Regression guard: a multi-valued dc.date.issued must still be collapsed to the single derived value,
        // even if the first stored value already equals the derived one.
        mockApproximateDate("2022");
        mockCurrentDateIssued(Arrays.asList(mv("2022"), mv("1999")));

        clarinItemService.updateItemDatesMetadata(context, item);

        verify(itemService).clearMetadata(context, item, "dc", "date", "issued", Item.ANY);
        verify(itemService).addMetadata(context, item, "dc", "date", "issued", Item.ANY, "2022");
    }

    @Test
    public void update_writes_whenSingleValueDiffers() throws SQLException {
        mockApproximateDate("2022");
        mockCurrentDateIssued(Collections.singletonList(mv("1900")));

        clarinItemService.updateItemDatesMetadata(context, item);

        verify(itemService).clearMetadata(context, item, "dc", "date", "issued", Item.ANY);
        verify(itemService).addMetadata(context, item, "dc", "date", "issued", Item.ANY, "2022");
    }

    @Test
    public void update_skips_whenApproximateDateEmpty() throws SQLException {
        mockApproximateDate(null);

        clarinItemService.updateItemDatesMetadata(context, item);

        verify(itemService, never()).clearMetadata(context, item, "dc", "date", "issued", Item.ANY);
    }
}
