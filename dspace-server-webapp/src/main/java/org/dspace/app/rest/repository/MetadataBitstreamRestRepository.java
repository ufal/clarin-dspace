/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.repository;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;
import org.dspace.app.rest.Parameter;
import org.dspace.app.rest.SearchRestMethod;
import org.dspace.app.rest.converter.MetadataBitstreamWrapperConverter;
import org.dspace.app.rest.exception.DSpaceBadRequestException;
import org.dspace.app.rest.exception.UnprocessableEntityException;
import org.dspace.app.rest.model.MetadataBitstreamWrapperRest;
import org.dspace.app.rest.model.wrapper.MetadataBitstreamWrapper;
import org.dspace.content.Bitstream;
import org.dspace.content.Bundle;
import org.dspace.content.DSpaceObject;
import org.dspace.content.Item;
import org.dspace.content.PreviewContent;
import org.dspace.content.Thumbnail;
import org.dspace.content.service.ItemService;
import org.dspace.content.service.PreviewContentService;
import org.dspace.core.Context;
import org.dspace.handle.service.HandleService;
import org.dspace.services.ConfigurationService;
import org.dspace.util.FileInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

/**
 * This controller returns content of the bitstream to the `Preview` box in the Item View.
 */
@Component(MetadataBitstreamWrapperRest.CATEGORY + "." + MetadataBitstreamWrapperRest.NAME)
public class MetadataBitstreamRestRepository extends DSpaceRestRepository<MetadataBitstreamWrapperRest, Integer> {
    private static Logger log = org.apache.logging.log4j.LogManager.getLogger(MetadataBitstreamRestRepository.class);
    private static String TEXT_HTML_MIME_TYPE = "text/html";

    @Autowired
    HandleService handleService;

    @Autowired
    MetadataBitstreamWrapperConverter metadataBitstreamWrapperConverter;

    @Autowired
    ItemService itemService;

    @Autowired
    PreviewContentService previewContentService;

    @Autowired
    ConfigurationService configurationService;

    @SearchRestMethod(name = "byHandle")
    public Page<MetadataBitstreamWrapperRest> findByHandle(@Parameter(value = "handle", required = true) String handle,
                                                           @Parameter(value = "fileGrpType") String fileGrpType,
                                                           Pageable pageable)
            throws Exception {
        if (StringUtils.isBlank(handle)) {
            throw new DSpaceBadRequestException("handle cannot be null!");
        }
        Context context = obtainContext();
        if (Objects.isNull(context)) {
            throw new RuntimeException("Cannot obtain the context from the request.");
        }
        HttpServletRequest request = getRequestService().getCurrentRequest().getHttpServletRequest();
        String contextPath = request.getContextPath();
        List<MetadataBitstreamWrapperRest> rs = new ArrayList<>();
        DSpaceObject dso;

        try {
            dso = handleService.resolveToObject(context, handle);
        } catch (Exception e) {
            throw new RuntimeException("Cannot resolve handle: " + handle);
        }

        if (!(dso instanceof Item)) {
            throw new UnprocessableEntityException("Cannot fetch bitstreams from different object than Item.");
        }

        Item item = (Item) dso;
        List<String> fileGrpTypes = (fileGrpType == null ? List.of() : Arrays.asList(fileGrpType.split(",")));
        List<Bundle> bundles = findEnabledBundles(fileGrpTypes, item);
        for (Bundle bundle : bundles) {
            List<Bitstream> bitstreams = new ArrayList<>(bundle.getBitstreams());
            String use = bundle.getName();
            if (StringUtils.equals("THUMBNAIL", use)) {
                Thumbnail thumbnail = itemService.getThumbnail(context, item, false);
                if (Objects.nonNull(thumbnail)) {
                    bitstreams.clear();
                    bitstreams.add(thumbnail.getThumb());
                }
            }

            for (Bitstream bitstream : bitstreams) {
                String url = previewContentService.composePreviewURL(context, item, bitstream, contextPath);
                List<FileInfo> fileInfos = new ArrayList<>();
                boolean canPreview = previewContentService.canPreview(context, bitstream, false);
                String mimeType = bitstream.getFormat(context).getMIMEType();
                // HTML content could be longer than the limit, so we do not store it in the DB.
                // It has to be generated even if property is false.
                if (StringUtils.equals(mimeType, TEXT_HTML_MIME_TYPE) || canPreview) {
                    try {
                        // Generate new content if we didn't find any
                        if (!previewContentService.hasPreview(context, bitstream)) {
                            boolean allowComposePreviewContent = configurationService.getBooleanProperty
                                    ("create.file-preview.on-item-page-load", false);
                            if (allowComposePreviewContent) {
                                fileInfos.addAll(previewContentService.getFilePreviewContent(context, bitstream));
                                // Do not store HTML content in the database because it could be longer than the limit
                                // of the database column
                                if (!fileInfos.isEmpty() &&
                                    !StringUtils.equals(TEXT_HTML_MIME_TYPE,
                                            bitstream.getFormat(context).getMIMEType())) {
                                    for (FileInfo fi : fileInfos) {
                                        previewContentService.createPreviewContent(context, bitstream, fi);
                                    }
                                }
                            }
                        } else {
                            List<PreviewContent> prContents = previewContentService.getPreview(context, bitstream);
                            for (PreviewContent pc : prContents) {
                                fileInfos.add(previewContentService.createFileInfo(pc));
                            }
                        }
                    } catch (Exception e) {
                        log.error("Cannot create preview content for bitstream: {} because: {}",
                                bitstream.getID(), e.getMessage());
                    }
                }
                MetadataBitstreamWrapper bts = new MetadataBitstreamWrapper(bitstream, fileInfos,
                        bitstream.getFormat(context).getMIMEType(),
                        bitstream.getFormatDescription(context), url, canPreview);
                rs.add(metadataBitstreamWrapperConverter.convert(bts, utils.obtainProjection()));
            }
        }

        return new PageImpl<>(rs, pageable, rs.size());
    }

    /**
     * Check if the user is requested a specific bundle or all bundles.
     */
    protected List<Bundle> findEnabledBundles(List<String> fileGrpTypes, Item item) {
        List<Bundle> bundles;
        if (fileGrpTypes.size() == 0) {
            bundles = item.getBundles();
        } else {
            bundles = new ArrayList<Bundle>();
            for (String fileGrpType : fileGrpTypes) {
                for (Bundle newBundle : item.getBundles(fileGrpType)) {
                    bundles.add(newBundle);
                }
            }
        }

        return bundles;
    }

    @Override
    public MetadataBitstreamWrapperRest findOne(Context context, Integer integer) {
        return null;
    }

    @Override
    public Page<MetadataBitstreamWrapperRest> findAll(Context context, Pageable pageable) {
        return null;
    }

    @Override
    public Class<MetadataBitstreamWrapperRest> getDomainClass() {
        return MetadataBitstreamWrapperRest.class;
    }
}
