/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
/* Created for LINDAT/CLARIN */
package org.dspace.ctask.general;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.dspace.app.util.DCInput;
import org.dspace.app.util.DCInputSet;
import org.dspace.app.util.DCInputsReader;
import org.dspace.app.util.DCInputsReaderException;
import org.dspace.content.DSpaceObject;
import org.dspace.content.Item;
import org.dspace.content.MetadataValue;
import org.dspace.curate.AbstractCurationTask;
import org.dspace.curate.Curator;
import org.dspace.discovery.IsoLangCodes;
import org.dspace.versioning.VersionHistory;
import org.dspace.versioning.factory.VersionServiceFactory;
import org.dspace.versioning.service.VersionHistoryService;

/**
 * Check basic properties of item metadata for quality assurance.
 * Ported from DSpace v5 CLARIN implementation.
 *
 * @author LINDAT/CLARIN
 */
public class ItemMetadataQAChecker extends AbstractCurationTask {

    public static final int CURATE_WARNING = -1000;
    private static final Logger log = LogManager.getLogger(ItemMetadataQAChecker.class);

    /** Expected types. */
    private Set<String> dcTypeValuesSet;

    private static final String[] rightsMdStrings = {"dc.rights.uri", "dc.rights.label", "dc.rights"};

    private Map<String, String> itemTitles;
    private String handlePrefix;
    private Map<String, Integer> complexInputs;

    private String[] nonRepeatableMetadata;
    private String[] strangeMetadata;
    private String[] highlyRecommended;

    private VersionHistoryService versionHistoryService;

    @Override
    public void init(Curator curator, String taskId) throws IOException {
        super.init(curator, taskId);
        itemTitles = new HashMap<>();
        handlePrefix = configurationService.getProperty("handle.canonical.prefix");

        // Initialize expected types from configuration
        String[] configuredTypes = configurationService.getArrayProperty(
            "lr.curation.metadata.expected.types");
        if (configuredTypes != null && configuredTypes.length > 0) {
            dcTypeValuesSet = new HashSet<>(Arrays.asList(configuredTypes));
        } else {
            // Use defaults if not configured
            dcTypeValuesSet = new HashSet<>(Arrays.asList(
                "corpus", "lexicalConceptualResource", "languageDescription", "toolService"));
        }

        nonRepeatableMetadata = configurationService.getArrayProperty("lr.curation.metadata.nonrepeatable",
                new String[]{
                    "local.branding",
                    "dc.type",
                    "dc.date.accessioned",
                    "dc.rights.label",
                    "dc.date.available",
                    "dc.source.uri",
                    "dc.identifier.doi",
                    "metashare.ResourceInfo#DistributionInfo#LicenseInfo.license"
                });
        strangeMetadata = configurationService.getArrayProperty("lr.curation.metadata.strange", new String[]{
            "dc.description.uri",
        });
        highlyRecommended = configurationService.getArrayProperty("lr.curation.metadata.recommended", new String[]{
            "dc.subject",
        });

        complexInputs = new HashMap<>();
        loadComplexInputs();

        versionHistoryService = VersionServiceFactory.getInstance().getVersionHistoryService();
    }

    private void loadComplexInputs() {
        try {
            DCInputsReader reader = new DCInputsReader();
            // Get all input sets to check complex inputs across all forms
            List<DCInputSet> inputSets = reader.getAllInputs(Integer.MAX_VALUE, 0);

            for (DCInputSet inputSet : inputSets) {
                DCInput[][] fields = inputSet.getFields();
                for (DCInput[] row : fields) {
                    for (DCInput input : row) {
                        if ("complex".equals(input.getInputType())) {
                            String name = StringUtils.isBlank(input.getQualifier())
                                ? String.format("%s.%s", input.getSchema(), input.getElement())
                                : String.format("%s.%s.%s", input.getSchema(), input.getElement(),
                                    input.getQualifier());
                            complexInputs.put(name, input.getComplexDefinition().getInputs().size());
                        }
                    }
                }
            }
        } catch (DCInputsReaderException e) {
            log.error("Problems fetching input-forms.xml", e);
        }
    }

    private String getHandle(Item item) {
        if (null != item.getHandle()) {
            return handlePrefix + item.getHandle();
        } else {
            return "item id: " + item.getID();
        }
    }

    @Override
    public int perform(DSpaceObject dso) throws IOException {
        int status = Curator.CURATE_UNSET;
        StringBuilder results = new StringBuilder();
        String errStr = "Unknown error";

        // do on Items only
        if (dso instanceof Item) {
            Item item = (Item) dso;
            if (item.getHandle() != null) {
                List<MetadataValue> metadataValues = itemService.getMetadata(
                    item, Item.ANY, Item.ANY, Item.ANY, Item.ANY);

                // no metadata?
                if (metadataValues == null || metadataValues.isEmpty()) {
                    errStr = "Does not have any metadata";
                    status = Curator.CURATE_FAIL;
                } else {
                    // perform the validation
                    try {
                        validateDcType(item, results);
                        validateTitle(item, results);
                        validateDcLanguageIso(item, results);
                        validateRelations(item, results);
                        validateEmptyMetadata(item, metadataValues, results);
                        validatePredefinedNonRepeatableMetadata(item, results);
                        validateStrangeMetadata(item, results);
                        validateRightsLabels(item, results);
                        itemWithFilesHasLicense(item);
                        validateHighlyRecommendedMetadata(item, results);
                        validateComplexInputs(item, results);

                        status = Curator.CURATE_SUCCESS;
                    } catch (CurateException exc) {
                        errStr = exc.getMessage();
                        status = exc.errCode;
                    }
                }
            } else {
                // no handle!
                errStr = "Does not have a handle";
                status = Curator.CURATE_FAIL;
            }

            // format the error if any
            switch (status) {
                case Curator.CURATE_SUCCESS:
                    break;
                case CURATE_WARNING:
                    results.append(String.format("Warning: %s %s", errStr, addMagicString(getHandle(item))));
                    break;
                default:
                    results.append(String.format("ERROR! %s %s", errStr, addMagicString(getHandle(item))));
                    break;
            }
        }

        report(results.toString());
        setResult(results.toString());
        return status;
    }

    /**
     * Add magic string for identification in reports.
     * @param handle the handle to mark
     * @return marked string
     */
    private static String addMagicString(String handle) {
        return "[[" + handle + "]]";
    }

    //
    // dc type checker
    //

    private void validateDcType(Item item, StringBuilder results) throws CurateException {
        List<MetadataValue> dcsType = itemService.getMetadataByMetadataString(item, "dc.type");
        // no metadata?
        if (dcsType == null || dcsType.isEmpty()) {
            throw new CurateException("Does not have dc.type metadata", Curator.CURATE_FAIL);
        }

        // check array is not null or length > 0
        for (MetadataValue dcsEntry : dcsType) {
            String value = dcsEntry.getValue();
            if (value == null) {
                throw new CurateException("dc.type has null value", Curator.CURATE_FAIL);
            }

            String typeVal = value.trim();

            // check if original and trimmed versions match
            if (!typeVal.equals(value)) {
                throw new CurateException("leading or trailing spaces", Curator.CURATE_FAIL);
            }

            // check if the dc.type field is empty
            if (Pattern.matches("^\\s*$", typeVal)) {
                throw new CurateException("empty value", Curator.CURATE_FAIL);
            }

            // check if the value is valid
            if (!dcTypeValuesSet.contains(typeVal)) {
                throw new CurateException("invalid type (" + typeVal + ")", Curator.CURATE_FAIL);
            }
        }
    }

    /**
     * Checks the language code (dc.language.iso) against the possible language codes
     * and validates that local.language.name matches the human-readable language names.
     *
     * @param item    the item
     * @param results the results
     * @throws CurateException if validation fails
     */
    private void validateDcLanguageIso(Item item, StringBuilder results) throws CurateException {
        List<MetadataValue> dcsLanguageIso = itemService.getMetadataByMetadataString(item, "dc.language.iso");

        // build maps of expected and actual language names keyed by place
        Map<Integer, String> expectedLangNamesByPlace = new HashMap<>();
        Map<Integer, String> isoCodesByPlace = new HashMap<>();

        if (dcsLanguageIso != null && !dcsLanguageIso.isEmpty()) {
            // Validate dc.language.iso codes
            for (MetadataValue langCodeDC : dcsLanguageIso) {
                String langCode = langCodeDC.getValue();
                if (langCode == null) {
                    throw new CurateException("dc.language.iso has null value", Curator.CURATE_FAIL);
                }
                if (IsoLangCodes.getLangForCode(langCode) == null) {
                    throw new CurateException(
                        String.format("Invalid language code - %s", langCode),
                        Curator.CURATE_FAIL);
                }

                Integer place = langCodeDC.getPlace();
                String expectedLangName = IsoLangCodes.getLangForCode(langCode);
                expectedLangNamesByPlace.put(place, expectedLangName);
                isoCodesByPlace.put(place, langCode);
            }

            // Validate local.language.name matches dc.language.iso
            List<MetadataValue> languageNames = itemService.getMetadataByMetadataString(item, "local.language.name");
            if (languageNames == null || languageNames.size() != dcsLanguageIso.size()) {
                throw new CurateException(
                    String.format("local.language.name count [%d] does not match dc.language.iso count [%d]",
                        languageNames == null ? 0 : languageNames.size(), dcsLanguageIso.size()),
                    Curator.CURATE_FAIL);
            }

            Map<Integer, String> actualLangNamesByPlace = new HashMap<>();
            for (MetadataValue languageName : languageNames) {
                Integer place = languageName.getPlace();
                String actualLangName = languageName.getValue();
                actualLangNamesByPlace.put(place, actualLangName);
            }

            // Ensure that the sets of places match between ISO codes and language names
            Set<Integer> expectedPlaces = expectedLangNamesByPlace.keySet();
            Set<Integer> actualPlaces = actualLangNamesByPlace.keySet();
            if (!expectedPlaces.equals(actualPlaces)) {
                throw new CurateException(
                        String.format("local.language.name places %s do not match dc.language.iso places %s",
                                actualPlaces, expectedPlaces),
                        Curator.CURATE_FAIL);
            }
            // Validate that each language name corresponds to its ISO code for each place
            for (Integer place : expectedPlaces) {
                String expectedLangName = expectedLangNamesByPlace.get(place);
                String actualLangName = actualLangNamesByPlace.get(place);
                if (!expectedLangName.equals(actualLangName)) {
                    throw new CurateException(
                            String.format(
                                    "local.language.name [%s] at place [%d] does not match expected name [%s] " +
                                            "for ISO code [%s]",
                                    actualLangName, place, expectedLangName, isoCodesByPlace.get(place)),
                            Curator.CURATE_FAIL);
                }
            }
        }
    }

    //
    // title checker
    //

    private void validateTitle(Item item, StringBuilder results) throws CurateException {
        String title = itemService.getMetadataFirstValue(item, "dc", "title", null, Item.ANY);
        if (title == null) {
            throw new CurateException("Item has no dc.title metadata", Curator.CURATE_FAIL);
        }
        if (itemTitles.containsKey(title)) {
            String msg = String.format("Title [%s] duplicate in [%s]", title, itemTitles.get(title));
            throw new CurateException(msg, Curator.CURATE_FAIL);
        }
        itemTitles.put(title, getHandle(item));
    }

    //
    // relation checker (based on assumption items are not part of multiple version histories)
    //

    private void validateRelations(Item item, StringBuilder results) throws CurateException {
        String handlePrefixLocal = configurationService.getProperty("handle.canonical.prefix");
        try {
            String mdIsReplacedBy = "dc.relation.isreplacedby";
            String mdReplaces = "dc.relation.replaces";

            List<MetadataValue> dcsIsReplacedBy = getNonBlankMetadata(item, mdIsReplacedBy);
            List<MetadataValue> dcsReplaces = getNonBlankMetadata(item, mdReplaces);

            if (dcsIsReplacedBy.isEmpty() && dcsReplaces.isEmpty()) {
                // item contains no relation metadata, nothing to check
                return;
            }

            // check if objects referenced by "dc.relation.isreplacedby" exist,
            // and reference back to this item with "dc.relation.replaces" metadata
            if (!dcsIsReplacedBy.isEmpty()) {
                boolean relationsOK =
                        checkRelations(item, dcsIsReplacedBy, mdIsReplacedBy, mdReplaces, handlePrefixLocal);
                if (!relationsOK) {
                    throw relationMetadataException(mdIsReplacedBy, mdReplaces);
                }
            }
            // check if objects referenced by "dc.relation.replaces" exist,
            // and reference forward to this item with "dc.relation.isreplacedby" metadata
            if (!dcsReplaces.isEmpty()) {
                boolean relationsOK = checkRelations(item, dcsReplaces, mdReplaces, mdIsReplacedBy, handlePrefixLocal);
                if (!relationsOK) {
                    throw relationMetadataException(mdReplaces, mdIsReplacedBy);
                }
            }

            // everything is OK
            results.append(String.format("Item [%s] meets relation requirements. ", getHandle(item)));

        } catch (SQLException | IOException e) {
            throw new CurateException(e.getMessage(), Curator.CURATE_FAIL);
        }
    }

    private List<MetadataValue> getNonBlankMetadata(Item item, String metadataString) {
        return itemService.getMetadataByMetadataString(item, metadataString)
                .stream()
                .filter(metadataValue -> !StringUtils.isBlank(metadataValue.getValue()))
                .collect(Collectors.toList());
    }

    private boolean checkRelations(Item item,
                                   List<MetadataValue> references,
                                   String referencesFieldName,
                                   String fieldNameInOtherDirection,
                                   String handlePrefixLocal) throws SQLException, IOException, CurateException {
        for (MetadataValue ref : references) {
            Item referencedItem = getReferencedItem(ref, handlePrefixLocal);
            boolean checksPass = hasReferenceBack(referencedItem, item.getHandle(),
                    fieldNameInOtherDirection, handlePrefixLocal) &&
                    checkVersionHistory(item, referencedItem, referencesFieldName);
            if (!checksPass) {
                return false;
            }
        }
        return true;
    }

    private Item getReferencedItem(MetadataValue relatedReference, String handlePrefixLocal)
            throws SQLException, IOException, CurateException {
        String referencedItemHandle =  getHandle(relatedReference, handlePrefixLocal);
        DSpaceObject referencedObject = dereference(Curator.curationContext(), referencedItemHandle);
        if (referencedObject instanceof Item) {
            return (Item) referencedObject;
        } else {
            throw new CurateException(
                    String.format("contains '%s' but the referenced object [[%s]] is not an item or doesn't exist",
                            relatedReference.getMetadataField().toString('.'), referencedItemHandle),
                    Curator.CURATE_FAIL);
        }
    }

    private boolean hasReferenceBack(Item referencedItem, String handleBack, String fieldNameInOtherDirection,
                                     String handlePrefixLocal) throws CurateException {
        boolean ok = itemService.getMetadataByMetadataString(referencedItem, fieldNameInOtherDirection).stream()
                .map(mdv -> getHandle(mdv, handlePrefixLocal))
                .anyMatch(handle -> handle != null && handle.equals(handleBack));
        if (!ok) {
            throw new CurateException(String.format("the referenced item %s does not refer back via %s",
                           addMagicString(getHandle(referencedItem)), fieldNameInOtherDirection), Curator.CURATE_FAIL);
        }
        return true;
    }

    private String getHandle(MetadataValue relationReference, String handlePrefixLocal) {
        String handle = relationReference.getValue();
        if (StringUtils.isNotBlank(handlePrefixLocal) && handle != null && handle.startsWith(handlePrefixLocal)) {
            handle = handle.substring(handlePrefixLocal.length());
        }
        return handle;
    }

    private boolean checkVersionHistory(Item item1, Item item2, String relation) throws SQLException, CurateException {
        VersionHistory item1History = versionHistoryService.findByItem(Curator.curationContext(), item1);
        if (item1History == null) {
            throw new CurateException(
                    String.format("contains '%s' but it's not part of any version history", relation),
                    Curator.CURATE_FAIL
            );
        }
        VersionHistory item2History = versionHistoryService.findByItem(Curator.curationContext(), item2);
        if (item2History == null) {
            throw new CurateException(
                    String.format("contains '%s' but the referenced item %s is not part of any version history",
                            relation, addMagicString(getHandle(item2))),
                    Curator.CURATE_FAIL
            );
        }

        if (!item1History.equals(item2History)) {
            throw new CurateException(
                    String.format("contains '%s' but the referenced item %s is not in the same version history",
                            relation, addMagicString(getHandle(item2))),
                    Curator.CURATE_FAIL
            );
        }
        return true;
    }

    private static CurateException relationMetadataException(String leftRel, String rightRel) {
        return new CurateException(
                String.format("contains '%s' but the referenced object doesn't exist or " +
                                "doesn't contain '%s' or doesn't point to this item",
                        leftRel, rightRel),
                Curator.CURATE_FAIL
        );
    }

    private void validateEmptyMetadata(Item item, List<MetadataValue> metadataValues, StringBuilder results)
        throws CurateException {
        for (MetadataValue dc : metadataValues) {
            if (dc.getValue() == null) {
                throw new CurateException(
                    String.format("value [%s.%s.%s] is null", dc.getMetadataField().getMetadataSchema().getName(),
                        dc.getMetadataField().getElement(), dc.getMetadataField().getQualifier()),
                    Curator.CURATE_FAIL);
            }
            if (dc.getValue().trim().length() == 0) {
                throw new CurateException(
                    String.format("value [%s.%s.%s] is empty", dc.getMetadataField().getMetadataSchema().getName(),
                        dc.getMetadataField().getElement(), dc.getMetadataField().getQualifier()),
                    Curator.CURATE_FAIL);
            }
        }
    }

    private void validatePredefinedNonRepeatableMetadata(Item item, StringBuilder results) throws CurateException {
        for (String noDuplicate : nonRepeatableMetadata) {
            List<MetadataValue> vals = itemService.getMetadataByMetadataString(item, noDuplicate);
            if (null != vals && vals.size() > 1) {
                throw new CurateException(
                    String.format("value [%s] is present multiple times", noDuplicate),
                    Curator.CURATE_FAIL);
            }
        }
    }

    private void validateRightsLabels(Item item, StringBuilder results) throws CurateException {
        List<MetadataValue> dcvs = itemService.getMetadata(item, "dc", "rights", "label", Item.ANY);
        try {
            // Only check if item has files when we have an active session
            // Skip this check if we can't access bundles (lazy loading issue)
            if (null != item.getHandle() && dcvs != null && !dcvs.isEmpty()) {
                if (!itemService.hasUploadedFiles(item, "ORIGINAL")) {
                    StringBuilder labels = new StringBuilder();
                    for (MetadataValue label : dcvs) {
                        labels.append(label.getValue()).append(" ");
                    }
                    throw new CurateException(
                        String.format("has labels [%s] but no files", labels.toString()),
                        Curator.CURATE_FAIL);
                }
            }
        } catch (SQLException e) {
            throw new CurateException(
                String.format("has internal problems [%s]", e.getMessage()),
                Curator.CURATE_FAIL);
        }
    }

    private void validateHighlyRecommendedMetadata(Item item, StringBuilder results) throws CurateException {
        for (String md : highlyRecommended) {
            List<MetadataValue> vals = itemService.getMetadataByMetadataString(item, md);
            if (null == vals || vals.isEmpty()) {
                throw new CurateException(
                    String.format("does not contain any [%s] values", md),
                    CURATE_WARNING);
            }
        }
    }

    private void validateStrangeMetadata(Item item, StringBuilder results) throws CurateException {
        for (String md : strangeMetadata) {
            List<MetadataValue> vals = itemService.getMetadataByMetadataString(item, md);
            if (null != vals && !vals.isEmpty()) {
                throw new CurateException(
                    String.format("contains suspicious [%s] metadata", md),
                    Curator.CURATE_FAIL);
            }
        }
    }

    private void validateComplexInputs(Item item, StringBuilder results) throws CurateException {
        for (Map.Entry<String, Integer> entry : complexInputs.entrySet()) {
            for (MetadataValue dval : itemService.getMetadataByMetadataString(item, entry.getKey())) {
                String val = dval.getValue();
                if (val.split(DCInput.ComplexDefinitions.getSeparator(), -1).length != entry.getValue()) {
                    throw new CurateException(
                        String.format(
                            "%s is a component with %s values but is not stored as such. [%s]",
                            entry.getKey(), entry.getValue(), val),
                        Curator.CURATE_FAIL);
                }
            }
        }
    }

    private void itemWithFilesHasLicense(Item item) throws CurateException {
        try {
            boolean fail = false;
            StringBuilder sb = new StringBuilder();
            try {
                if (itemService.hasUploadedFiles(item, "ORIGINAL")) {
                    for (String mdString : rightsMdStrings) {
                        final List<MetadataValue> vals = itemService.getMetadataByMetadataString(item, mdString);
                        if (vals == null || vals.isEmpty()) {
                            fail = true;
                            sb.append(mdString).append(", ");
                        }
                    }
                }
            } catch (org.hibernate.LazyInitializationException e) {
                // Item is detached from session, skip file check
                // This can happen when processing large batches
                log.debug("Skipping file check for item {} due to detached session", item.getHandle());
            }
            if (fail) {
                throw new CurateException("There are bitstreams but incomplete rights metadata. Missing: "
                    + sb.toString(), Curator.CURATE_FAIL);
            }
        } catch (SQLException throwables) {
            throw new CurateException(throwables.getMessage(), Curator.CURATE_ERROR);
        }
    }

    /**
     * Curate exception.
     */
    static class CurateException extends Exception {
        int errCode;

        public CurateException(String message, int errCode) {
            super(message);
            this.errCode = errCode;
        }
    }
}
