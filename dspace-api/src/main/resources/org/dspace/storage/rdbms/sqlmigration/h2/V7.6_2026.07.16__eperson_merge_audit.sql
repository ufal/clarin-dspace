--
-- The contents of this file are subject to the license and copyright
-- detailed in the LICENSE and NOTICE files at the root of the source
-- tree and available online at
--
-- http://www.dspace.org/license/
--

-- Audit trail for the eperson-merge tool. Kept even if one side is later
-- deleted, so from_eperson/to_eperson are plain UUIDs, not FKs.

CREATE SEQUENCE eperson_merge_audit_id_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE eperson_merge_audit (
    eperson_merge_audit_id INTEGER NOT NULL DEFAULT NEXTVAL('eperson_merge_audit_id_seq') PRIMARY KEY,
    from_eperson UUID NOT NULL,
    to_eperson   UUID NOT NULL,
    performed_by UUID,
    performed_at TIMESTAMP NOT NULL,
    detail       CLOB NOT NULL
);
