/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.converter;

import org.dspace.app.rest.model.MatomoReportSubscriptionRest;
import org.dspace.app.rest.projection.Projection;
import org.dspace.content.clarin.MatomoReportSubscription;
import org.springframework.stereotype.Component;

/**
 * This is the converter from/to the MatomoReportSubscription in the DSpace API data model and the REST data model
 *
 * @author Milan Kuchtiak
 */
@Component
public class MatomoReportSubscriptionConverter
        implements DSpaceConverter<MatomoReportSubscription, MatomoReportSubscriptionRest> {

    @Override
    public MatomoReportSubscriptionRest convert(MatomoReportSubscription modelObject, Projection projection) {
        MatomoReportSubscriptionRest matomoReportSubscriptionRest = new MatomoReportSubscriptionRest();
        matomoReportSubscriptionRest.setProjection(projection);
        matomoReportSubscriptionRest.setId(modelObject.getID());
        matomoReportSubscriptionRest.setEpersonId(modelObject.getEPerson().getID().toString());
        matomoReportSubscriptionRest.setItemId(modelObject.getItem().getID().toString());
        return matomoReportSubscriptionRest;
    }

    @Override
    public Class<MatomoReportSubscription> getModelClass() {
        return MatomoReportSubscription.class;
    }
}
