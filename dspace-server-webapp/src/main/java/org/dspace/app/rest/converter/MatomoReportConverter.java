/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.converter;

import org.dspace.app.rest.model.MatomoReportRest;
import org.dspace.app.rest.projection.Projection;
import org.dspace.content.clarin.MatomoReport;
import org.springframework.stereotype.Component;

/**
 * This is the converter from/to the MatomoReport in the DSpace API data model and the REST data model
 *
 * @author Milan Kuchtiak
 */
@Component
public class MatomoReportConverter implements DSpaceConverter<MatomoReport, MatomoReportRest> {

    @Override
    public MatomoReportRest convert(MatomoReport modelObject, Projection projection) {
        MatomoReportRest matomoReport = new MatomoReportRest();
        matomoReport.setProjection(projection);
        matomoReport.setId(modelObject.getID());
        matomoReport.setEpersonId(modelObject.getEPerson().getID().toString());
        matomoReport.setItemId(modelObject.getItem().getID().toString());
        return matomoReport;
    }

    @Override
    public Class<MatomoReport> getModelClass() {
        return MatomoReport.class;
    }
}
