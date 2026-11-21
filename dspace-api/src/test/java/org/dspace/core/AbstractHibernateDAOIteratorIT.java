/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.core;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import java.util.Iterator;

import org.dspace.AbstractIntegrationTestWithDatabase;
import org.dspace.content.MetadataValue;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.MetadataValueService;
import org.junit.Test;

/**
 * Regression test for the intermittent {@code ConcurrentModificationException} thrown during
 * {@code @After} integration-test cleanup and traced to
 * {@link AbstractHibernateDAO#iterate(javax.persistence.Query)}.
 *
 * <p>That method used to close its Hibernate {@code Stream} from a {@code finalize()} override. {@code finalize()}
 * runs on the GC Finalizer thread, so closing the stream there mutated the owning {@code Session}'s per-session,
 * non-thread-safe JDBC {@code ResourceRegistry} (xref) concurrently with the thread that owns the session. That
 * is a genuine data race which intermittently threw {@code ConcurrentModificationException} from
 * {@code ResourceRegistryStandardImpl.releaseResources} during an unrelated commit/rollback. The fix closes the
 * stream on the owning thread once the iteration is exhausted. This test guards against reintroducing any
 * stream-closing finalizer on the returned iterator.</p>
 */
public class AbstractHibernateDAOIteratorIT extends AbstractIntegrationTestWithDatabase {

    private final MetadataValueService metadataValueService =
            ContentServiceFactory.getInstance().getMetadataValueService();

    /**
     * Verifies that the iterator returned by {@link AbstractHibernateDAO#iterate(javax.persistence.Query)}
     * (exercised here through {@code MetadataValueService.findByValueLike}) does not close its backing Hibernate
     * stream from a {@code finalize()} override anywhere in its class hierarchy, and that it still iterates to
     * exhaustion (closing its cursor on the owning thread) without error. No matching rows are required - the
     * wrapper iterator is created regardless of the result count.
     *
     * @throws Exception passed through.
     */
    @Test
    public void iterateIteratorMustNotCloseStreamFromFinalizer() throws Exception {
        Iterator<MetadataValue> iterator =
                metadataValueService.findByValueLike(context, "no-such-metadata-value-" + System.nanoTime());
        assertNotNull(iterator);

        // The returned iterator - and every class in its hierarchy up to Object - MUST NOT declare a finalize()
        // override: closing the backing Hibernate Stream from the GC Finalizer thread is exactly the cross-thread
        // access to the non-thread-safe per-session JDBC ResourceRegistry that caused the flaky
        // ConcurrentModificationException. Walking the hierarchy also catches a finalizer reintroduced on a
        // superclass/helper rather than on the anonymous leaf class.
        for (Class<?> type = iterator.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            try {
                type.getDeclaredMethod("finalize");
                fail("AbstractHibernateDAO.iterate() iterator must not declare a finalize() override (found on "
                        + type.getName() + ") - closing the Hibernate Stream on the GC Finalizer thread races the "
                        + "owning thread's non-thread-safe JDBC ResourceRegistry and intermittently throws "
                        + "ConcurrentModificationException.");
            } catch (NoSuchMethodException expected) {
                // good: no stream-closing finalizer on this class
            }
        }

        // It must still iterate to exhaustion and close its cursor on THIS (the owning) thread without error.
        while (iterator.hasNext()) {
            assertNotNull(iterator.next());
        }
    }
}
