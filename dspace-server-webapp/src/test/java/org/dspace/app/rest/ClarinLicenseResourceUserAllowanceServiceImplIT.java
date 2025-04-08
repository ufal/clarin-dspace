/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest;

import org.dspace.app.rest.test.AbstractControllerIntegrationTest;
import org.dspace.builder.ClarinLicenseResourceUserAllowanceBuilder;
import org.dspace.builder.ClarinUserRegistrationBuilder;
import org.dspace.content.clarin.ClarinLicenseResourceUserAllowance;
import org.dspace.content.clarin.ClarinUserRegistration;
import org.dspace.content.service.clarin.ClarinLicenseResourceUserAllowanceService;
import org.dspace.eperson.EPerson;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;

public class ClarinLicenseResourceUserAllowanceServiceImplIT extends AbstractControllerIntegrationTest {

    @Autowired
    ClarinLicenseResourceUserAllowanceService clarinLicenseResourceUserAllowanceService;

    private ClarinLicenseResourceUserAllowance clarinLicenseResourceUserAllowance;

    @Test
    public void testFind() throws Exception {
        context.turnOffAuthorisationSystem();
        ClarinUserRegistration clarinUserRegistration = ClarinUserRegistrationBuilder
                .createClarinUserRegistration(context)
                .withEPersonID(admin.getID())
                .build();

        clarinLicenseResourceUserAllowance = ClarinLicenseResourceUserAllowanceBuilder
                .createClarinLicenseResourceUserAllowance(context)
                .withUser(clarinUserRegistration)
                .build();

        context.restoreAuthSystemState();
        EPerson currentUser = context.getCurrentUser();
        context.setCurrentUser(admin);
        Assert.assertEquals(clarinLicenseResourceUserAllowance,
                clarinLicenseResourceUserAllowanceService
                .find(context, clarinLicenseResourceUserAllowance.getID()));
        context.setCurrentUser(currentUser);
    }

    @Override
    public void destroy() throws Exception {
        ClarinLicenseResourceUserAllowanceBuilder.deleteClarinLicenseResourceUserAllowance(
                clarinLicenseResourceUserAllowance.getID());
        super.destroy();
    }
}

