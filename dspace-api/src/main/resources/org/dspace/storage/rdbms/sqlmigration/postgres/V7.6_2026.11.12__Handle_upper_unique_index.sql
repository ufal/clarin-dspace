--
-- The contents of this file are subject to the license and copyright
-- detailed in the LICENSE and NOTICE files at the root of the source
-- tree and available online at
--
-- http://www.dspace.org/license/
--

-----------------------------------------------------------------------------------
-- The Handle server (case_sensitive = "no" in config.dct) uppercases handles before
-- passing them to HandlePlugin, which therefore looks them up by upper(handle).
-- Index that lookup and forbid handles that differ only by case.
-- If this fails, find the conflicting rows with:
--   SELECT upper(handle), count(*) FROM handle GROUP BY 1 HAVING count(*) > 1;
-----------------------------------------------------------------------------------
CREATE UNIQUE INDEX IF NOT EXISTS handle_handle_upper_idx ON handle (upper(handle));
