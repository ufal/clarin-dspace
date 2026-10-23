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

    private BitstreamService bitstreamService = ContentServiceFactory.getInstance().getBitstreamService();
    private BundleService bundleService = ContentServiceFactory.getInstance().getBundleService();
    private CollectionService collectionService = ContentServiceFactory.getInstance().getCollectionService();
    private CommunityService communityService = ContentServiceFactory.getInstance().getCommunityService();
    private MetadataValueService metadataValueService = ContentServiceFactory.getInstance().getMetadataValueService();
    private ItemService itemService = ContentServiceFactory.getInstance().getItemService();
    private WorkspaceItemService workspaceItemService = ContentServiceFactory.getInstance().getWorkspaceItemService();
    private XmlWorkflowItemService workflowItemService =
            XmlWorkflowServiceFactory.getInstance().getXmlWorkflowItemService();
    private HandleService handleService = HandleServiceFactory.getInstance().getHandleService();
    private EPersonService ePersonService = EPersonServiceFactory.getInstance().getEPersonService();
    private GroupService groupService = EPersonServiceFactory.getInstance().getGroupService();


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
                tot_cnt += comSize;
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
                                        row.getKey(), //"stage_reached"
                                        row.getValue() //"cnt"
                ));
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


    /**
     * Appends the entity counts to the human-readable report and puts each of them into {@code jo} under
     * the key {@code report-diff-fields.json} addresses it by.
     *
     * @param context current DSpace session
     * @param jo the check's JSON report, extended in place
     * @return the human-readable section of the report
     * @throws SQLException passed through so that {@link #run(ReportInfo)} records it with
     *         {@link Check#error(Throwable)} and still finishes the rest of the report
     */
    public String getObjectSizesInfo(Context context, JSONObject jo) throws SQLException {
        StringBuilder sb = new StringBuilder();
        sb.append(countLine(jo, "Bitstream", "bitstreamsCount", bitstreamService.countTotal(context)));
        sb.append(countLine(jo, "Bundle", "bundlesCount", bundleService.countTotal(context)));
        sb.append(countLine(jo, "Collection", "collectionsCount", collectionService.countTotal(context)));
        sb.append(countLine(jo, "Community", "communitiesCount", communityService.countTotal(context)));
        sb.append(countLine(jo, "MetadataValue", "metadataValuesCount", metadataValueService.countTotal(context)));
        sb.append(countLine(jo, "EPerson", "ePersonsCount", ePersonService.countTotal(context)));
        sb.append(countLine(jo, "Item", "itemsCount", itemService.countTotal(context)));
        sb.append(countLine(jo, "Handle", "handlesCount", handleService.countTotal(context)));
        sb.append(countLine(jo, "Group", "groupsCount", groupService.countTotal(context)));
        sb.append(countLine(jo, "BasicWorkflowItem", "basicWorkflowItemsCount", workflowItemService.countAll(context)));
        sb.append(countLine(jo, "WorkspaceItem", "workspaceItemsCount", workspaceItemService.countTotal(context)));
        return sb.toString();
    }

    /**
     * Emits one count into both halves of the report: the formatted text line and the JSON key.
     */
    private String countLine(JSONObject jo, String displayName, String jsonKey, int count) {
        jo.put(jsonKey, count);
        return String.format("Count %-20s: %s\n", displayName, String.valueOf(count));
    }

    /**
     * Appends the per-collection sizes to the human-readable report and puts the aggregate numbers into
     * {@code jo}, which {@link #run(ReportInfo)} stores under {@code collectionsSizesInfo}.
     *
     * @param context current DSpace session
     * @param jo the {@code collectionsSizesInfo} object, extended in place
     * @return the human-readable section of the report
     * @throws SQLException passed through, see {@link #getObjectSizesInfo(Context, JSONObject)}
     */
    public String getCollectionSizesInfo(final Context context, JSONObject jo) throws SQLException {
        final StringBuffer ret = new StringBuffer();
        List<Map.Entry<Collection, Long>> colBitSizes = collectionService
            .getCollectionsWithBitstreamSizesTotal(context);
        long total_size = 0;

        Collections.sort(colBitSizes, new Comparator<Map.Entry<Collection, Long>>() {
            @Override
            public int compare(Map.Entry<Collection, Long> o1, Map.Entry<Collection, Long> o2) {
                try {
                    return CollectionDropDown.collectionPath(context, o1.getKey()).compareTo(
                        CollectionDropDown.collectionPath(context, o2.getKey())
                    );
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
