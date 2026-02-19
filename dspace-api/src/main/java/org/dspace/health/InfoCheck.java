/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.health;

import java.io.File;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.apache.commons.io.FileUtils;
import org.dspace.services.ConfigurationService;
import org.dspace.storage.bitstore.DSBitStoreService;
import org.dspace.utils.DSpace;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * @author LINDAT/CLARIN dev team
 */
public class InfoCheck extends Check {

    @Override
    public String run(ReportInfo ri) {
        ConfigurationService configurationService
            = new DSpace().getConfigurationService();
        StringBuilder sb = new StringBuilder();
        JSONObject root = new JSONObject();

        String generatedStr = LocalDateTime.now().format(DateFormatConstants.DATETIME_FORMATTER);
        sb.append("Generated: ").append(generatedStr).append("\n");
        root.put("generated", generatedStr);

        String fromTill = "From - Till: "
            + ri.from().toInstant().atZone(ZoneId.systemDefault()).format(DateFormatConstants.DATE_FORMATTER)
            + " - " + ri.till().toInstant().atZone(ZoneId.systemDefault()).format(DateFormatConstants.DATE_FORMATTER);
        sb.append(fromTill).append("\n");
        root.put("fromTill", fromTill);

        String urlValue = configurationService.getProperty("dspace.ui.url");
        sb.append("Url: ").append(urlValue).append("\n");
        sb.append("\n");
        root.put("url", urlValue);

        DSBitStoreService localStore = new DSpace().getServiceManager()
                .getServicesByType(DSBitStoreService.class)
                .get(0);

        // Build an array of “directory stats”
        JSONArray dirStatsArray = new JSONArray();
        for (String[] ss : new String[][] {
            new String[] {
                localStore.getBaseDir().toString(),
                "Assetstore size",},
            new String[] {
                configurationService.getProperty("log.report.dir"),
                "Log dir size",},}) {
            JSONObject oneStat = new JSONObject();
            oneStat.put("label", ss[1]);

            if (ss[0] != null) {
                try {
                    File dir = new File(ss[0]);
                    if (dir.exists()) {
                        long dir_size = FileUtils.sizeOfDirectory(dir);
                        String displaySize = FileUtils.byteCountToDisplaySize(dir_size);
                        sb.append(String.format("%-20s: %s\n", ss[1], displaySize)
                        );
                        oneStat.put("path", ss[0]);
                        oneStat.put("size_bytes", dir_size);
                        oneStat.put("size_display", displaySize);
                    } else {
                        String msg = String.format("Directory %s does not exist!", ss[0]);
                        oneStat.put("path", ss[0]);
                        oneStat.put("notExist", msg);
                        sb.append(msg).append("\n");
                    }
                } catch (Exception e) {
                    error(e, "directory - " + ss[0]);
                }
            } else { // cannot read property for some reason
                String msg = String.format("Could not get information for %s!\n", ss[1]);
                sb.append(msg);
                oneStat.put("warning", msg);
            }
            dirStatsArray.put(oneStat);
        }
        root.put("directoryStats", dirStatsArray);

        this.setReportJson(root);
        return sb.toString();
    }
}
