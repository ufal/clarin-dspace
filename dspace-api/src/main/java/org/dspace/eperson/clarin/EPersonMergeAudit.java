/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.eperson.clarin;

import java.util.Date;
import java.util.UUID;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Lob;
import javax.persistence.SequenceGenerator;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;

import org.dspace.core.ReloadableEntity;

/**
 * Audit trail for {@code eperson-merge}: from/to are kept as plain UUIDs (not
 * FKs) so the record survives even if an EPerson is later deleted outright.
 * {@code detail} is a JSON blob with per-table re-pointed row counts and tool
 * version, making every merge reconstructible.
 *
 * @author Ondrej Kosarko
 */
@Entity
@Table(name = "eperson_merge_audit")
public class EPersonMergeAudit implements ReloadableEntity<Integer> {

    @Id
    @Column(name = "eperson_merge_audit_id")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "eperson_merge_audit_id_seq")
    @SequenceGenerator(name = "eperson_merge_audit_id_seq", sequenceName = "eperson_merge_audit_id_seq",
            allocationSize = 1)
    private Integer id;

    @Column(name = "from_eperson", nullable = false)
    private UUID fromEPerson;

    @Column(name = "to_eperson", nullable = false)
    private UUID toEPerson;

    @Column(name = "performed_by")
    private UUID performedBy;

    @Column(name = "performed_at", nullable = false)
    @Temporal(TemporalType.TIMESTAMP)
    private Date performedAt;

    @Lob
    @Column(name = "detail", nullable = false)
    private String detail;

    protected EPersonMergeAudit() {
    }

    @Override
    public Integer getID() {
        return id;
    }

    public UUID getFromEPerson() {
        return fromEPerson;
    }

    public void setFromEPerson(UUID fromEPerson) {
        this.fromEPerson = fromEPerson;
    }

    public UUID getToEPerson() {
        return toEPerson;
    }

    public void setToEPerson(UUID toEPerson) {
        this.toEPerson = toEPerson;
    }

    public UUID getPerformedBy() {
        return performedBy;
    }

    public void setPerformedBy(UUID performedBy) {
        this.performedBy = performedBy;
    }

    public Date getPerformedAt() {
        return performedAt;
    }

    public void setPerformedAt(Date performedAt) {
        this.performedAt = performedAt;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }
}
