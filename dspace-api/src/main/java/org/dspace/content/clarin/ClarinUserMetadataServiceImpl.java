/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.content.clarin;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.commons.lang.NullArgumentException;
import org.dspace.authorize.AuthorizeException;
import org.dspace.authorize.service.AuthorizeService;
import org.dspace.content.dao.clarin.ClarinUserMetadataDAO;
import org.dspace.content.service.clarin.ClarinUserMetadataService;
import org.dspace.core.Context;
import org.dspace.core.LogHelper;
import org.dspace.eperson.EPerson;
import org.hibernate.ObjectNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.CollectionUtils;

public class ClarinUserMetadataServiceImpl implements ClarinUserMetadataService {
    private static final Logger log = LoggerFactory.getLogger(ClarinUserMetadataService.class);

    @Autowired
    AuthorizeService authorizeService;
    @Autowired
    ClarinUserMetadataDAO clarinUserMetadataDAO;

    @Override
    public ClarinUserMetadata create(Context context) throws SQLException {
        // Create a table row
        ClarinUserMetadata clarinUserMetadata = clarinUserMetadataDAO.create(context,
                new ClarinUserMetadata());

        log.info(LogHelper.getHeader(context, "create_clarin_user_metadata",
                "clarin_user_metadata_id=" + clarinUserMetadata.getID()));

        return clarinUserMetadata;
    }

    @Override
    public ClarinUserMetadata find(Context context, int valueId) throws SQLException, AuthorizeException {
        ClarinUserMetadata clarinUserMetadata = clarinUserMetadataDAO
                .findByID(context, ClarinUserMetadata.class, valueId);

        if (Objects.isNull(clarinUserMetadata)) {
            return null;
        }
        this.authorizeClarinUserMetadataAction(context, List.of(clarinUserMetadata));
        return clarinUserMetadata;
    }

    @Override
    public List<ClarinUserMetadata> findAll(Context context) throws SQLException, AuthorizeException {
        if (!authorizeService.isAdmin(context)) {
            throw new AuthorizeException(
                    "You must be an admin to get all clarin user metadata.");
        }
        return clarinUserMetadataDAO.findAll(context, ClarinUserMetadata.class);
    }

    @Override
    public void update(Context context, ClarinUserMetadata clarinUserMetadata) throws SQLException, AuthorizeException {
        if (Objects.isNull(clarinUserMetadata)) {
            throw new NullArgumentException("Cannot update user metadata because the new user metadata is null");
        }

        ClarinUserMetadata foundUserMetadata = find(context, clarinUserMetadata.getID());
        if (Objects.isNull(foundUserMetadata)) {
            throw new ObjectNotFoundException(clarinUserMetadata.getID(),
                    "Cannot update the user metadata because the user metadata wasn't found in the database.");
        }

        clarinUserMetadataDAO.save(context, clarinUserMetadata);
    }

    @Override
    public void delete(Context context, ClarinUserMetadata clarinUserMetadata) throws SQLException, AuthorizeException {
        if (!authorizeService.isAdmin(context)) {
            throw new AuthorizeException(
                    "You must be an admin to create an Clarin user metadata");
        }
        clarinUserMetadataDAO.delete(context, clarinUserMetadata);
    }

    @Override
    public List<ClarinUserMetadata> findByUserRegistrationAndBitstream(Context context, Integer userRegUUID,
                                                                       UUID bitstreamUUID, boolean lastTransaction)
            throws SQLException, AuthorizeException {
        List<ClarinUserMetadata> userMetadata = null;
        if (lastTransaction) {
            userMetadata = getLastTransactionUserMetadata(clarinUserMetadataDAO
                    .findByUserRegistrationAndBitstream(context, userRegUUID, bitstreamUUID));
        } else {
            userMetadata = clarinUserMetadataDAO.findByUserRegistrationAndBitstream(context,
                    userRegUUID, bitstreamUUID);
        }

        this.authorizeClarinUserMetadataAction(context, userMetadata);

        if (userMetadata == null) {
            userMetadata = List.of();
        }
        return userMetadata;
    }

    private List<ClarinUserMetadata> getLastTransactionUserMetadata(List<ClarinUserMetadata> userMetadataList) {
        Integer latestTransactionId = getIdOfLastTransaction(userMetadataList);
        if (latestTransactionId == null) {
            return userMetadataList;
        }

        List<ClarinUserMetadata> filteredUserMetadata = null;
        // Filter all user metadata by the last transaction
        try {
            filteredUserMetadata = userMetadataList.stream()
                    .filter(clarinUserMetadata -> clarinUserMetadata.getTransaction().getID()
                            .equals(latestTransactionId))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Error filtering user metadata by the last transaction", e);
        }
        return filteredUserMetadata;
    }

    private Integer getIdOfLastTransaction(List<ClarinUserMetadata> userMetadataList) {
        // userMetadataList is filtered by the last transaction - first element is the last transaction
        try {
            return userMetadataList.get(0).getTransaction().getID();
        } catch (IndexOutOfBoundsException e) {
            log.error("No transaction found for the user metadata");
            return null;
        }
    }

    /**
     * Check if the user is admin or if the user is the same as from the userRegistration
     */
    private void authorizeClarinUserMetadataAction(Context context, List<ClarinUserMetadata> userMetadata)
            throws SQLException, AuthorizeException {
        if (authorizeService.isAdmin(context)) {
            return;
        }

        if (CollectionUtils.isEmpty(userMetadata)) {
            return;
        }

        // Check if the user is the same as from the userRegistration
        // Do not allow to get the userRegistration of another user
        EPerson currentUser = context.getCurrentUser();
        ClarinUserMetadata userMetadatum = userMetadata.get(0);

        // Check if the userRegistration is not null
        if (Objects.isNull(userMetadatum.getEperson())) {
            return;
        }

        UUID userRegistrationEpersonUUID = userMetadatum.getEperson().getPersonID();
        if (currentUser.getID().equals(userRegistrationEpersonUUID)) {
            return;
        }

        throw new AuthorizeException("You are not authorized to access the Clarin User Metadata " +
                "because it is not associated with your account.");
    }
}
