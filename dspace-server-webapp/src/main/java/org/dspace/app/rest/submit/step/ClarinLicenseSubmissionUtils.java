/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.submit.step;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.dspace.app.rest.exception.ClarinLicenseNotFoundException;
import org.dspace.authorize.AuthorizeException;
import org.dspace.content.Bitstream;
import org.dspace.content.Bundle;
import org.dspace.content.Item;
import org.dspace.content.clarin.ClarinLicense;
import org.dspace.content.factory.ClarinServiceFactory;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.ItemService;
import org.dspace.content.service.clarin.ClarinLicenseResourceMappingService;
import org.dspace.content.service.clarin.ClarinLicenseService;
import org.dspace.core.Constants;
import org.dspace.core.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared logic for applying a CLARIN license selection to an in-progress
 * submission item. Used by both the legacy top-level {@code /license} patch
 * path in {@code WorkspaceItemRestRepository} and the section-scoped patch
 * path handled by {@link ClarinLicenseResourceStep}.
 *
 * @author Milan Majchrak (milan.majchrak at dataquest.sk)
 */
public final class ClarinLicenseSubmissionUtils {

    private static final Logger log = LoggerFactory.getLogger(ClarinLicenseSubmissionUtils.class);

    private ClarinLicenseSubmissionUtils() {
    }

    /**
     * Apply the given CLARIN license selection to the supplied item.
     * <ul>
     *     <li>Always clears the previously stored {@code dc.rights*}
     *         metadata and detaches any existing CLARIN license mapping
     *         from the bitstreams in the {@code ORIGINAL} bundle.</li>
     *     <li>If a non-blank {@code clarinLicenseName} is provided and
     *         resolves to an existing {@link ClarinLicense} the new
     *         license metadata is added to the item and the license is
     *         attached to every bitstream in the {@code ORIGINAL} bundle.
     *     </li>
     * </ul>
     *
     * @param context            DSpace context
     * @param item               the item being submitted
     * @param clarinLicenseName  name of the CLARIN license to apply, or
     *                           {@code null}/empty to clear the current
     *                           selection
     * @throws SQLException        on database errors
     * @throws AuthorizeException  on authorization errors
     * @throws ClarinLicenseNotFoundException if a non-empty name was
     *         supplied but no matching CLARIN license exists
     */
    public static void applyLicense(Context context, Item item, String clarinLicenseName)
            throws SQLException, AuthorizeException {
        if (Objects.isNull(item)) {
            log.info("Cannot apply CLARIN license, item is null.");
            return;
        }

        ClarinLicenseService clarinLicenseService =
                ClarinServiceFactory.getInstance().getClarinLicenseService();
        ClarinLicenseResourceMappingService clarinLicenseResourceMappingService =
                ClarinServiceFactory.getInstance().getClarinLicenseResourceMappingService();
        ItemService itemService = ContentServiceFactory.getInstance().getItemService();

        // Resolve license up-front so we fail before mutating state
        ClarinLicense clarinLicense = null;
        if (StringUtils.isNotBlank(clarinLicenseName)) {
            clarinLicense = clarinLicenseService.findByName(context, clarinLicenseName);
            if (Objects.isNull(clarinLicense)) {
                throw new ClarinLicenseNotFoundException(
                        "The CLARIN license with name: " + clarinLicenseName
                                + " isn't supported in the CLARIN/DSpace");
            }
        }

        // Clear existing license metadata from the item
        clarinLicenseService.clearLicenseMetadataFromItem(context, item);

        // Detach existing CLARIN licenses from the uploaded bitstreams
        List<Bundle> bundles = item.getBundles(Constants.CONTENT_BUNDLE_NAME);
        for (Bundle bundle : bundles) {
            for (Bitstream bitstream : bundle.getBitstreams()) {
                clarinLicenseResourceMappingService.detachLicenses(context, bitstream);
            }
        }

        if (Objects.isNull(clarinLicense)) {
            // Persist the cleared metadata state and stop.
            itemService.update(context, item);
            log.info("CLARIN license selection cleared on item {}.", item.getID());
            return;
        }

        // Attach the new CLARIN license to every bitstream and add metadata
        clarinLicenseService.addLicenseMetadataToItem(context, clarinLicense, item);
        for (Bundle bundle : bundles) {
            for (Bitstream bitstream : bundle.getBitstreams()) {
                clarinLicenseResourceMappingService.attachLicense(context, clarinLicense, bitstream);
            }
        }
        // Persist all metadata changes in a single update at the end.
        itemService.update(context, item);
        log.info("CLARIN license '{}' applied to item {}.", clarinLicenseName, item.getID());
    }
}
