/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.ctask.general;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.dspace.app.util.DCInput;
import org.dspace.app.util.DCInputSet;
import org.dspace.app.util.DCInputsReader;
import org.dspace.app.util.DCInputsReaderException;
import org.dspace.content.Collection;
import org.dspace.content.DSpaceObject;
import org.dspace.content.Item;
import org.dspace.content.MetadataSchemaEnum;
import org.dspace.content.MetadataValue;
import org.dspace.content.WorkspaceItem;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.WorkspaceItemService;
import org.dspace.core.Constants;
import org.dspace.core.Context;
import org.dspace.curate.AbstractCurationTask;
import org.dspace.curate.Curator;
import org.dspace.curate.Suspendable;
import org.dspace.workflow.WorkflowItem;
import org.dspace.workflow.WorkflowItemService;
import org.dspace.workflow.factory.WorkflowServiceFactory;

/**
 * RequiredMetadata task compares item metadata with fields
 * marked as required in submission-forms.xml. The task succeeds if all
 * required fields are present in the item metadata, otherwise it fails.
 * Primarily a curation task demonstrator.
 *
 * @author richardrodgers
 */
@Suspendable(statusCodes = {Curator.CURATE_ERROR})
public class RequiredMetadata extends AbstractCurationTask {
    // map of DCInputSets
    protected DCInputsReader reader = null;
    // map of required fields
    protected Map<ReqKey, List<String>> reqMap = new HashMap<>();

    private Context context;
    private WorkflowItemService<?> workflowItemService;
    private WorkspaceItemService workspaceItemService;

    @Override
    public void init(Curator curator, String taskId) throws IOException {
        super.init(curator, taskId);
        try {
            reader = new DCInputsReader();
        } catch (DCInputsReaderException dcrE) {
            throw new IOException(dcrE.getMessage(), dcrE);
        }
        context = new Context(Context.Mode.READ_ONLY);
        this.workflowItemService = WorkflowServiceFactory.getInstance().getWorkflowItemService();
        this.workspaceItemService = ContentServiceFactory.getInstance().getWorkspaceItemService();
    }

    /**
     * Perform the curation task upon passed DSO
     *
     * @param dso the DSpace object
     * @throws IOException if IO error
     */
    @Override
    public int perform(DSpaceObject dso) throws IOException {
        if (dso.getType() == Constants.ITEM) {
            Item item = (Item) dso;
            int count = 0;
            try {
                StringBuilder sb = new StringBuilder();
                String handle = item.getHandle();
                if (handle == null) {
                    // we are still in workflow - no handle assigned
                    handle = "in workflow";
                }
                sb.append("Item: ").append(handle);

                Collection collection = item.getOwningCollection();

                // when the owning collection is null it may be the case
                // when the item is a workspace item or a workflow item
                try {
                    if (collection == null && itemService.isInProgressSubmission(context, item)) {
                        WorkflowItem workflowItem = workflowItemService.findByItem(context, item);
                        if (workflowItem != null) {
                            collection = workflowItem.getCollection();
                        } else {
                            WorkspaceItem workspaceItem = workspaceItemService.findByItem(context, item);
                            if (workspaceItem != null) {
                                collection = workspaceItem.getCollection();
                            }
                        }
                    }
                } catch (SQLException ex) {
                    throw new RuntimeException(ex.getMessage(), ex);
                }

                String resourceType = itemService.getMetadataFirstValue(
                        item, MetadataSchemaEnum.DC.getName(), "type", null, Item.ANY);

                for (String req : getReqList(collection.getHandle(), resourceType)) {
                    List<MetadataValue> vals = itemService.getMetadataByMetadataString(item, req);
                    if (vals.size() == 0) {
                        sb.append(" missing required field: ").append(req);
                        count++;
                    }
                }
                if (count == 0) {
                    sb.append(" has all required fields");
                }
                report(sb.toString());
                setResult(sb.toString());
            } catch (DCInputsReaderException dcrE) {
                throw new IOException(dcrE.getMessage(), dcrE);
            }
            return (count == 0) ? Curator.CURATE_SUCCESS : Curator.CURATE_FAIL;
        } else {
            setResult("Object skipped");
            return Curator.CURATE_SKIP;
        }
    }

    protected List<String> getReqList(String handle, String resourceType) throws DCInputsReaderException {
        ReqKey reqKey = new ReqKey(handle, resourceType);
        List<String> reqList = reqMap.get(reqKey);
        if (reqList == null) {
            Set<String> reqSet = new LinkedHashSet<>();
            List<DCInputSet> inputSet = reader.getInputsByCollectionHandle(handle);
            for (DCInputSet inputs : inputSet) {
                for (DCInput[] row : inputs.getFields()) {
                    for (DCInput input : row) {
                        if (input.isRequired() && ((resourceType == null) || input.isAllowedFor(resourceType))) {
                            StringBuilder sb = new StringBuilder();
                            sb.append(input.getSchema()).append(".");
                            sb.append(input.getElement()).append(".");
                            String qual = input.getQualifier();
                            if (qual == null) {
                                qual = "";
                            }
                            sb.append(qual);
                            reqSet.add(sb.toString());
                        }
                    }
                }
            }
            reqList = new ArrayList<>(reqSet);
            reqMap.put(reqKey, reqList);
        }
        return reqList;
    }

    protected static class ReqKey {
        private final String handle;
        private final String resourceType;

        protected ReqKey(String handle, String resourceType) {
            this.handle = handle;
            this.resourceType = resourceType;
        }

        @Override
        public boolean equals(Object o) {
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            ReqKey reqKey = (ReqKey) o;
            return Objects.equals(handle, reqKey.handle) && Objects.equals(resourceType, reqKey.resourceType);
        }

        @Override
        public int hashCode() {
            return Objects.hash(handle, resourceType);
        }
    }
}
