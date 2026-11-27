/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.content.clarin;

import java.sql.SQLException;
import java.util.Date;
import java.util.List;
import javax.ws.rs.BadRequestException;

import org.apache.commons.lang3.StringUtils;
import org.dspace.authorize.AuthorizeException;
import org.dspace.authorize.service.AuthorizeService;
import org.dspace.content.dao.clarin.AuthenticationIdentityDAO;
import org.dspace.content.service.clarin.AuthenticationIdentityService;
import org.dspace.core.Context;
import org.dspace.eperson.EPerson;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Service implementation for the AuthenticationIdentity object.
 * This class is responsible for all business logic calls for the AuthenticationIdentity object
 * and is autowired by spring. This class should never be accessed directly.
 *
 * @author Milan Kuchtiak
 */
public class AuthenticationIdentityServiceImpl implements AuthenticationIdentityService {

    @Autowired
    AuthenticationIdentityDAO authenticationIdentityDAO;

    @Autowired
    AuthorizeService authorizeService;

    @Override
    public AuthenticationIdentity find(Context context, Integer id) throws SQLException {
        return authenticationIdentityDAO.findByID(context, AuthenticationIdentity.class, id);
    }

    @Override
    public AuthenticationIdentity find(Context context, String authMethod, String provider, String userId)
            throws SQLException {
        return authenticationIdentityDAO.findByAuthMethodAndProviderAndUserId(context, authMethod, provider,
                userId);
    }

    @Override
    public List<AuthenticationIdentity> findByEPerson(Context context, EPerson ePerson) throws SQLException {
        return authenticationIdentityDAO.findByEPerson(context, ePerson);
    }

    @Override
    public List<AuthenticationIdentity> findByEPersonAndAuthMethod(Context context,
                                                                   EPerson ePerson,
                                                                   String authMethod) throws SQLException {
        return authenticationIdentityDAO.findByEPersonAndAuthMethod(context, ePerson, authMethod);
    }

    @Override
    public AuthenticationIdentity create(Context context,
                                         EPerson ePerson,
                                         String authMethod,
                                         String provider,
                                         String userId) throws SQLException, AuthorizeException {
        if (ePerson == null) {
            throw new BadRequestException("EPerson must be defined.");
        }
        if (StringUtils.isAnyBlank(authMethod, provider, userId)) {
            throw new BadRequestException("authMethod, provider and userId must be defined.");
        }
        checkAdmin(context, "You must be admin user to create authentication identity");

        AuthenticationIdentity identity = new AuthenticationIdentity();
        identity.setEPerson(ePerson);
        identity.setAuthMethod(authMethod);
        identity.setProvider(provider);
        identity.setUserId(userId);
        Date now = new Date();
        identity.setCreated(now);
        identity.setLastLogin(now);

        return authenticationIdentityDAO.create(context, identity);
    }

    @Override
    public void updateLastLogin(Context context, AuthenticationIdentity identity)
            throws SQLException, AuthorizeException {
        checkAdminOrOwner(context, identity.getEPerson(),
                "Only admin or the owner can update this authentication identity");

        identity.setLastLogin(new Date());
        authenticationIdentityDAO.save(context, identity);
    }

    @Override
    public void delete(Context context, AuthenticationIdentity identity)
            throws SQLException, AuthorizeException {
        checkAdminOrOwner(context, identity.getEPerson(),
                "Only admin or the owner can delete this authentication identity");

        authenticationIdentityDAO.delete(context, identity);
    }

    @Override
    public void delete(Context context, EPerson ePerson) throws SQLException, AuthorizeException {
        checkAdminOrOwner(context, ePerson,
                "Only admin or the user itself can delete authentication identities of this user");

        authenticationIdentityDAO.deleteByEPerson(context, ePerson);
    }

    private void checkAdmin(Context context, String message) throws SQLException, AuthorizeException {
        if (!context.ignoreAuthorization() && !authorizeService.isAdmin(context)) {
            throw new AuthorizeException(message);
        }
    }

    private void checkAdminOrOwner(Context context, EPerson ePerson, String message)
            throws SQLException, AuthorizeException {
        if (context.ignoreAuthorization()) {
            return;
        }
        if (context.getCurrentUser() == null) {
            throw new AuthorizeException("You must be authenticated user");
        }
        if (!authorizeService.isAdmin(context) && !context.getCurrentUser().equals(ePerson)) {
            throw new AuthorizeException(message);
        }
    }
}
