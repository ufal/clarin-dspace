--
-- The contents of this file are subject to the license and copyright
-- detailed in the LICENSE and NOTICE files at the root of the source
-- tree and available online at
--
-- http://www.dspace.org/license/
--

ALTER TABLE license_label_extended_mapping
    DROP CONSTRAINT IF EXISTS license_label_license_label_extended_mapping_fk;

-- here the "ON DELETE RESTRICT" clause (default clause) is used, which prevents deletion of a license_label record
-- when there are any license_label_extended_mapping records that reference it
ALTER TABLE license_label_extended_mapping
    ADD CONSTRAINT license_label_license_label_extended_mapping_fk FOREIGN KEY (label_id) REFERENCES license_label(label_id);

ALTER TABLE license_label DROP CONSTRAINT IF EXISTS license_label_label_unique;
ALTER TABLE license_label ADD CONSTRAINT license_label_label_unique UNIQUE(label);
