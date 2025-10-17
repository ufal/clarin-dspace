--
-- The contents of this file are subject to the license and copyright
-- detailed in the LICENSE and NOTICE files at the root of the source
-- tree and available online at
--
-- http://www.dspace.org/license/
--

-----------------------------------------------------------------------------------
-- Create table for MatomoReportSubscription
-----------------------------------------------------------------------------------

CREATE SEQUENCE matomo_report_registry_id_seq;

CREATE TABLE matomo_report_registry
(
    id INTEGER NOT NULL,
    eperson_id UUID NOT NULL,
    item_id UUID NOT NULL,
    CONSTRAINT matomo_report_registry_pkey PRIMARY KEY (id),
    CONSTRAINT matomo_report_registry_eperson_id_fkey FOREIGN KEY (eperson_id) REFERENCES eperson (uuid) ON DELETE CASCADE,
    CONSTRAINT matomo_report_registry_item_id_fkey FOREIGN KEY (item_id) REFERENCES item (uuid) ON DELETE CASCADE,
    CONSTRAINT matomo_report_registry_unique UNIQUE(eperson_id, item_id)
);