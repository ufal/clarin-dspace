/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.content.clarin;

import java.util.ArrayList;
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
import jakarta.persistence.OneToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import org.dspace.content.Bitstream;
import org.dspace.core.ReloadableEntity;

@Entity
@Table(name = "license_resource_mapping")
public class ClarinLicenseResourceMapping implements ReloadableEntity<Integer> {

    @Id
    @Column(name = "mapping_id")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "license_resource_mapping_mapping_id_seq")
    @SequenceGenerator(name = "license_resource_mapping_mapping_id_seq",
            sequenceName = "license_resource_mapping_mapping_id_seq",
            allocationSize = 1)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, cascade = {CascadeType.PERSIST})
    @JoinColumn(name = "license_id")
    private ClarinLicense license;

    @OneToOne(cascade = {CascadeType.PERSIST})
    @JoinColumn(name = "bitstream_uuid", referencedColumnName = "uuid")
    private Bitstream bitstream;

    @OneToMany(fetch = FetchType.LAZY, mappedBy = "licenseResourceMapping", cascade = CascadeType.PERSIST)
    private List<ClarinLicenseResourceUserAllowance> licenseResourceUserAllowances = new ArrayList<>();

    @Override
    public Integer getID() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public Bitstream getBitstream() {
        return bitstream;
    }

    public void setBitstream(Bitstream bitstream) {
        this.bitstream = bitstream;
    }

    public ClarinLicense getLicense() {
        return license;
    }

    public void setLicense(ClarinLicense license) {
        this.license = license;
    }

    public List<ClarinLicenseResourceUserAllowance> getLicenseResourceUserAllowances() {
        return licenseResourceUserAllowances;
    }

    public void setLicenseResourceUserAllowances(List<ClarinLicenseResourceUserAllowance>
                                                         licenseResourceUserAllowances) {
        this.licenseResourceUserAllowances = licenseResourceUserAllowances;
    }
}
