--
-- The contents of this file are subject to the license and copyright
-- detailed in the LICENSE and NOTICE files at the root of the source
-- tree and available online at
--
-- http://www.dspace.org/license/
--

-- ===================================================================
-- PERFORMANCE INDEXES
-- ===================================================================

--
-- Index to speed up queries filtering previewcontent by bitstream_id,
-- used in hasPreview() and getPreview() JOIN with bitstream table.
--
CREATE INDEX idx_previewcontent_bitstream_id
ON previewcontent (bitstream_id);

--
-- Index to optimize NOT EXISTS subquery in getPreview(),
-- checking for existence of child_id in preview2preview.
--
CREATE INDEX idx_preview2preview_child_id
ON preview2preview (child_id);