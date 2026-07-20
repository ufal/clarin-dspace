--
-- The contents of this file are subject to the license and copyright
-- detailed in the LICENSE and NOTICE files at the root of the source
-- tree and available online at
--
-- http://www.dspace.org/license/
--

CREATE SEQUENCE report_result_id_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE report_result (
    report_result_id INTEGER NOT NULL DEFAULT NEXTVAL('report_result_id_seq') PRIMARY KEY,
    type VARCHAR(256),
    value TEXT,
    executor_id UUID,
    args TEXT,
    last_modified TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (executor_id) REFERENCES EPerson(uuid) ON DELETE SET NULL
);
