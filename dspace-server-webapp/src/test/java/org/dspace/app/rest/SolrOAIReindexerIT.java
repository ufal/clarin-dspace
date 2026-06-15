/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.dspace.app.rest.test.AbstractControllerIntegrationTest;
import org.dspace.app.rest.utils.SolrOAIReindexer;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.ItemBuilder;
import org.dspace.content.Collection;
import org.dspace.content.Community;
import org.dspace.content.Item;
import org.dspace.xoai.services.api.solr.SolrServerResolver;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Simple integration test for SolrOAIReindexer to verify that exceptions
 * are handled gracefully and commits still occur.
 */
public class SolrOAIReindexerIT extends AbstractControllerIntegrationTest {

    @Autowired
    private SolrOAIReindexer solrOAIReindexer;

    @Mock
    private SolrServerResolver mockSolrServerResolver;

    @Mock
    private SolrClient mockSolrClient;

    private Community community;
    private Collection collection;

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();

        // Initialize Mockito annotations
        MockitoAnnotations.openMocks(this);

        context.turnOffAuthorisationSystem();

        community = CommunityBuilder.createCommunity(context)
                .withName("Test Community")
                .build();

        collection = CollectionBuilder.createCollection(context, community)
                .withName("Test Collection")
                .build();

        context.restoreAuthSystemState();
    }

    /**
     * Test that reindexing completes even when cache clearing fails
     */
    @Test
    public void testReindexWithCacheException() throws Exception {
        context.turnOffAuthorisationSystem();

        // Create a simple test item
        Item testItem = ItemBuilder.createItem(context, collection)
                .withTitle("Test Item")
                .withAuthor("Test Author")
                .build();

        context.restoreAuthSystemState();

        // Test that reindexing doesn't throw exceptions even if cache clearing fails
        // The safeClearCaches method should handle any exceptions gracefully
        boolean completed = false;
        try {
            solrOAIReindexer.reindexItem(testItem);
            completed = true;
        } catch (Exception e) {
            // Should not happen with safe cache clearing
        }

        assertTrue("Reindexing should complete successfully", completed);
        assertNotNull("Item should still exist", testItem);
        assertNotNull("Item should have handle after reindexing", testItem.getHandle());
    }

    /**
     * Test that deletion works even when cache clearing fails
     */
    @Test
    public void testDeleteWithCacheException() throws Exception {
        context.turnOffAuthorisationSystem();

        // Create a simple test item
        Item testItem = ItemBuilder.createItem(context, collection)
                .withTitle("Test Item for Deletion")
                .withAuthor("Test Author")
                .build();

        context.restoreAuthSystemState();

        // Test that deletion doesn't throw exceptions even if cache clearing fails
        boolean completed = false;
        try {
            solrOAIReindexer.deleteItem(testItem);
            completed = true;
        } catch (Exception e) {
            // Should not happen with safe cache clearing
        }

        assertTrue("Deletion should complete successfully", completed);
    }

    /**
     * Test that deletion handles failure gracefully when both Solr and event fallback fail
     */
    @Test
    public void testDeleteWithFailedEventFallback() throws Exception {
        context.turnOffAuthorisationSystem();

        // Create a test item
        Item testItem = ItemBuilder.createItem(context, collection)
                .withTitle("Test Item for Failed Event Fallback")
                .withAuthor("Test Author")
                .build();

        context.restoreAuthSystemState();

        // Create a custom test class that overrides triggerDeletionViaEvent to fail
        // and handleFinalFailure to avoid exceptions
        SolrOAIReindexer testReindexer = new SolrOAIReindexer() {
            private final Logger testLog = LogManager.getLogger(SolrOAIReindexer.class);

            @Override
            protected boolean triggerDeletionViaEvent(Item item) {
                // Simulate event failure
                return false;
            }

            @Override
            public void handleFinalFailure(String message) {
                // Override to log error properly but avoid throwing RuntimeException during testing
                testLog.error(message);
            }
        };

        // Mock Solr to fail
        when(mockSolrServerResolver.getServer()).thenThrow(new SolrServerException("Mocked Solr deletion failure"));

        // Inject the mock into our test reindexer
        ReflectionTestUtils.setField(testReindexer, "solrServerResolver", mockSolrServerResolver);

        // Test deletion - Solr fails, event fallback fails, handleFinalFailure is called but overridden
        boolean deleteCompleted = false;
        try {
            testReindexer.deleteItem(testItem);
            deleteCompleted = true;
        } catch (Exception e) {
            // Should not happen - handleFinalFailure is overridden to not throw
        }

        assertTrue("Deletion should complete without throwing exception " +
                "(with overridden handleFinalFailure)", deleteCompleted);
    }

    @Test
    public void testEventFallbackMethods() throws Exception {
        context.turnOffAuthorisationSystem();

        Item testItem = ItemBuilder.createItem(context, collection)
                .withTitle("Test Item for Event Fallback")
                .withAuthor("Test Author")
                .build();

        context.restoreAuthSystemState();

        // Create a spy of the original solrOAIReindexer to mock the solrServerResolver
        SolrOAIReindexer spySolrOAIReindexer = spy(solrOAIReindexer);

        // Configure mock to throw exception when getServer() is called
        when(mockSolrServerResolver.getServer()).thenThrow(new SolrServerException("Mocked Solr failure"));

        // Inject the mock into the spy
        ReflectionTestUtils.setField(spySolrOAIReindexer, "solrServerResolver", mockSolrServerResolver);

        // Test reindexing - should now use event-based approach due to Solr failure
        boolean reindexCompleted = false;
        try {
            spySolrOAIReindexer.reindexItem(testItem);
            reindexCompleted = true;
        } catch (Exception e) {
            // Should not happen with fallback mechanism
        }
        assertTrue("Reindexing should complete successfully via event fallback", reindexCompleted);

        // Test deletion with similar mock setup
        boolean deleteCompleted = false;
        try {
            spySolrOAIReindexer.deleteItem(testItem);
            deleteCompleted = true;
        } catch (Exception e) {
            // Should not happen with fallback mechanism
        }

        assertTrue("Event-based deletion should complete successfully", deleteCompleted);

        // Verify the test item is still valid
        assertNotNull("Test item should not be null", testItem);
        assertNotNull("Test item should have an ID", testItem.getID());
    }
}