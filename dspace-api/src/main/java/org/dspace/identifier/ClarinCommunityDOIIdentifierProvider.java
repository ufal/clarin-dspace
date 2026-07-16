/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.identifier;

import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.dspace.authorize.AuthorizeException;
import org.dspace.content.DSpaceObject;
import org.dspace.content.Item;
import org.dspace.content.MetadataValue;
import org.dspace.content.logic.Filter;
import org.dspace.core.Context;

/**
 * DOI identifier provider for CLARIN communities.
 * It requires the DOI prefix and the list of CLARIN community IDs to be configured.
 * The DOI connector (a ClarinDataCiteConnector) is configured per provider in Spring, including its
 * credentials and DOI prefix — the connector's prefix must match this provider's prefix.
 *
 * @author Milan Kuchtiak (kuchtiak@ufal.mff.cuni.cz)
 */
public class ClarinCommunityDOIIdentifierProvider extends DOIIdentifierProvider {

    private static final Logger log = LogManager.getLogger(ClarinCommunityDOIIdentifierProvider.class);

    private String doiPrefix;

    private String namespaceSeparator;

    private Set<String> communities = new HashSet<>();

    public void init() {
        if (doiPrefix == null) {
            throw new IllegalStateException("No DOI prefix configured for ClarinCommunityDOIIdentifierProvider");
        }
        if (namespaceSeparator == null) {
            namespaceSeparator = "";
        }
    }

    /**
     * Mint a new DOI identifier for a given DSpaceObject. This method cleans up any old DOI metadata
     * that may be left on the item if it was versioned and the DOI metadata was
     * not removed from the original item before versioning.
     *
     * @param context    - DSpace context
     * @param dso        - DSpaceObject identified by the new identifier
     * @param filter     - Logical item filter to determine whether this identifier should be registered
     * @return minted DOI identifier for the given DSpaceObject
     */
    @Override
    public String mint(Context context, DSpaceObject dso, Filter filter) throws IdentifierException {
        if (!(dso instanceof Item)) {
            throw new IdentifierException("Currently only Items are supported for DOIs.");
        }
        Item item = (Item) dso;

        try {
            String doi = getDOIByObject(context, dso);
            if (doi != null) {
                return doi;
            }
        } catch (SQLException e) {
            log.error("Error while attempting to retrieve information about a DOI for "
                    + contentServiceFactory.getDSpaceObjectService(dso).getTypeText(dso) +
                    " with ID " + dso.getID() + ".");
            throw new RuntimeException("Error while attempting to retrieve " +
                    "information about a DOI for " +
                    contentServiceFactory.getDSpaceObjectService(dso).getTypeText(dso) +
                    " with ID " + dso.getID() + ".", e);
        }
        String doi;
        try {
            doi = loadOrCreateDOI(context, dso, null, filter).getDoi();
        } catch (SQLException e) {
            log.error("Error while creating new DOI for Object of " +
                    "ResourceType {} with id {}.", dso.getType(), dso.getID());
            throw new RuntimeException("Error while attempting to create a " +
                    "new DOI for " + contentServiceFactory.getDSpaceObjectService(dso).getTypeText(dso) +
                    " with ID " + dso.getID() + ".", e);
        }

        // check whether we have old DOI metadata and if we have - remove it
        // this metadata could be left on the item if it was versioned
        // and the DOI metadata was not removed from the original item before versioning
        String leftPart = doiService.getResolver() + SLASH + getPrefix() + SLASH + getNamespaceSeparator();
        List<MetadataValue> metadataToRemove =
                itemService.getMetadata(item, MD_SCHEMA, DOI_ELEMENT, DOI_QUALIFIER, Item.ANY)
                        .stream()
                        .filter(id -> id.getValue().startsWith(leftPart))
                        .collect(Collectors.toList());
        if (!metadataToRemove.isEmpty()) {
            log.debug("Found old DOI metadata that needs to be removed.");
            try {
                itemService.removeMetadataValues(context, item, metadataToRemove);
                itemService.update(context, item);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            } catch (AuthorizeException e) {
                throw new RuntimeException(
                        "Trying to remove an old DOI from a versioned item, but wasn't authorized to.", e);
            }
        }

        return doi.startsWith(DOI.SCHEME) ? doi : DOI.SCHEME + doi;
    }

    public void setDoiPrefix(String doiPrefix) {
        this.doiPrefix = doiPrefix;
    }

    public void setNamespaceSeparator(String namespaceSeparator) {
        this.namespaceSeparator = namespaceSeparator;
    }

    public void setCommunities(Set<String> communities) {
        this.communities = communities;
    }

    @Override
    protected String getPrefix() {
        return this.doiPrefix;
    }

    @Override
    protected String getNamespaceSeparator() {
        return this.namespaceSeparator;
    }

    Set<String> getCommunities() {
        return this.communities;
    }

}
