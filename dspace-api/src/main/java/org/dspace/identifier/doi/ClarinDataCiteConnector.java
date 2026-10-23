/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.identifier.doi;

import org.apache.commons.lang3.StringUtils;
import org.dspace.identifier.DOI;

/**
 * DataCite connector for CLARIN communities.
 * It allows to set the username and password via setter methods. The DOI prefix is not configured,
 * it is taken from the DOI being sent, so one connector serves any prefix its DataCite account owns.
 *
 * @author Milan Kuchtiak (kuchtiak@ufal.mff.cuni.cz)
 */
public class ClarinDataCiteConnector extends DataCiteConnector {

    public void setUsername(String username) {
        super.USERNAME = username;
    }

    public void setPassword(String password) {
        super.PASSWORD = password;
    }

    @Override
    protected String getDoiPrefix(String doi) {
        return StringUtils.substringBefore(StringUtils.removeStart(doi, DOI.SCHEME), "/");
    }

}
