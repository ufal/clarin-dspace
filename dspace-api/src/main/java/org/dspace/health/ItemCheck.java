/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.health;

import java.sql.SQLException;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.commons.io.FileUtils;
import org.dspace.app.util.CollectionDropDown;
import org.dspace.content.Bitstream;
import org.dspace.content.Collection;
import org.dspace.content.Community;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.BitstreamService;
import org.dspace.content.service.BundleService;
import org.dspace.content.service.CollectionService;
import org.dspace.content.service.CommunityService;
import org.dspace.content.service.ItemService;
import org.dspace.content.service.MetadataValueService;
import org.dspace.content.service.WorkspaceItemService;
import org.dspace.core.Context;
import org.dspace.eperson.factory.EPersonServiceFactory;
import org.dspace.eperson.service.EPersonService;
import org.dspace.eperson.service.GroupService;
import org.dspace.handle.factory.HandleServiceFactory;
import org.dspace.handle.service.HandleService;
import org.dspace.xmlworkflow.factory.XmlWorkflowServiceFactory;
import org.dspace.xmlworkflow.storedcomponents.service.XmlWorkflowItemService;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * @author LINDAT/CLARIN dev team
 */
public class ItemCheck extends Check {

    private static final BitstreamService bitstreamService =
            ContentServiceFactory.getInstance().getBitstreamService();
    private static final BundleService bundleService =
            ContentServiceFactory.getInstance().getBundleService();
    private static final CollectionService collectionService =
            ContentServiceFactory.getInstance().getCollectionService();
    private static final CommunityService communityService =
            ContentServiceFactory.getInstance().getCommunityService();
    private static final MetadataValueService metadataValueService =
            ContentServiceFactory.getInstance().getMetadataValueService();
    private static final ItemService itemService =
            ContentServiceFactory.getInstance().getItemService();
    private static final WorkspaceItemService workspaceItemService =
            ContentServiceFactory.getInstance().getWorkspaceItemService();
    private static final XmlWorkflowItemService workflowItemService =
            XmlWorkflowServiceFactory.getInstance().getXmlWorkflowItemService();
    private static final HandleService handleService =
            HandleServiceFactory.getInstance().getHandleService();
    private static final EPersonService ePersonService =
            EPersonServiceFactory.getInstance().getEPersonService();
    private static final GroupService groupService =
            EPersonServiceFactory.getInstance().getGroupService();


    @Override
    public String run(ReportInfo ri) {
        StringBuilder sb = new StringBuilder();
        JSONObject root = new JSONObject();
        int tot_cnt = 0;
        Context context = new Context();
        try {
            JSONArray communitiesArray = new JSONArray();
            for (Map.Entry<String, Integer> name_count : getCommunities(context)) {
                String comName = name_count.getKey();
                int comSize = name_count.getValue();
                sb.append(String.format("Community [%s]: %d\n", comName, comSize));
                tot_cnt += name_count.getValue();
                JSONObject oneCommunity = new JSONObject();
                oneCommunity.put("name", comName);
                oneCommunity.put("size", comSize);
                communitiesArray.put(oneCommunity);
            }
            root.put("communities", communitiesArray);
        } catch (SQLException e) {
            error(e);
        }

        try {
            JSONObject colSizesInfo = new JSONObject();
            sb.append("\nCollection sizes:\n");
            sb.append(getCollectionSizesInfo(context, colSizesInfo));
            root.put("collectionsSizesInfo", colSizesInfo);
        } catch (SQLException e) {
            error(e);
        }

        sb.append(String.format("\nPublished items (archived, not withdrawn): %d\n", tot_cnt));
        root.put("publishedItems", tot_cnt);
        try {
            int withdrawnItems = itemService.countWithdrawnItems(context);
            sb.append(String.format("Withdrawn items: %d\n", withdrawnItems));
            root.put("withdrawnItems", withdrawnItems);
            int notPublishedItems = itemService.countNotArchivedItems(context);
            sb.append(String.format("Not published items (in workspace or workflow mode): %d\n", notPublishedItems));
            root.put("notPublishedItems", notPublishedItems);

            JSONArray stagesCountArray = new JSONArray();
            for (Map.Entry<Integer, Long> row : workspaceItemService.getStageReachedCounts(context)) {
                sb.append(String.format("\tIn Stage %s: %s\n",
                                row.getKey(),   //"stage_reached"
                                row.getValue()) //"cnt"
                );
                JSONObject oneStage = new JSONObject();
                oneStage.put("stage", row.getKey());
                oneStage.put("count", row.getValue());
                stagesCountArray.put(oneStage);
            }
            root.put("stagesCounts", stagesCountArray);

            int waitingForApprovalCount = workflowItemService.countAll(context);
            sb.append(String.format("\tWaiting for approval (workflow items): %d\n", waitingForApprovalCount));
            root.put("waitingForApproval", waitingForApprovalCount);
        } catch (SQLException e) {
            error(e);
        }

        try {
            sb.append(getObjectSizesInfo(context, root));
            context.complete();
        } catch (SQLException e) {
            error(e);
        }

        this.setReportJson(root);
        return sb.toString();
    }


    public String getObjectSizesInfo(Context context, JSONObject jo) throws SQLException {
        StringBuilder sb = new StringBuilder();

        // Simple ordered map approach - maintains insertion order
        java.util.LinkedHashMap<String, CountInfo> entities = new java.util.LinkedHashMap<>();
        entities.put("Bitstream", new CountInfo("bitstreamsCount", wrapSql(bitstreamService::countTotal)));
        entities.put("Bundle", new CountInfo("bundlesCount", wrapSql(bundleService::countTotal)));
        entities.put("Collection", new CountInfo("collectionsCount", wrapSql(collectionService::countTotal)));
        entities.put("Community", new CountInfo("communitiesCount", wrapSql(communityService::countTotal)));
        entities.put("MetadataValue", new CountInfo("metadataValuesCount", wrapSql(metadataValueService::countTotal)));
        entities.put("EPerson", new CountInfo("ePersonsCount", wrapSql(ePersonService::countTotal)));
        entities.put("Item", new CountInfo("itemsCount", wrapSql(itemService::countTotal)));
        entities.put("Handle", new CountInfo("handlesCount", wrapSql(handleService::countTotal)));
        entities.put("Group", new CountInfo("groupsCount", wrapSql(groupService::countTotal)));
        entities.put("BasicWorkflowItem", new CountInfo("basicWorkflowItemsCount",
                wrapSql(workflowItemService::countAll)));
        entities.put("WorkspaceItem", new CountInfo("workspaceItemsCount", wrapSql(workspaceItemService::countTotal)));

        // Single iteration - do countTotal, append, put only once
        for (java.util.Map.Entry<String, CountInfo> entry : entities.entrySet()) {
            String displayName = entry.getKey();
            CountInfo info = entry.getValue();
            int count = info.countFunction.apply(context);
            sb.append(String.format("Count %-20s: %s\n", displayName, String.valueOf(count)));
            jo.put(info.jsonKey, count);
        }

        return sb.toString();
    }

    /**
     * Helper method to wrap SQLException-throwing functions into regular Functions
     */
    private static java.util.function.Function<Context, Integer> wrapSql(SqlFunction<Context, Integer> sqlFunction) {
        return ctx -> {
            try {
                return sqlFunction.apply(ctx);
            } catch (SQLException e) {
                throw new RuntimeException("Error executing SQL function in wrapSql", e);
            }
        };
    }

    /**
     * Functional interface for functions that throw SQLException
     */
    @FunctionalInterface
    private interface SqlFunction<T, R> {
        R apply(T t) throws SQLException;
    }

    /**
     * Simple data holder for count information
     */
    private static class CountInfo {
        final String jsonKey;
        final java.util.function.Function<Context, Integer> countFunction;

        CountInfo(String jsonKey, java.util.function.Function<Context, Integer> countFunction) {
            this.jsonKey = jsonKey;
            this.countFunction = countFunction;
        }
    }

    public String getCollectionSizesInfo(final Context context, JSONObject jo) throws SQLException {
        final StringBuffer ret = new StringBuffer();
        List<Map.Entry<Collection, Long>> colBitSizes = collectionService
                .getCollectionsWithBitstreamSizesTotal(context);
        long total_size = 0;

        Collections.sort(colBitSizes, new Comparator<Map.Entry<Collection, Long>>() {
            @Override
            public int compare(Map.Entry<Collection, Long> o1, Map.Entry<Collection, Long> o2) {
                try {
                    String p1 = CollectionDropDown.collectionPath(context, o1.getKey());
                    String p2 = CollectionDropDown.collectionPath(context, o2.getKey());
                    return p1.compareTo(p2);
                } catch (Exception e) {
                    ret.append(e.getMessage());
                }
                return 0;
            }
        });

        JSONArray collectionsSizesArray = new JSONArray();
        for (Map.Entry<Collection, Long> row : colBitSizes) {
            Long size = row.getValue();
            total_size += size;
            Collection col = row.getKey();
            String colPath = CollectionDropDown.collectionPath(context, col);
            String colSize = FileUtils.byteCountToDisplaySize((long) size);
            ret.append(String.format(
                    "\t%s:  %s\n", colPath, colSize));
            JSONObject oneColSize = new JSONObject();
            oneColSize.put("path", colPath);
            oneColSize.put("size", colSize);
            collectionsSizesArray.put(oneColSize);
        }
        jo.put("collectionSizes", collectionsSizesArray);

        String totalSizeToDisplay = FileUtils.byteCountToDisplaySize(total_size);
        ret.append(String.format(
                "Total size:              %s\n", totalSizeToDisplay));
        jo.put("totalSize", totalSizeToDisplay);


        int resourceWOPolicyCount = bitstreamService.countBitstreamsWithoutPolicy(context);
        ret.append(String.format(
                "Resource without policy: %d\n", resourceWOPolicyCount));
        jo.put("resourceWOPolicy", resourceWOPolicyCount);

        int deletedBitstreamsCount = bitstreamService.countDeletedBitstreams(context);
        ret.append(String.format(
                "Deleted bitstreams:      %d\n", deletedBitstreamsCount));
        jo.put("deletedBitstreams", deletedBitstreamsCount);

        String list_str = "";
        JSONArray orphanBitstreamsArray = new JSONArray();
        List<Bitstream> bitstreamOrphans = bitstreamService.getNotReferencedBitstreams(context);
        for (Bitstream orphan : bitstreamOrphans) {
            UUID id = orphan.getID();
            JSONObject oneOrphanBitstream = new JSONObject();
            oneOrphanBitstream.put("uuid", id.toString());
            orphanBitstreamsArray.put(oneOrphanBitstream);
            list_str += String.format("%s, ", id);
        }
        ret.append(String.format(
                "Orphan bitstreams:       %d [%s]\n", bitstreamOrphans.size(), list_str));
        jo.put("orphanBitstreamsCount", bitstreamOrphans.size());
        jo.put("orphanBitstreams", orphanBitstreamsArray);

        return ret.toString();
    }

    public List<Map.Entry<String, Integer>> getCommunities(Context context)
        throws SQLException {

        List<Map.Entry<String, Integer>> cl = new java.util.ArrayList<>();
        List<Community> top_communities = communityService.findAllTop(context);
        for (Community c : top_communities) {
            cl.add(
                new java.util.AbstractMap.SimpleEntry<>(c.getName(), itemService.countItems(context, c))
            );
        }
        return cl;
    }
}
