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
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.dspace.content.Bitstream;
import org.dspace.content.Bundle;
import org.dspace.content.Item;
import org.dspace.content.clarin.ClarinLicenseLabel;
import org.dspace.content.clarin.ClarinLicenseResourceMapping;
import org.dspace.content.factory.ClarinServiceFactory;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.ItemService;
import org.dspace.content.service.clarin.ClarinLicenseResourceMappingService;
import org.dspace.core.Constants;
import org.dspace.core.Context;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * This check provides information about the number of items categorized by clarin license type (PUB/RES/ACA),
 * as well as details about items that are missing bundles, bitstreams, or license mappings.
 * @author Matus Kasak (dspace at dataquest.sk)
 */
public class LicenseCheck extends Check {
    private ClarinLicenseResourceMappingService clarinLicenseResourceMappingService =
            ClarinServiceFactory.getInstance().getClarinLicenseResourceMappingService();


    @Override
    protected String run(ReportInfo ri) {
        Context context = new Context();
        StringBuilder sb = new StringBuilder();
        JSONObject root = new JSONObject();

        Iterator<Item> items;
        ItemService itemService = ContentServiceFactory.getInstance().getItemService();
        Map<String, Integer> licensesCount = new HashMap<>();
        Map<String, List<UUID>> problemItems = new HashMap<>();
        List<UUID> bitstreamUUIDs = new ArrayList<>();
        try {
            items = itemService.findAll(context);
            while (items.hasNext()) {
                Item item = items.next();
                List<Bundle> bundles = item.getBundles(Constants.DEFAULT_BUNDLE_NAME);
                if (bundles.isEmpty()) {
                    licensesCount.put("no bundle", licensesCount.getOrDefault("no bundle", 0) + 1);
                    continue;
                }
                if (item.getBundles(Constants.LICENSE_BUNDLE_NAME).isEmpty()) {
                    problemItems.computeIfAbsent(
                            "UUIDs of items without license bundle", k -> new ArrayList<>()).add(item.getID());
                }
                List<Bitstream> bitstreams = bundles.get(0).getBitstreams();
                if (bitstreams.isEmpty()) {
                    problemItems.computeIfAbsent(
                            "UUIDs of items without bitstreams", k -> new ArrayList<>()).add(item.getID());
                    continue;
                }
                Bitstream firstBitstream = bitstreams.get(0);
                UUID uuid = firstBitstream.getID();
                bitstreamUUIDs.add(uuid);
            }
            // Batch fetch all mappings for the collected UUIDs
            List<ClarinLicenseResourceMapping> mappingList =
                clarinLicenseResourceMappingService.findByBitstreamUUIDs(context, bitstreamUUIDs);
            Map<UUID, ClarinLicenseResourceMapping> mappingByUUID = new HashMap<>();
            for (ClarinLicenseResourceMapping mapping : mappingList) {
                if (mapping.getBitstream() != null) {
                    mappingByUUID.put(mapping.getBitstream().getID(), mapping);
                }
            }
            // Process results in memory
            for (UUID uuid : bitstreamUUIDs) {
                ClarinLicenseResourceMapping mapping = mappingByUUID.get(uuid);
                if (mapping == null) {
                    problemItems.computeIfAbsent(
                        "UUIDs of bitstreams without license mappings", k -> new ArrayList<>()).add(uuid);
                    continue;
                }
                // Every resource mapping between license and the bitstream has only one record,
                // because the bitstream has unique UUID, so get the first record from the List
                ClarinLicenseLabel nonExtendedLabel = mapping.getLicense().getNonExtendedClarinLicenseLabel();
                if (Objects.isNull(nonExtendedLabel)) {
                    problemItems.computeIfAbsent(
                            "UUIDs of bitstreams without non-extended license labels",
                            k -> new ArrayList<>()).add(uuid);
                } else {
                    licensesCount.put(nonExtendedLabel.getLabel(),
                            licensesCount.getOrDefault(nonExtendedLabel.getLabel(), 0) + 1);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error while fetching items or license mappings.", e);
        } finally {
            context.close();
        }

        JSONArray licensesArray = new JSONArray();
        for (Map.Entry<String, Integer> result : licensesCount.entrySet()) {
            JSONObject oneLicense = new JSONObject();

            sb.append(String.format("%-20s: %d\n", result.getKey(), result.getValue()));
            oneLicense.put("type", result.getKey());
            oneLicense.put("count", result.getValue());

            licensesArray.put(oneLicense);
        }
        root.put("licenses", licensesArray);

        if (!problemItems.isEmpty()) {
            JSONArray problemItemsArray = new JSONArray();
            for (Map.Entry<String, List<UUID>> entry: problemItems.entrySet()) {
                List<UUID> uuids = entry.getValue();
                JSONObject oneProblemItem = new JSONObject();
                JSONArray problemUUIDsArray = new JSONArray();

                sb.append(String.format("\n%s: %d\n", entry.getKey(), uuids.size()));
                oneProblemItem.put("type", entry.getKey());
                oneProblemItem.put("count", uuids.size());

                for (UUID uuid : uuids) {
                    sb.append(String.format("     %s\n", uuid));
                    problemUUIDsArray.put(uuid.toString());
                }
                oneProblemItem.put("problemUUIDs", problemUUIDsArray);
                problemItemsArray.put(oneProblemItem);
            }
            root.put("problemItems", problemItemsArray);
        }

        context.close();
        this.setReportJson(root);
        return sb.toString();
    }
}
