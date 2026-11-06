/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.handle.external;

import static org.apache.commons.lang.StringUtils.isBlank;
import static org.apache.commons.lang.StringUtils.isNotBlank;
import static org.dspace.handle.external.ExternalHandleConstants.MAGIC_BEAN;

import java.util.Objects;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.dspace.handle.HandlePlugin;

/**
 * The external Handle which contains the url with the `@magicLindat` string. That string is parsed to the
 * attributes.
 * Created by
 * @author okosarko on 13.10.15.
 * Modified by
 * @author Milan Majchrak (milan.majchrak at dataquest.sk)
 */
public class Handle {

    private static final Logger log = LogManager.getLogger(Handle.class);

    private static final int MAGIC_URL_PARTS = 10;
    private static final int TOKEN_INDEX = 8;

    private String handle;
    public String url;
    public String title;
    public String repository;
    public String submitdate;
    public String reportemail;
    public String subprefix;
    public String datasetName;
    public String datasetVersion;
    public String query;
    private String token;

    public Handle() {

    }

    public Handle(String handle, String url, String title, String repository, String submitdate, String reportemail,
                  String datasetName, String datasetVersion, String query, String token, String subprefix) {
        this.handle = handle;
        this.url = url;
        this.title = title;
        this.repository = repository;
        this.submitdate = submitdate;
        this.reportemail = reportemail;
        this.datasetName = datasetName;
        this.datasetVersion = datasetVersion;
        this.query = query;
        this.token = token;
        this.subprefix = subprefix;
    }

    /**
     * Constructor which parse the magicURL to the attributes
     * @param handle
     * @param magicURL
     */
    public Handle(String handle, String magicURL) {
        this.handle = handle;
        //similar to HandlePlugin
        String[] splits = magicURL.split(MAGIC_BEAN, MAGIC_URL_PARTS);
        this.url = splits[splits.length - 1];
        this.title = splits[1];
        this.repository = splits[2];
        this.submitdate = splits[3];
        this.reportemail = splits[4];
        if (isNotBlank(splits[5])) {
            this.datasetName = splits[5];
        }
        if (isNotBlank(splits[6])) {
            this.datasetVersion = splits[6];
        }
        if (isNotBlank(splits[7])) {
            this.query = splits[7];
        }
        if (isNotBlank(splits[TOKEN_INDEX])) {
            this.token = splits[TOKEN_INDEX];
        }
        this.subprefix = handle.split("/",2)[1].split("-",2)[0];
    }

    /**
     * Return the url with an empty token, so a magic URL can be shown without the secret that allows updating it.
     * @param url handle url, magic or not
     * @return the url with the token part left empty, or the url as it is when it is not a magic URL
     */
    public static String hideToken(String url) {
        String[] splits = splitMagicUrl(url);
        if (Objects.isNull(splits)) {
            return url;
        }
        splits[TOKEN_INDEX] = "";
        return String.join(MAGIC_BEAN, splits);
    }

    /**
     * Put the stored token back into a magic URL whose token is empty, e.g. one loaded with a hidden token.
     * @param url new handle url
     * @param storedUrl handle url currently stored in the database
     * @return the url with the stored token, or the url as it is when it has its own token or is not a magic URL
     */
    public static String restoreToken(String url, String storedUrl) {
        String[] splits = splitMagicUrl(url);
        String[] storedSplits = splitMagicUrl(storedUrl);
        if (Objects.isNull(splits) || Objects.isNull(storedSplits) || isNotBlank(splits[TOKEN_INDEX])) {
            return url;
        }
        splits[TOKEN_INDEX] = storedSplits[TOKEN_INDEX];
        return String.join(MAGIC_BEAN, splits);
    }

    private static String[] splitMagicUrl(String url) {
        if (isBlank(url) || !url.contains(MAGIC_BEAN)) {
            return null;
        }
        String[] splits = url.split(MAGIC_BEAN, MAGIC_URL_PARTS);
        if (splits.length != MAGIC_URL_PARTS) {
            // the url is not logged, it may contain the token
            log.warn("Magic URL has {} parts instead of {}, it is used unchanged.", splits.length, MAGIC_URL_PARTS);
            return null;
        }
        return splits;
    }

    /**
     * Generate new token and combine the properties into the url with `@magicLindat` string
     * @return url with the `@magicLindat` string
     */
    public String generateMagicUrl() {
        return generateMagicUrl(this.title, this.submitdate, this.reportemail, this.datasetName, this.datasetVersion,
                this.query, this.url);
    }

    /**
     * Generate new token and combine the params into the url with `@magicLindat` string
     * @return url with the `@magicLindat` string
     */
    private static String generateMagicUrl(String title, String submitdate, String reportemail, String datasetName,
                                          String datasetVersion, String query, String url) {
        String magicURL = "";
        String token = UUID.randomUUID().toString();
        String[] magicURLProps = new String[] {title, HandlePlugin.getRepositoryName(), submitdate, reportemail,
            datasetName, datasetVersion, query, token, url};
        for (String part : magicURLProps) {
            if (isBlank(part)) {
                //optional dataset etc...
                part = "";
            }
            magicURL += MAGIC_BEAN + part;
        }
        return magicURL;
    }

    /**
     * It the `handle` attribute is null return the CanonicalHandlePrefix
     * @return `handle` attribute value or the CanonicalHandlePrefix loaded from the configuration
     */
    public String getHandle() {
        return Objects.isNull(handle) ? null : HandlePlugin.getCanonicalHandlePrefix() + handle;
    }

    /**
     * Remove the CanonicalHandlePrefix from the `handle` attribute
     * @param handle
     */
    public void setHandle(String handle) {
        this.handle = handle.replace(HandlePlugin.getCanonicalHandlePrefix(),"");
    }

    /**
     * The token is needed to update the handle.
     * @return the update token, or null when there is none
     */
    public String getToken() {
        return token;
    }

    /**
     * Set the token, e.g. from the update request.
     * @param token
     */
    public void setToken(String token) {
        this.token = token;
    }
}
