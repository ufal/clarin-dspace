/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.eperson.dao.clarin;

import java.sql.SQLException;
import java.util.List;

import org.dspace.core.Context;
import org.dspace.core.GenericDAO;
import org.dspace.eperson.EPerson;
import org.dspace.eperson.clarin.EPersonNetidAlias;

/**
 * Database Access Object interface class for the EPersonNetidAlias object.
 *
 * @author Ondrej Kosarko
 */
public interface EPersonNetidAliasDAO extends GenericDAO<EPersonNetidAlias> {

    /**
     * Find the alias row for an exact formatted "value[authority]" netid.
     */
    EPersonNetidAlias findByNetid(Context context, String netid) throws SQLException;

    /**
     * Find aliases whose stored netid is {@code value[<any authority>]} - the netid column
     * stores identities formatted by {@code Util#formatNetId(value, authority)}, while
     * upstream attributes like voperson_external_id release the bare value without the
     * authority suffix.
     */
    List<EPersonNetidAlias> findByValueAnyAuthority(Context context, String value) throws SQLException;

    /**
     * Find all aliases currently attached to the given EPerson.
     */
    List<EPersonNetidAlias> findByEPerson(Context context, EPerson ePerson) throws SQLException;
}
