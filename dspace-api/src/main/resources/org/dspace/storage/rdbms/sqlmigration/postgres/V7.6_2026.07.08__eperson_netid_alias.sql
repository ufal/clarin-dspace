--
-- The contents of this file are subject to the license and copyright
-- detailed in the LICENSE and NOTICE files at the root of the source
-- tree and available online at
--
-- http://www.dspace.org/license/
--

-- CLARIN identity linking: alias table used as the *only* source netid-based
-- login resolution consults.

CREATE TABLE eperson_netid_alias (
    eperson_netid_alias_id integer NOT NULL PRIMARY KEY,
    eperson_id   UUID NOT NULL REFERENCES eperson(uuid),
    netid        VARCHAR(256) NOT NULL UNIQUE,
    source       VARCHAR(64)  NOT NULL,
    created_by   UUID REFERENCES eperson(uuid) ON DELETE SET NULL,
    created_date TIMESTAMP NOT NULL
);

CREATE INDEX eperson_netid_alias_eperson_id_idx ON eperson_netid_alias(eperson_id);

CREATE SEQUENCE eperson_netid_alias_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MAXVALUE
    NO MINVALUE
    CACHE 1;

ALTER TABLE eperson_netid_alias
  ALTER COLUMN eperson_netid_alias_id
    SET DEFAULT nextval('eperson_netid_alias_id_seq');

-- Seed the alias table from every existing netid so existing logins are
-- unaffected once resolution moves to alias-only lookup.
INSERT INTO eperson_netid_alias (eperson_netid_alias_id, eperson_id, netid, source, created_date)
SELECT nextval('eperson_netid_alias_id_seq'), uuid, netid, 'migration', CURRENT_TIMESTAMP
FROM eperson
WHERE netid IS NOT NULL;
