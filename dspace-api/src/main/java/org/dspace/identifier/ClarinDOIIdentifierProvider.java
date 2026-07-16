/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.identifier;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.dspace.content.Collection;
import org.dspace.content.Community;
import org.dspace.content.DSpaceObject;
import org.dspace.content.Item;
import org.dspace.content.MetadataValue;
import org.dspace.content.logic.Filter;
import org.dspace.core.Context;
import org.dspace.identifier.doi.DOIConnector;
import org.dspace.identifier.doi.DOIIdentifierException;
import org.dspace.identifier.doi.DOIIdentifierNotApplicableException;
import org.dspace.services.ConfigurationService;

/**
 * DOI identifier provider for CLARIN communities.
 * It delegates to individual ClarinCommunityDOIIdentifierProvider instances based on the item's community.
 *
 * @author Milan Kuchtiak (kuchtiak@ufal.mff.cuni.cz)
 */
public class ClarinDOIIdentifierProvider extends DOIIdentifierProvider {

    private static final Logger log = LogManager.getLogger(ClarinDOIIdentifierProvider.class);

    private List<ClarinCommunityDOIIdentifierProvider> providers;

    /**
     * Cache of the matched provider per collection, keyed by collection UUID. The matched provider is
     * a function of the collection's community ancestry (not of the item), so the cache is shared by all
     * items of a collection and is naturally bounded by the number of collections. An empty Optional
     * records that the collection is in no configured community, so negative lookups are cached too.
     * Entries live for the JVM lifetime: a collection moved to another community keeps its old provider
     * until restart.
     */
    protected final Map<UUID, Optional<ClarinCommunityDOIIdentifierProvider>> collectionProviderCache =
            new ConcurrentHashMap<>();

    public void setProviders(List<ClarinCommunityDOIIdentifierProvider> providers) {
        this.providers = providers;
    }

    @Override
    public void setDOIConnector(DOIConnector connector) {
        // Do nothing, as the connector is injected into the individual providers
    }

    @Override
    public void setConfigurationService(ConfigurationService configurationService) {
        // Do nothing, as the configuration service is injected into the individual providers
    }

    @Override
    public void register(Context context, DSpaceObject dso, String identifier, Filter filter)
            throws IdentifierException {
        if (dso instanceof Item) {
            ClarinCommunityDOIIdentifierProvider provider = getProviderForItem(context, (Item) dso);
            if (provider != null) {
                provider.register(context, dso, identifier, filter);
            } else {
                logInfoNonConfigurableEntity(dso, "registration");
            }
        } else {
            logInfoNoItem(dso, "registration");
        }
    }

    @Override
    public void registerOnline(Context context, DSpaceObject dso, String identifier, Filter filter)
            throws IdentifierException, IllegalArgumentException, SQLException {
        if (dso instanceof Item) {
            ClarinCommunityDOIIdentifierProvider provider = getProviderForItem(context, (Item) dso);
            if (provider != null) {
                provider.registerOnline(context, dso, identifier, filter);
            } else {
                logInfoNonConfigurableEntity(dso, "registration");
            }
        } else {
            logInfoNoItem(dso, "registration");
        }
    }

    @Override
    public void reserve(Context context, DSpaceObject dso, String identifier, Filter filter)
            throws IdentifierException, IllegalArgumentException {
        if (dso instanceof Item) {
            ClarinCommunityDOIIdentifierProvider provider = getProviderForItem(context, (Item) dso);
            if (provider != null) {
                provider.reserve(context, dso, identifier, filter);
            } else {
                logInfoNonConfigurableEntity(dso, "reservation");
            }
        } else {
            logInfoNoItem(dso, "reservation");
        }
    }

    @Override
    public void reserveOnline(Context context, DSpaceObject dso, String identifier, Filter filter)
            throws IdentifierException, IllegalArgumentException, SQLException {
        if (dso instanceof Item) {
            ClarinCommunityDOIIdentifierProvider provider = getProviderForItem(context, (Item) dso);
            if (provider != null) {
                provider.reserveOnline(context, dso, identifier, filter);
            } else {
                logInfoNonConfigurableEntity(dso, "reservation");
            }
        } else {
            logInfoNoItem(dso, "reservation");
        }
    }

    @Override
    public void checkMintable(Context context, Filter filter, DSpaceObject dso)
            throws DOIIdentifierNotApplicableException {
        if (dso instanceof Item) {
            ClarinCommunityDOIIdentifierProvider provider = getProviderForItem(context, (Item) dso);
            if (provider != null) {
                provider.checkMintable(context, filter, dso);
            } else {
                log.warn("Item {} is not in a configured CLARIN community", dso.getID());
                throw new DOIIdentifierNotApplicableException("Item " + dso.getID() +
                        " is not applicable by DOI identifier provider as it is not in a configured CLARIN community");
            }
        } else {
            log.warn("DSpaceObject {} is not an Item", dso.getID());
            throw new DOIIdentifierNotApplicableException("DSpaceObject " + dso.getID() +
                    " is not applicable by DOI identifier provider as it is not an Item");
        }
    }

    /**
     * Delete the given DOI online (at the registration agency) using the community provider whose
     * configured prefix matches the DOI.
     * <p>
     * Unlike the item-based operations above (register/reserve/mint/...), this method deliberately fails
     * loud when no provider matches the DOI's prefix: the lookup here is by DOI prefix alone, there is no
     * DSpaceObject available at delete time, so there is no item context in which "not in a configured
     * community" would be an expected, skippable state.
     * <p>
     * The only caller is DOIOrganiser's {@code --delete-doi} CLI path, which catches
     * {@link DOIIdentifierException} and reports the failure to the operator. The delegated
     * {@code deleteOnline} already throws {@link DOIIdentifierException} for other failure modes;
     * silently skipping an unknown prefix would leave the DOI row stuck in {@code TO_BE_DELETED}
     * with no visible error.
     *
     * @param context    the DSpace context
     * @param identifier the DOI to delete online
     * @throws DOIIdentifierException if no configured provider matches the DOI's prefix, or if the
     *                                 delegated online deletion fails
     */
    @Override
    public void deleteOnline(Context context, String identifier) throws DOIIdentifierException {
        getProviderForIdentifier(identifier).deleteOnline(context, identifier);
    }

    @Override
    public String mint(Context context, DSpaceObject dso, Filter filter) throws IdentifierException {
        if (dso instanceof Item) {
            ClarinCommunityDOIIdentifierProvider provider = getProviderForItem(context, (Item) dso);
            if (provider != null) {
                return provider.mint(context, dso, filter);
            } else {
                logInfoNonConfigurableEntity(dso, "minting");
            }
        } else {
            logInfoNoItem(dso, "minting");
        }
        return null;
    }

    @Override
    public String getDOIOutOfObject(DSpaceObject dso) throws DOIIdentifierException {
        if (!(dso instanceof Item)) {
            throw new IllegalArgumentException("We currently support DOIs for Items only, not for " +
                    contentServiceFactory.getDSpaceObjectService(dso).getTypeText(dso) + ".");
        }
        Item item = (Item) dso;

        List<MetadataValue> metadata = itemService.getMetadata(item, MD_SCHEMA, DOI_ELEMENT, DOI_QUALIFIER, Item.ANY);
        for (MetadataValue metadataValue : metadata) {
            String doiResolver = doiService.getResolver();
            for (ClarinCommunityDOIIdentifierProvider provider : providers) {
                String leftPart = doiResolver + "/" + provider.getPrefix() + "/" + provider.getNamespaceSeparator();
                if (metadataValue.getValue().startsWith(leftPart)) {
                    return doiService.DOIFromExternalFormat(metadataValue.getValue());
                }
            }
        }
        return null;
    }

    @Override
    public void updateMetadata(Context context, DSpaceObject dso, String identifier)
            throws IdentifierException, SQLException {
        if (dso instanceof Item) {
            ClarinCommunityDOIIdentifierProvider provider = getProviderForItem(context, (Item) dso);
            if (provider != null) {
                provider.updateMetadata(context, dso, identifier);
            } else {
                logInfoNonConfigurableEntity(dso, "metadata update");
            }
        } else {
            logInfoNoItem(dso, "metadata update");
        }
    }

    @Override
    public void updateMetadataOnline(Context context, DSpaceObject dso, String identifier)
            throws IdentifierException, SQLException {
        if (dso instanceof Item) {
            ClarinCommunityDOIIdentifierProvider provider = getProviderForItem(context, (Item) dso);
            if (provider != null) {
                provider.updateMetadataOnline(context, dso, identifier);
            } else {
                logInfoNonConfigurableEntity(dso, "metadata update");
            }
        } else {
            logInfoNoItem(dso, "metadata update");
        }
    }

    private ClarinCommunityDOIIdentifierProvider getProviderForItem(Context context, Item item) {

        // Check communities of item.getCollections() - this will only see collections if the item is archived
        for (Collection collection : item.getCollections()) {
            ClarinCommunityDOIIdentifierProvider provider = getProviderForCollection(context, collection);
            if (provider != null) {
                return provider;
            }
        }

        // Look for the parent object of the item. This is important as the item.getOwningCollection method
        // may return null, even though the item itself does have a parent object, at the point of archival
        try {
            DSpaceObject parent = itemService.getParentObject(context, item);
            if (parent instanceof Collection) {
                log.debug("Got parent DSO for item: " + parent.getID().toString());
                log.debug("Parent DSO handle: " + parent.getHandle());
                return getProviderForCollection(context, (Collection) parent);
            } else {
                log.debug("Parent DSO is null or is not a Collection...");
            }
        } catch (SQLException e) {
            log.error("Error obtaining parent DSO", e);
        }

        return null;
    }

    private ClarinCommunityDOIIdentifierProvider getProviderForCollection(Context context, Collection collection) {
        Optional<ClarinCommunityDOIIdentifierProvider> cached = collectionProviderCache.get(collection.getID());
        if (cached != null) {
            return cached.orElse(null);
        }
        ClarinCommunityDOIIdentifierProvider match = null;
        try {
            for (ClarinCommunityDOIIdentifierProvider provider : providers) {
                if (isCollectionInProvider(provider, collection)) {
                    match = provider;
                    break;
                }
            }
        } catch (SQLException e) {
            // don't cache on error, so a transient DB problem doesn't pin a wrong (negative) result
            log.error("Error while determining DOI provider for collection {}", collection.getID(), e);
            return null;
        }
        collectionProviderCache.put(collection.getID(), Optional.ofNullable(match));
        return match;
    }

    private boolean isCollectionInProvider(ClarinCommunityDOIIdentifierProvider provider,
                                           Collection collection) throws SQLException {
        List<Community> parentCommunities = collection.getCommunities();
        for (Community parentCommunity : parentCommunities) {
            if (provider.getCommunities().contains(parentCommunity.getID().toString())) {
                return true;
            }
            String parentCommunityHandle = parentCommunity.getHandle();
            if (parentCommunityHandle != null && provider.getCommunities().contains(parentCommunityHandle)) {
                return true;
            }
        }
        return false;
    }

    private ClarinCommunityDOIIdentifierProvider getProviderForIdentifier(String identifier)
            throws DOIIdentifierException {
        String doi = doiService.formatIdentifier(identifier).substring(DOI.SCHEME.length());
        String prefix = doi.split("/")[0];
        return providers.stream()
                .filter(provider -> provider.getPrefix().equals(prefix))
                .findFirst()
                .orElseThrow(() -> new DOIIdentifierException("No provider found for DOI prefix: " + prefix));
    }

    private void logInfoNoItem(DSpaceObject dso, String actionName) {
        log.info("DSpaceObject {} is not an Item, skipping DOI {}", dso.getID(), actionName);
    }

    private void logInfoNonConfigurableEntity(DSpaceObject dso, String actionName) {
        log.info("Item {} is not in a configured CLARIN community, skipping DOI {}", dso.getID(), actionName);
    }

}