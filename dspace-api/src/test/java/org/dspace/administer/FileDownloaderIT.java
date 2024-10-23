/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.administer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

import java.util.List;

import org.dspace.AbstractIntegrationTestWithDatabase;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.ItemBuilder;
import org.dspace.content.Bitstream;
import org.dspace.content.Collection;
import org.dspace.content.Community;
import org.dspace.content.Item;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.BitstreamService;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockserver.junit.MockServerRule;


public class FileDownloaderIT extends AbstractIntegrationTestWithDatabase {

    @Rule
    public MockServerRule mockServerRule = new MockServerRule(this);

    private Item item;

    //Prepare a community and a collection before the test
    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();
        context.setCurrentUser(admin);
        Community community = CommunityBuilder.createCommunity(context).build();
        Collection collection = CollectionBuilder.createCollection(context, community).build();
        item = ItemBuilder.createItem(context, collection).withTitle("FileDownloaderIT Item").build();

        mockServerRule.getClient().when(request()
                .withMethod("GET")
                .withPath("/test400")
        ).respond(
                response()
                        .withStatusCode(400)
                        .withBody("test")
        );

        mockServerRule.getClient().when(request()
                .withMethod("GET")
                .withPath("/test")
        ).respond(
                response()
                        .withStatusCode(200)
                        .withHeader("Content-Disposition", "attachment; filename=\"test.txt\"")
                        .withBody("test")
        );
    }

    //Test that when an error occurs no bitstream is actually added to the item
    @Test()
    public void testDownloadFileError() throws Exception {


        BitstreamService bitstreamService = ContentServiceFactory.getInstance().getBitstreamService();
        int oldBitCount = bitstreamService.countTotal(context);

        int port = mockServerRule.getPort();
        String[] args = new String[]{"file-downloader", "-i", item.getID().toString(),
                "-u", String.format("http://localhost:%s/test400", port), "-e", "admin@email.com"};
        try {
            runDSpaceScript(args);
        } catch (IllegalArgumentException e) {
            assertEquals(0, item.getBundles().size());
            int newBitCount = bitstreamService.countTotal(context);
            assertEquals(oldBitCount, newBitCount);
            return;
        }
        assertEquals(0, 1);
    }


    //Test that FileDownlaoder actually adds the bitstream to the item
    @Test
    public void testDownloadFile() throws Exception {

          int port = mockServerRule.getPort();
          String[] args = new String[] {"file-downloader", "-i", item.getID().toString(),
                  "-u", String.format("http://localhost:%s/test", port), "-e", "admin@email.com"};
        runDSpaceScript(args);


        assertEquals(1, item.getBundles().size());
        List<Bitstream> bs = item.getBundles().get(0).getBitstreams();
        assertEquals(1, bs.size());
        assertNotNull("Expecting name to be defined", bs.get(0).getName());

    }

}