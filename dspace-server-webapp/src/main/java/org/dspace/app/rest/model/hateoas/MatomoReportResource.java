/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.model.hateoas;

import org.dspace.app.rest.model.MatomoReportRest;
import org.dspace.app.rest.model.hateoas.annotations.RelNameDSpaceResource;
import org.dspace.app.rest.utils.Utils;

/**
 * MatomoReport Rest HAL Resource. The HAL Resource wraps the REST Resource
 * adding support for the links and embedded resources
 *
 * @author Milan Kuchtiak
 */
@RelNameDSpaceResource(MatomoReportRest.NAME)
public class MatomoReportResource extends DSpaceResource<MatomoReportRest> {
    public MatomoReportResource(MatomoReportRest ms, Utils utils) {
        super(ms, utils);
    }
}
