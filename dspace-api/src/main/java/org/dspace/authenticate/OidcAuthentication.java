/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.authenticate;

import java.sql.SQLException;
import java.util.List;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.dspace.core.Context;
import org.dspace.eperson.EPerson;
import org.dspace.eperson.Group;
import org.dspace.kernel.ServiceManager;
import org.dspace.services.ConfigurationService;
import org.dspace.utils.DSpace;

/**
 * Implementation of {@link AuthenticationMethod} that delegate all the method
 * invocations to the bean of class {@link OrcidAuthenticationBean}.
 * <p>
 * By default, this delegates to the "oidcAuthentication" bean (plain {@link OidcAuthenticationBean}). When the
 * "authentication-oidc.type" property is set to "clarin", it delegates to the "clarinOidcAuthentication" bean
 * ({@link org.dspace.authenticate.clarin.ClarinOidcAuthenticationBean}), which identifies users by a stable
 * provider user ID instead of email, supports linking by email, OIDC group/role mapping, and a default
 * authentication group.
 *
 * @author Luca Giamminonni (luca.giamminonni at 4science.it)
 *
 */
public class OidcAuthentication implements AuthenticationMethod {

    private static final String CLARIN_TYPE = "clarin";

    private final ServiceManager serviceManager = new DSpace().getServiceManager();

    private final ConfigurationService configurationService = new DSpace().getConfigurationService();

    private static final String OIDC_AUTHENTICATED = "oidc.authenticated";

    @Override
    public boolean canSelfRegister(Context context, HttpServletRequest request, String username) throws SQLException {
        return getOidcAuthentication().canSelfRegister(context, request, username);
    }

    @Override
    public void initEPerson(Context context, HttpServletRequest request, EPerson eperson) throws SQLException {
        getOidcAuthentication().initEPerson(context, request, eperson);
    }

    @Override
    public boolean allowSetPassword(Context context, HttpServletRequest request, String username) throws SQLException {
        return getOidcAuthentication().allowSetPassword(context, request, username);
    }

    @Override
    public boolean isImplicit() {
        return getOidcAuthentication().isImplicit();
    }

    @Override
    public List<Group> getSpecialGroups(Context context, HttpServletRequest request) throws SQLException {
        return getOidcAuthentication().getSpecialGroups(context, request);
    }

    @Override
    public boolean areSpecialGroupsApplicable(Context context, HttpServletRequest request) {
        return getOidcAuthentication().areSpecialGroupsApplicable(context, request);
    }

    @Override
    public int authenticate(Context context, String username, String password, String realm, HttpServletRequest request)
        throws SQLException {
        return getOidcAuthentication().authenticate(context, username, password, realm, request);
    }

    @Override
    public String loginPageURL(Context context, HttpServletRequest request, HttpServletResponse response) {
        return getOidcAuthentication().loginPageURL(context, request, response);
    }

    @Override
    public String getName() {
        return getOidcAuthentication().getName();
    }

    private OidcAuthenticationBean getOidcAuthentication() {
        String beanName = CLARIN_TYPE.equals(configurationService.getProperty("authentication-oidc.type"))
                ? "clarinOidcAuthentication" : "oidcAuthentication";
        return serviceManager.getServiceByName(beanName, OidcAuthenticationBean.class);
    }

    @Override
    public boolean isUsed(final Context context, final HttpServletRequest request) {
        if (request != null &&
                context.getCurrentUser() != null &&
                request.getAttribute(OIDC_AUTHENTICATED) != null) {
            return true;
        }
        return false;
    }

    @Override
    public boolean canChangePassword(Context context, EPerson ePerson, String currentPassword) {
        return false;
    }

}
