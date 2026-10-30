/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.model.refbox;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * DTO for the reference box in DSpace.
 * This class represents a reference box containing export formats and featured services.
 * It includes display text, export formats, featured services, and a title.
 * @author Milan Majchrak (dspace at dataquest.sk)
 */
public class RefBoxDTO implements Serializable {
    private String displayText;
    private Map<String, List<ExportFormatDTO>> exportFormats;
    private Map<String, List<FeaturedServiceDTO>> featuredServices;
    private String title;

    public RefBoxDTO(String displayText,
                     Map<String, List<ExportFormatDTO>> exportFormats,
                     Map<String, List<FeaturedServiceDTO>> featuredServices,
                     String title) {
        this.displayText = displayText;
        this.exportFormats = exportFormats;
        this.featuredServices = featuredServices;
        this.title = title;
    }

    public String getDisplayText() {
        return displayText;
    }

    public void setDisplayText(String displayText) {
        this.displayText = displayText;
    }

    public Map<String, List<ExportFormatDTO>> getExportFormats() {
        return exportFormats;
    }

    public void setExportFormats(Map<String, List<ExportFormatDTO>> exportFormats) {
        this.exportFormats = exportFormats;
    }

    public Map<String, List<FeaturedServiceDTO>> getFeaturedServices() {
        return featuredServices;
    }

    public void setFeaturedServices(Map<String, List<FeaturedServiceDTO>> featuredServices) {
        this.featuredServices = featuredServices;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }
}
