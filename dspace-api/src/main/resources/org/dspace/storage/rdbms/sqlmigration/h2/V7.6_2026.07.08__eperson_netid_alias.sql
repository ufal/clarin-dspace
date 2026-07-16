--
-- The contents of this file are subject to the license and copyright
-- detailed in the LICENSE and NOTICE files at the root of the source
-- tree and available online at
--
-- http://www.dspace.org/license/
--

-- CLARIN identity linking: alias table used as the *only* source netid-based
-- login resolution consults.

CREATE SEQUENCE eperson_netid_alias_id_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE eperson_netid_alias (
    eperson_netid_alias_id INTEGER NOT NULL DEFAULT NEXTVAL('eperson_netid_alias_id_seq') PRIMARY KEY,
    eperson_id   UUID NOT NULL,
    netid        VARCHAR(256) NOT NULL UNIQUE,
    source       VARCHAR(64)  NOT NULL,
    created_by   UUID,
    created_date TIMESTAMP NOT NULL,
    FOREIGN KEY (eperson_id) REFERENCES eperson(uuid),
    FOREIGN KEY (created_by) REFERENCES eperson(uuid) ON DELETE SET NULL
);

CREATE INDEX eperson_netid_alias_eperson_id_idx ON eperson_netid_alias(eperson_id);

-- Seed the alias table from every existing netid so existing logins are
-- unaffected once resolution moves to alias-only lookup.
INSERT INTO eperson_netid_alias (eperson_netid_alias_id, eperson_id, netid, source, created_date)
SELECT NEXTVAL('eperson_netid_alias_id_seq'), uuid, netid, 'migration', CURRENT_TIMESTAMP
FROM eperson
WHERE netid IS NOT NULL;
