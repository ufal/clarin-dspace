--
-- The contents of this file are subject to the license and copyright
-- detailed in the LICENSE and NOTICE files at the root of the source
-- tree and available online at
--
-- http://www.dspace.org/license/
--

CREATE TABLE report_result (
    report_result_id integer NOT NULL PRIMARY KEY,
    type varchar(256),
    value TEXT,
    executor_id UUID REFERENCES EPerson(uuid) ON DELETE SET NULL,
    args TEXT,
    last_modified TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

--
-- Name: report_result_id_seq; Type: SEQUENCE; Schema: public; Owner: dspace
--

CREATE SEQUENCE report_result_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MAXVALUE
    NO MINVALUE
    CACHE 1;

ALTER TABLE report_result
  ALTER COLUMN report_result_id
    SET DEFAULT nextval('report_result_id_seq');