/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.content.dao.impl.clarin;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.persistence.Query;

import org.dspace.content.clarin.ClarinLicenseResourceMapping;
import org.dspace.content.dao.clarin.ClarinLicenseResourceMappingDAO;
import org.dspace.core.AbstractHibernateDAO;
import org.dspace.core.Context;

public class ClarinLicenseResourceMappingDAOImpl extends AbstractHibernateDAO<ClarinLicenseResourceMapping>
        implements ClarinLicenseResourceMappingDAO {
    protected ClarinLicenseResourceMappingDAOImpl() {
        super();
    }

    /**
     * Maximum number of UUIDs to include per query batch.
     */
    private static final int BATCH_SIZE = 10_000;

    @Override
    public List<ClarinLicenseResourceMapping> findByBitstreamUUID(Context context, UUID bitstreamUUID)
            throws SQLException {
        Query query = createQuery(context, "SELECT clrm " +
                "FROM ClarinLicenseResourceMapping clrm " +
                "WHERE clrm.bitstream.id = :bitstreamUUID");

        query.setParameter("bitstreamUUID", bitstreamUUID);
        query.setHint("org.hibernate.cacheable", Boolean.TRUE);

        return list(query);
    }

    @Override
    public List<ClarinLicenseResourceMapping> findByBitstreamUUIDs(Context context, List<UUID> bitstreamUUIDs)
        throws SQLException {
        if (bitstreamUUIDs == null || bitstreamUUIDs.isEmpty()) {
            return List.of();
        }
        List<ClarinLicenseResourceMapping> results = new ArrayList<>();

        for (int i = 0; i < bitstreamUUIDs.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, bitstreamUUIDs.size());
            List<UUID> batch = bitstreamUUIDs.subList(i, end);

            Query query = createQuery(context,
                    "SELECT clrm FROM ClarinLicenseResourceMapping clrm " +
                            "WHERE clrm.bitstream.id IN :bitstreamUUIDs");
            query.setParameter("bitstreamUUIDs", batch);
            query.setHint("org.hibernate.cacheable", Boolean.TRUE);

            results.addAll(list(query));
        }

        return results;
    }

    @Override
    public void delete(Context context, ClarinLicenseResourceMapping clarinLicenseResourceMapping) throws SQLException {
        clarinLicenseResourceMapping.setBitstream(null);
        super.delete(context, clarinLicenseResourceMapping);
    }
}
