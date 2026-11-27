/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.authenticate.clarin;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.dspace.authenticate.AuthenticationMethod;
import org.dspace.core.Context;
import org.dspace.eperson.EPerson;
import org.dspace.eperson.Group;
import org.dspace.kernel.ServiceManager;
import org.dspace.services.ConfigurationService;
import org.dspace.utils.DSpace;

/**
 * The CLARIN OIDC authentication plugin, an alternative to the stock
 * {@link org.dspace.authenticate.OidcAuthentication}: a deployment enables one of the two in
 * plugin.sequence.org.dspace.authenticate.AuthenticationMethod, not both (they share the method name "oidc").
 * <p>
 * Delegates all invocations to the "clarinOidcAuthentication" bean ({@link ClarinOidcAuthenticationBean}), like
 * the stock plugin delegates to its bean. The OIDC protocol itself is done by the REST webapp
 * (ClarinOidcLoginConfigurer), which is only installed when this plugin is enabled at startup.
 */
public class ClarinOidcAuthentication implements AuthenticationMethod {

    private static final String PLUGIN_SEQUENCE = "plugin.sequence." + AuthenticationMethod.class.getName();

    private final ServiceManager serviceManager = new DSpace().getServiceManager();

    /**
     * Whether this plugin is enabled in plugin.sequence.org.dspace.authenticate.AuthenticationMethod.
     *
     * @param configurationService the configuration service
     * @return true if the plugin sequence lists this class
     */
    public static boolean isEnabled(ConfigurationService configurationService) {
        return Arrays.stream(configurationService.getArrayProperty(PLUGIN_SEQUENCE))
                .map(StringUtils::trim)
                .anyMatch(ClarinOidcAuthentication.class.getName()::equals);
    }

    @Override
    public boolean canSelfRegister(Context context, HttpServletRequest request, String username) throws SQLException {
        return getBean().canSelfRegister(context, request, username);
    }

    @Override
    public void initEPerson(Context context, HttpServletRequest request, EPerson eperson) throws SQLException {
        getBean().initEPerson(context, request, eperson);
    }

    @Override
    public boolean allowSetPassword(Context context, HttpServletRequest request, String username) throws SQLException {
        return getBean().allowSetPassword(context, request, username);
    }

    @Override
    public boolean isImplicit() {
        return getBean().isImplicit();
    }

    @Override
    public List<Group> getSpecialGroups(Context context, HttpServletRequest request) throws SQLException {
        return getBean().getSpecialGroups(context, request);
    }

    @Override
    public boolean areSpecialGroupsApplicable(Context context, HttpServletRequest request) {
        return getBean().areSpecialGroupsApplicable(context, request);
    }

    @Override
    public int authenticate(Context context, String username, String password, String realm, HttpServletRequest request)
        throws SQLException {
        return getBean().authenticate(context, username, password, realm, request);
    }

    @Override
    public String loginPageURL(Context context, HttpServletRequest request, HttpServletResponse response) {
        return getBean().loginPageURL(context, request, response);
    }

    @Override
    public String getName() {
        return getBean().getName();
    }

    @Override
    public boolean isUsed(Context context, HttpServletRequest request) {
        return getBean().isUsed(context, request);
    }

    @Override
    public boolean canChangePassword(Context context, EPerson ePerson, String currentPassword) {
        return getBean().canChangePassword(context, ePerson, currentPassword);
    }

    private AuthenticationMethod getBean() {
        return serviceManager.getServiceByName("clarinOidcAuthentication", AuthenticationMethod.class);
    }
}
