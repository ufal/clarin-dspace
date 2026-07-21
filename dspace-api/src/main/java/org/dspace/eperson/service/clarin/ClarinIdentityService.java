/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.eperson.service.clarin;

import java.sql.SQLException;
import java.util.List;

import org.dspace.core.Context;
import org.dspace.eperson.EPerson;
import org.dspace.eperson.clarin.EPersonNetidAlias;

/**
 * Single identity-resolution point for netid-based authentication methods
 * (Shibboleth today, OIDC/ORCID/LDAP if they are ever converted). Backed by
 * a proxy-agnostic alias table ({@link EPersonNetidAlias}) that is the
 * primary source consulted at login. {@code EPerson.netid} is otherwise a
 * denormalized display field nothing here writes to, but {@link #resolve}
 * still falls back to it for EPersons whose netid was set without going
 * through {@link #attach} (e.g. legacy rows, or a netid locked in by
 * {@code ClarinShibAuthentication#updateEPerson} outside of this service).
 *
 * @author Ondrej Kosarko
 */
public interface ClarinIdentityService {

    /**
     * Value persisted in {@code eperson_netid_alias.source} for aliases created by the
     * one-off migration backfill of pre-existing legacy netids
     * ({@code V7.6_2026.07.08__eperson_netid_alias.sql}).
     */
    String SOURCE_MIGRATION = "migration";

    /**
     * Value persisted in {@code eperson_netid_alias.source} for aliases created by
     * {@link #autoLink} matching an allowlisted proxy's {@code voperson_external_id}.
     */
    String SOURCE_AUTO_VOPERSON = "auto-voperson";

    /**
     * Value persisted in {@code eperson_netid_alias.source} for aliases created manually by
     * an admin via the identity-link endpoint.
     */
    String SOURCE_ADMIN = "admin";

    /**
     * Value persisted in {@code eperson_netid_alias.source} for aliases carried over onto the
     * surviving EPerson by the {@code eperson-merge} tooling.
     */
    String SOURCE_MERGE = "merge";

    /**
     * Resolve a formatted "value[authority]" netid to the EPerson it is aliased
     * to. Falls back to a legacy {@code EPerson.netid} column match when no
     * alias exists. Returns null if neither matches.
     */
    EPerson resolve(Context context, String netid) throws SQLException;

    /**
     * Attach a new identity to an EPerson as an alias. No-op (returns the
     * existing alias) if this exact netid is already attached to this EPerson.
     *
     * @param netid formatted "value[authority]" identity
     * @param source 'auto-voperson' | 'admin' | 'merge' | 'migration'
     * @param createdBy the admin EPerson performing a manual link, or null
     * @throws IllegalStateException if the netid is already attached to a
     *      *different* EPerson
     */
    EPersonNetidAlias attach(Context context, EPerson ePerson, String netid, String source, EPerson createdBy)
        throws SQLException;

    /**
     * All aliases currently attached to an EPerson.
     */
    List<EPersonNetidAlias> findAliases(Context context, EPerson ePerson) throws SQLException;

    /**
     * Remove an alias, freeing its netid value to be attached elsewhere. Production login and
     * merge flows never call this; it exists for admin unlink operations and test fixture
     * cleanup (an alias row is not deleted automatically when its EPerson is, so anything that
     * attaches an alias to a to-be-deleted EPerson must detach it first).
     */
    void detach(Context context, EPersonNetidAlias alias) throws SQLException;

    /**
     * Is this SAML IdP / OIDC issuer entityID allowlisted to drive auto-linking
     * via {@code voperson_external_id} (config key
     * {@code identity.auto-link.proxy-allowlist})?
     */
    boolean isAllowlistedProxy(String authority);

    /**
     * Auto-linking, section 4 of the design: given the list of eppns released
     * by an allowlisted identity proxy in {@code voperson_external_id} (i.e.
     * "merging by all registered external identities" per Perun), and the
     * formatted netid of the proxy identity actually used to log in:
     *
     * <ul>
     *   <li>exactly one EPerson already holds an alias matching one of the
     *       released eppns -&gt; attach {@code proxyNetid} to it and return it;</li>
     *   <li>zero matches -&gt; return null (the caller may fall through to its
     *       other identification methods);</li>
     *   <li>more than one match -&gt; refuse to guess and throw
     *       {@link org.dspace.eperson.clarin.AmbiguousIdentityException}; the
     *       caller must not auto-register a new EPerson for this login, the
     *       existing accounts need an admin merge/link first.</li>
     * </ul>
     *
     * @throws org.dspace.eperson.clarin.AmbiguousIdentityException if the
     *      released eppns match more than one existing EPerson
     */
    EPerson autoLink(Context context, List<String> upstreamEppns, String proxyAuthority, String proxyNetid)
        throws SQLException;
}
