/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.identifier.doi;

/**
 * DataCite connector for CLARIN communities.
 * It allows to set the username, password and DOI prefix via setter methods.
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

    public void setDoiPrefix(String doiPrefix) {
        super.doiPrefix = doiPrefix;
    }

}
