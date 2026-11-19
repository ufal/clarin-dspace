/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.xoai.util;

import static org.junit.Assert.assertEquals;

import java.io.InputStream;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import com.lyncode.xoai.dataprovider.xml.xoai.Element;
import com.lyncode.xoai.dataprovider.xml.xoai.Metadata;
import org.apache.commons.io.IOUtils;
import org.dspace.app.rest.test.AbstractControllerIntegrationTest;
import org.dspace.authorize.factory.AuthorizeServiceFactory;
import org.dspace.authorize.service.AuthorizeService;
import org.dspace.builder.BitstreamBuilder;
import org.dspace.builder.ClarinLicenseBuilder;
import org.dspace.builder.ClarinLicenseResourceMappingBuilder;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.GroupBuilder;
import org.dspace.builder.ItemBuilder;
import org.dspace.builder.ResourcePolicyBuilder;
import org.dspace.content.Bitstream;
import org.dspace.content.Collection;
import org.dspace.content.Item;
import org.dspace.content.clarin.ClarinLicense;
import org.dspace.content.clarin.ClarinLicense.Confirmation;
import org.dspace.content.clarin.ClarinLicenseResourceMapping;
import org.dspace.content.factory.ClarinServiceFactory;
import org.dspace.content.service.clarin.ClarinLicenseResourceMappingService;
import org.dspace.content.service.clarin.ClarinLicenseService;
import org.dspace.core.Constants;
import org.dspace.eperson.Group;
import org.dspace.eperson.factory.EPersonServiceFactory;
import org.dspace.eperson.service.GroupService;
import org.junit.Before;
import org.junit.Test;

/**
 * others/anonymousAccess, which the CCMM crosswalk publishes as access_rights, has to follow what the
 * download actually does for an anonymous user: the Anonymous READ policy of every ORIGINAL bitstream
 * and the confirmation of its Clarin license.
 */
public class ItemUtilsAnonymousAccessIT extends AbstractControllerIntegrationTest {

    private final AuthorizeService authorizeService = AuthorizeServiceFactory.getInstance().getAuthorizeService();
    private final ClarinLicenseService clarinLicenseService =
        ClarinServiceFactory.getInstance().getClarinLicenseService();
    private final ClarinLicenseResourceMappingService mappingService =
        ClarinServiceFactory.getInstance().getClarinLicenseResourceMappingService();
    private final GroupService groupService = EPersonServiceFactory.getInstance().getGroupService();

    private Collection collection;
    private Group anonymous;

    @Before
    public void createCollection() throws Exception {
        context.turnOffAuthorisationSystem();
        parentCommunity = CommunityBuilder.createCommunity(context).withName("Community").build();
        collection = CollectionBuilder.createCollection(context, parentCommunity).withName("Collection").build();
        anonymous = groupService.findByName(context, Group.ANONYMOUS);
        context.restoreAuthSystemState();
    }

    @Test
    public void itemWithoutFilesIsMetadataOnly() throws Exception {
        Item item = item();
        assertEquals(ItemUtils.ACCESS_METADATA_ONLY, ItemUtils.getAnonymousAccess(context, item));
    }

    @Test
    public void anonymousReadAndNoLicenseIsOpen() throws Exception {
        Item item = item();
        file(item);
        assertEquals(ItemUtils.ACCESS_OPEN, ItemUtils.getAnonymousAccess(context, item));
    }

    @Test
    public void licenseThatAllowsAnonymousIsOpen() throws Exception {
        for (Confirmation confirmation : new Confirmation[] {Confirmation.NOT_REQUIRED,
            Confirmation.ALLOW_ANONYMOUS}) {
            Item item = item();
            license(file(item), confirmation);
            assertEquals(confirmation.name(), ItemUtils.ACCESS_OPEN, ItemUtils.getAnonymousAccess(context, item));
        }
    }

    @Test
    public void licenseThatRequiresSignInIsRestrictedDespiteAnonymousRead() throws Exception {
        for (Confirmation confirmation : new Confirmation[] {Confirmation.ASK_ONLY_ONCE,
            Confirmation.ASK_ALWAYS}) {
            Item item = item();
            license(file(item), confirmation);
            assertEquals(confirmation.name(), ItemUtils.ACCESS_RESTRICTED,
                ItemUtils.getAnonymousAccess(context, item));
        }
    }

    @Test
    public void noAnonymousReadIsRestrictedDespiteAPermissiveLicense() throws Exception {
        Item item = item();
        Bitstream bitstream = file(item);
        license(bitstream, Confirmation.NOT_REQUIRED);
        removeRead(bitstream);
        assertEquals(ItemUtils.ACCESS_RESTRICTED, ItemUtils.getAnonymousAccess(context, item));
    }

    @Test
    public void anonymousReadStartingInTheFutureIsEmbargo() throws Exception {
        Item item = item();
        embargo(file(item));
        assertEquals(ItemUtils.ACCESS_EMBARGO, ItemUtils.getAnonymousAccess(context, item));
    }

    @Test
    public void embargoedFileWithALicenseThatRequiresSignInIsRestricted() throws Exception {
        // the license keeps the file closed to anonymous users after the embargo ends too
        Item item = item();
        Bitstream bitstream = file(item);
        embargo(bitstream);
        license(bitstream, Confirmation.ASK_ALWAYS);
        assertEquals(ItemUtils.ACCESS_RESTRICTED, ItemUtils.getAnonymousAccess(context, item));
    }

    @Test
    public void oneClosedFileMakesTheItemRestricted() throws Exception {
        // access-status looks only at the primary bitstream; every file has to be downloadable
        Item item = item();
        file(item);
        removeRead(file(item));
        assertEquals(ItemUtils.ACCESS_RESTRICTED, ItemUtils.getAnonymousAccess(context, item));
    }

    @Test
    public void readOfAGroupContainingAnonymousIsOpen() throws Exception {
        // the download lets in every member of a group that has Anonymous as a subgroup
        Item item = item();
        Bitstream bitstream = file(item);
        removeRead(bitstream);
        read(bitstream, groupContainingAnonymous(), null);
        assertEquals(ItemUtils.ACCESS_OPEN, ItemUtils.getAnonymousAccess(context, item));
    }

    @Test
    public void futureReadOfAGroupContainingAnonymousIsEmbargo() throws Exception {
        Item item = item();
        Bitstream bitstream = file(item);
        removeRead(bitstream);
        read(bitstream, groupContainingAnonymous(), inThirtyDays());
        assertEquals(ItemUtils.ACCESS_EMBARGO, ItemUtils.getAnonymousAccess(context, item));
    }

    @Test
    public void readOfAGroupWithoutAnonymousIsRestricted() throws Exception {
        Item item = item();
        Bitstream bitstream = file(item);
        removeRead(bitstream);
        context.turnOffAuthorisationSystem();
        Group readers = GroupBuilder.createGroup(context).withName("Readers without Anonymous").build();
        context.restoreAuthSystemState();
        read(bitstream, readers, null);
        assertEquals(ItemUtils.ACCESS_RESTRICTED, ItemUtils.getAnonymousAccess(context, item));
    }

    @Test
    public void retrieveMetadataPublishesTheValueUnderOthers() throws Exception {
        Item item = item();
        file(item);
        Metadata metadata = ItemUtils.retrieveMetadata(context, item);
        Element others = ItemUtils.getElement(metadata.getElement(), "others");
        String value = others.getField().stream()
            .filter(f -> "anonymousAccess".equals(f.getName()))
            .map(Element.Field::getValue)
            .findFirst().orElse(null);
        assertEquals(ItemUtils.ACCESS_OPEN, value);
    }

    @Test
    public void retrieveMetadataMarksWhetherAnonymousCanReadEachFile() throws Exception {
        // the CCMM crosswalk leaves out files an anonymous visitor cannot see; a licence only gates
        // the download, so a licence-gated file still counts as readable
        Item item = item();
        file(item, "open.txt");
        embargo(file(item, "embargoed.txt"));
        removeRead(file(item, "private.txt"));
        license(file(item, "licensed.txt"), Confirmation.ASK_ALWAYS);
        Bitstream viaGroup = file(item, "group.txt");
        removeRead(viaGroup);
        read(viaGroup, groupContainingAnonymous(), null);

        Map<String, String> flags = anonymousReadByFileName(item);
        assertEquals("true", flags.get("open.txt"));
        assertEquals("false", flags.get("embargoed.txt"));
        assertEquals("false", flags.get("private.txt"));
        assertEquals("true", flags.get("licensed.txt"));
        assertEquals("true", flags.get("group.txt"));
    }

    private Map<String, String> anonymousReadByFileName(Item item) throws Exception {
        Metadata metadata = ItemUtils.retrieveMetadata(context, item);
        Element bundles = ItemUtils.getElement(metadata.getElement(), "bundles");
        Map<String, String> flags = new HashMap<>();
        for (Element bundle : bundles.getElement()) {
            for (Element bitstream : ItemUtils.getElement(bundle.getElement(), "bitstreams").getElement()) {
                flags.put(fieldValue(bitstream, "name"), fieldValue(bitstream, "anonymousRead"));
            }
        }
        return flags;
    }

    private static String fieldValue(Element element, String name) {
        return element.getField().stream()
            .filter(f -> name.equals(f.getName()))
            .map(Element.Field::getValue)
            .findFirst().orElse(null);
    }

    private Item item() {
        context.turnOffAuthorisationSystem();
        Item item = ItemBuilder.createItem(context, collection).withTitle("Item").build();
        context.restoreAuthSystemState();
        return item;
    }

    private Bitstream file(Item item) throws Exception {
        return file(item, "data.txt");
    }

    private Bitstream file(Item item, String name) throws Exception {
        context.turnOffAuthorisationSystem();
        try (InputStream is = IOUtils.toInputStream("data", "UTF-8")) {
            Bitstream bitstream = BitstreamBuilder.createBitstream(context, item, is).withName(name).build();
            context.restoreAuthSystemState();
            return bitstream;
        }
    }

    private void license(Bitstream bitstream, Confirmation confirmation) throws Exception {
        context.turnOffAuthorisationSystem();
        ClarinLicense license = ClarinLicenseBuilder.createClarinLicense(context).build();
        license.setName("License " + confirmation.name());
        license.setConfirmation(confirmation);
        clarinLicenseService.update(context, license);
        ClarinLicenseResourceMapping mapping =
            ClarinLicenseResourceMappingBuilder.createClarinLicenseResourceMapping(context).build();
        mapping.setLicense(license);
        mapping.setBitstream(bitstream);
        mappingService.update(context, mapping);
        context.restoreAuthSystemState();
    }

    private void embargo(Bitstream bitstream) throws Exception {
        removeRead(bitstream);
        read(bitstream, anonymous, inThirtyDays());
    }

    private void read(Bitstream bitstream, Group group, Date startDate) throws Exception {
        context.turnOffAuthorisationSystem();
        ResourcePolicyBuilder.createResourcePolicy(context, null, group)
            .withDspaceObject(bitstream)
            .withAction(Constants.READ)
            .withStartDate(startDate)
            .build();
        context.restoreAuthSystemState();
    }

    private Group groupContainingAnonymous() throws Exception {
        context.turnOffAuthorisationSystem();
        Group readers = GroupBuilder.createGroup(context).withName("Readers with Anonymous").build();
        groupService.addMember(context, readers, anonymous);
        groupService.update(context, readers);
        context.restoreAuthSystemState();
        return readers;
    }

    private static Date inThirtyDays() {
        return new Date(System.currentTimeMillis() + 30L * 24 * 3600 * 1000);
    }

    private void removeRead(Bitstream bitstream) throws Exception {
        context.turnOffAuthorisationSystem();
        authorizeService.removePoliciesActionFilter(context, bitstream, Constants.READ);
        context.restoreAuthSystemState();
    }
}
