/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */

/* Created for LINDAT/CLARIAH-CZ (UFAL) */
package org.dspace.utils;

import java.io.InputStreamReader;
import java.io.Reader;
import java.sql.SQLException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.dspace.app.util.DCInput;
import org.dspace.authorize.ResourcePolicy;
import org.dspace.authorize.factory.AuthorizeServiceFactory;
import org.dspace.authorize.service.ResourcePolicyService;
import org.dspace.content.Bitstream;
import org.dspace.content.Bundle;
import org.dspace.content.DSpaceObject;
import org.dspace.content.Item;
import org.dspace.content.MetadataField;
import org.dspace.content.MetadataValue;
import org.dspace.content.factory.ClarinServiceFactory;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.BitstreamService;
import org.dspace.content.service.ItemService;
import org.dspace.content.service.MetadataFieldService;
import org.dspace.content.service.clarin.ClarinItemService;
import org.dspace.core.Constants;
import org.dspace.core.Context;
import org.dspace.handle.factory.HandleServiceFactory;
import org.dspace.handle.service.HandleService;
import org.dspace.xoai.exceptions.InvalidMetadataFieldException;
import org.dspace.xoai.services.impl.DSpaceFieldResolver;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

@Component

/**
 * Provides various information based on
 * provided metadata or strings.
 *
 * Class is copied from the LINDAT/CLARIAH-CZ (https://github.com/ufal/clarin-dspace/blob
 * /si-master-origin/dspace-oai/src/main/java/cz/cuni/mff/ufal/utils/ItemUtil.java) and modified by
 * @author Marian Berger (marian.berger at dataquest.sk)
 */
public class SpecialItemService {
    private SpecialItemService() {}
    /** log4j logger */
    private static final org.apache.logging.log4j.Logger log = org.apache.logging.log4j
            .LogManager.getLogger(SpecialItemService.class);

    /**
     * Returns cmdi metadata of item, if uploaded and marked as local.hasCMDI = true.
     * @param handle handle of object for which we need metadata.
     * @return Document repserenting cmdi metadata uploaded to METADATA bundle of item.
     */
    public static Node getUploadedMetadata(String handle) {
        Node ret = null;
        Context context = null;
        try {
            context = new Context();
            ContentServiceFactory csf = ContentServiceFactory.getInstance();
            ItemService itemService = csf.getItemService();
            BitstreamService bitstreamService = csf.getBitstreamService();
            HandleService hs = HandleServiceFactory.getInstance().getHandleService();
            DSpaceObject dSpaceObject = hs.resolveToObject(context, handle);
            List<MetadataValue> metadataValues = itemService.getMetadataByMetadataString(((Item) dSpaceObject),
                    "local.hasCMDI");
            if (Objects.nonNull(dSpaceObject) && dSpaceObject.getType() == Constants.ITEM
                    && hasOwnMetadata(metadataValues)) {

                Bitstream bitstream = itemService.getBundles(((Item) dSpaceObject), "METADATA").get(0)
                        .getBitstreams().get(0);
                if (Objects.isNull(bitstream)) {
                    return ret;
                }
                context.turnOffAuthorisationSystem();
                Reader reader = new InputStreamReader(bitstreamService.retrieve(context, bitstream));
                context.restoreAuthSystemState();
                try {
                    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                    factory.setNamespaceAware(true);
                    DocumentBuilder builder = factory.newDocumentBuilder();
                    Document doc = builder.parse(new InputSource(reader));
                    ret = doc;
                } finally {
                    reader.close();
                }

            }
        } catch (Exception e) {
            log.error(e);
            try {
                ret = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
            } catch (ParserConfigurationException ex) {
                log.error(ex);
            }
        } finally {
            closeContext(context);
        }
        return ret;
    }

    /**
     * Splits funding into separate values and creates document with those values.
     * @param mdValue String of funding, expected to have 4 fields separated by ;
     * @return document representing separated values from param
     */
    public static Node getFunding(String mdValue) {
        String ns = "http://www.clarin.eu/cmd/";
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder;
        try {
            builder = factory.newDocumentBuilder();
            Document doc = builder.newDocument();
            Element el = doc.createElementNS(ns, "funding");
            doc.appendChild(el);
            Element organization = doc.createElementNS(ns, "organization");
            Element projName = doc.createElementNS(ns, "projectName");
            Element code = doc.createElementNS(ns, "code");
            Element fundsType = doc.createElementNS(ns, "fundsType");

            if (Objects.isNull(mdValue)) {
                log.warn("Trying to extract funding from null value!");
                return null;
            }
            String[] values = mdValue
                    .split(DCInput.ComplexDefinitions.getSeparator(), -1);
            // ORIGINAL order of funding was org;code;projname;type
            // Element[] elements = {organization, code, projName, fundsType};

            // TODO 2024/07 - order was changed to fundsType, code, org, projName
            Element[] elements = {fundsType, code, organization, projName};

            for (int i = 0; i < elements.length; i++) {
                if (values.length <= i) {
                    elements[i].appendChild(doc.createTextNode(""));
                } else {
                    elements[i].appendChild(doc.createTextNode(values[i]));
                }

            }
            // swap to original order to display correctly
            Element[] correctOrder = {organization, code, projName, fundsType};

            for (Element e : correctOrder) {
                el.appendChild(e);
            }

            return doc;
        } catch (ParserConfigurationException e) {
            return null;
        }
    }

    /**
     * Creates document representing separated/parsed contact info from param
     * @param mdValue Contact field with several values delimited by ;
     * @return document representing separated values
     */
    public static Node getContact(String mdValue) {
        String ns = "http://www.clarin.eu/cmd/";
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder;
        try {
            builder = factory.newDocumentBuilder();
            Document doc = builder.newDocument();
            Element el = doc.createElementNS(ns, "contactPerson");
            doc.appendChild(el);
            Element first = doc.createElementNS(ns, "firstName");
            Element last = doc.createElementNS(ns, "lastName");
            Element email = doc.createElementNS(ns, "email");
            Element affil = doc.createElementNS(ns, "affiliation");

            String[] values = mdValue
                    .split(DCInput.ComplexDefinitions.getSeparator(), -1);

            Element[] elements = {first, last, email, affil};
            for (int i = 0; i < elements.length; i++) {
                if (values.length <= i) {
                    elements[i].appendChild(doc.createTextNode(""));
                } else {
                    elements[i].appendChild(doc.createTextNode(values[i]));
                }
                el.appendChild(elements[i]);
            }

            return doc;
        } catch (ParserConfigurationException e) {
            return null;
        }
    }

    public static Node getSize(String mdValue) {
        String ns = "http://www.clarin.eu/cmd/";
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder;
        try {
            builder = factory.newDocumentBuilder();
            Document doc = builder.newDocument();
            Element el = doc.createElementNS(ns, "size");
            doc.appendChild(el);
            Element size = doc.createElementNS(ns, "size");
            Element unit = doc.createElementNS(ns, "unit");

            String[] values = mdValue
                    .split(DCInput.ComplexDefinitions.getSeparator(), -1);

            Element[] elements = {size, unit};
            for (int i = 0; i < elements.length; i++) {
                if (values.length <= i) {
                    elements[i].appendChild(doc.createTextNode(""));
                } else {
                    elements[i].appendChild(doc.createTextNode(values[i]));
                }
                el.appendChild(elements[i]);
            }
            return doc;
        } catch (ParserConfigurationException e) {
            return null;
        }
    }

    /**
     * Generates author document from provided string.
     * @param mdValue String containing author, possibly with separated Firstname by ;
     * @return document representing possibly separated values from param.
     */
    public static Node getAuthor(String mdValue) {
        String ns = "http://www.clarin.eu/cmd/";
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder;
        try {
            builder = factory.newDocumentBuilder();
            Document doc = builder.newDocument();
            Element el = doc.createElementNS(ns, "author");
            doc.appendChild(el);
            Element last = doc.createElementNS(ns, "lastName");

            if (Objects.isNull(mdValue) || mdValue.isEmpty()) {
                log.warn("Trying to extract author from empty string!");
                return null;
            }
            String[] values = mdValue
                    .split(",", 2);

            last.appendChild(doc.createTextNode(values[0]));
            el.appendChild(last);
            if (values.length > 1) {
                // this probably means that if there are multiple fields, first is surname, second
                // is first name. Taken from here:
                // https://github.com/ufal/clarin-dspace/blob/8780782ce2977d304f2390b745a98eaea00b8255/
                // dspace-oai/src/main/java/cz/cuni/mff/ufal/utils/ItemUtil.java#L168
                Element first = doc.createElementNS(ns, "firstName");
                first.appendChild(doc.createTextNode(values[1]));
                el.appendChild(first);
            }
            return doc;
        } catch (ParserConfigurationException e) {
            return null;
        }
    }

    /**
     * Retrieves the earliest available date for an item identified by the given identifier URI.
     * This method checks for any embargo date first and then retrieves the "dc.date.available"
     * metadata value as a fallback if no embargo date is found.
     *
     * @param identifierUri The identifier URI of the item whose available date is to be retrieved.
     * @return A string representation of the earliest available date, or null if no date is found or an error occurs.
     */
    public static String getAvailable(String identifierUri) {
        Context context = new Context();
        // Find the metadata field for "dc.identifier.uri"
        String mtdField = "dc.identifier.uri";
        MetadataField metadataField = findMetadataField(context, mtdField);
        if (Objects.isNull(metadataField)) {
            log.error(String.format("Metadata field for %s not found.", mtdField));
            return null;
        }

        // Retrieve the item using the handle
        ClarinItemService clarinItemService = ClarinServiceFactory.getInstance().getClarinItemService();
        Item item;
        try {
            List<Item> itemList = clarinItemService.findByHandle(context, metadataField, identifierUri);
            item = itemList.isEmpty() ? null : itemList.get(0);
        } catch (SQLException e) {
            log.error("Error retrieving item by handle.", e);
            return null;
        }
        if (Objects.isNull(item)) {
            log.error(String.format("Item for handle %s doesn't exist!", identifierUri));
            return null;
        }

        // Check if there is an embargo or get the earliest available date
        Date startDate = getEmbargoDate(context, item);
        if (Objects.isNull(startDate)) {
            startDate = getAvailableDate(context, item);
        }
        return (Objects.nonNull(startDate)) ? startDate.toString() : null;
    }

    /**
     * Finds the metadata field corresponding to the provided string.
     *
     * @param context The DSpace context
     * @param mtd The metadata field string
     * @return The MetadataField object, or null if not found.
     */
    private static MetadataField findMetadataField(Context context, String mtd) {
        MetadataFieldService metadataFieldService = ContentServiceFactory.getInstance().getMetadataFieldService();
        try {
            return metadataFieldService.findByString(context, mtd, '.');
        } catch (SQLException e) {
            log.error(String.format("Error finding metadata field %s.", mtd), e);
            return null;
        }
    }

    /**
     * Retrieves the embargo start date for the given item bitstreams.
     * If an embargo has ended, the end date is returned.
     *
     * @param context The DSpace context
     * @param item The item whose embargo date is to be retrieved.
     * @return The start or end date of the embargo, or null if no embargo exists.
     */
    private static Date getEmbargoDate(Context context, Item item) {
        ResourcePolicyService resPolicyService = AuthorizeServiceFactory.getInstance().getResourcePolicyService();
        Date startDate = null;
        for (Bundle bundle : item.getBundles()) {
            for (Bitstream bitstream : bundle.getBitstreams()) {
                List<ResourcePolicy> resPolList;
                try {
                    resPolList = resPolicyService.find(context, bitstream, Constants.READ);
                } catch (SQLException e) {
                    log.error(String.format("Error during finding resource policies READ for bitstream %s",
                            bitstream.getID().toString()));
                    return null;
                }
                for (ResourcePolicy resPol : resPolList) {
                    Date date = resPol.getStartDate();
                    // If the embargo has already ended, use the date of its end.
                    if (Objects.nonNull(date) && Objects.nonNull(resPol.getEndDate())) {
                        date = resPol.getEndDate();
                    }
                    if (Objects.isNull(startDate) || (Objects.nonNull(date) && date.compareTo(startDate) > 0)) {
                        startDate = date;
                    }
                }
            }
        }
        return startDate;
    }

    /**
     * Retrieves the available date for the given item by checking the "dc.date.available" metadata.
     *
     * @param context The DSpace context
     * @param item The item whose available date is to be retrieved.
     * @return The available date, or null if no available date is found.
     */
    private static Date getAvailableDate(Context context, Item item) {
        DSpaceFieldResolver dSpaceFieldResolver = new DSpaceFieldResolver();
        List<MetadataValue> metadataValueList = item.getMetadata();
        String mtdField = "dc.date.available";
        int fieldID;
        try {
            fieldID = dSpaceFieldResolver.getFieldID(context, mtdField);
        } catch (SQLException | InvalidMetadataFieldException e) {
            log.error(String.format("Error during finding ID of metadata field %s.", mtdField));
            return null;
        }
        Date startDate = null;
        for (MetadataValue mtd : metadataValueList) {
            if (mtd.getMetadataField().getID() == fieldID) {
                Date availableDate = parseDate(mtd.getValue());
                if (Objects.isNull(startDate) || (Objects.nonNull(availableDate)
                        && availableDate.compareTo(startDate) > 0)) {
                    startDate = availableDate;
                }
            }
        }
        return startDate;
    }

    /**
     * Parses a date string in the format "yyyy-MM-dd" into a Date object.
     *
     * @param dateString The date string to be parsed.
     * @return A Date object representing the parsed date, or null if parsing fails.
     */
    private static Date parseDate(String dateString) {
        String format = "yyyy-MM-dd";
        SimpleDateFormat dateFormat = new SimpleDateFormat(format); // Example format
        dateFormat.setLenient(false); // Set lenient to false to avoid parsing incorrect dates
        try {
            return dateFormat.parse(dateString); // Attempt to parse the date
        } catch (ParseException e) {
            log.warn(String.format("Date %s cannot be parsed using the format %s.", dateString, format));
            return null;
        }
    }

    public static boolean hasOwnMetadata(List<MetadataValue> metadataValues) {
        if (metadataValues.size() == 1 && metadataValues.get(0).getValue().equalsIgnoreCase("true")) {
            return true;
        }
        return false;
    }

    private static void closeContext(Context c) {
        if (Objects.nonNull(c)) {
            c.abort();
        }
    }
}
