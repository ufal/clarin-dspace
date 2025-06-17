/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.repository;

import org.dspace.app.rest.model.EpicHandleRest;
import org.dspace.core.Context;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

/**
 * This is the repository responsible to manage Epic Handle Rest object
 *
 * @author Milan Kuchtiak
 */
@Component(EpicHandleRest.CATEGORY + "." + EpicHandleRest.NAME)
public class EpicHandleRestRepository extends DSpaceRestRepository<EpicHandleRest, Integer> {

    @Override
    public EpicHandleRest findOne(Context context, Integer prefix) {
        return null;
    }

    @Override
    public Page<EpicHandleRest> findAll(Context context, Pageable pageable) {
        return null;
    }

    @Override
    public Class<EpicHandleRest> getDomainClass() {
        return EpicHandleRest.class;
    }
}
