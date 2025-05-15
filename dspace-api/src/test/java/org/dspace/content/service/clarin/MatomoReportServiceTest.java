/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.content.service.clarin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.sql.SQLException;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.dspace.AbstractIntegrationTestWithDatabase;
import org.dspace.authorize.AuthorizeException;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.content.Collection;
import org.dspace.content.Community;
import org.dspace.content.Item;
import org.dspace.content.WorkspaceItem;
import org.dspace.content.clarin.MatomoReport;
import org.dspace.content.factory.ClarinServiceFactory;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.InstallItemService;
import org.dspace.content.service.WorkspaceItemService;
import org.junit.Before;
import org.junit.Test;

public class MatomoReportServiceTest extends AbstractIntegrationTestWithDatabase {
    private static final Logger log = LogManager.getLogger(MatomoReportServiceTest.class);

    protected InstallItemService installItemService = ContentServiceFactory.getInstance().getInstallItemService();
    protected WorkspaceItemService workspaceItemService = ContentServiceFactory.getInstance().getWorkspaceItemService();
    private final MatomoReportService matomoReportService = ClarinServiceFactory.getInstance().getMatomoReportService();

    Community community;
    Collection collection1;

    Item item;

    /**
     * This method will be run before every test as per @Before. It will
     * initialize resources required for the tests.
     */
    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();
        try {
            context.turnOffAuthorisationSystem();

            community = CommunityBuilder.createCommunity(context)
                .build();

            collection1 = CollectionBuilder.createCollection(context, community)
                .withEntityType("Publication")
                .build();

            WorkspaceItem is = workspaceItemService.create(context, collection1, false);

            item = installItemService.installItem(context, is);

            context.restoreAuthSystemState();
        } catch (AuthorizeException ex) {
            log.error("Authorization Error in init", ex);
            fail("Authorization Error in init: " + ex.getMessage());
        } catch (SQLException ex) {
            log.error("SQL Error in init", ex);
            fail("SQL Error in init: " + ex.getMessage());
        }
    }

    @Test
    public void testMatomoReportService() throws Exception {
        MatomoReport mr = new MatomoReport();
        mr.setItem(item);
        mr.setEPerson(eperson);
        MatomoReport expectedReport = new MatomoReport();
        expectedReport.setItem(item);
        expectedReport.setEPerson(eperson);

        context.setCurrentUser(admin);
        MatomoReport createdReport = matomoReportService.create(context, mr);
        expectedReport.setId(createdReport.getID());
        assertEquals(expectedReport, createdReport);

        MatomoReport retrievedReport = matomoReportService.find(context, createdReport.getID());
        assertEquals(expectedReport, retrievedReport);

        List<MatomoReport> retrievedReports = matomoReportService.findAll(context);
        context.turnOffAuthorisationSystem();
        assertEquals(1, retrievedReports.size());
        assertEquals(expectedReport, retrievedReports.get(0));

        matomoReportService.delete(context, createdReport);
        assertEquals(0, matomoReportService.findAll(context).size());

        MatomoReport mr1 = new MatomoReport();
        mr1.setItem(item);
        mr1.setEPerson(eperson);
        MatomoReport anotherReport = matomoReportService.create(context, mr1);
        assertTrue(anotherReport.getID() > 1);
        assertEquals(1, matomoReportService.findAll(context).size());
    }
}
