/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.health;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import org.dspace.authorize.ResourcePolicy;
import org.dspace.content.Bitstream;
import org.dspace.content.Bundle;
import org.dspace.content.Collection;
import org.dspace.content.Community;
import org.dspace.content.Item;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.CollectionService;
import org.dspace.content.service.CommunityService;
import org.dspace.content.service.ItemService;
import org.dspace.core.Context;

/**
 * This check identifies DSpace objects that have a start date or end date defined in their resource policies.
 * @author Matus Kasak (dspace at dataquest.sk)
 */
public class EmbargoInfoCheck extends Check {

    private final List<EmbargoInfo> embItems = new ArrayList<>();
    private final List<EmbargoInfo> embBitstreams = new ArrayList<>();
    private final List<EmbargoInfo> embBundles = new ArrayList<>();
    private final List<EmbargoInfo> embComs = new ArrayList<>();
    private final List<EmbargoInfo> embCols = new ArrayList<>();

    private static final int DISPLAY_THRESHOLD = 50;
    // Separator line width chosen to align with table column layout
    private static final int TABLE_WIDTH = 113;

    @Override
    public String run(ReportInfo ri) {
        StringBuilder sb = new StringBuilder();

        ItemService itemService = ContentServiceFactory.getInstance().getItemService();
        CollectionService collectionService = ContentServiceFactory.getInstance().getCollectionService();
        CommunityService communityService = ContentServiceFactory.getInstance().getCommunityService();

        // Traverse all objects (items, bundles, bitstreams, collections, communities)
        // and collect embargo information from their associated resource policies.
        try (Context context = new Context()) {
            Iterator<Item> items = itemService.findAll(context);
            while (items.hasNext()) {
                Item item = items.next();
                collectEmbargoedObjectInfos(item.getResourcePolicies(), embItems, item.getID(), null);

                for (Bundle bundle : item.getBundles()) {
                    collectEmbargoedObjectInfos(bundle.getResourcePolicies(), embBundles, bundle.getID(), item.getID());
                    for (Bitstream bitstream : bundle.getBitstreams()) {
                        collectEmbargoedObjectInfos(
                                bitstream.getResourcePolicies(), embBitstreams, bitstream.getID(), item.getID());
                    }
                }
            }

            for (Collection col : collectionService.findAll(context)) {
                collectEmbargoedObjectInfos(col.getResourcePolicies(), embCols, col.getID(), null);
            }

            for (Community com : communityService.findAll(context)) {
                collectEmbargoedObjectInfos(com.getResourcePolicies(), embComs, com.getID(), null);
            }

        } catch (SQLException e) {
            throw new RuntimeException("Error while fetching items, collections or communities ", e);
        }

        appendReport(sb, "Items", embItems, false);
        appendReport(sb, "Bitstreams", embBitstreams, true);
        appendReport(sb, "Bundles", embBundles, true);
        appendReport(sb, "Communities", embComs, false);
        appendReport(sb, "Collections", embCols, false);

        sb.append("\n");
        sb.append(String.format("Items:       %d\n", embItems.size()));
        sb.append(String.format("Bitstreams:  %d\n", embBitstreams.size()));
        sb.append(String.format("Bundles:     %d\n", embBundles.size()));
        sb.append(String.format("Communities: %d\n", embComs.size()));
        sb.append(String.format("Collections: %d\n", embCols.size()));

        return sb.toString();
    }

    /**
     * Add embargo info to target list of DSpace object
     */
    private void collectEmbargoedObjectInfos(
            List<ResourcePolicy> policies, List<EmbargoInfo> targetList, UUID id, UUID parentId) {
        for (ResourcePolicy policy : policies) {
            if (policy.getStartDate() != null || policy.getEndDate() != null) {
                targetList.add(new EmbargoInfo(id, policy.getStartDate(), policy.getEndDate(), parentId));
            }
        }
    }

    private void appendReport(StringBuilder sb, String label, List<EmbargoInfo> list, boolean includeParent) {
        int size = list.size();
        if (size == 0) {
            return;
        }

        sb.append(String.format("\n%s (%d):\n", label, size));
        if (includeParent) {
            sb.append(String.format("%-40s | %-12s | %-12s | %-40s\n",
                    label + " UUID", "Start Date", "End Date", "Item UUID"));
        } else {
            sb.append(String.format("%-40s | %-12s | %-12s\n", label + " UUID", "Start Date", "End Date"));
        }
        sb.append("-".repeat(TABLE_WIDTH)).append("\n");

        int limit = Math.min(size, DISPLAY_THRESHOLD);
        for (int i = 0; i < limit; ++i) {
            EmbargoInfo ei = list.get(i);
            if (includeParent) {
                sb.append(String.format("%-40s | %-12s | %-12s | %-40s\n",
                        ei.id, ei.startDate, ei.endDate, ei.parentItemId));
            } else {
                sb.append(String.format("%-40s | %-12s | %-12s\n", ei.id, ei.startDate, ei.endDate));
            }
        }

        if (size > DISPLAY_THRESHOLD) {
            sb.append(String.format("... (%d more rows not shown)\n", size - DISPLAY_THRESHOLD));
        }
    }

    /**
     * Holds embargo-related information for any object
     * (e.g. item, bundle, bitstream, collection, or community).
     */
    private static class EmbargoInfo {
        UUID id;
        UUID parentItemId;
        Date startDate;
        Date endDate;

        EmbargoInfo(UUID id, Date startDate, Date endDate, UUID parentItemId) {
            this.id = id;
            this.startDate = startDate;
            this.endDate = endDate;
            this.parentItemId = parentItemId;
        }
    }
}
