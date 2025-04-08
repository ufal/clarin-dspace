/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest;

import org.dspace.app.rest.test.AbstractControllerIntegrationTest;
import org.dspace.builder.ClarinUserRegistrationBuilder;
import org.dspace.content.clarin.ClarinUserRegistration;
import org.dspace.content.service.clarin.ClarinUserRegistrationService;
import org.dspace.eperson.EPerson;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;

public class ClarinUserRegistrationServiceImplIT extends AbstractControllerIntegrationTest {

    @Autowired
    ClarinUserRegistrationService clarinUserRegistrationService;

    @Test
    public void testFind() throws Exception {
        context.turnOffAuthorisationSystem();
        ClarinUserRegistration clarinUserRegistration = ClarinUserRegistrationBuilder
                .createClarinUserRegistration(context)
                .withEPersonID(admin.getID())
                .build();
        context.restoreAuthSystemState();
        // Find created handle
        EPerson currentUser = context.getCurrentUser();
        context.setCurrentUser(admin);
        Assert.assertEquals(clarinUserRegistration, clarinUserRegistrationService
                .find(context, clarinUserRegistration.getID()));
        context.setCurrentUser(currentUser);
    }
}
