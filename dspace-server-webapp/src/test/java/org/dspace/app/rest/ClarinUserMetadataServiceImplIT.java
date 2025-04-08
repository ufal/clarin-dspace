/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest;

import org.dspace.app.rest.test.AbstractControllerIntegrationTest;
import org.dspace.builder.ClarinUserMetadataBuilder;
import org.dspace.builder.ClarinUserRegistrationBuilder;
import org.dspace.content.clarin.ClarinUserMetadata;
import org.dspace.content.clarin.ClarinUserRegistration;
import org.dspace.content.service.clarin.ClarinUserMetadataService;
import org.dspace.eperson.EPerson;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;

public class ClarinUserMetadataServiceImplIT extends AbstractControllerIntegrationTest {

    @Autowired
    ClarinUserMetadataService clarinUserMetadataService;

    ClarinUserMetadata clarinUserMetadata;

    @Test
    public void testFind() throws Exception {
        context.turnOffAuthorisationSystem();
        ClarinUserRegistration clarinUserRegistration = ClarinUserRegistrationBuilder
                .createClarinUserRegistration(context)
                .withEPersonID(admin.getID())
                .build();
        clarinUserMetadata = ClarinUserMetadataBuilder
                .createClarinUserMetadata(context)
                .withUserRegistration(clarinUserRegistration)
                .build();
        context.restoreAuthSystemState();
        EPerson currentUser = context.getCurrentUser();
        context.setCurrentUser(admin);
        Assert.assertEquals(clarinUserMetadata, clarinUserMetadataService
                .find(context, clarinUserMetadata.getID()));
        context.setCurrentUser(currentUser);
    }

    @Override
    public void destroy() throws Exception {
        ClarinUserMetadataBuilder.deleteClarinUserMetadata(clarinUserMetadata.getID());
        super.destroy();
    }
}
