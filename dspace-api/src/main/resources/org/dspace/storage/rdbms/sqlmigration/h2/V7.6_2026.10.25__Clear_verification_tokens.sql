--
-- The contents of this file are subject to the license and copyright
-- detailed in the LICENSE and NOTICE files at the root of the source
-- tree and available online at
--
-- http://www.dspace.org/license/
--

-----------------------------------------------------------------------------------
-- Shibboleth autoregistration rework: rows written before
-- the change carry e-mailed tokens whose e-mail was never tied to the netid owner.
-- Drop every pending row; affected users simply repeat the Shibboleth login.
-----------------------------------------------------------------------------------
DELETE FROM verification_token;
