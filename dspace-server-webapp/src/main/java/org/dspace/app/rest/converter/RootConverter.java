/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.converter;

import static org.dspace.app.util.Util.getSourceVersion;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.StringUtils;
import org.dspace.app.rest.model.RootRest;
import org.dspace.services.ConfigurationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * This class read the core configuration properties and constructs a RootRest instance to return
 */
@Component
public class RootConverter {

    @Autowired
    private ConfigurationService configurationService;

    public RootRest convert(HttpServletRequest request) {
        RootRest rootRest = new RootRest();
        rootRest.setDspaceName(configurationService.getProperty("dspace.name"));
        rootRest.setDspaceUI(configurationService.getProperty("dspace.ui.url"));
        String requestUrl = request.getRequestURL().toString();
        String dspaceUrl = configurationService.getProperty("dspace.server.url");
        String dspaceSSRUrl = configurationService.getProperty("dspace.server.ssr.url", dspaceUrl);
        if (!dspaceUrl.equals(dspaceSSRUrl) && StringUtils.isNotBlank(dspaceSSRUrl)
            && requestUrl.startsWith(dspaceSSRUrl)) {
            rootRest.setDspaceServer(dspaceSSRUrl);
        } else {
            rootRest.setDspaceServer(dspaceUrl);
        }
        String versionPrefix = StringUtils.defaultIfBlank(
                configurationService.getProperty("dspace.version.prefix", "CLARIN-DSpace"), "CLARIN-DSpace");
        rootRest.setDspaceVersion(versionPrefix + " " + getSourceVersion());
        rootRest.setBuildVersion(getBuildVersion());
        return rootRest;
    }



    private String getBuildVersion() {
        String bVersionFilePath = configurationService.getProperty("build.version.file.path");
        if (StringUtils.isBlank(bVersionFilePath)) {
            return "Unknown";
        }
        StringBuilder buildVersion = new StringBuilder();
        try (BufferedReader bufferedReader = new BufferedReader(new FileReader(bVersionFilePath))) {
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                buildVersion.append(line);
            }
        } catch (IOException e) {
            // Empty - do not log anything
        }
        return buildVersion.toString();
    }
}
