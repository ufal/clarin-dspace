# CLARIN-DSpace v9 — local full-stack runbook (compose project `clarinv9`)

Canonical build/deploy recipe for the local CLARIN v9 stack. Ports: FE `:14000`,
BE REST `:18080` (`/server`), BE debug `:18000`, Postgres `:15432`, Solr `:18983`.
Containers: `clarinv9-dspace`, `clarinv9-dspacedb` (postgres:15, db/user `dspace`),
`clarinv9-dspacesolr`. Admin login: `dspace.admin.dev@dataquest.sk` / `admin`.

All docker compose commands run from the `DSpace/` repo root with:

```
COMPOSE_PROJECT_NAME=clarinv9 docker compose -f docker-compose.yml -f docker-compose.clarinv9.yml <cmd>
```

## First bring-up / restore after a Docker wipe

Follow `../_saved/RESTORE.md` (image tar + migrated dev-5 dump + admin creation).
Summary: `docker load -i ../_saved/clarinv9_be_image.tar`, `up -d dspacedb dspacesolr`,
load `../_saved/clarinv9_migrated_v9.sql` into `clarinv9-dspacedb` via `psql -U dspace -d dspace`,
`up -d dspace` (env `dspace__P__name="LINDAT/CLARIAH-CZ digital library at the Institute of Formal
and Applied Linguistics (ÚFAL)"`), then reindex + oai import (below), then serve the FE.

## Which change class needs what

### Config-only change (`dspace/config/**`) — NO rebuild
`/dspace/config` inside `clarinv9-dspace` is a **bind mount of the repo's `dspace/config`**.
Edit in the repo, then:
```
docker restart clarinv9-dspace
```
Success signal: `curl -s http://localhost:18080/server/api` returns 200 within ~2 min.

### Java change — rebuild the boot jar + refresh CLI jars
The webapp runs from `/dspace/webapps/server-boot.jar`; the in-container `dspace` CLI
uses `/dspace/lib/*.jar` (a DIFFERENT classpath — keep both in sync or the CLI breaks
on new Spring beans).
```
# 1. install changed modules (use `clean` if an IDE compiled into target/ - see gotcha below)
mvn -q -pl dspace-api install -DskipTests
mvn -q -pl dspace-oai install -DskipTests            # if dspace-oai changed
mvn -q -pl dspace-server-webapp install -DskipTests
# 2. repackage the Spring Boot jar
mvn -q -pl dspace/modules/server-boot clean install -DskipTests -Dcheckstyle.skip
# 3. deploy (relative host paths; MSYS_NO_PATHCONV=1 for the container-side path)
MSYS_NO_PATHCONV=1 docker cp dspace/modules/server-boot/target/server-boot-9.3.jar clarinv9-dspace:/dspace/webapps/server-boot.jar
MSYS_NO_PATHCONV=1 docker cp dspace-api/target/dspace-api-9.3.jar clarinv9-dspace:/dspace/lib/dspace-api-9.3.jar
MSYS_NO_PATHCONV=1 docker cp dspace-oai/target/dspace-oai-9.3.jar clarinv9-dspace:/dspace/lib/dspace-oai-9.3.jar
# any NEW third-party dependency must also be copied into /dspace/lib (e.g. matomo-java-tracker*.jar)
docker restart clarinv9-dspace
```
Success signals: `/server/api` 200; `dspace version` inside the container prints `9.*`;
`MSYS_NO_PATHCONV=1 docker exec clarinv9-dspace /dspace/bin/dspace dsprop -p matomo.track.enabled`
exits 0 (proves the CLI classpath is consistent).

### Full image rebuild (Dockerfile / base image change)
Snapshot first (`pg_dump` per `../_saved/RESTORE.md`), then
`... build dspace && ... up -d --no-deps dspace`, re-set `dspace__P__name`,
then re-run the reindex + oai import below.

## Post-(re)start operational steps

```
# Discovery reindex (needed after fresh Solr volume or schema change)
MSYS_NO_PATHCONV=1 docker exec clarinv9-dspace /dspace/bin/dspace index-discovery -b
# OAI core (needed after fresh Solr volume, crosswalk/format change, or ItemUtils/XOAI change)
MSYS_NO_PATHCONV=1 docker exec clarinv9-dspace /dspace/bin/dspace oai import -c
```
NOTE: the `oai import` log line `Total: N items` is an incremental counter, NOT the core size;
verify with `curl -s 'http://localhost:18983/solr/oai/select?q=*:*&rows=0'` (expect numFound ≈ 2950
on the dev-5 dataset).

## Frontend

Always production build (`NODE_ENV=production`), otherwise ALL i18n renders as raw keys:
```
cd ../dspace-angular
npm run build:prod
MSYS_NO_PATHCONV=1 DSPACE_REST_SSL=false DSPACE_REST_HOST=localhost DSPACE_REST_PORT=18080 \
  DSPACE_REST_NAMESPACE=/server DSPACE_UI_SSL=false DSPACE_UI_HOST=localhost \
  DSPACE_UI_PORT=14000 DSPACE_UI_NAMESPACE=/ node dist/server/main.js
```
UI port MUST be 14000 (BE CORS + dspace.ui.url expect it). Success signals:
`curl -s http://localhost:14000/` returns 200 AND
`curl -s http://localhost:14000/ | grep -c 'menu.section.browse_global'` is 0 (no raw i18n keys).

## Integration-test environment

DSpace ITs read config from the parent `testEnvironment.zip`, NOT the live config. After
changing `dspace/config/**` or `*/src/test/data/dspaceFolder/**`, refresh it with the
module set in the reactor (a bare `-N` build produces a stale/empty zip):
```
mvn install -DskipTests -Dcheckstyle.skip -pl .,dspace,dspace-api,dspace-server-webapp
```

## Gotchas

- **IDE-compiled classes poison jars**: VS Code Java writes ECJ classes into `target/classes`;
  Maven may skip javac and pack them ("Unresolved compilation problems" at runtime).
  Use `clean install` on modules you edited in the IDE before packaging the boot jar.
- **docker cp path mangling (Git Bash)**: use relative host paths + `MSYS_NO_PATHCONV=1`;
  absolute `/c/...` host paths get mangled to `C:\c\...` when path conversion is disabled.
- **Assetstore**: the local assetstore volume does not contain dev-5 files. Positive
  byte-delivery of existing bitstreams cannot be verified live (`/content` 500 on file read);
  it is proven by ITs that deposit into their own test assetstore. Live license-gate checks
  are negative gates (gated `/content`/`/api/authrn` → 401).
- **dev-5 assetstore import is env-gated** on the availability of the production assetstore
  tarball; when available: `docker cp <assetstore-dir> clarinv9-dspace:/dspace/assetstore`
  + `chown -R dspace` + `dspace checker`.
