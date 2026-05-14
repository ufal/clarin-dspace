/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.utils;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.dspace.app.rest.exception.DSpaceBadRequestException;
import org.dspace.app.rest.model.patch.Operation;
import org.dspace.app.rest.model.patch.ReplaceOperation;
import org.dspace.authorize.AuthorizeException;
import org.dspace.content.Bitstream;
import org.dspace.content.Bundle;
import org.dspace.content.InProgressSubmission;
import org.dspace.content.Item;
import org.dspace.content.clarin.ClarinLicense;
import org.dspace.content.service.ItemService;
import org.dspace.content.service.clarin.ClarinLicenseResourceMappingService;
import org.dspace.content.service.clarin.ClarinLicenseService;
import org.dspace.core.Constants;
import org.dspace.core.Context;

/**
 * Utility class to manage the CLARIN license in DSpace REST API.
 *
 * @author Milan Kuchtiak (Charles University, Prague, Czech Republic)
 */
public class ClarinLicenseUtils {

    private static final Logger log = LogManager.getLogger(ClarinLicenseUtils.class);

    private ClarinLicenseUtils() {}

    /**
     * Detach the clarin license from the bitstreams and if the clarin license is not null attach the
     * new clarin license to the bitstream.
     * @param context DSpace context object
     * @param itemService the item service
     * @param clarinLicenseService the clarin license service
     * @param clarinLicenseResourceMappingService the clarin license resource mapping service
     * @param source InProgressSubmission object which mai contains the item with bitstreams
     *               to which the clarin license is attached
     * @param op should be ReplaceOperation, if it is not - do nothing
     */
    public static void updateLicenseForItem(Context context,
                                            ItemService itemService,
                                            ClarinLicenseService clarinLicenseService,
                                            ClarinLicenseResourceMappingService clarinLicenseResourceMappingService,
                                            InProgressSubmission source,
                                            Operation op) throws SQLException, AuthorizeException {
        // Get item
        Item item = source.getItem();
        if (Objects.isNull(item)) {
            log.error("The item is null for the submission with id: {} so the clarin license cannot be updated.",
                    source.getID());
            return;
        }
        // Get value from operation
        if (!(op instanceof ReplaceOperation)) {
            log.error("The operation is not a replace operation");
            return;
        }

        String clarinLicenseName;
        if (!(op.getValue() instanceof String)) {
            throw new DSpaceBadRequestException("Missing value for operation: " + op.getOp());
        }

        clarinLicenseName = (String) op.getValue();

        // Get clarin license by definition
        ClarinLicense clarinLicense = clarinLicenseService.findByName(context, clarinLicenseName);
        if (StringUtils.isNotBlank(clarinLicenseName) && Objects.isNull(clarinLicense)) {
            throw new DSpaceBadRequestException("Cannot patch submission item with id: " + source.getID() + "," +
                    " because the clarin license with name: " + clarinLicenseName + " isn't supported in" +
                    " the CLARIN/DSpace");
        }

        // Clear the license metadata from the item
        clarinLicenseService.clearLicenseMetadataFromItem(context, item);

        // Detach the clarin licenses from the uploaded bitstreams
        List<Bundle> bundles = item.getBundles(Constants.CONTENT_BUNDLE_NAME);
        for (Bundle bundle : bundles) {
            List<Bitstream> bitstreamList = bundle.getBitstreams();
            for (Bitstream bitstream : bitstreamList) {
                // in case bitstream ID exists in license table for some reason .. just remove it
                clarinLicenseResourceMappingService.detachLicenses(context, bitstream);
            }
        }

        // Save changes to database
        itemService.update(context, item);

        if (Objects.isNull(clarinLicense)) {
            log.info("The clarin license is null so all item metadata for license was cleared and the" +
                    "licenses was detached.");
            return;
        }

        // If the clarin license is not null that means some clarin license was updated and accepted
        // Attach the new clarin license to every bitstream and add clarin license values to the item metadata.

        // update item metadata with license data
        clarinLicenseService.addLicenseMetadataToItem(context, clarinLicense, item);

        // Attach the clarin license to the bitstreams
        for (Bundle bundle : bundles) {
            List<Bitstream> bitstreamList = bundle.getBitstreams();
            for (Bitstream bitstream : bitstreamList) {
                // in case bitstream ID exists in license table for some reason .. just remove it
                clarinLicenseResourceMappingService.attachLicense(context, clarinLicense, bitstream);
            }
        }

        // Save changes to database
        itemService.update(context, item);
    }
}
