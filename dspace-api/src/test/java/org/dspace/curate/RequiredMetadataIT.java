/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.curate;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import java.io.IOException;

import org.dspace.AbstractIntegrationTestWithDatabase;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.ItemBuilder;
import org.dspace.content.Collection;
import org.dspace.content.Item;
import org.junit.Test;

/**
 * Test for requiredmetadata curation task.
 *
 * @author mkuchtiak
 */
public class RequiredMetadataIT extends AbstractIntegrationTestWithDatabase {
    private static final String TASK_NAME = "requiredmetadata";

    @Test
    public void testPerform() throws IOException {
        Curator curator = new Curator();
        curator.addTask(TASK_NAME);
        CuratorReportTest.ListReporter reporter = new CuratorReportTest.ListReporter();
        curator.setReporter(reporter);

        context.setCurrentUser(admin);
        parentCommunity = CommunityBuilder.createCommunity(context).build();

        Collection collection = CollectionBuilder.createCollection(context, parentCommunity, "123456789/113")
                                                 .build();
        Item item1 = ItemBuilder.createItem(context, collection)
                .withHandle("123456789/113-1")
                .build();

        Item item2 = ItemBuilder.createItem(context, collection)
                .withHandle("123456789/113-2")
                .withMetadata("dc", "title", null, "Test item")
                .withMetadata("dc", "date", "issued", "2025-07-23")
                .build();

        String failResult =
                "Item: 123456789/113-1 missing required field: dc.title. missing required field: dc.date.issued";
        String successResult = "Item: 123456789/113-2 has all required fields";

        // run curateTask for item1 - should fail
        curator.curate(context, item1);
        assertEquals("Curation should fail", Curator.CURATE_FAIL, curator.getStatus(TASK_NAME));
        assertEquals(failResult, curator.getResult(TASK_NAME));
        assertThat(reporter.getReport(), contains(failResult));
        reporter.getReport().clear();

        // run curateTask for item2 - should pass
        curator.curate(context, item2);
        assertEquals("Curation should succed", Curator.CURATE_SUCCESS, curator.getStatus(TASK_NAME));
        assertEquals(successResult, curator.getResult(TASK_NAME));
        assertThat(reporter.getReport(), contains(successResult));
        reporter.getReport().clear();

        // run curateTask for collection
        curator.curate(context, collection);
        assertThat(reporter.getReport(), containsInAnyOrder(failResult, successResult));
    }

}
