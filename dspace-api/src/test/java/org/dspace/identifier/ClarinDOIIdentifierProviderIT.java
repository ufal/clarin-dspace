/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.identifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.dspace.AbstractIntegrationTestWithDatabase;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.ItemBuilder;
import org.dspace.content.Collection;
import org.dspace.content.Community;
import org.dspace.content.Item;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.ItemService;
import org.dspace.identifier.doi.ClarinDataCiteConnector;
import org.dspace.identifier.doi.DOIIdentifierException;
import org.dspace.identifier.doi.DOIIdentifierNotApplicableException;
import org.dspace.identifier.factory.IdentifierServiceFactory;
import org.dspace.identifier.service.DOIService;
import org.dspace.kernel.ServiceManager;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.dspace.versioning.factory.VersionServiceFactory;
import org.dspace.versioning.service.VersionHistoryService;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the ClarinDOIIdentifierProvider class.
 *
 * @author Milan Kuchtiak (kuchtiak@ufal.mff.cuni.cz)
 */
public class ClarinDOIIdentifierProviderIT extends AbstractIntegrationTestWithDatabase {

    private VersionHistoryService versionHistoryService;
    private ConfigurationService configurationService;
    private DOIService doiService;
    private ItemService itemService;

    ClarinDOIIdentifierProvider provider;

    private Item item1;
    private Item item2;
    private Item item3;

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();
        context.turnOffAuthorisationSystem();

        configurationService = DSpaceServicesFactory.getInstance().getConfigurationService();
        versionHistoryService = VersionServiceFactory.getInstance().getVersionHistoryService();
        doiService = IdentifierServiceFactory.getInstance().getDOIService();
        itemService = ContentServiceFactory.getInstance().getItemService();

        ServiceManager serviceManager = DSpaceServicesFactory.getInstance().getServiceManager();

        IdentifierServiceImpl identifierService = serviceManager.getServicesByType(IdentifierServiceImpl.class).get(0);

        // Clean out providers to avoid any being used for creation of community and collection
        identifierService.setProviders(new ArrayList<>());

        parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Parent Community 1")
                .build();
        Community parentCommunity2 = CommunityBuilder.createCommunity(context)
                .withName("Parent Community 2")
                .build();
        Community parentCommunity3 = CommunityBuilder.createCommunity(context)
                .withName("Parent Community 3")
                .build();

        Collection collection = CollectionBuilder.createCollection(context, parentCommunity)
                .withName("Collection 1")
                .build();
        Collection collection2 = CollectionBuilder.createCollection(context, parentCommunity2)
                .withName("Collection 2")
                .build();
        Collection collection3 = CollectionBuilder.createCollection(context, parentCommunity3)
                .withName("Collection 3")
                .build();

        item1 = ItemBuilder.createItem(context, collection)
                .withTitle("First Item")
                .build();
        item2 = ItemBuilder.createItem(context, collection2)
                .withTitle("Second Item")
                .build();
        item3 = ItemBuilder.createItem(context, collection3)
                .withTitle("Third Item")
                .build();

        context.restoreAuthSystemState();

        ClarinCommunityDOIIdentifierProvider communityProvider1 =
                createCommunityProvider("10.1", "1-", Set.of(parentCommunity.getID().toString()));

        ClarinCommunityDOIIdentifierProvider communityProvider2 =
                createCommunityProvider("10.2", "2-", Set.of(parentCommunity2.getID().toString()));

        provider = new ClarinDOIIdentifierProvider();
        provider.setProviders(List.of(communityProvider1, communityProvider2));

        provider.itemService = itemService;
        provider.contentServiceFactory = ContentServiceFactory.getInstance();
    }

    @Test
    public void testMint() throws IdentifierException, SQLException {
        String doi1 = provider.mint(context, item1);
        assertTrue(doi1.startsWith("doi:10.1/1-"));

        checkDoi(doi1, DOIIdentifierProvider.MINTED);

        String doi2 = provider.mint(context, item2);
        assertTrue(doi2.startsWith("doi:10.2/2-"));

        checkDoi(doi2, DOIIdentifierProvider.MINTED);

        // item3 is not mintable
        String doi3 = provider.mint(context, item3);
        assertNull(doi3);
    }

    @Test
    public void testCheckMintable() throws IdentifierException {
        provider.checkMintable(context, item1);
        provider.checkMintable(context, item2);
        // item3 is not mintable
        assertThrows(DOIIdentifierNotApplicableException.class, () -> provider.checkMintable(context, item3));
    }

    @Test
    public void testRegister() throws IdentifierException, SQLException {
        String doi1 = provider.register(context, item1);
        assertTrue(doi1.startsWith("doi:10.1/1-"));

        checkDoi(doi1, DOIIdentifierProvider.TO_BE_REGISTERED);

        // item3 is not mintable
        String doi3 = provider.register(context, item3);
        assertNull(doi3);
    }

    @Test
    public void tesReserve() throws IdentifierException, SQLException {
        String doi1 = "doi:10.1/res-1";
        String doi2 = "doi:10.1/res-2";
        provider.reserve(context, item1, doi1);

        checkDoi(doi1, DOIIdentifierProvider.MINTED);

        // trying to reserve the same DOI for another item should fail
        assertThrows(DOIIdentifierException.class, () -> provider.reserve(context, item2, doi1));

        // trying to reserve a DOI with wrong prefix should fail (10.1 prefix is reserved by community1)
        assertThrows(DOIIdentifierException.class, () -> provider.reserve(context, item2, doi2));

        // trying to reserve a DOI for an item3 that belongs to community not configured for DOI should fail
        // (object is not stored to DOI table)
        provider.reserve(context, item3, doi2);
        assertNull(doiService.findByDoi(context, doi2.substring(DOI.SCHEME.length())));
    }

    private ClarinCommunityDOIIdentifierProvider createCommunityProvider(String doiPrefix,
                                                                         String namespaceSeparator,
                                                                         Set<String> communityIds) {
        configurationService.setProperty("identifier.doi." + doiPrefix + ".user", "test_user");
        configurationService.setProperty("identifier.doi." + doiPrefix + ".password", "password");

        ClarinDataCiteConnector connector = new ClarinDataCiteConnector();

        ClarinCommunityDOIIdentifierProvider communityProvider = new ClarinCommunityDOIIdentifierProvider();
        communityProvider.setCommunities(communityIds);
        communityProvider.setDoiPrefix(doiPrefix);
        communityProvider.setNamespaceSeparator(namespaceSeparator);
        communityProvider.setConfigurationService(configurationService);
        communityProvider.setDOIConnector(connector);
        communityProvider.versionHistoryService = versionHistoryService;
        communityProvider.doiService = doiService;
        communityProvider.itemService = itemService;
        communityProvider.contentServiceFactory = ContentServiceFactory.getInstance();

        communityProvider.init();

        return communityProvider;
    }

    private void checkDoi(String doi, Integer expectedStatus) throws SQLException {
        DOI doiRow2 = doiService.findByDoi(context, doi.substring(DOI.SCHEME.length()));
        assertNotNull(doiRow2);
        assertEquals(expectedStatus, doiRow2.getStatus());
    }

}
