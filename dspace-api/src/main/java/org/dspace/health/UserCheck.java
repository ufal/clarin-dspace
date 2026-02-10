/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.health;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.common.base.CaseFormat;
import org.dspace.content.Collection;
import org.dspace.content.DSpaceObject;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.CollectionService;
import org.dspace.core.Context;
import org.dspace.eperson.EPerson;
import org.dspace.eperson.Group;
import org.dspace.eperson.factory.EPersonServiceFactory;
import org.dspace.eperson.service.EPersonService;
import org.dspace.eperson.service.GroupService;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * @author LINDAT/CLARIN dev team
 */
public class UserCheck extends Check {

    private static final EPersonService ePersonService = EPersonServiceFactory.getInstance().getEPersonService();
    private static final GroupService groupService = EPersonServiceFactory.getInstance().getGroupService();
    private static final CollectionService collectionService = ContentServiceFactory.getInstance()
                                                                                    .getCollectionService();
    private static final String HAVE_EMAIL = "Have email";
    private static final String COUNT = "Count";

    @Override
    public String run(ReportInfo ri) {
        Context context = new Context();
        StringBuilder sb = new StringBuilder();
        JSONObject root = new JSONObject();
        Map<String, Integer> info = new HashMap<String, Integer>();
        try {
            List<EPerson> epersons = ePersonService.findAll(context, EPerson.LASTNAME);
            info.put(COUNT, epersons.size());
            info.put("Can log in (password)", 0);
            info.put(HAVE_EMAIL, 0);
            info.put("Have 1st name", 0);
            info.put("Have 2nd name", 0);
            info.put("Have lang", 0);
            info.put("Have netid", 0);
            info.put("Self registered", 0);

            for (EPerson e : epersons) {
                if (e.getEmail() != null && e.getEmail().length() > 0) {
                    info.put(HAVE_EMAIL, info.get(HAVE_EMAIL) + 1);
                }
                if (e.canLogIn()) {
                    info.put("Can log in (password)",
                             info.get("Can log in (password)") + 1);
                }
                if (e.getFirstName() != null && e.getFirstName().length() > 0) {
                    info.put("Have 1st name", info.get("Have 1st name") + 1);
                }
                if (e.getLastName() != null && e.getLastName().length() > 0) {
                    info.put("Have 2nd name", info.get("Have 2nd name") + 1);
                }
                if (e.getLanguage() != null && e.getLanguage().length() > 0) {
                    info.put("Have lang", info.get("Have lang") + 1);
                }
                if (e.getNetid() != null && e.getNetid().length() > 0) {
                    info.put("Have netid", info.get("Have netid") + 1);
                }
                if (e.getSelfRegistered()) {
                    info.put("Self registered", info.get("Self registered") + 1);
                }
            }

        } catch (SQLException e) {
            error(e);
        }

        sb.append(String.format("%-22s: %d\n", "Users", info.get(COUNT)));
        root.put("users", info.get(COUNT));
        sb.append(String.format("%-22s: %d\n", HAVE_EMAIL, info.get(HAVE_EMAIL)));
        root.put("haveEmail", info.get(HAVE_EMAIL));
        for (Map.Entry<String, Integer> e : info.entrySet()) {
            if (!e.getKey().equals(COUNT) && !e.getKey().equals(HAVE_EMAIL)) {
                String key = e.getKey();
                int value = e.getValue();
                sb.append(String.format("%-22s: %s\n", key, value));

                key = toCamelCase(key);
                root.put(key, value);
            }
        }

        sb.append("\n");

        try {
            // empty group
            List<Group> emptyGroups = groupService.getEmptyGroups(context);
            sb.append(String.format("Empty groups: #%d\n    ", emptyGroups.size()));
            JSONArray emptyGroupsArray = new JSONArray();
            for (Group group : emptyGroups) {
                JSONObject oneEmptyGroup = new JSONObject();
                sb.append(String.format("id=%s;name=%s,\n    ", group.getID(), group.getName()));
                oneEmptyGroup.put("id", group.getID());
                oneEmptyGroup.put("name", group.getName());
                emptyGroupsArray.put(oneEmptyGroup);
            }
            root.put("emptyGroups", emptyGroupsArray);

            sb.append("\n");

            //subscribers
            List<EPerson> subscribers = ePersonService.findEPeopleWithSubscription(context);
            JSONArray subsIdsArray = new JSONArray();
            sb.append(String.format("Subscribers: #%d ", subscribers.size()));
            formatIds(subscribers, subsIdsArray, sb);
            sb.append("\n");
            root.put("subscribers", subsIdsArray);

            //subscribed collections
            List<Collection> subscribedCols = collectionService.findCollectionsWithSubscribers(context);
            JSONArray subsColsArray = new JSONArray();
            sb.append(String.format("Subscribed cols.: #%d ", subscribedCols.size()));
            formatIds(subscribedCols, subsColsArray, sb);
            sb.append("\n");
            root.put("subscribedCollections", subsColsArray);

            context.complete();

        } catch (SQLException e) {
            error(e);
        }

        this.setReportJson(root);
        return sb.toString();
    }

    /**
     * Formats a list of DSpace objects' IDs into both a JSON array and a human-readable string representation.
     * <p>
     * This method takes a list of DSpace objects and extracts their IDs, adding them to:
     * <ul>
     *   <li>A JSON array for programmatic access</li>
     *   <li>A StringBuilder in the format "[id1, id2, id3]" for logging or display purposes</li>
     * </ul>
     *
     * @param objects The list of DSpace objects whose IDs should be formatted
     * @param jsonOut The JSON array to which the object IDs will be added
     * @param strOut The StringBuilder that will be populated with the formatted string representation of IDs
     *              in the format "[id1, id2, id3]"
     */
    private void formatIds(List<? extends DSpaceObject> objects, JSONArray jsonOut, StringBuilder strOut) {
        strOut.append("[");
        for (DSpaceObject o : objects) {
            strOut.append(o.getID()).append(", ");
            jsonOut.put(o.getID());
        }

        //deleting last delimiter (comma and space)
        if (!objects.isEmpty() && strOut.length() > 1) {
            strOut.delete(strOut.length() - 2, strOut.length());
        }

        strOut.append("]");
    }

    private String toCamelCase(String str) {
        str = str.toLowerCase().replace(" ", "_");
        str = CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, str);
        return str;
    }
}
