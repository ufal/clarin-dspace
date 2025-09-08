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
CREATE TABLE personal_access_token
(
    eperson_id UUID NOT NULL,
    shared_secret VARCHAR(50) NOT NULL,
    CONSTRAINT personal_access_token_pkey PRIMARY KEY (eperson_id),
    CONSTRAINT personal_access_token_eperson_id_fkey FOREIGN KEY (eperson_id) REFERENCES eperson (uuid) ON DELETE CASCADE
);
