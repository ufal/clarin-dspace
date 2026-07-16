/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.eperson.clarin;

import java.util.Date;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.SequenceGenerator;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;

import org.dspace.core.ReloadableEntity;
import org.dspace.eperson.EPerson;

/**
 * An alias binding one identity value (a formatted "value[authority]" netid,
 * where authority is a SAML IdP entityID or OIDC issuer URL) to an EPerson.
 *
 * This table is the primary source netid-based login resolution consults;
 * {@link EPerson#getNetid()} is otherwise a denormalized display field, but
 * is still consulted as a fallback by
 * {@link org.dspace.eperson.service.clarin.ClarinIdentityService#resolve}
 * for EPersons that do not (yet) have an alias row of their own.
 *
 * @author Ondrej Kosarko
 */
@Entity
@Table(name = "eperson_netid_alias")
public class EPersonNetidAlias implements ReloadableEntity<Integer> {

    @Id
    @Column(name = "eperson_netid_alias_id")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "eperson_netid_alias_id_seq")
    @SequenceGenerator(name = "eperson_netid_alias_id_seq", sequenceName = "eperson_netid_alias_id_seq",
            allocationSize = 1)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "eperson_id", nullable = false)
    private EPerson ePerson;

    /**
     * Formatted "value[authority]" identity, e.g. "novak@cuni.cz[https://cas.cuni.cz/idp/shibboleth]".
     */
    @Column(name = "netid", nullable = false, unique = true)
    private String netid;

    /**
     * How this alias was created: 'migration' | 'auto-voperson' | 'admin' | 'merge'.
     */
    @Column(name = "source", nullable = false)
    private String source;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private EPerson createdBy;

    @Column(name = "created_date", nullable = false)
    @Temporal(TemporalType.TIMESTAMP)
    private Date createdDate;

    protected EPersonNetidAlias() {
    }

    @Override
    public Integer getID() {
        return id;
    }

    public EPerson getEPerson() {
        return ePerson;
    }

    public void setEPerson(EPerson ePerson) {
        this.ePerson = ePerson;
    }

    public String getNetid() {
        return netid;
    }

    public void setNetid(String netid) {
        this.netid = netid;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public EPerson getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(EPerson createdBy) {
        this.createdBy = createdBy;
    }

    public Date getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(Date createdDate) {
        this.createdDate = createdDate;
    }
}
