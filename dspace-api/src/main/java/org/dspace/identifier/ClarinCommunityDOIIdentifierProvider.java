/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.identifier;

import java.util.HashSet;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.dspace.identifier.doi.ClarinDataCiteConnector;

/**
 * DOI identifier provider for CLARIN communities.
 * It uses the ClarinDataCiteConnector to connect to DataCite and requires the DOI prefix
 * and the list of CLARIN community IDs to be configured.
 *
 * @author Milan Kuchtiak (kuchtiak@ufal.mff.cuni.cz)
 */
public class ClarinCommunityDOIIdentifierProvider extends VersionedDOIIdentifierProvider {

    private static final Logger log = LogManager.getLogger(ClarinCommunityDOIIdentifierProvider.class);

    private String doiPrefix;

    private String namespaceSeparator;

    private Set<String> communities = new HashSet<>();

    public void init() {
        ClarinDataCiteConnector dataCiteConnector = (ClarinDataCiteConnector) this.connector;

        dataCiteConnector.setDoiPrefix(doiPrefix);

        String username = this.configurationService.getProperty("identifier.doi." + doiPrefix + ".user");
        if (username == null) {
            log.error("No username configured for DOI prefix '{}'", doiPrefix);
            throw new IllegalStateException("No username configured for DOI prefix '" + doiPrefix + "'");
        }
        dataCiteConnector.setUsername(username);

        String password = this.configurationService.getProperty("identifier.doi." + doiPrefix + ".password");
        if (password == null) {
            log.error("No password configured for DOI prefix '{}'", doiPrefix);
            throw new IllegalStateException("No password configured for DOI prefix '" + doiPrefix + "'");
        }
        dataCiteConnector.setPassword(password);
    }

    public void setDoiPrefix(String doiPrefix) {
        this.doiPrefix = doiPrefix;
    }

    public void setNamespaceSeparator(String namespaceSeparator) {
        this.namespaceSeparator = namespaceSeparator;
    }

    public void setCommunities(Set<String> communities) {
        this.communities = communities;
    }

    @Override
    protected String getPrefix() {
        return this.doiPrefix;
    }

    @Override
    protected String getNamespaceSeparator() {
        return this.namespaceSeparator;
    }

    Set<String> getCommunities() {
        return this.communities;
    }

}
