/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.process;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Date;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.dspace.AbstractIntegrationTestWithDatabase;
import org.dspace.authorize.factory.AuthorizeServiceFactory;
import org.dspace.builder.GroupBuilder;
import org.dspace.builder.ProcessBuilder;
import org.dspace.content.Bitstream;
import org.dspace.content.ProcessStatus;
import org.dspace.core.Constants;
import org.dspace.eperson.Group;
import org.dspace.eperson.factory.EPersonServiceFactory;
import org.dspace.eperson.service.GroupService;
import org.dspace.scripts.Process;
import org.dspace.scripts.factory.ScriptServiceFactory;
import org.dspace.scripts.service.ProcessService;
import org.junit.Test;

/**
 * This class will aim to test Process related use cases
 *
 * @author Mykhaylo Boychuk (mykhaylo.boychuk at 4science.it)
 */
public class ProcessIT extends AbstractIntegrationTestWithDatabase {

    protected ProcessService processService = ScriptServiceFactory.getInstance().getProcessService();
    protected GroupService groupService = EPersonServiceFactory.getInstance().getGroupService();

    @Test
    public void checkProcessGroupsTest() throws Exception {
        context.turnOffAuthorisationSystem();
        Group groupA = GroupBuilder.createGroup(context)
            .withName("Group A")
            .addMember(admin)
            .build();

        Set<Group> groupSet = new HashSet<>();
        groupSet.add(groupA);

        Process processA = ProcessBuilder.createProcess(context, admin, "mock-script",
                                                        new LinkedList<>(),
                                                        groupSet).build();

        context.restoreAuthSystemState();
        Process process = processService.find(context, processA.getID());
        List<Group> groups = process.getGroups();
        boolean isPresent = groups.stream().anyMatch(g -> g.getID().equals(groupA.getID()));
        assertTrue(isPresent);
    }

    @Test
    public void removeOneGroupTest() throws Exception {

        context.turnOffAuthorisationSystem();
        Group groupA = GroupBuilder.createGroup(context)
            .withName("Group A")
            .addMember(admin).build();

        Set<Group> groupSet = new HashSet<>();
        groupSet.add(groupA);

        UUID groupUuid = groupA.getID();
        Process processA = ProcessBuilder.createProcess(context, admin, "mock-script", new LinkedList<>(),
                                                        groupSet).build();

        context.restoreAuthSystemState();

        groupService.delete(context, groupA);
        context.commit();
        context.reloadEntity(groupA);
        processA = context.reloadEntity(processA);

        Process process = processService.find(context, processA.getID());
        List<Group> groups = process.getGroups();
        boolean isPresent = groups.stream().anyMatch(g -> g.getID().equals(groupUuid));
        assertFalse(isPresent);

    }

    @Test
    public void addProcessWithNullEPersonTest() throws Exception {
        try {
            ProcessBuilder.createProcess(context, null, "mock-script", new LinkedList<>(),
                new HashSet<>()).build();
        } catch (NullPointerException e) {
            fail("Should not have thrown NullPointerException");
        }
    }

    @Test
    public void failRunningProcessesWithoutCurrentUserTest() throws Exception {
        context.turnOffAuthorisationSystem();
        Process runningProcess = ProcessBuilder.createProcess(context, eperson, "mock-script", new LinkedList<>())
                                               .withProcessStatus(ProcessStatus.RUNNING)
                                               .withCreationTime(new Date(System.currentTimeMillis() - 60000))
                                               .build();
        context.restoreAuthSystemState();

        // failRunningProcesses runs on webapp startup with a fresh Context, i.e. without a current user
        context.setCurrentUser(null);
        processService.failRunningProcesses(context);

        Process process = processService.find(context, runningProcess.getID());
        assertEquals(ProcessStatus.FAILED, process.getProcessStatus());
        Bitstream log = processService.getBitstream(context, process, Process.OUTPUT_TYPE);
        assertNotNull(log);
        // the process owner must still be able to read the log of their failed process
        assertTrue(AuthorizeServiceFactory.getInstance().getAuthorizeService()
                                          .authorizeActionBoolean(context, eperson, log, Constants.READ, false));
    }

    @Test
    public void failRunningProcessesWithoutOwnerTest() throws Exception {
        context.turnOffAuthorisationSystem();
        // the owner is null when the EPerson who started the process has been deleted
        Process runningProcess = ProcessBuilder.createProcess(context, null, "mock-script", new LinkedList<>())
                                               .withProcessStatus(ProcessStatus.RUNNING)
                                               .withCreationTime(new Date(System.currentTimeMillis() - 60000))
                                               .build();
        context.restoreAuthSystemState();

        context.setCurrentUser(null);
        processService.failRunningProcesses(context);

        Process process = processService.find(context, runningProcess.getID());
        assertEquals(ProcessStatus.FAILED, process.getProcessStatus());
    }
}
