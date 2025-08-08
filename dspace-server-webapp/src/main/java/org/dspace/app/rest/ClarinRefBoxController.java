/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest;

import static java.util.Arrays.asList;
import static javax.servlet.http.HttpServletResponse.SC_OK;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.NotFoundException;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.stream.XMLStreamException;

import com.hp.hpl.jena.rdf.model.Model;
import com.lyncode.xoai.dataprovider.OAIDataProvider;
import com.lyncode.xoai.dataprovider.OAIRequestParameters;
import com.lyncode.xoai.dataprovider.core.XOAIManager;
import com.lyncode.xoai.dataprovider.exceptions.InvalidContextException;
import com.lyncode.xoai.dataprovider.exceptions.OAIException;
import com.lyncode.xoai.dataprovider.exceptions.WritingXmlException;
import com.lyncode.xoai.dataprovider.xml.XmlOutputContext;
import com.lyncode.xoai.dataprovider.xml.oaipmh.OAIPMH;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;
import org.dspace.app.rest.converter.ConverterService;
import org.dspace.app.rest.exception.UnprocessableEntityException;
import org.dspace.app.rest.model.ClarinFeaturedServiceRest;
import org.dspace.app.rest.model.refbox.ExportFormatDTO;
import org.dspace.app.rest.model.refbox.FeaturedServiceDTO;
import org.dspace.app.rest.model.refbox.FeaturedServiceLinkDTO;
import org.dspace.app.rest.model.refbox.RefBoxDTO;
import org.dspace.app.rest.utils.ContextUtil;
import org.dspace.app.rest.utils.Utils;
import org.dspace.content.DSpaceObject;
import org.dspace.content.Item;
import org.dspace.content.MetadataValue;
import org.dspace.content.clarin.ClarinFeaturedService;
import org.dspace.content.clarin.ClarinFeaturedServiceLink;
import org.dspace.content.service.ItemService;
import org.dspace.core.Context;
import org.dspace.handle.service.HandleService;
import org.dspace.services.ConfigurationService;
import org.dspace.xoai.services.api.config.XOAIManagerResolver;
import org.dspace.xoai.services.api.config.XOAIManagerResolverException;
import org.dspace.xoai.services.api.context.ContextService;
import org.dspace.xoai.services.api.context.ContextServiceException;
import org.dspace.xoai.services.api.xoai.IdentifyResolver;
import org.dspace.xoai.services.api.xoai.ItemRepositoryResolver;
import org.dspace.xoai.services.api.xoai.SetRepositoryResolver;
import org.dspace.xoai.services.impl.xoai.DSpaceResumptionTokenFormatter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

/**
 * A Controller for fetching the data for the ref-box in the Item View (FE).
 * It is fetching the featured services and the citation data from the OAI-PMH.
 *
 * @author Milan Majchrak (milan.majchrak at dataquest.sk)
 */
@RestController
@RequestMapping("/api/core/refbox")
public class ClarinRefBoxController {

    private final static String BIBTEX_TYPE = "bibtex";

    /**
     * Default language for the RefBox metadata values
     * This will be changed in the future to support multiple languages, probably fetching the language from the
     * request, but for now there is a mess in the metadata value languages, so we will use the default.
     */
    private final static String DEFAULT_LANGUAGE = "*";

    private final Logger log = org.apache.logging.log4j.LogManager.getLogger(ClarinRefBoxController.class);

    @Autowired
    ConfigurationService configurationService;

    @Autowired
    ItemService itemService;

    @Autowired
    private ConverterService converterService;

    @Autowired
    protected Utils utils;

    @Autowired
    private ContextService contextService;

    @Autowired
    private XOAIManagerResolver xoaiManagerResolver;

    @Autowired
    private IdentifyResolver identifyResolver;

    @Autowired
    private SetRepositoryResolver setRepositoryResolver;

    @Autowired
    private ItemRepositoryResolver itemRepositoryResolver;

    @Autowired
    private HandleService handleService;

    private final DSpaceResumptionTokenFormatter resumptionTokenFormat = new DSpaceResumptionTokenFormatter();

    /**
     * Return Featured Service objects based on the configuration and Item Metadata.
     */
    @RequestMapping(method = RequestMethod.GET, value = "/services")
    public Page<ClarinFeaturedServiceRest> getServices(@RequestParam(name = "id") UUID id,
                                                       HttpServletResponse response,
                                                       HttpServletRequest request, Pageable pageable)
            throws SQLException {
        // Get context
        Context context = ContextUtil.obtainCurrentRequestContext();
        if (Objects.isNull(context)) {
            throw new RuntimeException("Cannot obtain the context from the request.");
        }

        // Get item
        Item item = itemService.find(context, id);
        if (Objects.isNull(item)) {
            throw new NotFoundException("Cannot find the item with the uuid: " + id);
        }

        // Create the Featured Service list for the response.
        List<ClarinFeaturedService> featuredServiceList = new ArrayList<>();

        // Get service definition from configuration.
        List<String> featuredServiceNames = Arrays.asList(
                configurationService.getArrayProperty("featured.services"));
        for (String featuredServiceName : featuredServiceNames) {
            // Get full name, url and description of the featured service from the cfg
            String fullName = configurationService.getProperty("featured.service." + featuredServiceName + ".fullname");
            String url = configurationService.getProperty("featured.service." + featuredServiceName + ".url");
            String description = configurationService.getProperty("featured.service." + featuredServiceName +
                    ".description");

            // The URL cannot be empty because the user must be redirected to that featured service.
            if (StringUtils.isBlank(url)) {
                throw new RuntimeException("The configuration property: `featured.service." + featuredServiceName +
                        ".url cannot be empty!");
            }

            // Check if the item has the metadata for this featured service, if it doesn't have - do NOT return the
            // featured service.
            List<MetadataValue> itemMetadata = itemService.getMetadata(item, "local", "featuredService",
                    featuredServiceName, DEFAULT_LANGUAGE);
            if (CollectionUtils.isEmpty(itemMetadata)) {
                continue;
            }

            // Add the fullname, url, description, links to the REST object
            ClarinFeaturedService clarinFeaturedService = new ClarinFeaturedService();
            clarinFeaturedService.setName(fullName);
            clarinFeaturedService.setUrl(url);
            clarinFeaturedService.setDescription(description);
            clarinFeaturedService.setFeaturedServiceLinks(mapFeaturedServiceLinks(itemMetadata));

            featuredServiceList.add(clarinFeaturedService);
        }

        return converterService.toRestPage(featuredServiceList, pageable, utils.obtainProjection());
    }

    /**
     * Get the metadata from the OAI-PMH based on the metadata type and the item handle.
     */
    @RequestMapping(method = RequestMethod.GET, value = "/citations", produces = "application/json")
    public ResponseEntity getCitationText(@RequestParam(name = "type") String type,
                                          @RequestParam(name = "handle") String handle,
                                          Model model,
                                          HttpServletResponse response,
                                          HttpServletRequest request) throws IOException, ServletException {
        Context context = null;
        OAIPMH oaipmh = null;
        // ClarinOutputStream write OAI-PMH data into String instead of bytes.
        OutputStream output = new UTF8ClarinOutputStream();
        try {
            request.setCharacterEncoding("UTF-8");
            context = contextService.getContext();

            // Get OAI data provider.
            XOAIManager manager = xoaiManagerResolver.getManager();
            OAIDataProvider dataProvider = new OAIDataProvider(manager, "request",
                    identifyResolver.getIdentify(),
                    setRepositoryResolver.getSetRepository(),
                    itemRepositoryResolver.getItemRepository(),
                    resumptionTokenFormat);

            // Adding some defaults for /cite requests this will make the URL simple
            // only handle and metadataPrefix will be required.
            Map<String, List<String>> parameterMap = buildParametersMap(request);
            if (parameterMap.containsKey("type")) {
                parameterMap.remove("type");
            }
            if (!parameterMap.containsKey("verb")) {
                parameterMap.put("verb", asList("GetRecord"));
            }
            if (!parameterMap.containsKey("metadataPrefix")) {
                parameterMap.put("metadataPrefix", asList(type));
            } else {
                List<String> mp = parameterMap.get("metadataPrefix");
                List<String> lcMP = new ArrayList<String>();
                for (String m : mp) {
                    lcMP.add(m.toLowerCase());
                }
                parameterMap.put("metadataPrefix", lcMP);
            }
            if (!parameterMap.containsKey("identifier")) {
                parameterMap.put("identifier", asList("oai:" + request.getServerName() + ":" + handle));
                parameterMap.remove("handle");
            }

            // Some preparing for the getting the data.
            OAIRequestParameters parameters = new OAIRequestParameters(parameterMap);
            response.setContentType("application/xml");

            // Get the OAI-PMH data.
            oaipmh = dataProvider.handle(parameters);

            // XMLOutputObject which has our Clarin output object.
            XmlOutputContext xmlOutContext = XmlOutputContext.emptyContext(output);
            xmlOutContext.getWriter().writeStartDocument();

            // Try to obtain just the metadata, if that fails return "normal" response
            try {
                oaipmh.getInfo().getGetRecord().getRecord().getMetadata().write(xmlOutContext);
            } catch (Exception e) {
                oaipmh.write(xmlOutContext);
            }

            xmlOutContext.getWriter().writeEndDocument();
            xmlOutContext.getWriter().flush();
            xmlOutContext.getWriter().close();

            output.close();
        } catch (InvalidContextException e) {
            return ResponseEntity.ok(indexAction(response, model));
        } catch (ContextServiceException | WritingXmlException | XMLStreamException e) {
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Unexpected error while writing the output. For more information visit the log files.");
        } catch (XOAIManagerResolverException e) {
            String errMessage = "OAI 2.0 wasn't correctly initialized, please check the log for previous errors. " +
                    "Error message: " + e.getMessage();
            log.error(errMessage);
            throw new ServletException(errMessage);
        } catch (OAIException e) {
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Unexpected error. For more information visit the log files.");
        } catch (IOException ioException) {
            ioException.printStackTrace();
        } finally {
            closeContext(context);
        }

        // Something went wrong and OAI data are null,
        if (Objects.isNull(oaipmh)) {
            return new ResponseEntity<String>("Cannot get oaipmh data",
                    HttpStatus.valueOf(HttpServletResponse.SC_NO_CONTENT));
        }

        // Update the output string and remove the unwanted parts.
        String outputString = updateOutput(type, output.toString());

        // Wrap the String output to the class for better parsing in the FE
        OaiMetadataWrapper oaiMetadataWrapper = new OaiMetadataWrapper(StringUtils.defaultIfEmpty(outputString, ""));
        return new ResponseEntity<>(oaiMetadataWrapper, HttpStatus.valueOf(SC_OK));
    }

    /**
     * Get the RefBox information based on the handle.
     * It returns the display text, export formats and featured services.
     */
    @RequestMapping(method = RequestMethod.GET, produces = "application/json")
    public ResponseEntity<RefBoxDTO> getRefboxInfo(
            @RequestParam(name = "handle") String handle,
            HttpServletRequest request) throws SQLException {

        Context context = ContextUtil.obtainContext(request);
        if (context == null) {
            throw new RuntimeException("Cannot obtain the context from the request.");
        }

        DSpaceObject dSpaceObject = handleService.resolveToObject(context, handle);
        if (!(dSpaceObject instanceof Item)) {
            throw new UnprocessableEntityException("The handle does not resolve to an Item.");
        }
        Item item = (Item) dSpaceObject;

        String title = itemService.getMetadataFirstValue(item, "dc", "title", null, DEFAULT_LANGUAGE);
        String displayText = buildDisplayText(context, item);

        // Build exportFormats as a map with "exportFormat" key
        Map<String, List<ExportFormatDTO>> exportFormatsMap = new HashMap<>();
        exportFormatsMap.put("exportFormat", buildExportFormats(item));

        // Build featuredServices as a map with "featuredService" key
        Map<String, List<FeaturedServiceDTO>> featuredServicesMap = new HashMap<>();
        featuredServicesMap.put("featuredService", buildFeaturedServices(context, item));

        // Pass these maps to RefBoxDTO
        RefBoxDTO refBoxDTO = new RefBoxDTO(
                displayText,
                exportFormatsMap,
                featuredServicesMap,
                title != null ? title : ""
        );
        return ResponseEntity.ok(refBoxDTO);
    }

    /**
     * Build the display text for the RefBox based on the Item Metadata.
     */
    private String buildDisplayText(Context context, Item item) {
        // Check for custom format template
        String formatTemplate = itemService.getMetadataFirstValue(item, "local", "refbox", "format", DEFAULT_LANGUAGE);

        if (formatTemplate != null && !formatTemplate.isEmpty()) {
            return buildDisplayTextFromTemplate(context, item, formatTemplate);
        } else {
            return buildDisplayTextDefault(context, item);
        }
    }

    /**
     * Build display text using a custom format template with variable interpolation.
     * Supported variables: {title}, {authors}, {pid}, {repository}, {year}, {publisher}
     */
    private String buildDisplayTextFromTemplate(Context context, Item item, String template) {
        // Extract all metadata values
        List<String> authors = itemService.getMetadata(item, "dc", "contributor", "author", DEFAULT_LANGUAGE)
                .stream().map(MetadataValue::getValue).collect(Collectors.toList());
        String authorText = formatAuthors(authors);

        String year = "";
        String issued = itemService.getMetadataFirstValue(item, "dc", "date", "issued", DEFAULT_LANGUAGE);
        if (issued != null && !issued.isEmpty()) {
            year = issued.split("-")[0];
        }

        String title = itemService.getMetadataFirstValue(item, "dc", "title", null, DEFAULT_LANGUAGE);
        String publisher = itemService.getMetadataFirstValue(item, "dc", "publisher", null, DEFAULT_LANGUAGE);
        String repository = configurationService.getProperty("dspace.name");

        // Get identifier (prefer DOI, fallback to URI)
        String pid = itemService.getMetadataFirstValue(item, "dc", "identifier", "doi", DEFAULT_LANGUAGE);
        if (pid == null) {
            pid = itemService.getMetadataFirstValue(item, "dc", "identifier", "uri", DEFAULT_LANGUAGE);
        }

        // Perform template interpolation
        String result = template;
        result = result.replace("{title}", title != null ? title : "");
        result = result.replace("{authors}", authorText != null ? authorText : "");
        result = result.replace("{pid}", pid != null ? pid : "");
        result = result.replace("{repository}", repository != null ? repository : "");
        result = result.replace("{year}", year != null ? year : "");
        result = result.replace("{publisher}", publisher != null ? publisher : "");

        return result;
    }

    /**
     * Build display text using the default hardcoded format.
     */
    private String buildDisplayTextDefault(Context context, Item item) {
        // 1. Authors
        List<String> authors = itemService.getMetadata(item, "dc", "contributor", "author", DEFAULT_LANGUAGE)
                .stream().map(MetadataValue::getValue).collect(Collectors.toList());
        String authorText = formatAuthors(authors);

        // 2. Year
        String year = "";
        String issued = itemService.getMetadataFirstValue(item, "dc", "date", "issued", DEFAULT_LANGUAGE);
        if (issued != null && !issued.isEmpty()) {
            // The issued date is in the format YYYY-MM-DD, we take the year part
            year = issued.split("-")[0];
        }

        // 3. Title
        String title = itemService.getMetadataFirstValue(item, "dc", "title", null, DEFAULT_LANGUAGE);

        // 4. Publisher
        String publisher = itemService.getMetadataFirstValue(item, "dc", "publisher", null, DEFAULT_LANGUAGE);

        // 5. Repository name
        String repository = configurationService.getProperty("dspace.name");

        // 6. Identifier URI (prefer DOI)
        String identifier = itemService.getMetadataFirstValue(item, "dc", "identifier", "doi", DEFAULT_LANGUAGE);
        if (identifier == null) {
            identifier = itemService.getMetadataFirstValue(item, "dc", "identifier", "uri", DEFAULT_LANGUAGE);
        }

        // 7. Format as: {authors}, {year}, {title}, {publisher}, {repository}, {identifier}
        // If no authors, use publisher as author fallback for backwards compatibility
        if (authorText == null || authorText.isEmpty()) {
            if (publisher != null && !publisher.isEmpty()) {
                authorText = publisher;
            }
        }

        // Using html tags to format the output because this display text will be rendered in the UI
        StringBuilder sb = new StringBuilder();
        if (authorText != null && !authorText.isEmpty()) {
            sb.append(authorText);
        }
        if (year != null && !year.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(year);
        }
        sb.append(", \n  <i>").append(title != null ? title : "").append("</i>");
        if (publisher != null && !publisher.isEmpty() &&
            (authorText == null || authorText.isEmpty() || !authorText.equals(publisher))) {
            sb.append(", ").append(publisher);
        }
        if (repository != null && !repository.isEmpty()) {
            sb.append(", ").append(repository);
        }
        sb.append(", \n  <a href=\"").append(identifier != null ? identifier : "").append("\">")
                .append(identifier != null ? identifier : "").append("</a>.");
        return sb.toString();
    }

    /**
     * Format the authors for the display text.
     * If there is one author, it will return that author.
     * If there are 2-5 authors, it will join them with "; " and replace the last ";" with " and".
     * If there are more than 5 authors, it will return the first author and "et al.".
     */
    private String formatAuthors(List<String> authors) {
        String authorText = "";
        if (authors.size() == 1) {
            authorText = authors.get(0);
        } else if (authors.size() <= 5) {
            authorText = String.join("; ", authors);
            authorText = authorText.replaceAll("; ([^;]*)$", " and $1");
        } else {
            authorText = authors.get(0) + "; et al.";
        }
        return authorText;
    }

    /**
     * Build the export formats for the RefBox based on the Item handle.
     * It returns a list of ExportFormatDTO objects with the URL to the citation data.
     */
    private List<ExportFormatDTO> buildExportFormats(Item item) {
        List<ExportFormatDTO> exportFormats = new ArrayList<>();
        String itemHandle = item.getHandle();
        if (itemHandle != null) {
            String baseUrl = configurationService.getProperty("dspace.server.url") +
                    "/api/core/refbox/citations?handle=/" + Utils.getCanonicalHandleUrlNoProtocol(item);

            String bibtexUrl = baseUrl + "&type=bibtex";
            String cmdiUrl = baseUrl + "&type=cmdi";

            exportFormats.add(new ExportFormatDTO("bibtex", bibtexUrl, "json", ""));
            exportFormats.add(new ExportFormatDTO("cmdi", cmdiUrl, "json", ""));
        } else {
            log.error("Item with ID {} does not have a handle, export formats cannot be built.", item.getID());
        }
        return exportFormats;
    }

    /**
     * Build the featured services for the RefBox based on the Item Metadata.
     * This method retrieves the metadata values for the featured services,
     * groups them by service name (qualifier),
     * and constructs a list of FeaturedServiceDTO objects
     * with the full name, URL, description, and links.
     */
    private List<FeaturedServiceDTO> buildFeaturedServices(Context context, Item item) {
        List<MetadataValue> fsMeta = itemService.getMetadata(item, "local", "featuredService", "*", DEFAULT_LANGUAGE);
        Map<String, List<FeaturedServiceLinkDTO>> serviceLinksMap = new HashMap<>();

        // Group links by service name (qualifier)
        for (MetadataValue mv : fsMeta) {
            String qualifier = mv.getMetadataField().getQualifier();
            if (qualifier == null) {
                continue;
            }
            String[] parts = mv.getValue().split("\\|");
            if (parts.length == 2) {
                serviceLinksMap
                        .computeIfAbsent(qualifier, k -> new ArrayList<>())
                        .add(new FeaturedServiceLinkDTO(parts[0], parts[1]));
            } else {
                log.error("Invalid metadata value format for featured service: {}. " +
                        "Expected format is '<KEY>|<VALUE>'.", mv.getValue());
            }
        }

        List<FeaturedServiceDTO> featuredServiceList = new ArrayList<>();
        // Iterate over the grouped service links and create FeaturedServiceDTO objects
        for (Map.Entry<String, List<FeaturedServiceLinkDTO>> entry : serviceLinksMap.entrySet()) {
            String name = entry.getKey();
            String fullname = configurationService.getProperty("featured.service." + name + ".fullname");
            String url = configurationService.getProperty("featured.service." + name + ".url");
            String description = configurationService.getProperty("featured.service." + name + ".description");
            Map<String, List<FeaturedServiceLinkDTO>> linksMap = new HashMap<>();
            linksMap.put("entry", entry.getValue());
            featuredServiceList.add(new FeaturedServiceDTO(
                    fullname != null ? fullname : name,
                    url != null ? url : "",
                    description != null ? description : "",
                    linksMap
            ));
        }
        return featuredServiceList;
    }

    private void closeContext(Context context) {
        if (Objects.nonNull(context) && context.isValid()) {
            context.abort();
        }
    }

    private String indexAction(HttpServletResponse response, Model model) {
        return "index";
    }

    private Map<String, List<String>> buildParametersMap(HttpServletRequest request) {
        Map<String, List<String>> map = new HashMap<String, List<String>>();
        Enumeration<String> names = request.getParameterNames();
        while (names.hasMoreElements()) {
            String name = (String) names.nextElement();
            String[] values = request.getParameterValues(name);
            map.put(name, asList(values));
        }
        return map;
    }

    /**
     * Based on the Item Metadata add the Featured Service Link object to the List. If the Item doesn't have
     * the metadata for the appropriate Featured Service these links won't be added to the list.
     */
    private List<ClarinFeaturedServiceLink> mapFeaturedServiceLinks(List<MetadataValue> itemMetadata) {
        List<ClarinFeaturedServiceLink> featuredServiceLinkList = new ArrayList<>();

        // Go through all item metadata and check for the featured service metadata fields.
        for (MetadataValue mv : itemMetadata) {
            if (Objects.isNull(mv)) {
                log.error("The metadata value object is null!");
                continue;
            }

            // The featured service key and value are stored like `<KEY>|<VALUE>`, it must split by `|`
            String metadataValue = mv.getValue();
            if (StringUtils.isBlank(metadataValue)) {
                log.error("The value of the metadata value object is null!");
                continue;
            }

            // Check if the metadata value has the data in the right format.
            List<String> keyAndValue = List.of(metadataValue.split("\\|"));
            if (keyAndValue.size() < 2) {
                log.error("Cannot properly split the key and value from the metadata value!");
                continue;
            }

            // Create object with key and value
            ClarinFeaturedServiceLink clarinFeaturedServiceLink = new ClarinFeaturedServiceLink();
            // The key is always in the `0` position.
            clarinFeaturedServiceLink.setKey(keyAndValue.get(0));
            // The value is always in the `1` position.
            clarinFeaturedServiceLink.setValue(keyAndValue.get(1));

            // Add the created object to the list.
            featuredServiceLinkList.add(clarinFeaturedServiceLink);
        }

        return featuredServiceLinkList;
    }

    /**
     * Remove the unnecessary parts from the output.
     */
    private String updateOutput(String type, String output) {
        try {
            if (StringUtils.equals(type, BIBTEX_TYPE)) {
                // Remove the XML header tag and the <bib:bibtex> tag from the string.
                return getXmlTextContent(output);
            } else {
                // Remove the XML header tag from the string.
                return removeXmlHeaderTag(output);
            }
        } catch (Exception e) {
            log.error("Cannot update the xml string for citation because of: " + e.getMessage());
            return null;
        }
    }

    /**
     * Remove the XML header tag from the string.
     *
     * @param xml
     * @return
     */
    private String removeXmlHeaderTag(String xml) {
        String xmlHeaderPattern = "<\\?xml[^>]*\\?>";
        Pattern xmlHeaderRegex = Pattern.compile(xmlHeaderPattern);
        Matcher xmlHeaderMatcher = xmlHeaderRegex.matcher(xml);
        if (xmlHeaderMatcher.find()) {
            return xml.replaceFirst(xmlHeaderPattern, "");
        } else {
            return xml;
        }
    }

    /**
     * Get the text content from the xml string.
     */
    private String getXmlTextContent(String xml) throws ParserConfigurationException, IOException, SAXException {
        // Parse the XML string
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document document = builder.parse(new org.xml.sax.InputSource(new java.io.StringReader(xml)));

        // Get the root element
        Node root = document.getDocumentElement();

        // Get the text content of the root element
        return root.getTextContent().trim();
    }
}

/**
 * This ClarinOutputStream write the content into the string instead of bytes.
 */
class UTF8ClarinOutputStream extends OutputStream {
    private ByteArrayOutputStream bao = new ByteArrayOutputStream();

    @Override
    public void write(int b) throws IOException {
        bao.write(b);
    }

    @Override
    public String toString() {
        return bao.toString(StandardCharsets.UTF_8);
    }
}

/**
 * For better response parsing wrap the OAI data to the object.
 */
class OaiMetadataWrapper {
    private String value;

    public OaiMetadataWrapper(String value) {
        this.value = value;
    }

    public String getMetadata() {
        return value;
    }

    public void setMetadata(String value) {
        this.value = value;
    }
}
