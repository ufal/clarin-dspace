/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.security;

import java.sql.SQLException;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.dspace.app.requestitem.RequestItem;
import org.dspace.app.requestitem.service.RequestItemService;
import org.dspace.app.rest.utils.ContextUtil;
import org.dspace.authorize.AuthorizationBitstreamUtils;
import org.dspace.authorize.AuthorizeException;
import org.dspace.content.Bitstream;
import org.dspace.content.service.BitstreamService;
import org.dspace.core.Context;
import org.dspace.services.ConfigurationService;
import org.dspace.services.RequestService;
import org.dspace.services.model.Request;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Decides whether a request-a-copy access token may authorize a bitstream download.
 * <p>
 * The token opens only bitstreams of the item of its request, and the CLARIN licence still applies, the same
 * check a download without a token goes through.
 *
 * @author Milan Majchrak (milan.majchrak at dataquest.sk)
 */
@Component(value = "clarinBitstreamAccessTokenSecurity")
public class ClarinBitstreamAccessTokenSecurityBean {

    private static final Logger log = LogManager.getLogger(ClarinBitstreamAccessTokenSecurityBean.class);

    @Autowired
    private BitstreamService bitstreamService;
    @Autowired
    private RequestItemService requestItemService;
    @Autowired
    private AuthorizationBitstreamUtils authorizationBitstreamUtils;
    @Autowired
    private ConfigurationService configurationService;
    @Autowired
    private RequestService requestService;

    /**
     * Used by {@code @PreAuthorize}: true when request-a-copy is enabled and the token authorizes the
     * bitstream, see {@link #authorizeAccessToken}.
     *
     * @param uuid bitstream ID from the request path
     * @param accessToken request-a-copy access token from the request, may be null
     * @return true only if the token authorizes downloading the bitstream
     */
    public boolean canDownloadWithAccessToken(UUID uuid, String accessToken) {
        if (uuid == null || StringUtils.isBlank(accessToken)) {
            return false;
        }

        // If request-a-copy is switched off, no access token authorizes anything.
        if (configurationService.getProperty("request.item.type") == null) {
            return false;
        }

        Request currentRequest = requestService.getCurrentRequest();
        if (currentRequest == null || currentRequest.getHttpServletRequest() == null) {
            return false;
        }
        Context context = ContextUtil.obtainContext(currentRequest.getHttpServletRequest());
        if (context == null) {
            return false;
        }

        try {
            Bitstream bitstream = bitstreamService.find(context, uuid);
            if (bitstream == null || bitstream.isDeleted()) {
                // Let the REST layer answer 404; a token must not turn a missing bitstream into a 200.
                return false;
            }
            authorizeAccessToken(context, bitstream, accessToken);
            return true;
        } catch (AuthorizeException e) {
            log.debug("Access token did not authorize download of bitstream {}: {}", uuid, e.getMessage());
            return false;
        } catch (SQLException e) {
            log.error("Failed to check the access token for bitstream " + uuid, e);
            return false;
        }
    }

    /**
     * Checks that the token authorizes the bitstream: the request is accepted, the token matches and has not
     * expired, the request covers this bitstream, the bitstream belongs to the item of the request, and the
     * CLARIN licence lets the current user have the bitstream.
     *
     * @param context DSpace context
     * @param bitstream bitstream to download
     * @param accessToken request-a-copy access token
     * @throws AuthorizeException if the token does not authorize this bitstream or the licence is not agreed
     * @throws SQLException if the database cannot be read
     */
    public void authorizeAccessToken(Context context, Bitstream bitstream, String accessToken)
            throws AuthorizeException, SQLException {
        authorizeAccessTokenRequest(context, bitstream, accessToken);

        // The same CLARIN licence check as a download without a token. Throws MissingLicenseAgreementException
        // or DownloadTokenExpiredException when the licence is not satisfied.
        authorizationBitstreamUtils.authorizeBitstream(context, bitstream);
    }

    /**
     * The request-a-copy part of {@link #authorizeAccessToken}, without the CLARIN licence: the request is
     * accepted, the token matches and has not expired, the request covers this bitstream, and the bitstream
     * belongs to the item of the request.
     *
     * @param context DSpace context
     * @param bitstream bitstream the token is used for
     * @param accessToken request-a-copy access token
     * @throws AuthorizeException if the token is not valid for this bitstream
     * @throws SQLException if the database cannot be read
     */
    public void authorizeAccessTokenRequest(Context context, Bitstream bitstream, String accessToken)
            throws AuthorizeException, SQLException {
        RequestItem requestItem = requestItemService.findByAccessToken(context, accessToken);
        requestItemService.authorizeAccessByAccessToken(context, requestItem, bitstream, accessToken);

        boolean inRequestedItem = bitstream.getBundles().stream()
                .anyMatch(bundle -> bundle.getItems().contains(requestItem.getItem()));
        if (!inRequestedItem) {
            throw new AuthorizeException("The access token belongs to another item than bitstream "
                    + bitstream.getID());
        }
    }
}
