/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.versioning.utils;

import java.sql.SQLException;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;
import org.dspace.content.Item;
import org.dspace.content.service.ItemService;
import org.dspace.core.Context;

public class RelationMetadataUtils {
    private static final Logger log = org.apache.logging.log4j.LogManager.getLogger(RelationMetadataUtils.class);

    private RelationMetadataUtils() {}

    /**
     * Add the metadata 'dc.relation.isreplacedby' to the previous item,
     * with the value of dc.identifier.uri of the new item.
     *
     * @param c the context
     * @param itemService the item service
     * @param previousItem the previous item, to which the metadata 'dc.relation.isreplacedby' will be added
     * @param newItem the new item, from which the value of dc.identifier.uri will be taken
     * @throws SQLException if any error during database access occurs
     */

    public static void setIsReplacedByMetadata(Context c, ItemService itemService, Item previousItem, Item newItem)
        throws SQLException {
        String identifierUri = itemService.getMetadataFirstValue(newItem, "dc", "identifier","uri", Item.ANY);
        if (StringUtils.isBlank(identifierUri)) {
            log.warn("The new item (id: {}) doesn't have the metadata dc.identifier.uri, " +
                            "so it's not possible to add dc.relation.isreplacedby to the previous item",
                    newItem.getID());
        } else {
            boolean isReplacedByAlreadyExists =
                    itemService.getMetadata(previousItem, "dc", "relation", "isreplacedby", Item.ANY)
                            .stream()
                            .anyMatch(m -> identifierUri.equals(m.getValue()));
            if (!isReplacedByAlreadyExists) {
                itemService.addMetadata(c, previousItem, "dc", "relation", "isreplacedby", null, identifierUri);
            }
        }
    }
}
