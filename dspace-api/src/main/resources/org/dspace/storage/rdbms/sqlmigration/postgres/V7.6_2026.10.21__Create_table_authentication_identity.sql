--
-- The contents of this file are subject to the license and copyright
-- detailed in the LICENSE and NOTICE files at the root of the source
-- tree and available online at
--
-- http://www.dspace.org/license/
--

-- ufal/clarin-dspace#1140

-----------------------------------------------------------------------------------
-- Create table for external authentication identities (OIDC, may be later Shibboleth)
-----------------------------------------------------------------------------------
CREATE SEQUENCE IF NOT EXISTS authentication_identity_id_seq;

CREATE TABLE IF NOT EXISTS authentication_identity
(
    id          INTEGER PRIMARY KEY,
    auth_method VARCHAR(32)  NOT NULL,
    provider    VARCHAR(256) NOT NULL,
    user_id     VARCHAR(256) NOT NULL,
    eperson_id  UUID         NOT NULL,
    created     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_login  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT authentication_identity_eperson_id_fkey
        FOREIGN KEY (eperson_id) REFERENCES eperson (uuid) ON DELETE CASCADE,
    CONSTRAINT authentication_identity_unique
        UNIQUE (auth_method, provider, user_id),
    -- an EPerson must not be linked to more than one identity of the same auth_method/provider; also
    -- guards against two concurrent first logins with different user_id linking to the same EPerson
    CONSTRAINT authentication_identity_eperson_auth_method_provider_unique
        UNIQUE (eperson_id, auth_method, provider)
);

CREATE INDEX IF NOT EXISTS authentication_identity_eperson_id_idx ON authentication_identity (eperson_id);
