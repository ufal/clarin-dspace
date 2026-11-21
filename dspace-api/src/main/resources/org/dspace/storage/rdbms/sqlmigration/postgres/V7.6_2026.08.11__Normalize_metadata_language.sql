--
-- The contents of this file are subject to the license and copyright
-- detailed in the LICENSE and NOTICE files at the root of the source
-- tree and available online at
--
-- http://www.dspace.org/license/
--

-- ufal/clarin-dspace#1406
--
-- Metadata rows written before the 7.6.5 upgrade carry text_lang = '*' (Item.ANY), because the
-- language argument of addMetadata() was passed through verbatim. Since 7.6.5
-- MetadataValue.setLanguage() normalizes '*' -> NULL on write, so this migration only repairs
-- pre-existing rows; no code path can create new ones.
--
-- Stale '*' rows break the metadata-export -> metadata-import round trip (the export emits
-- rejected [*] headers) and make OAI store <element name="*"/>, from which oai_openaire emits an
-- invalid xml:lang="*".
--
-- Only '*' is normalized. Empty-string languages are legal and round-trip correctly - leave them.

-- Step 1 must run before step 2: it uses the '*' marker to find the affected items.
-- Bumping last_modified is what makes the reindex happen without a forced full rebuild:
-- `dspace oai import` selects items with last_modified > watermark, and `dspace index-discovery`
-- (no flags) reindexes documents whose search.lastindexed predates last_modified.
UPDATE item SET last_modified = CURRENT_TIMESTAMP
 WHERE uuid IN (SELECT dspace_object_id FROM metadatavalue WHERE text_lang = '*');

-- Step 2 is deliberately not restricted to items. Rows on collections, communities and
-- bitstreams are normalized too and need no reindex: those tables have no last_modified column,
-- they are not in OAI or in metadata-export, and no indexing path reads the language of a
-- non-item DSO (SolrServiceMetadataBrowseIndexingPlugin bails on non-items, and the container
-- index factories never call getLanguage()).
UPDATE metadatavalue SET text_lang = NULL WHERE text_lang = '*';
