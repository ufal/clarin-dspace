/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.content.dao.impl;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.Query;
import org.dspace.content.Bitstream;
import org.dspace.content.PreviewContent;
import org.dspace.content.dao.PreviewContentDAO;
import org.dspace.core.AbstractHibernateDAO;
import org.dspace.core.Context;

/**
 * Hibernate implementation of the Database Access Object interface class for the PreviewContent object.
 * This class should never be accessed directly.
 *
 * @author Michaela Paurikova (dspace at dataquest.sk)
 */
public class PreviewContentDAOImpl extends AbstractHibernateDAO<PreviewContent> implements PreviewContentDAO {

    protected PreviewContentDAOImpl() {
        super();
    }

    @Override
    public List<PreviewContent> findByBitstream(Context context, UUID bitstreamId) throws SQLException {
        Query query = createQuery(context, "SELECT pc FROM " + PreviewContent.class.getSimpleName() +
                        " as pc join pc.bitstream as b WHERE b.id = :bitstream_id");
        query.setParameter("bitstream_id", bitstreamId);
        query.setHint("org.hibernate.cacheable", Boolean.TRUE);
        return findMany(context, query);
    }

    @Override
    public boolean hasPreview(Context context, Bitstream bitstream) throws SQLException {
        Query query = createQuery(context,
                "SELECT COUNT(pc) FROM " + PreviewContent.class.getSimpleName() +
                        " pc WHERE pc.bitstream.id = :bitstream_id");
        query.setParameter("bitstream_id", bitstream.getID());
        return count(query) > 0;
    }

    @Override
    public List<PreviewContent> getPreview(Context context, Bitstream bitstream) throws SQLException {
        // select only previewcontent rows that are not a child in the preview2preview join table
        // (JPQL instead of the fork's native SQL: Hibernate 6 does not auto-flush pending changes
        // before an unsynchronized native query, which left this query blind to just-created rows)
        Query query = createQuery(context,
                "SELECT pc FROM " + PreviewContent.class.getSimpleName() + " pc " +
                        "WHERE pc.bitstream.id = :bitstream_id " +
                        "AND NOT EXISTS (SELECT 1 FROM " + PreviewContent.class.getSimpleName() + " parent " +
                        "JOIN parent.sub child WHERE child.id = pc.id)");
        query.setParameter("bitstream_id", bitstream.getID());
        query.setHint("org.hibernate.cacheable", Boolean.TRUE);
        return findMany(context, query);
    }
}
