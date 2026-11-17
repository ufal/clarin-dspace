/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.content.dao.clarin;

import java.sql.SQLException;

import org.dspace.content.clarin.ClarinToken;
import org.dspace.core.Context;
import org.dspace.core.GenericDAO;
import org.dspace.eperson.EPerson;

/**
 * Database Access Object interface class for the ClarinToken object.
 * The implementation of this class is responsible for all database calls for the ClarinToken object
 * and is autowired by spring This class should only be accessed from a single service and should never be exposed
 * outside the API
 *
 * @author Milan Kuchtiak
 */
public interface ClarinTokenDAO extends GenericDAO<ClarinToken> {

    void deleteAll(Context context) throws SQLException;

    void deleteTokensForEPerson(Context context, EPerson ePerson) throws SQLException;

}
