--
-- The contents of this file are subject to the license and copyright
-- detailed in the LICENSE and NOTICE files at the root of the source
-- tree and available online at
--
-- http://www.dspace.org/license/
--

-----------------------------------------------------------------------------------
-- Create table for personal access token entity
-----------------------------------------------------------------------------------
CREATE SEQUENCE personal_access_token_id_seq;

CREATE TABLE personal_access_token
(
    id INTEGER PRIMARY KEY,
    eperson_id UUID NOT NULL UNIQUE,
    mac_secret VARCHAR(50) NOT NULL,
    aes_key VARCHAR(50) NOT NULL,
    CONSTRAINT personal_access_token_eperson_id_fkey FOREIGN KEY (eperson_id) REFERENCES eperson (uuid) ON DELETE CASCADE
);
