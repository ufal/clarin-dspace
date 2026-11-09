/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.repository;

import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import javax.servlet.ServletRequest;

import org.dspace.app.rest.exception.RESTAuthorizationException;
import org.dspace.authorize.service.AuthorizeService;
import org.dspace.core.Context;
import org.dspace.services.RequestService;
import org.dspace.services.model.Request;
import org.dspace.xmlworkflow.storedcomponents.XmlWorkflowItem;
import org.dspace.xmlworkflow.storedcomponents.service.XmlWorkflowItemService;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

/**
 * Checks that {@link WorkflowItemRestRepository} lets only administrators expunge a workflow item.
 */
@RunWith(MockitoJUnitRunner.class)
public class WorkflowItemRestRepositoryDeleteTest {

    private static final int WORKFLOW_ITEM_ID = 1;

    @Mock
    private XmlWorkflowItemService wis;

    @Mock
    private AuthorizeService authorizeService;

    @Mock
    private RequestService requestService;

    @Mock
    private Request request;

    @Mock
    private ServletRequest servletRequest;

    @Mock
    private Context context;

    @Mock
    private XmlWorkflowItem witem;

    private WorkflowItemRestRepository repository;

    @Before
    public void setUp() throws Exception {
        // the real constructor needs the DSpace kernel, a mock skips it
        repository = mock(WorkflowItemRestRepository.class, CALLS_REAL_METHODS);
        repository.wis = wis;
        repository.authorizeService = authorizeService;
        repository.requestService = requestService;
        when(requestService.getCurrentRequest()).thenReturn(request);
        when(request.getServletRequest()).thenReturn(servletRequest);
        when(servletRequest.getParameter(WorkflowItemRestRepository.REQUESTPARAMETER_EXPUNGE)).thenReturn("true");
        when(wis.find(context, WORKFLOW_ITEM_ID)).thenReturn(witem);
    }

    @Test
    public void expungeByNonAdminIsRejected() throws Exception {
        when(authorizeService.isAdmin(context)).thenReturn(false);

        assertThrows(RESTAuthorizationException.class, () -> repository.delete(context, WORKFLOW_ITEM_ID));
        verify(wis, never()).delete(context, witem);
    }

    @Test
    public void expungeByAdminDeletesItem() throws Exception {
        when(authorizeService.isAdmin(context)).thenReturn(true);

        repository.delete(context, WORKFLOW_ITEM_ID);
        verify(wis).delete(context, witem);
    }
}
