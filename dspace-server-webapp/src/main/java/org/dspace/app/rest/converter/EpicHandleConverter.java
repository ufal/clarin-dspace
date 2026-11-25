/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.converter;

import org.dspace.app.rest.model.EpicHandleRest;
import org.dspace.app.rest.projection.Projection;
import org.dspace.handle.service.EpicHandleService;
import org.springframework.stereotype.Component;

/**
 * This is the converter from/to the Epic Handle in the DSpace API data model and the REST data model
 *
 * @author Milan Kuchtiak
 */
@Component
public class EpicHandleConverter
        implements DSpaceConverter<EpicHandleService.Handle, EpicHandleRest> {

    @Override
    public EpicHandleRest convert(EpicHandleService.Handle modelObject, Projection projection) {
        EpicHandleRest epicHandleRest = new EpicHandleRest();
        String handle = modelObject.getHandle();
        epicHandleRest.setId(handle);
        epicHandleRest.setUrl(modelObject.getUrl());
        epicHandleRest.setProjection(projection);
        return epicHandleRest;
    }

    @Override
    public Class<EpicHandleService.Handle> getModelClass() {
        return EpicHandleService.Handle.class;
    }
}