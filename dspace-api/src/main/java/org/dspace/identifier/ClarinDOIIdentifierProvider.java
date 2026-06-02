/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.identifier;

import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    protected final SimpleCache<UUID, ClarinCommunityDOIIdentifierProvider> simpleCache = new SimpleCache<>(5);

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
                log.info("Item {} is not in a configured CLARIN community, skipping DOI reservation", dso.getID());
            }
        } else {
            log.info("DSpaceObject {} is not an Item, skipping DOI reservation", dso.getID());
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
                log.info("Item {} is not in a configured CLARIN community, skipping DOI reservation", dso.getID());
            }
        } else {
            log.info("DSpaceObject {} is not an Item, skipping DOI reservation", dso.getID());
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
                log.info("Item {} is not in a configured CLARIN community, skipping DOI minting check", dso.getID());
                throw new DOIIdentifierNotApplicableException("Item " + dso.getID() +
                        " is not applicable by DOI identifier provider as it is not in a configured CLARIN community");
            }
        } else {
            log.info("DSpaceObject {} is not an Item, skipping DOI minting check", dso.getID());
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
                log.info("Item {} is not in a configured CLARIN community, skipping DOI registration", dso.getID());
            }
        } else {
            log.info("DSpaceObject {} is not an Item, skipping DOI registration", dso.getID());
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
                log.info("Item {} is not in a configured CLARIN community, skipping DOI reservation", dso.getID());
            }
        } else {
            log.info("DSpaceObject {} is not an Item, skipping DOI reservation", dso.getID());
        }
    }
    @Override
    public void deleteOnline(Context context, String identifier) throws DOIIdentifierException {
        ClarinCommunityDOIIdentifierProvider provider = getProviderForIdentifier(identifier);
        if (provider != null) {
            provider.deleteOnline(context, identifier);
        } else {
            log.info("No provider found for DOI {}, skipping DOI deletion", identifier);
        }
    }

    @Override
    public String mint(Context context, DSpaceObject dso, Filter filter) throws IdentifierException {
        if (dso instanceof Item) {
            ClarinCommunityDOIIdentifierProvider provider = getProviderForItem(context, (Item) dso);
            if (provider != null) {
                return provider.mint(context, dso, filter);
            } else {
                log.info("Item {} is not in a configured CLARIN community, skipping DOI minting", dso.getID());
            }
        } else {
            log.info("DSpaceObject {} is not an Item, skipping DOI minting", dso.getID());
        }
        return null;
    }

    @Override
    public String getDOIByObject(Context context, DSpaceObject dso) throws SQLException {
        if (dso instanceof Item) {
            ClarinCommunityDOIIdentifierProvider provider = getProviderForItem(context, (Item) dso);
            if (provider != null) {
                return provider.getDOIByObject(context, dso);
            } else {
                log.info("Item {} is not in a configured CLARIN community, skipping DOI searching", dso.getID());
            }
        } else {
            log.info("DSpaceObject {} is not an Item, skipping DOI searching", dso.getID());
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

        List<MetadataValue> metadata = itemService.getMetadata(item, MD_SCHEMA, DOI_ELEMENT, DOI_QUALIFIER, null);
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
    public void updateMetadataOnline(Context context, DSpaceObject dso, String identifier)
            throws IdentifierException, SQLException {
        if (dso instanceof Item) {
            ClarinCommunityDOIIdentifierProvider provider = getProviderForItem(context, (Item) dso);
            if (provider != null) {
                provider.updateMetadataOnline(context, dso, identifier);
            } else {
                log.info("Item {} is not in a configured CLARIN community, skipping DOI metadata update", dso.getID());
                throw new DOIIdentifierNotApplicableException("Item " + dso.getHandle() +
                        " is not applicable by DOI identifier provider as it is not in a configured CLARIN community");
            }
        } else {
            log.info("DSpaceObject {} is not an Item, skipping DOI update", dso.getID());
            throw new DOIIdentifierNotApplicableException("Currently only Items are supported for DOIs.");
        }
    }

    private ClarinCommunityDOIIdentifierProvider getProviderForItem(Context context, Item item) {

        if (simpleCache.containsKey(item.getID())) {
            return simpleCache.get(item.getID());
        }

        // Check communities of item.getCollections() - this will only see collections if the item is archived
        for (Collection collection : item.getCollections()) {
            try {
                for (ClarinCommunityDOIIdentifierProvider provider : providers) {
                    if (isCollectionInProvider(provider, collection)) {
                        simpleCache.put(item.getID(), provider);
                        return provider;
                    }
                }
            } catch (SQLException e) {
                log.error(e.getMessage());
            }
        }

        // Look for the parent object of the item. This is important as the item.getOwningCollection method
        // may return null, even though the item itself does have a parent object, at the point of archival
        try {
            DSpaceObject parent = itemService.getParentObject(context, item);
            if (parent instanceof Collection) {
                log.debug("Got parent DSO for item: " + parent.getID().toString());
                log.debug("Parent DSO handle: " + parent.getHandle());
                try {
                    // Now iterate communities of this parent collection
                    for (ClarinCommunityDOIIdentifierProvider provider : providers) {
                        if (isCollectionInProvider(provider, (Collection) parent)) {
                            simpleCache.put(item.getID(), provider);
                            return provider;
                        }
                    }
                } catch (SQLException e) {
                    log.error(e.getMessage());
                }
            } else {
                log.debug("Parent DSO is null or is not a Collection...");
            }
        } catch (SQLException e) {
            log.error("Error obtaining parent DSO", e);
        }

        simpleCache.put(item.getID(), null);
        return null;
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

    protected static class SimpleCache<K, V> {
        private final Map<K, V> cache;

        public SimpleCache(int maxCapacity) {
            this.cache = Collections.synchronizedMap(new LinkedHashMap<K, V>(maxCapacity, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                    return size() > maxCapacity;
                }
            });
        }

        public V get(K key) {
            return cache.get(key);
        }

        public void put(K key, V value) {
            cache.put(key, value);
        }

        public boolean containsKey(K key) {
            return cache.containsKey(key);
        }

        public void clear() {
            cache.clear();
        }
    }

}