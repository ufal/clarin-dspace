/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.content.clarin;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import org.apache.logging.log4j.Logger;
import org.dspace.core.ReloadableEntity;

@Entity
@Table(name = "license_resource_user_allowance")
public class ClarinLicenseResourceUserAllowance implements ReloadableEntity<Integer> {

    private static Logger log = org.apache.logging.log4j.LogManager.getLogger(ClarinLicenseResourceUserAllowance.class);

    @Id
    @Column(name = "transaction_id")
    @GeneratedValue(strategy = GenerationType.SEQUENCE,
            generator = "license_resource_user_allowance_transaction_id_seq")
    @SequenceGenerator(name = "license_resource_user_allowance_transaction_id_seq",
            sequenceName = "license_resource_user_allowance_transaction_id_seq",
            allocationSize = 1)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, cascade = {CascadeType.PERSIST})
    @JoinColumn(name = "user_registration_id")
    private ClarinUserRegistration userRegistration;

    @ManyToOne(fetch = FetchType.LAZY, cascade = {CascadeType.PERSIST})
    @JoinColumn(name = "mapping_id")
    private ClarinLicenseResourceMapping licenseResourceMapping;

    @Column(name = "created_on")
    private Date createdOn;

    @Column(name = "token")
    private String token;

    @OneToMany(fetch = FetchType.LAZY, mappedBy = "transaction", cascade = CascadeType.PERSIST)
    private List<ClarinUserMetadata> userMetadata = new ArrayList<>();

    @Override
    public Integer getID() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public ClarinUserRegistration getUserRegistration() {
        return userRegistration;
    }

    public void setUserRegistration(ClarinUserRegistration userRegistration) {
        this.userRegistration = userRegistration;
    }

    public ClarinLicenseResourceMapping getLicenseResourceMapping() {
        return licenseResourceMapping;
    }

    public void setLicenseResourceMapping(ClarinLicenseResourceMapping licenseResourceMapping) {
        this.licenseResourceMapping = licenseResourceMapping;
    }

    public Date getCreatedOn() {
        return createdOn;
    }

    public void setCreatedOn(Date createdOn) {
        this.createdOn = createdOn;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public List<ClarinUserMetadata> getUserMetadata() {
        return userMetadata;
    }

    public void setUserMetadata(List<ClarinUserMetadata> userMetadata) {
        this.userMetadata = userMetadata;
    }
}
