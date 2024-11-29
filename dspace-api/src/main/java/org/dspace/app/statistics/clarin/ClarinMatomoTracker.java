/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.statistics.clarin;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Calendar;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;
import org.dspace.content.Item;
import org.dspace.content.factory.ClarinServiceFactory;
import org.dspace.core.Context;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.matomo.java.tracking.MatomoException;
import org.matomo.java.tracking.MatomoRequest;
import org.matomo.java.tracking.parameters.AcceptLanguage;

/**
 * The statistics Tracker for Matomo. This class prepare and send the track GET request to the `/matomo.php`
 *
 * The class is copied from UFAL/CLARIN-DSPACE (https://github.com/ufal/clarin-dspace) and modified by
 * @author Milan Majchrak (milan.majchrak at dataquest.sk)
 */
public class ClarinMatomoTracker {
    ClarinMatomoTracker() {
    }

    /** log4j category */
    private static Logger log = org.apache.logging.log4j.LogManager.getLogger(ClarinMatomoTracker.class);

    private final ConfigurationService configurationService =
            DSpaceServicesFactory.getInstance().getConfigurationService();

    private org.matomo.java.tracking.MatomoTracker tracker = ClarinServiceFactory.getInstance().getMatomoTracker();

    /**
     * Create, prepare and send the track request
     *
     * @param context DSpace context object  - can be null
     * @param request current request
     * @param item downloading item - can be null
     * @param pageName - action name
     */
    public void trackPage(Context context, HttpServletRequest request, Item item, String pageName) {
        log.debug("Matomo tracks " + pageName);
        // `&bots=1` because we want to track downloading by bots
        String pageURL = getFullURL(request) + "&bots=1";

        MatomoRequest matomoRequest = createMatomoRequest(request, pageName, pageURL);
        if (Objects.isNull(matomoRequest)) {
            return;
        }

        // Add some headers and parameters to the request
        preTrack(context, matomoRequest, item, request);
        sendTrackingRequest(matomoRequest);
    }

    /**
     * Create the Matomo Request for the Matomo endpoint. This object is send in the tracking request.
     *
     * @param request currrent request
     * @param pageName action name
     * @param pageURL item handle or OAI harvesting current page URL
     * @return MatomoRequest object or null
     */
    protected MatomoRequest createMatomoRequest(HttpServletRequest request, String pageName, String pageURL) {
        MatomoRequest matomoRequest = null;
        try {
            matomoRequest = MatomoRequest.builder()
                    .siteId(1)
                    .actionUrl(pageURL) // include the query parameters to the url
                    .actionName(pageName)
                    .authToken(configurationService.getProperty("matomo.auth.token"))
                    .visitorIp(getIpAddress(request))
                    .build();
        } catch (MatomoException e) {
            log.error("Cannot create Matomo Request because: " + e.getMessage());
        }
        return matomoRequest;
    }

    /**
     * Prepare the Matomo Request for sending - add the request parameters to the Matomo object
     *
     * @param context DSpace context object
     * @param matomoRequest Matomo request object where will be added request parameters
     * @param item from where the bitstream is downloading or null
     * @param request current request
     */
    protected void preTrack(Context context, MatomoRequest matomoRequest, Item item, HttpServletRequest request) {
        if (StringUtils.isNotBlank(request.getHeader("referer"))) {
            matomoRequest.setReferrerUrl(request.getHeader("referer"));
        }
        if (StringUtils.isNotBlank(request.getHeader("user-agent"))) {
            matomoRequest.setHeaderUserAgent(request.getHeader("user-agent"));
        }
        if (StringUtils.isNotBlank(request.getHeader("accept-language"))) {
            matomoRequest.setHeaderAcceptLanguage(AcceptLanguage.fromHeader(request.getHeader("accept-language")));
        }

        // Creating a calendar using getInstance method
        Calendar now = Calendar.getInstance();

        // Add request parameters to the MatomoRequest object
        matomoRequest.setCurrentHour(now.get(Calendar.HOUR_OF_DAY));
        matomoRequest.setCurrentMinute(now.get(Calendar.MINUTE));
        matomoRequest.setCurrentSecond(now.get(Calendar.SECOND));
        matomoRequest.setReferrerUrl(configurationService.getProperty("dspace.ui.url"));
        matomoRequest.setPluginPDF(true);
        matomoRequest.setPluginQuicktime(false);
        matomoRequest.setPluginRealPlayer(false);
        matomoRequest.setPluginWindowsMedia(false);
        matomoRequest.setPluginDirector(false);
        matomoRequest.setPluginFlash(false);
        matomoRequest.setPluginJava(false);
        matomoRequest.setPluginGears(false);
        matomoRequest.setPluginSilverlight(false);
        matomoRequest.setParameter("cookie", 1);
        matomoRequest.setDeviceResolution("1920x1080");
    }

    /**
     * Send the Track request and process the response
     * @param matomoRequest prepared MatomoRequest for sending
     */
    public void sendTrackingRequest(MatomoRequest matomoRequest) {
        CompletableFuture<MatomoRequest> completableFuture = tracker.sendRequestAsync(matomoRequest);

        completableFuture.whenComplete((result, exception) -> {
            if (exception != null) {
                log.error("Matomo tracker error - the response exception message: {}", exception.getMessage());
            }
        });
    }

    protected String getFullURL(HttpServletRequest request) {
        StringBuilder url = new StringBuilder();
        url.append(request.getScheme());
        url.append("://");
        url.append(request.getServerName());
        url.append("http".equals(request.getScheme())
                && request.getServerPort() == 80
                || "https".equals(request.getScheme())
                && request.getServerPort() == 443 ? "" : ":" + request.getServerPort());
        url.append(request.getRequestURI());
        url.append(request.getQueryString() != null ? "?"
                + request.getQueryString() : "");
        return url.toString();
    }

    /**
     * Get IpAddress of the current user which throws this statistic event. Return only the first valid IPv4 address
     * because the Matomo tracker has a problem with IPv6 addresses.
     *
     * @param request current request
     * @return only the first valid IPv4 address
     */
    protected String getIpAddress(HttpServletRequest request) {
        String header = request.getHeader("X-Forwarded-For");
        if (header == null) {
            header = request.getRemoteAddr();
        }
        if (header != null) {
            String[] ips = header.split(", ");
            for (String candidateIp : ips) {
                // Validate if it's an IPv4 address
                if (isIPv4Address(candidateIp)) {
                    return candidateIp;
                }
            }
        }
        return null;
    }

    private boolean isIPv4Address(String ip) {
        try {
            InetAddress inetAddress = InetAddress.getByName(ip);
            return inetAddress.getHostAddress().equals(ip) && inetAddress instanceof java.net.Inet4Address;
        } catch (UnknownHostException e) {
            return false; // Not a valid IP address
        }
    }
}
