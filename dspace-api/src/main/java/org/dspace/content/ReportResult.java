/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.content;

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
import org.hibernate.annotations.UpdateTimestamp;

/**
 * ReportResult is an entity that stores the results of a Health Report execution.
 * It includes the type of report, the value of the result, the executor,
 * arguments used for the report, and the last modified date.
 *
 * @author Milan Majchrak (milan.majchrak at dataquest.sk)
 */
@Entity
@Table(name = "report_result")
public class ReportResult implements ReloadableEntity<Integer> {
    @Id
    @Column(name = "report_result_id")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "report_result_id_seq")
    @SequenceGenerator(name = "report_result_id_seq", sequenceName = "report_result_id_seq",
            allocationSize = 1)
    private Integer id;

    @Column(name = "type")
    private String type;

    @Column(name = "value")
    private String value;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "executor_id")
    private EPerson executor;

    @Column(name = "args")
    private String args;

    @Column(name = "last_modified")
    @UpdateTimestamp
    @Temporal(TemporalType.TIMESTAMP)
    private Date lastModified;

    @Override
    public Integer getID() {
        return id;
    }

    public ReportResult() {
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public EPerson getExecutor() {
        return executor;
    }

    public void setExecutor(EPerson executor) {
        this.executor = executor;
    }

    public String getArgs() {
        return args;
    }

    public void setArgs(String args) {
        this.args = args;
    }

    public Date getLastModified() {
        return lastModified;
    }

    public void setLastModified(Date lastModified) {
        this.lastModified = lastModified;
    }
}
