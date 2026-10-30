/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.authenticate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.ArrayList;

import org.dspace.AbstractUnitTest;
import org.dspace.core.Context;
import org.dspace.eperson.Group;
import org.dspace.eperson.service.GroupService;
import org.dspace.services.ConfigurationService;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for the {@code authentication-ldap.login.groupmap.N} parsing in
 * {@link LDAPAuthentication#assignGroups(String, ArrayList, Context)}. No LDAP server is
 * involved: the configuration and the group service are mocked and only the mapping logic runs.
 */
public class LDAPAuthenticationTest extends AbstractUnitTest {

    private static final String GROUPMAP = "authentication-ldap.login.groupmap.";
    private static final String DN = "uid=jdoe,ou=staff,dc=example,dc=org";

    private LDAPAuthentication ldapAuthentication;
    private ConfigurationService configurationService;
    private GroupService groupService;

    @Before
    public void setUp() {
        ldapAuthentication = new LDAPAuthentication();
        configurationService = mock(ConfigurationService.class);
        groupService = mock(GroupService.class);
        ldapAuthentication.configurationService = configurationService;
        ldapAuthentication.groupService = groupService;
    }

    /**
     * assignGroups is private, and the branch under test is the one taken when the caller has no
     * list of LDAP groups, so the second argument has to be a real null.
     */
    private void assignGroups() throws Exception {
        Method method = LDAPAuthentication.class
                .getDeclaredMethod("assignGroups", String.class, ArrayList.class, Context.class);
        method.setAccessible(true);
        method.invoke(ldapAuthentication, DN, null, context);
    }

    @Test
    public void assignGroupsSkipsEntryWithoutSeparator() throws Exception {
        when(configurationService.getProperty(GROUPMAP + 1)).thenReturn("ou=staff");

        assignGroups();

        verify(groupService, never()).findByName(any(), any());
    }

    @Test
    public void assignGroupsSkipsEntryWithBlankSearchPart() throws Exception {
        when(configurationService.getProperty(GROUPMAP + 1)).thenReturn(":Admins");

        assignGroups();

        verify(groupService, never()).findByName(any(), any());
    }

    @Test
    public void assignGroupsSkipsEntryWithBlankGroupPart() throws Exception {
        when(configurationService.getProperty(GROUPMAP + 1)).thenReturn("ou=staff:");

        assignGroups();

        verify(groupService, never()).findByName(any(), any());
    }

    @Test
    public void assignGroupsKeepsScanningAfterAMalformedEntry() throws Exception {
        when(configurationService.getProperty(GROUPMAP + 1)).thenReturn(":Admins");
        when(configurationService.getProperty(GROUPMAP + 2)).thenReturn("ou=staff:Staff");
        when(groupService.findByName(context, "Staff")).thenReturn(mock(Group.class));

        assignGroups();

        verify(groupService).findByName(context, "Staff");
    }

    @Test
    public void assignGroupsKeepsAColonInsideTheDSpaceGroupName() throws Exception {
        when(configurationService.getProperty(GROUPMAP + 1)).thenReturn("ou=staff:Staff:Local");
        when(groupService.findByName(context, "Staff:Local")).thenReturn(mock(Group.class));

        assignGroups();

        verify(groupService).findByName(context, "Staff:Local");
    }
}
