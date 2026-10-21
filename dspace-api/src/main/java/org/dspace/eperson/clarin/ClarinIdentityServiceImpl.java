/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.eperson.clarin;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.dspace.core.Context;
import org.dspace.eperson.EPerson;
import org.dspace.eperson.dao.clarin.EPersonNetidAliasDAO;
import org.dspace.eperson.service.EPersonService;
import org.dspace.eperson.service.clarin.ClarinIdentityService;
import org.dspace.services.ConfigurationService;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * @see ClarinIdentityService
 *
 * @author Ondrej Kosarko
 */
public class ClarinIdentityServiceImpl implements ClarinIdentityService {

    private static final Logger log = LogManager.getLogger(ClarinIdentityServiceImpl.class);

    @Autowired
    private EPersonNetidAliasDAO ePersonNetidAliasDAO;

    @Autowired
    private ConfigurationService configurationService;

    @Autowired
    private EPersonService ePersonService;

    @Override
    public EPerson resolve(Context context, String netid) throws SQLException {
        if (StringUtils.isBlank(netid)) {
            return null;
        }
        EPersonNetidAlias alias = ePersonNetidAliasDAO.findByNetid(context, netid);
        if (alias != null) {
            return alias.getEPerson();
        }
        // Alias rows exist only for netids that went through attach() or the migration backfill.
        // A netid written straight to eperson.netid after the migration (ClarinShibAuthentication#updateEPerson
        // locking a first login to its netid) has no alias row yet, so check the legacy column too.
        return ePersonService.findByNetid(context, netid);
    }

    @Override
    public EPersonNetidAlias attach(Context context, EPerson ePerson, String netid, String source, EPerson createdBy)
            throws SQLException {
        // Check-then-insert is racy under concurrent first logins with the same new netid; this is
        // accepted: the unique constraint on netid preserves integrity, the losing request fails
        // once and succeeds on retry. Recovering in-session (catching the constraint violation)
        // is unreliable after a failed flush, so we deliberately don't attempt it.
        EPersonNetidAlias existing = ePersonNetidAliasDAO.findByNetid(context, netid);
        if (existing != null) {
            if (!existing.getEPerson().getID().equals(ePerson.getID())) {
                throw new IllegalStateException(
                        "netid '" + netid + "' is already attached to a different EPerson (" +
                                existing.getEPerson().getID() + "), refusing to reattach to " + ePerson.getID());
            }
            return existing;
        }

        EPersonNetidAlias alias = new EPersonNetidAlias();
        alias.setEPerson(ePerson);
        alias.setNetid(netid);
        alias.setSource(source);
        alias.setCreatedBy(createdBy);
        alias.setCreatedDate(new Date());
        return ePersonNetidAliasDAO.create(context, alias);
    }

    @Override
    public List<EPersonNetidAlias> findAliases(Context context, EPerson ePerson) throws SQLException {
        return ePersonNetidAliasDAO.findByEPerson(context, ePerson);
    }

    @Override
    public void detach(Context context, EPersonNetidAlias alias) throws SQLException {
        ePersonNetidAliasDAO.delete(context, alias);
    }

    @Override
    public boolean isAllowlistedProxy(String authority) {
        if (StringUtils.isBlank(authority)) {
            return false;
        }
        String[] allowlist = configurationService.getArrayProperty("identity.auto-link.proxy-allowlist");
        return ArrayUtils.contains(allowlist, authority);
    }

    @Override
    public EPerson autoLink(Context context, List<String> upstreamEppns, String proxyAuthority, String proxyNetid)
            throws SQLException {
        if (upstreamEppns == null || upstreamEppns.isEmpty() || StringUtils.isBlank(proxyNetid)) {
            return null;
        }
        if (!isAllowlistedProxy(proxyAuthority)) {
            log.warn("Auto-link via proxy '{}' refused: proxy is not on the identity.auto-link.proxy-allowlist.",
                    proxyAuthority);
            return null;
        }

        // Already linked (e.g. concurrent/repeat login racing this method) - nothing to do.
        EPerson alreadyLinked = resolve(context, proxyNetid);
        if (alreadyLinked != null) {
            return alreadyLinked;
        }

        Set<EPerson> matches = new LinkedHashSet<>();
        List<String> matchedOn = new ArrayList<>();
        for (String eppn : upstreamEppns) {
            if (StringUtils.isBlank(eppn)) {
                continue;
            }
            for (EPersonNetidAlias alias : ePersonNetidAliasDAO.findByValueAnyAuthority(context, eppn)) {
                if (matches.add(alias.getEPerson())) {
                    matchedOn.add(eppn);
                }
            }
        }

        if (matches.isEmpty()) {
            return null;
        }
        if (matches.size() > 1) {
            throw new AmbiguousIdentityException(
                    "Auto-link via proxy '" + proxyAuthority + "' matched " + matches.size()
                            + " different EPersons for released identities " + matchedOn
                            + " (proxy netid '" + proxyNetid + "') - refusing to guess, "
                            + "the accounts need an admin merge/link first.");
        }

        EPerson matched = matches.iterator().next();
        attach(context, matched, proxyNetid, SOURCE_AUTO_VOPERSON, null);
        log.info("Auto-linked netid '{}' to EPerson {} via proxy '{}' voperson_external_id match.",
                proxyNetid, matched.getID(), proxyAuthority);
        return matched;
    }
}
