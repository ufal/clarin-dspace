--
-- The contents of this file are subject to the license and copyright
-- detailed in the LICENSE and NOTICE files at the root of the source
-- tree and available online at
--
-- http://www.dspace.org/license/
--

-----------------------------------------------------------------------------------
-- Create table for clarin token entity
-----------------------------------------------------------------------------------
CREATE SEQUENCE clarin_token_id_seq;

CREATE TABLE clarin_token
(
    id INTEGER PRIMARY KEY,
    eperson_id UUID NOT NULL,
    sign_key VARCHAR2(50) NOT NULL,
    CONSTRAINT clarin_token_eperson_id_fkey FOREIGN KEY (eperson_id) REFERENCES eperson (uuid) ON DELETE CASCADE
);
