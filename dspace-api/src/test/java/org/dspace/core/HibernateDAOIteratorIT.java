/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import java.util.Iterator;

import org.dspace.AbstractIntegrationTestWithDatabase;
import org.dspace.content.MetadataValue;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.MetadataValueService;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.junit.Test;

/**
 * Checks that {@link AbstractHibernateDAO#iterate(jakarta.persistence.Query)} closes its Hibernate stream on
 * the owning thread when the iteration is exhausted, and not from a finalizer.
 */
public class HibernateDAOIteratorIT extends AbstractIntegrationTestWithDatabase {

    private final MetadataValueService metadataValueService =
            ContentServiceFactory.getInstance().getMetadataValueService();

    /**
     * No class in the returned iterator's hierarchy may declare a stream-closing finalizer.
     */
    @Test
    public void iterateIteratorMustNotCloseStreamFromFinalizer() throws Exception {
        Iterator<MetadataValue> iterator =
                metadataValueService.findByValueLike(context, "no-such-metadata-value-" + System.nanoTime());
        assertNotNull(iterator);

        // Walk the hierarchy, not just the anonymous leaf class.
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

        // Exhausting it here is what closes the cursor, and on the owning thread.
        while (iterator.hasNext()) {
            assertNotNull(iterator.next());
        }
    }

    /**
     * Once the iterator is exhausted the owning session must hold no registered JDBC resources.
     */
    @Test
    public void iterateIteratorMustReleaseJdbcResourcesOnExhaustion() throws Exception {
        Iterator<MetadataValue> iterator = metadataValueService.findByValueLike(context, "%");
        assertNotNull(iterator);

        while (iterator.hasNext()) {
            assertNotNull(iterator.next());
        }

        // Context.getDBConnection() is package-private and this test lives in org.dspace.core, so the
        // registry is reachable without reflection.
        SharedSessionContractImplementor session = ((org.hibernate.Session) context.getDBConnection().getSession())
                .unwrap(SharedSessionContractImplementor.class);
        assertFalse("AbstractHibernateDAO.iterate() left a JDBC resource registered after the iteration was"
                        + " exhausted - the Hibernate Stream is not being closed on the owning thread, so the"
                        + " statement leaks until the whole Session is closed.",
                session.getJdbcCoordinator().getLogicalConnection().getResourceRegistry()
                        .hasRegisteredResources());
    }
}
