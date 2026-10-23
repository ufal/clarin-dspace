/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.xoai.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import com.lyncode.xoai.dataprovider.xml.xoai.Element;
import com.lyncode.xoai.dataprovider.xml.xoai.Metadata;
import com.lyncode.xoai.util.Base64Utils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.dspace.app.util.factory.UtilServiceFactory;
import org.dspace.app.util.service.MetadataExposureService;
import org.dspace.authorize.AuthorizeException;
import org.dspace.authorize.ResourcePolicy;
import org.dspace.authorize.factory.AuthorizeServiceFactory;
import org.dspace.authorize.service.AuthorizeService;
import org.dspace.content.Bitstream;
import org.dspace.content.Bundle;
import org.dspace.content.Item;
import org.dspace.content.MetadataField;
import org.dspace.content.MetadataValue;
import org.dspace.content.authority.Choices;
import org.dspace.content.clarin.ClarinLicenseResourceMapping;
import org.dspace.content.factory.ClarinServiceFactory;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.BitstreamService;
import org.dspace.content.service.ItemService;
import org.dspace.content.service.RelationshipService;
import org.dspace.content.service.clarin.ClarinLicenseResourceMappingService;
import org.dspace.core.Constants;
import org.dspace.core.Context;
import org.dspace.core.Utils;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.dspace.xoai.data.DSpaceItem;

/**
 * @author Lyncode Development Team (dspace at lyncode dot com)
 */
@SuppressWarnings("deprecation")
public class ItemUtils {

    private static final ClarinLicenseResourceMappingService clarinLicenseResourceMappingService
            = ClarinServiceFactory.getInstance().getClarinLicenseResourceMappingService();
    private static final Logger log = LogManager.getLogger(ItemUtils.class);

    private static final MetadataExposureService metadataExposureService
            = UtilServiceFactory.getInstance().getMetadataExposureService();

    private static final ItemService itemService
            = ContentServiceFactory.getInstance().getItemService();

    private static final RelationshipService relationshipService
            = ContentServiceFactory.getInstance().getRelationshipService();

    private static final BitstreamService bitstreamService
            = ContentServiceFactory.getInstance().getBitstreamService();

    private static final ConfigurationService configurationService
            = DSpaceServicesFactory.getInstance().getConfigurationService();

    private static final AuthorizeService authorizeService
            = AuthorizeServiceFactory.getInstance().getAuthorizeService();

    /**
     * Default constructor
     */
    private ItemUtils() {
    }

    public static Element getElement(List<Element> list, String name) {
        for (Element e : list) {
            if (name.equals(e.getName())) {
                return e;
            }
        }

        return null;
    }

    public static Element create(String name) {
        Element e = new Element();
        e.setName(name);
        return e;
    }

    public static Element.Field createValue(String name, String value) {
        Element.Field e = new Element.Field();
        e.setValue(value);
        e.setName(name);
        return e;
    }

    /**
     * Default list of bundle names excluded from OAI-PMH exposure.
     * These are typically derivative bundles produced by {@code dspace filter-media}
     * (extracted plain-text for indexing, generated thumbnails) or internal bundles
     * such as the SWORD deposit package. Exposing them may leak content that is not
     * intended to be a first-class resource of the item.
     * The {@code oai.bundle.excluded} configuration property, when set, overrides
     * this default list with a comma-separated list of bundle names.
     */
    private static final String[] DEFAULT_EXCLUDED_BUNDLES = new String[] {
        "TEXT", "THUMBNAIL", "SWORD"
    };

    /**
     * @return the effective names of bundles excluded from OAI-PMH exposure,
     * using {@code oai.bundle.excluded} when configured, or the default
     * excluded bundle list otherwise.
     */
    private static Set<String> getExcludedBundleNames() {
        String[] configured = configurationService
                .getArrayProperty("oai.bundle.excluded");
        String[] effective = (configured != null && configured.length > 0)
                ? configured
                : DEFAULT_EXCLUDED_BUNDLES;
        Set<String> excluded = new HashSet<>();
        for (String name : effective) {
            if (name != null) {
                String trimmed = name.trim();
                if (!trimmed.isEmpty()) {
                    excluded.add(trimmed);
                }
            }
        }
        return excluded;
    }

    private static Element createBundlesElement(Context context, Item item, AtomicBoolean restricted)
            throws SQLException {
        Element bundles = create("bundles");

        List<Bundle> bs;

        Set<String> excludedBundleNames = getExcludedBundleNames();

        bs = item.getBundles();
        for (Bundle b : bs) {
            // Skip bundles that must not be exposed via OAI-PMH (e.g. TEXT/THUMBNAIL
            // bundles produced by `dspace filter-media`).
            if (b.getName() != null && excludedBundleNames.contains(b.getName())) {
                continue;
            }
            Element bundle = create("bundle");
            bundles.getElement().add(bundle);
            bundle.getField().add(createValue("name", b.getName()));

            Element bitstreams = create("bitstreams");
            bundle.getElement().add(bitstreams);
            List<Bitstream> bits = b.getBitstreams();
            for (Bitstream bit : bits) {
                // Check if bitstream is null and log the error
                if (bit == null) {
                    log.error("Null bitstream found, check item uuid: " + item.getID());
                    break;
                }
                boolean primary = false;
                // Check if current bitstream is in original bundle + 1 of the 2 following
                // Bitstream = primary bitstream in bundle -> true
                // No primary bitstream found in bundle-> only the first one gets flagged as "primary"
                if (b.getName() != null && b.getName().equals("ORIGINAL") && (b.getPrimaryBitstream() != null
                        && b.getPrimaryBitstream().getID() == bit.getID()
                        || b.getPrimaryBitstream() == null && bit.getID() == bits.get(0).getID())) {
                    primary = true;
                }

                Element bitstream = create("bitstream");
                bitstreams.getElement().add(bitstream);

                String url = "";
                String bsName = bit.getName();
                String sid = String.valueOf(bit.getSequenceID());
                String baseUrl = configurationService.getProperty("oai.bitstream.baseUrl");
                String handle = null;
                // get handle of parent Item of this bitstream, if there
                // is one:
                List<Bundle> bn = bit.getBundles();
                if (!bn.isEmpty()) {
                    List<Item> bi = bn.get(0).getItems();
                    if (!bi.isEmpty()) {
                        handle = bi.get(0).getHandle();
                    }
                }
                if (bsName == null) {
                    List<String> ext = bit.getFormat(context).getExtensions();
                    bsName = "bitstream_" + sid + (ext.isEmpty() ? "" : ext.get(0));
                }
                if (handle != null && baseUrl != null) {
                    url = baseUrl + "/bitstream/"
                            + handle + "/"
                            + sid + "/"
                            + URLUtils.encode(bsName);
                } else {
                    url = URLUtils.encode(bsName);
                }

                String cks = bit.getChecksum();
                String cka = bit.getChecksumAlgorithm();
                String oname = bit.getSource();
                String name = bit.getName();
                String description = bit.getDescription();

                if (name != null) {
                    bitstream.getField().add(createValue("name", name));
                }
                if (oname != null) {
                    bitstream.getField().add(createValue("originalName", oname));
                }
                if (description != null) {
                    bitstream.getField().add(createValue("description", description));
                }
                // Add bitstream embargo information (READ policy present, for Anonymous group with a start date)
                addResourcePolicyInformation(context, bit, bitstream);

                bitstream.getField().add(createValue("format", bit.getFormat(context).getMIMEType()));
                bitstream.getField().add(createValue("size", "" + bit.getSizeBytes()));
                bitstream.getField().add(createValue("url", url));
                bitstream.getField().add(createValue("checksum", cks));
                bitstream.getField().add(createValue("checksumAlgorithm", cka));
                bitstream.getField().add(createValue("sid", bit.getSequenceID() + ""));
                bitstream.getField().add(createValue("id", bit.getID().toString()));
                // Add primary bitstream field to allow locating easily the primary bitstream information
                bitstream.getField().add(createValue("primary", primary + ""));
                if (!restricted.get()) {
                    List<ClarinLicenseResourceMapping> clarinLicenseResourceMappingList =
                            clarinLicenseResourceMappingService.findByBitstreamUUID(context, bit.getID());
                    for (ClarinLicenseResourceMapping clrm : clarinLicenseResourceMappingList) {
                        if (clrm.getLicense().getRequiredInfo() != null
                                && clrm.getLicense().getRequiredInfo().length() > 0) {
                            restricted.set(true);
                            break;
                        }
                    }
                }
            }
        }

        return bundles;
    }

    /**
     * Matches everything XML 1.0 forbids outright, in three alternations:
     * <ol>
     *   <li>C0 controls other than tab, LF and CR, plus the non-characters U+FFFE and U+FFFF;</li>
     *   <li>a high surrogate not followed by a low surrogate;</li>
     *   <li>a low surrogate not preceded by a high surrogate.</li>
     * </ol>
     * See https://www.w3.org/TR/xml/#charsets. Unpaired surrogates matter in practice: a truncated
     * 4-byte character in ingested metadata makes the StAX writer throw "Broken surrogate pair", and
     * because XOAI.index() catches that per item the record is silently dropped from the OAI index.
     */
    private static final Pattern INVALID_XML10_CHARS = Pattern.compile(
        "[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\uFFFE\\uFFFF]"
            + "|[\\uD800-\\uDBFF](?![\\uDC00-\\uDFFF])"
            + "|(?<![\\uD800-\\uDBFF])[\\uDC00-\\uDFFF]");

    /**
     * Sanitizes a string to remove characters that are invalid in XML 1.0.
     * <P>
     * NOTE: this deliberately REMOVES illegal characters rather than escaping the string. The value
     * returned here is handed to the XOAI serializer, which performs XML escaping itself (every text
     * event goes through {@code XMLStreamWriter.writeCharacters}), so escaping here as well would
     * double-escape every value containing &amp;, &lt;, &gt;, " or ' — a harvester would then read the
     * literal text "&amp;lt;" instead of a "&lt;" character. That silently corrupts every OAI format
     * built on the xoai document, including the cmdi and olac formats CLARIN/LINDAT is aggregated
     * through.
     * <P>
     * The removal set must stay equivalent to what {@code StringEscapeUtils.escapeXml10} removed —
     * notably including unpaired surrogates — otherwise items carrying them fail to serialize and
     * drop out of the OAI index entirely.
     * @param value The string to sanitize.
     * @return A sanitized string, or null if the input was null.
     */
    private static String sanitize(String value) {
        if (value == null) {
            return null;
        }
        return INVALID_XML10_CHARS.matcher(value).replaceAll("");
    }

    /**
     * This method will add metadata information about associated resource policies for a give bitstream.
     * It will parse of relevant policies and add metadata information
     * @param context
     * @param bitstream the bitstream object
     * @param bitstreamEl the bitstream metadata object to add resource policy information to
     * @throws SQLException
     */
    private static void addResourcePolicyInformation(Context context, Bitstream bitstream, Element bitstreamEl)
            throws SQLException {
        // Pre-filter access policies by DSO (bitstream) and Action (READ)
        List<ResourcePolicy> policies = authorizeService.getPoliciesActionFilter(context, bitstream, Constants.READ);

        // Create resourcePolicies container
        Element resourcePolicies = create("resourcePolicies");

        for (ResourcePolicy policy : policies) {
            String groupName = policy.getGroup() != null ? policy.getGroup().getName() : null;
            String user = policy.getEPerson() != null ? policy.getEPerson().getName() : null;
            String action = Constants.actionText[policy.getAction()];
            LocalDate startDate = policy.getStartDate();
            LocalDate endDate = policy.getEndDate();

            DateTimeFormatter formatter = DateTimeFormatter.ISO_LOCAL_DATE;

            Element resourcePolicyEl = create("resourcePolicy");
            resourcePolicyEl.getField().add(createValue("group", groupName));
            resourcePolicyEl.getField().add(createValue("user", user));
            resourcePolicyEl.getField().add(createValue("action", action));
            // Only add start-date if group is different to anonymous, or there is an active embargo
            if (startDate != null && startDate.isAfter(LocalDate.now())) {
                resourcePolicyEl.getField().add(createValue("start-date", formatter.format(startDate)));
            }
            if (endDate != null) {
                resourcePolicyEl.getField().add(createValue("end-date", formatter.format(endDate)));
            }
            // Add resourcePolicy to list of resourcePolicies
            resourcePolicies.getElement().add(resourcePolicyEl);
        }
        // Add list of resource policies to the corresponding Bitstream XML Element
        bitstreamEl.getElement().add(resourcePolicies);
    }

    private static Element createLicenseElement(Context context, Item item)
            throws SQLException, AuthorizeException, IOException {
        Element license = create("license");
        List<Bundle> licBundles;
        licBundles = itemService.getBundles(item, Constants.LICENSE_BUNDLE_NAME);
        if (!licBundles.isEmpty()) {
            Bundle licBundle = licBundles.get(0);
            List<Bitstream> licBits = licBundle.getBitstreams();
            if (!licBits.isEmpty()) {
                Bitstream licBit = licBits.get(0);
                if (authorizeService.authorizeActionBoolean(context, licBit, Constants.READ)) {
                    InputStream in;

                    in = bitstreamService.retrieve(context, licBit);
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    Utils.bufferedCopy(in, out);
                    license.getField().add(createValue("bin", Base64Utils.encode(out.toString())));
                } else {
                    log.info("Missing READ rights for license bitstream. Did not include license bitstream for item: "
                            + item.getID() + ".");
                }
            }
        }
        return license;
    }

    /**
     * This method will add all sub-elements to a top element, like: dc, or dcterms, ...     *
     * @param schema         Element argument passed by reference that will be changed
     * @param val            Metadatavalue that will be processed
     * @throws SQLException
     */
    private static void fillSchemaElement(Element schema, MetadataValue val) throws SQLException {
        MetadataField field = val.getMetadataField();
        Element valueElem = schema;

        // Has element.. with XOAI one could have only schema and value
        if (field.getElement() != null && !field.getElement().equals("")) {
            Element element = getElement(schema.getElement(), field.getElement());
            if (element == null) {
                element = create(field.getElement());
                schema.getElement().add(element);
            }
            valueElem = element;

            // Qualified element?
            if (field.getQualifier() != null && !field.getQualifier().equals("")) {
                Element qualifier = getElement(element.getElement(), field.getQualifier());
                if (qualifier == null) {
                    qualifier = create(field.getQualifier());
                    element.getElement().add(qualifier);
                }
                valueElem = qualifier;
            }
        }

        // Language?
        if (val.getLanguage() != null && !val.getLanguage().equals("")) {
            Element language = getElement(valueElem.getElement(), val.getLanguage());
            if (language == null) {
                language = create(val.getLanguage());
                valueElem.getElement().add(language);
            }
            valueElem = language;
        } else {
            Element language = getElement(valueElem.getElement(), "none");
            if (language == null) {
                language = create("none");
                valueElem.getElement().add(language);
            }
            valueElem = language;
        }

        valueElem.getField().add(createValue("value", sanitize(val.getValue())));
        if (val.getAuthority() != null) {
            valueElem.getField().add(createValue("authority", val.getAuthority()));
            if (val.getConfidence() != Choices.CF_NOVALUE) {
                valueElem.getField().add(createValue("confidence", val.getConfidence() + ""));
            }
        }
    }

    /**
     * Utility method to retrieve a structured XML in XOAI format
     * @param context
     * @param item
     * @return Structured XML Metadata in XOAI format
     */
    public static Metadata retrieveMetadata(Context context, Item item) {
        Metadata metadata;

        // read all metadata into Metadata Object
        metadata = new Metadata();

        List<MetadataValue> vals = itemService.getMetadata(item, Item.ANY, Item.ANY, Item.ANY, Item.ANY);
        for (MetadataValue val : vals) {
            MetadataField field = val.getMetadataField();
            try {
                // Don't expose fields that are hidden by configuration
                if (metadataExposureService.isHidden(context, field.getMetadataSchema().getName(), field.getElement(),
                        field.getQualifier())) {
                    continue;
                }

                Element schema = getElement(metadata.getElement(), field.getMetadataSchema().getName());
                if (schema == null) {
                    schema = create(field.getMetadataSchema().getName());
                    metadata.getElement().add(schema);
                }

                fillSchemaElement(schema, val);
            } catch (SQLException se) {
                throw new RuntimeException(se);
            }
        }

        // Done! Metadata has been read!
        // Now adding bitstream info

        //indicate restricted bitstreams -> restricted access
        AtomicBoolean restricted = new AtomicBoolean(false);

        try {
            Element bundles = createBundlesElement(context, item, restricted);
            metadata.getElement().add(bundles);
        } catch (SQLException e) {
            log.warn(e.getMessage(), e);
        }

        // Other info
        Element other = create("others");

        other.getField().add(createValue("handle", item.getHandle()));
        other.getField().add(createValue("identifier", DSpaceItem.buildIdentifier(item.getHandle())));
        other.getField().add(createValue("lastModifyDate", item.getLastModified().toString()));

        if (restricted.get()) {
            other.getField().add(createValue("restrictedAccess", "true"));
        }
        // Because we reindex Solr, which is not done in vanilla
        // The owning collection for workspace items is null
        other.getField().add(createValue("owningCollection",
                item.getOwningCollection() != null ? item.getOwningCollection().getName() : null));
        other.getField().add(createValue("itemId", item.getID().toString()));
        metadata.getElement().add(other);

        // Repository Info
        Element repository = create("repository");
        repository.getField().add(createValue("url", configurationService.getProperty("dspace.ui.url")));
        repository.getField().add(createValue("name", configurationService.getProperty("dspace.name")));
        repository.getField().add(createValue("mail", configurationService.getProperty("mail.admin")));
        metadata.getElement().add(repository);

        // Licensing info
        try {
            Element license = createLicenseElement(context, item);
            metadata.getElement().add(license);
        } catch (AuthorizeException | IOException | SQLException e) {
            log.warn(e.getMessage(), e);
        }

        return metadata;
    }
}
