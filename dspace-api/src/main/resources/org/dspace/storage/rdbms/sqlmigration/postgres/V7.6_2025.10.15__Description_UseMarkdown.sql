--
-- The contents of this file are subject to the license and copyright
-- detailed in the LICENSE and NOTICE files at the root of the source
-- tree and available online at
--
-- http://www.dspace.org/license/
--

-----------------------------------------------------------------------------------
-- INSERT new local.description.usemarkdown metadata property (if not exists already)
-----------------------------------------------------------------------------------
INSERT INTO metadatafieldregistry (metadata_field_id, metadata_schema_id, element, qualifier, scope_note)
SELECT nextval('metadatafieldregistry_seq'), msr.metadata_schema_id, 'description', 'usemarkdown',
       'true when dc.description metadata value should be rendered as Markdown formatted text'
FROM metadataschemaregistry msr WHERE msr.short_id='local'
    AND NOT EXISTS (SELECT 1 from metadatafieldregistry mfr WHERE mfr.element='description' AND mfr.qualifier='usemarkdown');
