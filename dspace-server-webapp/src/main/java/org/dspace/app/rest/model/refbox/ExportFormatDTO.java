/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.model.refbox;

import java.io.Serializable;

/**
 * DTO for export formats in the reference box.
 * This class represents the export format details including its name, URL, data type, and extraction.
 * @author Milan Majchrak (dspace at dataquest.sk)
 */
public class ExportFormatDTO implements Serializable {
    private String name;
    private String url;
    private String dataType;
    private String extract;

    public ExportFormatDTO(String name, String url, String dataType, String extract) {
        this.name = name;
        this.url = url;
        this.dataType = dataType;
        this.extract = extract;
    }

    public String getName() {
        return name;
    }
    public void setName(String name) {
        this.name = name;
    }

    public String getUrl() {
        return url;
    }
    public void setUrl(String url) {
        this.url = url;
    }

    public String getDataType() {
        return dataType;
    }
    public void setDataType(String dataType) {
        this.dataType = dataType;
    }

    public String getExtract() {
        return extract;
    }
    public void setExtract(String extract) {
        this.extract = extract;
    }
}
