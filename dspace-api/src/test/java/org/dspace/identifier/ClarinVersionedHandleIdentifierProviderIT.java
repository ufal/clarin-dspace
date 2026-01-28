/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.identifier;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.startsWith;
import static org.hamcrest.MatcherAssert.assertThat;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

import org.dspace.AbstractIntegrationTestWithDatabase;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.ItemBuilder;
import org.dspace.builder.VersionBuilder;
import org.dspace.content.Collection;
import org.dspace.content.Item;
import org.dspace.content.MetadataValue;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.InstallItemService;
import org.dspace.content.service.ItemService;
import org.dspace.kernel.ServiceManager;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.dspace.workflow.WorkflowItem;
import org.dspace.workflow.WorkflowItemService;
import org.dspace.workflow.factory.WorkflowServiceFactory;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit Tests for ClarinVersionedHandleIdentifierProvider
 *
 * @authorMilan Kuchtiak
 */
public class ClarinVersionedHandleIdentifierProviderIT extends AbstractIntegrationTestWithDatabase {
    private IdentifierServiceImpl identifierService;
    private InstallItemService installItemService;
    private ItemService itemService;
    private WorkflowItemService workflowItemService;

    private Collection collection;

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();
        context.turnOffAuthorisationSystem();

        ServiceManager serviceManager = DSpaceServicesFactory.getInstance().getServiceManager();
        identifierService = serviceManager.getServicesByType(IdentifierServiceImpl.class).get(0);

        itemService = ContentServiceFactory.getInstance().getItemService();
        installItemService = ContentServiceFactory.getInstance().getInstallItemService();
        workflowItemService = WorkflowServiceFactory.getInstance().getWorkflowItemService();

        // Clean out providers to avoid any being used for creation of community and collection
        identifierService.setProviders(new ArrayList<>());

        parentCommunity = CommunityBuilder.createCommunity(context)
                .withName("Parent Community")
                .build();
        collection = CollectionBuilder.createCollection(context, parentCommunity)
                .withName("Collection")
                .build();
    }

    @Test
    public void testNewVersionMetadata() throws Exception {
        registerProvider(ClarinVersionedHandleIdentifierProvider.class);
        Item itemV1 = ItemBuilder.createItem(context, collection)
                .withTitle("First version")
                .build();

        // new item "dc.relation.replaces" metadata has to be set to this value
        String itemV1HandleRef = itemService.getMetadataFirstValue(itemV1, "dc", "identifier", "uri", Item.ANY);

        // set "dc.relation.replaces" metadata on itemV1
        itemService.addMetadata(context, itemV1, "dc", "relation", "replaces", null, "some_value");
        // replace "dc.date.available" metadata on itemV1 to some old value
        itemService.clearMetadata(context, itemV1, "dc", "date", "available", Item.ANY);
        itemService.addMetadata(context, itemV1, "dc", "date", "available", null, "2020-01-01");
        // simulate itemV1 having a DOI identifier assigned
        itemService.addMetadata(context, itemV1, "dc", "identifier", "doi", null,
                "https://handle.stage.datacite.org/10.5072/dspace-1");

        Item itemV2 = VersionBuilder.createVersion(context, itemV1, "Second version").build().getItem();

        // check that "dc.date.available", metadata is not copied to itemV2
        assertThat(itemService.getMetadata(itemV2, "dc", "date", "available", Item.ANY).size(), equalTo(0));

        // check that "dc.identifier.uri", metadata is not copied to itemV2
        assertThat(itemService.getMetadata(itemV2, "dc", "identifier", "uri", Item.ANY).size(), equalTo(0));

        // check that "dc.identifier.doi", metadata is not copied to itemV2
        assertThat(itemService.getMetadata(itemV2, "dc", "identifier", "doi", Item.ANY).size(), equalTo(0));

        // check that "dc.relation.replaces" points to itemV1
        List<MetadataValue> metadataValues = itemService.getMetadata(itemV2, "dc", "relation", "replaces", Item.ANY);
        assertThat(metadataValues.size(), equalTo(1));
        assertThat(metadataValues.get(0).getValue(), equalTo(itemV1HandleRef));

        WorkflowItem workflowItem = workflowItemService.create(context, itemV2, collection);
        Item installedItem = installItemService.installItem(context, workflowItem);

        // get current date
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(System.currentTimeMillis());
        calendar.setTimeZone(TimeZone.getTimeZone("UTC"));
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
        String date = sdf.format(calendar.getTime());

        // check that "dc.relation.replaces" points to itemV1
        metadataValues = itemService.getMetadata(installedItem, "dc", "relation", "replaces", Item.ANY);
        assertThat(metadataValues.size(), equalTo(1));
        assertThat(metadataValues.get(0).getValue(), equalTo(itemV1HandleRef));

        // Check that itemV2 has the correct "dc.date.available" metadata set to current date
        metadataValues = itemService.getMetadata(installedItem, "dc", "date", "available", Item.ANY);
        assertThat(metadataValues.size(), equalTo(1));
        assertThat(metadataValues.get(0).getValue(), startsWith(date));

        // check "dc.identifier.uri" metadata has new value different from itemV1
        metadataValues = itemService.getMetadata(installedItem, "dc", "identifier", "uri", Item.ANY);
        assertThat(metadataValues.size(), equalTo(1));
        assertThat(metadataValues.get(0).getValue(), not(itemV1HandleRef));
    }

    private void registerProvider(Class type) {
        // Register our new provider
        IdentifierProvider identifierProvider =
                (IdentifierProvider) DSpaceServicesFactory.getInstance().getServiceManager()
                        .getServiceByName(type.getName(), type);
        if (identifierProvider == null) {
            DSpaceServicesFactory.getInstance().getServiceManager().registerServiceClass(type.getName(), type);
            identifierProvider = (IdentifierProvider) DSpaceServicesFactory.getInstance().getServiceManager()
                    .getServiceByName(type.getName(), type);
        }

        // Overwrite the identifier-service's providers with the new one to ensure only this provider is used
        identifierService = DSpaceServicesFactory.getInstance().getServiceManager()
                .getServicesByType(IdentifierServiceImpl.class).get(0);
        identifierService.setProviders(new ArrayList<>());
        identifierService.setProviders(List.of(identifierProvider));
    }
}
