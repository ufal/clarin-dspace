/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.repository;

import static org.apache.commons.lang3.StringUtils.isBlank;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.dspace.app.rest.exception.ClarinLicenseLabelNotFoundException;
import org.dspace.app.rest.exception.DSpaceBadRequestException;
import org.dspace.app.rest.model.ClarinLicenseLabelRest;
import org.dspace.authorize.AuthorizeException;
import org.dspace.content.clarin.ClarinLicense;
import org.dspace.content.clarin.ClarinLicenseLabel;
import org.dspace.content.service.clarin.ClarinLicenseLabelService;
import org.dspace.content.service.clarin.ClarinLicenseService;
import org.dspace.core.Context;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

/**
 * This is the repository responsible to manage Clarin License Label Rest object
 *
 * @author Milan Majchrak (milan.majchrak at dataquest.sk)
 */
@Component(ClarinLicenseLabelRest.CATEGORY + "." + ClarinLicenseLabelRest.PLURAL_NAME)
public class ClarinLicenseLabelRestRepository extends DSpaceRestRepository<ClarinLicenseLabelRest, Integer> {

    private static final int MAX_LABEL_LENGTH = 5;

    @Autowired
    ClarinLicenseService clarinLicenseService;

    @Autowired
    ClarinLicenseLabelService clarinLicenseLabelService;

    @Autowired
    ObjectMapper objectMapper;

    @Override
    @PreAuthorize("permitAll()")
    public ClarinLicenseLabelRest findOne(Context context, Integer id) {
        ClarinLicenseLabel clarinLicenseLabel;
        try {
            clarinLicenseLabel = clarinLicenseLabelService.find(context, id);
        } catch (SQLException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
        if (Objects.isNull(clarinLicenseLabel)) {
            return null;
        }
        return converter.toRest(clarinLicenseLabel, utils.obtainProjection());
    }

    @Override
    public Page<ClarinLicenseLabelRest> findAll(Context context, Pageable pageable) {
        try {
            List<ClarinLicenseLabel> clarinLicenseLabelList = clarinLicenseLabelService.findAll(context);
            return converter.toRestPage(clarinLicenseLabelList, pageable, utils.obtainProjection());
        } catch (SQLException | AuthorizeException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    // create
    @Override
    @PreAuthorize("hasAuthority('ADMIN')")
    protected ClarinLicenseLabelRest createAndReturn(Context context)
            throws AuthorizeException, SQLException {

        // parse request body
        ClarinLicenseLabelRest clarinLicenseLabelRest;
        try {
            clarinLicenseLabelRest = objectMapper.readValue(
                    getRequestService().getCurrentRequest().getHttpServletRequest().getInputStream(),
                    ClarinLicenseLabelRest.class
            );
        } catch (IOException excIO) {
            throw new DSpaceBadRequestException("error parsing request body", excIO);
        }

        checkLabelAndTitle(clarinLicenseLabelRest);
        if (clarinLicenseLabelService.findByLabel(context, clarinLicenseLabelRest.getLabel().trim()) != null) {
            throw new DSpaceBadRequestException("Clarin License Label with label " + clarinLicenseLabelRest.getLabel() +
                    " already exists");
        }

        // create
        ClarinLicenseLabel clarinLicenseLabel;
        clarinLicenseLabel = clarinLicenseLabelService.create(context);
        updateClarinLicenseLabel(clarinLicenseLabel, clarinLicenseLabelRest);

        clarinLicenseLabelService.update(context, clarinLicenseLabel);
        // return
        return converter.toRest(clarinLicenseLabel, utils.obtainProjection());
    }

    @Override
    @PreAuthorize("hasAuthority('ADMIN')")
    public ClarinLicenseLabelRest put(Context context,
                                      HttpServletRequest request,
                                      String apiCategory,
                                      String model,
                                      Integer id,
                                      JsonNode jsonNode) throws SQLException, AuthorizeException {
        ClarinLicenseLabel clarinLicenseLabel = clarinLicenseLabelService.find(context, id);
        if (Objects.isNull(clarinLicenseLabel)) {
            throw new ClarinLicenseLabelNotFoundException("Clarin License Label with id " + id + " was not found");
        }

        // parse request body
        ClarinLicenseLabelRest clarinLicenseLabelRest;
        try {
            clarinLicenseLabelRest = objectMapper.treeToValue(jsonNode, ClarinLicenseLabelRest.class);
        } catch (IOException excIO) {
            throw new DSpaceBadRequestException("error parsing request body", excIO);
        }

        checkLabelAndTitle(clarinLicenseLabelRest);

        ClarinLicenseLabel clarinLicenseLabelWithSameLabel = clarinLicenseLabelService.findByLabel(context,
                clarinLicenseLabelRest.getLabel().trim());
        if (clarinLicenseLabelWithSameLabel != null && !clarinLicenseLabelWithSameLabel.getID().equals(id)) {
            throw new DSpaceBadRequestException("Clarin License Label with label " + clarinLicenseLabelRest.getLabel() +
                    " already exists");
        }

        updateClarinLicenseLabel(clarinLicenseLabel, clarinLicenseLabelRest);

        clarinLicenseLabelService.update(context, clarinLicenseLabel);

        return converter.toRest(clarinLicenseLabel, utils.obtainProjection());
    }

    @Override
    @PreAuthorize("hasAuthority('ADMIN')")
    public void delete(Context context, Integer id) throws AuthorizeException {
        ClarinLicenseLabel clarinLicenseLabel;
        try {
            clarinLicenseLabel = clarinLicenseLabelService.find(context, id);
            if (Objects.isNull(clarinLicenseLabel)) {
                throw new ClarinLicenseLabelNotFoundException("Clarin License Label with id " + id + " was not found");
            }
            List<ClarinLicense> licenses = clarinLicenseService.findByLabel(context, clarinLicenseLabel.getLabel());
            if (!licenses.isEmpty()) {
                throw new DSpaceBadRequestException("Clarin License Label " + clarinLicenseLabel.getLabel() +
                        " is in use and cannot be deleted");
            }
            clarinLicenseLabelService.delete(context, clarinLicenseLabel);
        } catch (SQLException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    private void checkLabelAndTitle(ClarinLicenseLabelRest clarinLicenseLabelRest) {
        String label = Optional.ofNullable(clarinLicenseLabelRest.getLabel()).map(String::trim).orElse(null);
        // validate fields
        if (isBlank(label) || isBlank(clarinLicenseLabelRest.getTitle())) {
            throw new DSpaceBadRequestException("CLARIN License Label title and label cannot be null or empty");
        }
        if (label.length() > MAX_LABEL_LENGTH) {
            throw new DSpaceBadRequestException(
                    "CLARIN License Label -> label string cannot be longer than " + MAX_LABEL_LENGTH + " characters");
        }
    }

    private static void updateClarinLicenseLabel(ClarinLicenseLabel clarinLicenseLabel,
                                                 ClarinLicenseLabelRest clarinLicenseLabelRest) {
        clarinLicenseLabel.setLabel(clarinLicenseLabelRest.getLabel().trim());
        clarinLicenseLabel.setTitle(clarinLicenseLabelRest.getTitle());
        clarinLicenseLabel.setIcon(clarinLicenseLabelRest.getIcon());
        clarinLicenseLabel.setExtended(clarinLicenseLabelRest.isExtended());
    }

    @Override
    public Class<ClarinLicenseLabelRest> getDomainClass() {
        return ClarinLicenseLabelRest.class;
    }
}
