# CLARIN-DSpace v9 — Master Acceptance Checklist

> The upgrade may be declared DONE only when EVERY box below passes (or is explicitly marked env-gated with its covering IT named). Run probes against the local stack (FE :14000, BE :18080) and CI. Generated 2026-07-08; companion to CLARIN_V9_REMEDIATION_PLAN.md.

Hardening rules: dataset-pinned values assume the dev-5 dump; pair every negative gate with a positive probe; no subjective criteria.

## BE — item file listing, file preview, license download gate, restricted-access (C1, C2, C3 + preview-content, checksum, allzip/ZIP chain)

### BE-BASE-0 — Commit the untracked C1 metadata-bitstream REST tier to the PR #1339 branch (concurrency-guarded re-baseline) `[critical]`
- [ ] `git ls-files dspace-server-webapp/src/main/java/org/dspace/app/rest/repository/MetadataBitstreamRestRepository.java` prints the path (no longer untracked); same for all 6 files
- [ ] `git log -1 --oneline -- dspace-server-webapp/src/main/java/org/dspace/app/rest/BitstreamByHandleRestController.java` returns a commit reachable from HEAD of ufal/clarin-dspace-upgrade-v9
- [ ] `git status --porcelain` shows none of the 6 C1 paths with '??'
- [ ] Post-commit build still green: `mvn -q -pl dspace-server-webapp -am -DskipTests compile` exits 0
  - verify: `git ls-files | grep -c MetadataBitstreamWrapperRest.java (expect 1); git status --porcelain | grep -c MetadataBitstream (expect 0).`

### BE-GATE-1 — C2: wire the license/token download gate into AuthorizeServiceImpl (5-arg authorizeAction overload) `[critical]`
- [ ] `curl -s -o /dev/null -w '%{http_code}' http://localhost:18080/server/api/core/bitstreams/faf919b4-c68f-48f4-8bbb-0ee61f4c0ddb/content` returns 401 (currently 500)
- [ ] byHandle flip: `curl -s 'http://localhost:18080/server/api/core/metadatabitstreams/search/byHandle?handle=11858/00-097C-0000-0023-119E-8' | jq -r '._embedded.metadatabitstreams[] | select(.id=="faf919b4-c68f-48f4-8bbb-0ee61f4c0ddb").href'` now contains `isAllowed=n` (was isAllowed=y)
- [ ] Regression (open bitstream unchanged): `curl -s -o /dev/null -w '%{http_code}' http://localhost:18080/server/api/authrn/f15230ca-e4bf-4df9-b49a-9e80614aa871` returns 200 (confirmation=0, auto-allowed)
- [ ] Regression (gated advisory unchanged): `curl -s -o /dev/null -w '%{http_code}' http://localhost:18080/server/api/authrn/faf919b4-c68f-48f4-8bbb-0ee61f4c0ddb` returns 401
- [ ] Grep: authorizeAction(Context,EPerson,DSpaceObject,int,boolean) body contains a call to authorizationBitstreamUtils.authorizeBitstream guarded by `Constants.BITSTREAM && action != Constants.WRITE && !isAdmin`
- [ ] Named test passes: `mvn -pl dspace-server-webapp test -Dtest=AuthorizationRestControllerIT` BUILD SUCCESS, 0 failures (IT ported in BE-TEST-6)
- [ ] Item file list still loads (does not 500) for handle 11858/00-097C-0000-0023-119E-8: byHandle still returns 200 (canPreview handling already swallows MissingLicenseAgreementException)
  - verify: `curl the two /content + two /authrn probes above; jq the byHandle href for isAllowed=n; then mvn -pl dspace-server-webapp test -Dtest=AuthorizationRestControllerIT.`

### BE-REST-2 — C3: add null guard in IdentifierRestRepository.getDSObyIdentifier (restricted item -> 401, not 500 NPE) `[critical]`
- [ ] Grep: getDSObyIdentifier contains `if (dsor == null)` calling response.sendError(HttpServletResponse.SC_UNAUTHORIZED, ...) positioned after the `converter.toRest` call and before `linkTo(`
- [ ] IT-ONLY runtime proof (not curl-verifiable on this dataset — public handle 11858/00-097C-... returns 302 because toRest is non-null): add a test method to IdentifierRestRepositoryIT that builds a private item (READ removed for Anonymous) and asserts anonymous GET /api/pid/find?id=<that handle> returns 401 and NOT 500
- [ ] Named test passes: `mvn -pl dspace-server-webapp test -Dtest=IdentifierRestRepositoryIT` BUILD SUCCESS, 0 failures
  - verify: `grep 'dsor == null' dspace-server-webapp/src/main/java/org/dspace/app/rest/repository/IdentifierRestRepository.java; mvn -pl dspace-server-webapp test -Dtest=IdentifierRestRepositoryIT (new private-item method must assert 401).`

### BE-FILES-3 — C1 verify: metadata-bitstream byHandle file-listing is committed, correct, and covered by an IT `[critical]`
- [ ] `curl -s -o /dev/null -w '%{http_code}' 'http://localhost:18080/server/api/core/metadatabitstreams/search/byHandle?handle=11858/00-097C-0000-0023-119E-8'` returns 200
- [ ] `... | jq '._embedded.metadatabitstreams | length'` returns 2 (syn2005.gz + license.txt)
- [ ] Every element.href matches regex `^/server/api/core/bitstreams/handle/.+\?sequence=[0-9]+&isAllowed=[yn]$` (jq: `[._embedded.metadatabitstreams[].href | test("^/server/api/core/bitstreams/handle/.+\\?sequence=[0-9]+&isAllowed=[yn]$")] | all` == true) — NOTE href starts with the contextPath '/server/api/...' (composePreviewURL prepends request.getContextPath()), not '/api/...'
- [ ] Every element has non-null name, checksum, format (string) and canPreview (boolean): jq `[._embedded.metadatabitstreams[] | (.name and .checksum and .format and (.canPreview|type=="boolean"))] | all` == true
- [ ] Named test passes: `mvn -pl dspace-server-webapp test -Dtest=MetadataBitstreamRestRepositoryIT` BUILD SUCCESS
- [ ] FE: http://localhost:14000/items/db4dff24-ced9-4dac-be81-0df07eddaf0f renders the clarin-files-section with exactly 2 file rows (no perpetual spinner / empty state)
  - verify: `Run the curl+jq assertions above against :18080; mvn -Dtest=MetadataBitstreamRestRepositoryIT; open the FE item page and count file rows.`

### BE-FILES-4 — C1 verify + gate-cover: BitstreamByHandleRestController (download-link target for every file row) `[critical]`
- [ ] Route resolves (not 404): `curl -s -o /dev/null -w '%{http_code}' 'http://localhost:18080/server/api/core/bitstreams/handle/11858/00-097C-0000-0023-119E-8/syn2005.gz'` returns a non-404 status
- [ ] GATE DISCRIMINATOR (requires BE-GATE-1): the same anonymous by-handle GET of the confirmation=1 file syn2005.gz returns 401 (authorization short-circuits BEFORE the file read), distinguishing a working gate from the pre-fix 500
- [ ] Named test passes: `mvn -pl dspace-server-webapp test -Dtest=BitstreamByHandleRestControllerIT` BUILD SUCCESS, covering (a) positive bytes path with a test assetstore -> 200 + body, (b) gated file -> 401 before retrieve
  - verify: `curl the /handle/ path (assert non-404, then assert 401 after BE-GATE-1); mvn -Dtest=BitstreamByHandleRestControllerIT (positive path needs the IT's test assetstore since local prod assetstore is absent).`

### BE-CHK-5 — Port the CLARIN bitstream-checksum REST tier (/api/core/bitstreams/{uuid}/checksum) with the NAME->PLURAL_NAME bean fix `[medium]`
- [ ] `curl -s -o /dev/null -w '%{http_code}' http://localhost:18080/server/api/core/bitstreams/faf919b4-c68f-48f4-8bbb-0ee61f4c0ddb/checksum` returns 200 (currently 404)
- [ ] Response JSON exposes databaseChecksum with the DB md5: `curl -s .../checksum | jq -r '.databaseChecksum.value'` == `8a4605be74aa9ea9d79846c1fba20a33`
- [ ] Grep: BitstreamCheckSumLinkRepository @Component uses `BitstreamRest.PLURAL_NAME` (NOT BitstreamRest.NAME) and imports jakarta.servlet.http.HttpServletRequest + jakarta.annotation.Nullable
- [ ] Grep: BitstreamRest.java @LinkRest set contains `name = BitstreamRest.CHECKSUM` AND still contains ACCESS_STATUS, FORMAT, THUMBNAIL, BUNDLE
- [ ] All 5 checksum classes are tracked: `git ls-files | grep -E 'BitstreamChecksum(Rest|Converter|Resource)?\.java|BitstreamCheckSumLinkRepository' | wc -l` >= 5
- [ ] FE: item page bitstream row's checksum indicator resolves (no console 404 for /checksum on http://localhost:14000/items/db4dff24-ced9-4dac-be81-0df07eddaf0f)
  - verify: `curl+jq the /checksum endpoint (200 + md5 match); grep the @Component/@LinkRest lines; git ls-files count of checksum classes.`

### BE-TEST-6 — Port the 3 missing CLARIN download/gate ITs and extend IdentifierRestRepositoryIT `[major]`
- [ ] Files tracked: `git ls-files | grep -E 'MetadataBitstreamRestRepositoryIT|BitstreamByHandleRestControllerIT|AuthorizationRestControllerIT' | wc -l` == 3
- [ ] `mvn -pl dspace-server-webapp test -Dtest=MetadataBitstreamRestRepositoryIT,BitstreamByHandleRestControllerIT,AuthorizationRestControllerIT,IdentifierRestRepositoryIT` -> BUILD SUCCESS, Tests run > 0, Failures: 0, Errors: 0
- [ ] BitstreamByHandleRestControllerIT includes a gated-file case asserting 401 and an authorized case asserting 200 + body bytes
- [ ] AuthorizationRestControllerIT asserts confirmation-required bitstream -> 401 and auto-allowed -> 200
  - verify: `mvn -pl dspace-server-webapp test -Dtest=MetadataBitstreamRestRepositoryIT,BitstreamByHandleRestControllerIT,AuthorizationRestControllerIT,IdentifierRestRepositoryIT (Surefire report: 0 failures/errors).`

### BE-ZIP-7 — Harden allzip bulk download so a gated item throws BEFORE response headers/bytes commit `[major]`
- [ ] Gated item -> clean refusal, not partial zip: `curl -s -D - -o /dev/null 'http://localhost:18080/server/api/core/items/db4dff24-ced9-4dac-be81-0df07eddaf0f/allzip?handleId=11858/00-097C-0000-0023-119E-8'` returns status 401 or 403, the response Content-Type is NOT application/zip, and there is NO `Content-Disposition: attachment` header
- [ ] Grep: downloadFileZip performs an authorizeAction/authorizeActionBoolean READ pass over the bundle bitstreams that lexically PRECEDES the first `response.setContentType`/`response.setHeader(HttpHeaders.CONTENT_DISPOSITION`/`new ZipArchiveOutputStream(` call
- [ ] New/extended IT (MetadataBitstreamControllerIT): gated item allzip -> 401 with no bytes written; fully-open item allzip (test assetstore) -> 200 with Content-Type application/zip
  - verify: `curl -D - the allzip URL for the gated item and assert status in {401,403} AND no application/zip Content-Type; mvn -pl dspace-server-webapp test -Dtest=MetadataBitstreamControllerIT.`

### BE-PREVIEW-8 — Verify the file-preview CONTENT chain (canPreview/getFilePreviewContent -> fileInfo) via IT; document live population as env-gated `[medium]`
- [ ] Grep: dspace/config/launcher.xml contains a <command name="file-preview"> whose <class> is org.dspace.scripts.filepreview.FilePreview (ported from dtq-dev)
- [ ] IT proof of content chain: a test builds an item with a text bitstream + generates preview, and asserts byHandle response element.fileInfo length >= 1 with non-empty content (named test in MetadataBitstreamRestRepositoryIT or a PreviewContent IT) -> BUILD SUCCESS
- [ ] Documented-deferred note recorded: live localhost previewcontent population (fileInfo:[] today) is env-gated by the un-imported assetstore (same deferral as O-assetstore), NOT a code defect — no live fileInfo assertion is gated on this domain
  - verify: `grep 'file-preview' dspace/config/launcher.xml; mvn -pl dspace-server-webapp test -Dtest=MetadataBitstreamRestRepositoryIT (fileInfo-populated method). Live fileInfo remains empty (expected, env-gated).`

## BE — CLARIN OAI/CMDI/BibTeX crosswalk layer + ref-box export + CMDI upload (C5, S5, I6, O7)

### BE-OAI-1 — Port the 28 CLARIN dspace-oai Java classes (SharedSaxonProcessor + 21 Saxon fns + 5 utils + ColComFilter) and wire them in DSpaceResourceResolver `[critical]`
- [ ] git ls-tree -r --name-only HEAD -- dspace-oai/src/main/java/org/dspace/xoai/services/impl/resources/functions/ | wc -l returns exactly 21
- [ ] git cat-file -e HEAD:dspace-oai/src/main/java/org/dspace/xoai/services/impl/resources/SharedSaxonProcessor.java AND HEAD:dspace-oai/src/main/java/org/dspace/xoai/filter/ColComFilter.java AND all 5 utils under org/dspace/utils/ all exit 0 (present)
- [ ] mvn -q -pl dspace-oai -am -DskipTests compile exits 0
- [ ] mvn -q -pl dspace-oai checkstyle:check exits 0
- [ ] At runtime a GetRecord with a CLARIN prefix produces no 'SharedSaxonProcessor has not been initialized' warning and no net.sf.saxon ClassCastException in the OAI log
  - verify: `cd /c/workspace/clarin-dspace-v9-upgrade/DSpace && git ls-tree -r --name-only HEAD -- dspace-oai/src/main/java/org/dspace/xoai/services/impl/resources/functions/ | wc -l && mvn -q -pl dspace-oai -am -DskipTests compile && echo BUILD_OK`

### BE-OAI-2 — Apply the ItemUtils.java CLARIN delta (lindat bitstream URLs, restrictedAccess/owningCollection/itemId fields, excluded-bundle filtering) `[major]`
- [ ] Pick a handle whose mapped ClarinLicense has NON-EMPTY required_info (verified example 11234/1-3039 = SEND_TOKEN,NAME,EXTRA_EMAIL,REQUIRED_ORGANIZATION; do NOT use 11234/1-3118 whose CC-BY-NC-SA license has empty required_info so restrictedAccess is FALSE). Derive its OAI identifier from a live ListIdentifiers sample (identifier namespace segment must NOT be hardcoded to 'localhost' — oai.identifier.prefix is commented out at HEAD). GetRecord&metadataPrefix=xoai for that identifier contains <field name="restrictedAccess">true</field> and non-empty <field name="itemId"> and <field name="owningCollection">
- [ ] A bitstream url field in the xoai output matches regex /bitstream/[^/]+/[0-9]+/ (lindat form), not the vanilla /bitstreams/{uuid}/download
- [ ] TEXT, THUMBNAIL and SWORD bundles are absent from the xoai bundles element for an item that has them
- [ ] mvn -q -pl dspace-oai -am -DskipTests compile exits 0
  - verify: `H=11234/1-3039; ID=$(curl -s 'http://localhost:18080/server/oai/request?verb=ListIdentifiers&metadataPrefix=oai_dc' | grep -oE '<identifier>[^<]*'$H'</identifier>' | head -1 | sed 's/[<>]/ /g' | awk '{print $2}'); curl -s "http://localhost:18080/server/oai/request?verb=GetRecord&metadataPrefix=xoai&`

### BE-OAI-3 — Apply the XOAI.java local.hidden harvest delta so hidden-but-public items remain OAI-harvestable `[medium]`
- [ ] git show HEAD:dspace-oai/src/main/java/org/dspace/xoai/app/XOAI.java | grep -c isHidden returns >=1
- [ ] mvn -q -pl dspace-oai -am -DskipTests compile exits 0 with the delta applied
- [ ] After BE-OAI-8 re-runs oai import, an item carrying local.hidden=hidden but publicly readable returns a real record (not a status=deleted header) from GetRecord&metadataPrefix=oai_dc for its identifier
  - verify: `cd /c/workspace/clarin-dspace-v9-upgrade/DSpace && git show HEAD:dspace-oai/src/main/java/org/dspace/xoai/app/XOAI.java | grep -n 'isHidden' || echo NOT_YET_PORTED`

### BE-OAI-8 — Populate the (currently empty) OAI Solr core via dspace oai import so all downstream acceptance is provable `[major]`
- [ ] curl -s of the oai Solr core select?q=*:*&rows=0 returns numFound>0
- [ ] curl -s 'http://localhost:18080/server/oai/request?verb=ListIdentifiers&metadataPrefix=oai_dc' returns at least one <identifier> element and no <error code="noRecordsMatch">
- [ ] The dspace oai import command exits 0
  - verify: `curl -s 'http://localhost:18080/server/oai/request?verb=ListIdentifiers&metadataPrefix=oai_dc' | grep -c '<identifier>'`

### BE-OAI-4 — Wire the CLARIN OAI Formats/Filters/Contexts into xoai.xml + oai.cfg + description.xml (THE C5 fix) `[critical]`
- [ ] curl -s http://localhost:18080/server/oai/request?verb=ListMetadataFormats returns HTTP 200 and the body contains every one of <metadataPrefix>cmdi</metadataPrefix>, <metadataPrefix>olac</metadataPrefix>, <metadataPrefix>oai_metasharev2</metadataPrefix>, <metadataPrefix>bibtex</metadataPrefix>, <metadataPrefix>elg</metadataPrefix>
- [ ] For an identifier taken from a live ListIdentifiers sample, GetRecord&metadataPrefix=cmdi returns HTTP 200, body contains the CMDI root (xmlns:cmd="http://www.clarin.eu/cmd/") and contains NO <error code=
- [ ] Same identifier, GetRecord&metadataPrefix=olac returns an <olac:olac> record with no <error code=
- [ ] ListRecords&metadataPrefix=cmdi for the default context does not return HTTP 500 and excludes items in the DH/teaching communities (ColComFilter effective — assert an item known to be in the excluded community is absent)
- [ ] git show HEAD:dspace/config/modules/oai.cfg contains oai.bundle.excluded and still contains the oai.html comment block (v9 feature retained), and does NOT contain oai.description.file.1 (the OLAC repository description is customer/lindat only, see dspace-customers#969)
  - verify: `curl -s 'http://localhost:18080/server/oai/request?verb=ListMetadataFormats' | grep -oE 'metadataPrefix>(cmdi|olac|oai_metasharev2|bibtex|elg|oai_datacite)<' | sort -u`

### BE-OAI-9 — Port the oai_dc.xsl CLARIN crosswalk delta (downloadable_files_count, dc.date.issued-only, broadened bitstream selection) `[medium]`
- [ ] git diff --stat HEAD origin/dtq-dev -- dspace/config/crosswalks/oai/metadataFormats/oai_dc.xsl produces empty output (HEAD now byte-identical to dtq-dev)
- [ ] For an item with >=1 ORIGINAL bitstream, GetRecord&metadataPrefix=oai_dc for its identifier contains a <dc:format>downloadable_files_count: N</dc:format> element with N matching the ORIGINAL bitstream count
- [ ] For an item with both dc.date.issued and dc.date.accessioned, the oai_dc record's <dc:date> emits only the issued value (accessioned value absent)
  - verify: `cd /c/workspace/clarin-dspace-v9-upgrade/DSpace && git diff --stat HEAD origin/dtq-dev -- dspace/config/crosswalks/oai/metadataFormats/oai_dc.xsl`

### BE-OAI-5 — Make ref-box BIBTEX/CMDI citation export return real citations and port its regression IT `[major]`
- [ ] GET http://localhost:18080/server/api/core/refbox/citations?type=bibtex&handle=<realHandle> returns HTTP 200 and a JSON body whose metadata field starts with '@' (a BibTeX entry) and does NOT contain 'Unknown metadata format'
- [ ] GET ...?type=cmdi&handle=<realHandle> returns HTTP 200 with metadata containing the CMDI root (cmd:CMD or xmlns:cmd)
- [ ] ClarinRefBoxControllerIT exists at dspace-server-webapp/src/test/java/org/dspace/app/rest/ClarinRefBoxControllerIT.java and mvn -q -pl dspace-server-webapp -Dtest=ClarinRefBoxControllerIT test passes (exit 0)
  - verify: `curl -s 'http://localhost:18080/server/api/core/refbox/citations?type=bibtex' -G --data-urlencode 'handle=11234/1-3039' | grep -c 'Unknown metadata format'`

### BE-OAI-6 — Port CMDIRestController (/cmdi endpoint) and jakarta-migrate it `[medium]`
- [ ] git cat-file -e HEAD:dspace-server-webapp/src/main/java/org/dspace/app/rest/CMDIRestController.java exits 0
- [ ] git show HEAD:dspace-server-webapp/src/main/java/org/dspace/app/rest/CMDIRestController.java | grep -c 'javax.servlet' returns 0 (fully jakarta-migrated)
- [ ] mvn -q -pl dspace-server-webapp -am -DskipTests compile exits 0
- [ ] GET http://localhost:18080/server/cmdi/oai-metadata for a valid handle/identifier returns HTTP 200 with CMDI content and no stack trace
  - verify: `cd /c/workspace/clarin-dspace-v9-upgrade/DSpace && git cat-file -e HEAD:dspace-server-webapp/src/main/java/org/dspace/app/rest/CMDIRestController.java && echo PRESENT || echo MISSING`

### BE-OAI-7 — Port the CMDI-upload validation hook: CMDIFileBundleMaintainer + the MetadataValidation.java local.hasCMDI delta `[medium]`
- [ ] git cat-file -e HEAD:dspace-server-webapp/src/main/java/org/dspace/app/rest/submit/step/validation/CMDIFileBundleMaintainer.java exits 0
- [ ] git show HEAD:dspace-server-webapp/src/main/java/org/dspace/app/rest/submit/step/validation/MetadataValidation.java | grep -c 'local.hasCMDI' returns >=1
- [ ] mvn -q -pl dspace-server-webapp -Dtest=CMDIFileBundleMaintainerTest test passes (exit 0)
- [ ] A submission that sets local.hasCMDI and uploads a CMDI file has the file routed into the CMDI bundle (assert via the validator test, which is the machine-checkable proxy)
  - verify: `cd /c/workspace/clarin-dspace-v9-upgrade/DSpace && git cat-file -e HEAD:dspace-server-webapp/src/main/java/org/dspace/app/rest/submit/step/validation/CMDIFileBundleMaintainer.java && git show HEAD:dspace-server-webapp/src/main/java/org/dspace/app/rest/submit/step/validation/MetadataValidation.java |`

### BE-OAI-10 — Apply the DSpaceOAIDataProvider.java CLARIN delta (unhandled-exception log wrapper + Matomo OAI tracking) v9-safely `[medium]`
- [ ] git cat-file -e HEAD:dspace-api/src/main/java/org/dspace/app/statistics/clarin/ClarinMatomoOAITracker.java AND HEAD:dspace-api/src/main/java/org/dspace/app/statistics/clarin/ClarinMatomoTracker.java exit 0 (present)
- [ ] git show HEAD:dspace-oai/src/main/java/org/dspace/xoai/controller/DSpaceOAIDataProvider.java: grep -c 'javax.servlet' returns 0, grep -c 'jakarta.servlet' returns >=1, grep -c 'setUpHTMLTransformerFactory' returns >=1 (v9 HTML feature retained), grep -c 'matomoOAITracker.trackOAIStatistics' returns >=1, and grep -c 'catch (Exception e)' returns >=1
- [ ] mvn -q -pl dspace-oai -am -DskipTests compile exits 0
- [ ] With matomo.track.enabled=false, an OAI request returns HTTP 200 and produces no Matomo NPE/error in the log (tracker path skipped)
  - verify: `cd /c/workspace/clarin-dspace-v9-upgrade/DSpace && git show HEAD:dspace-oai/src/main/java/org/dspace/xoai/controller/DSpaceOAIDataProvider.java | grep -Ec 'matomoOAITracker.trackOAIStatistics|catch \(Exception e\)'`

## BE — submission steps, complex fields, autocomplete, ACL, registries, licenses config

### BE-SUB-1 — Port submission-forms parsing pipeline (DCInput/DCInputsReader/DCInputSet/SubmissionFormConverter/SubmissionFormFieldRest) — the linchpin `[critical]`
- [ ] mvn -q -pl dspace-api,dspace-server-webapp -am install -DskipTests exits 0
- [ ] mvn -pl dspace-api test -Dtest=DCInputTest passes (port dspace-api/src/test/java/org/dspace/app/util/DCInputTest.java from origin/dtq-dev; it constructs DCInput.ComplexDefinition directly — self-contained POJO test, no live stack required)
- [ ] Against the already-ported _cs form (webui.supported.locales=en,cs confirmed at dspace.cfg:1763, so Accept-Language:cs selects submission-forms_cs.xml via SubmissionFormRestRepository's Map<Locale,DCInputsReader>): curl -H 'Authorization: Bearer <adminJWT>' -H 'Accept-Language: cs' http://localhost:18080/server/api/config/submissionforms/traditionalpageone returns HTTP 200, and jq '[.rows[].fields[]|select(.complexDefinition!=null)]|length' >= 1 with at least one complexDefinition JSON string containing the substrings 'givenname' and 'affiliation', and jq '[.rows[].fields[]|select(.autocompleteCustom!=null and (.autocompleteCustom|contains("solr")))]|length' >= 1
- [ ] ACL read-deny skip proven on the _cs form: the fields carrying <acl> read-deny (local.hidden via hidden_list at _cs line 155-158, local.hasCMDI via hasCMDI_checkbox at line 169-172) are ABSENT from .rows[].fields[].selectableMetadata[].metadata when the request uses a NON-admin/anonymous context, and PRESENT with an admin JWT (SubmissionFormConverter.isInputAuthorized skip)
  - verify: `mvn -pl dspace-api test -Dtest=DCInputTest ; curl -H 'Authorization: Bearer <adminJWT>' -H 'Accept-Language: cs' http://localhost:18080/server/api/config/submissionforms/traditionalpageone | jq '.rows[].fields[] | {complexDefinition, autocompleteCustom}'`

### BE-SUB-2 — Merge CLARIN DescribeStep + MetadataValidation (+ CMDIFileBundleMaintainer) onto v9-reworked type-bind `[major]`
- [ ] Backend compiles (CMDIFileBundleMaintainer resolves MetadataValidation:188) and boots with no BeanCreationException/DCInputsReaderException in the server log
- [ ] Complex partial-value rejected with the EXACT key and path: POST /server/api/submission/workspaceitems?owningCollection=<coll>; PATCH add ONLY the givenname sub-value of the required complex field local.contact.person (leave surname/email/affiliation empty); GET /server/api/submission/workspaceitems/<id> returns a .errors[] entry where .message == 'error.validation.required' AND some .paths[] element matches the regex '^/sections/[^/]+/local\.contact\.person(/.*)?$'; after PATCHing all required sub-values that error entry is absent
- [ ] Type-bind '=>' drives off edm.type via the exact call sequence: in a collection whose form has a required field bound <type-bind>Text</type-bind> reading edm.type through submit.type-bind.field=...,dc.language.iso=>edm.type — PATCH add edm.type='Text' leaving the bound field empty then GET the workspaceitem shows .errors[] with message 'error.validation.required' for that bound field; PATCH change edm.type to 'Image' and re-GET shows that error gone (proving the bind reads edm.type, not dc.type). Cross-checked by named test SubmissionFormsControllerIT#findFieldWithTypeBindConfig
- [ ] Regression/coverage: mvn -pl dspace-server-webapp test -Dtest=SubmissionFormsControllerIT,ClarinWorkspaceItemRestRepositoryIT,ClarinWorkflowItemRestRepositoryIT all green (port ClarinWorkspaceItemRestRepositoryIT + ClarinWorkflowItemRestRepositoryIT from origin/dtq-dev — both ABSENT at HEAD); vanilla mvn -pl dspace-server-webapp test -Dtest=WorkspaceItemRestRepositoryIT still passes (no type-bind regression)
  - verify: `Drive submission via REST (login, POST /api/submission/workspaceitems?owningCollection=..., PATCH describe fields, GET and inspect .errors array); mvn -pl dspace-server-webapp test -Dtest=SubmissionFormsControllerIT,ClarinWorkspaceItemRestRepositoryIT`

### BE-SUB-3 — Port submission-forms.dtd CLARIN grammar `[major]`
- [ ] git show HEAD:dspace/config/submission-forms.dtd contains ALL of the tokens: 'form-complex-definitions', 'complex-definition-ref', 'autocomplete-custom', 'default-value', '<!ELEMENT acl'
- [ ] git diff HEAD origin/dtq-dev -- dspace/config/submission-forms.dtd prints 0 lines
- [ ] xmllint --noout --dtdvalid dspace/config/submission-forms.dtd dspace/config/submission-forms_cs.xml exits 0 (no undefined-element/attribute errors)
  - verify: `grep -E 'form-complex-definitions|complex-definition-ref|autocomplete-custom|ELEMENT acl' dspace/config/submission-forms.dtd ; xmllint --noout --dtdvalid dspace/config/submission-forms.dtd dspace/config/submission-forms_cs.xml`

### BE-SUB-4 — Port submission-forms.xml (EN) CLARIN content to match already-ported _cs (incl. value-pairs closure) `[critical]`
- [ ] git show HEAD:dspace/config/submission-forms.xml: grep -c 'complex-definition-ref' >= 3 AND grep -c 'autocomplete-custom' >= 5 AND contains '<form-complex-definitions>' AND '<definition name="contact_person">'
- [ ] Value-pairs closure has no dangling reference: the set of names in every <input-type value-pairs-name="X"> is a subset of the set of defined <value-pairs value-pairs-name="X"> in the same file (script: comm -23 <(grep -oE 'value-pairs-name="[^"]+"' referenced-in-input-type|sort -u) <(grep -oE 'value-pairs value-pairs-name="[^"]+"' defined|sort -u) prints nothing); concretely metashare_funding, metashare_sizeunit, edm_types, hasCMDI_checkbox, common_iso_languages, hidden_list all appear as <value-pairs> definitions
- [ ] App boots with NO DCInputsReaderException on submission-forms.xml load (server log clean)
- [ ] curl -H 'Authorization: Bearer <adminJWT>' http://localhost:18080/server/api/config/submissionforms/specialFields returns HTTP 200 (not 404); and .../traditionalpageone (EN, no Accept-Language) shows the dc.contributor.author field with autocompleteCustom containing 'solr' and the local.contact.person field with complexDefinition JSON listing inputs givenname/surname/email/affiliation
  - verify: `grep -c complex-definition-ref dspace/config/submission-forms.xml ; curl -s -o /dev/null -w '%{http_code}' -H 'Authorization: Bearer <adminJWT>' http://localhost:18080/server/api/config/submissionforms/specialFields`

### BE-SUB-5 — Wire CLARIN submission steps into item-submission.xml `[critical]`
- [ ] git show HEAD:dspace/config/item-submission.xml: grep -c 'clarin-license' >= 2 (step-definition + traditional-process step) AND contains '<step id="specialFields"/>' AND '<processing-class>org.dspace.app.rest.submit.step.ClarinLicenseResourceStep</processing-class>' AND a name-map with submission-name="clariahSubmissions"
- [ ] App boots; curl -H 'Authorization: Bearer <adminJWT>' http://localhost:18080/server/api/config/submissiondefinitions/traditional returns HTTP 200 and its embedded steps include one whose id/type == 'clarin-license' and one == 'specialFields'
- [ ] POST /server/api/submission/workspaceitems?owningCollection=<default-collection>; GET /server/api/submission/workspaceitems/<id> returns .sections containing the keys 'clarin-license' and 'specialFields'
  - verify: `grep -c clarin-license dspace/config/item-submission.xml ; curl -H 'Authorization: Bearer <adminJWT>' http://localhost:18080/server/api/config/submissiondefinitions/traditional | jq '.sections // .steps'`

### BE-SUB-6 — Port autocomplete REST cluster (5 files) WITH the v9 PLURAL_NAME fix + IT/matcher `[medium]`
- [ ] mvn -q -pl dspace-server-webapp -am install -DskipTests exits 0 (proves the abstract getTypePlural is implemented)
- [ ] GET http://localhost:18080/server/api/core/metadatavalues/search/byValue?schema=dc&element=contributor&qualifier=author&searchValue=<substring-of-an-indexed-author> returns HTTP 200 (NOT 404) and jq '._embedded.metadatavalues|length' >= 1 with the matching value present (requires the populated dev-5 Discovery index)
- [ ] GET http://localhost:18080/server/api/core returns a _links object containing a 'metadatavalues' href (endpoint registered at the correct plural)
- [ ] mvn -pl dspace-server-webapp test -Dtest=MetadataValueRestRepositoryIT passes (ported from dtq; the IT builds and indexes its own items)
  - verify: `curl -s -o /dev/null -w '%{http_code}' 'http://localhost:18080/server/api/core/metadatavalues/search/byValue?schema=dc&element=contributor&qualifier=author&searchValue=a' ; mvn -pl dspace-server-webapp test -Dtest=MetadataValueRestRepositoryIT`

### BE-SUB-7 — Wire CMDI file upload into the METADATA bundle (UploadStep/UploadValidation merge) `[medium]`
- [ ] Backend compiles (CMDIFileBundleMaintainer resolves MetadataValidation:188) and UploadStep keeps the v9 primary-bitstream branch (git grep for 'PRIMARY_FLAG_PATTERN' and 'getPrimaryBitstream' in UploadStep still present after the merge)
- [ ] In a collection whose form exposes local.hasCMDI: POST a workspaceitem, PATCH local.hasCMDI=true, and upload a .cmdi file to the item; GET /server/api/submission/workspaceitems/<id> lists that file in the upload section, and the item has exactly 1 bitstream in the METADATA bundle (verify via DB: SELECT count(*) FROM bundle bn JOIN item2bundle ib ON ib.bundle_id=bn.uuid WHERE bn.name='METADATA' AND ib.item_id=<item-uuid> returns >= 1)
- [ ] mvn -pl dspace-server-webapp test -Dtest=ClarinWorkspaceItemRestRepositoryIT passes its CMDI-upload test case (ported from dtq)
  - verify: `Drive REST upload of a .cmdi; then psql -h localhost -p 15432 -c "SELECT count(*) FROM bundle bn JOIN item2bundle ib ON ib.bundle_id=bn.uuid WHERE bn.name='METADATA' AND ib.item_id='<uuid>'"`

### BE-REG-1 — Re-apply CLARIN metadata/bitstream registries (local-types, bitstream-formats, dublin-core-types) `[major]`
- [ ] git show HEAD:dspace/config/registries/local-types.xml | grep -c '<dc-type>' == 20 (matches dtq)
- [ ] git show HEAD:dspace/config/registries/bitstream-formats.xml | grep -c '<bitstream-type>' == 97
- [ ] git show HEAD:dspace/config/registries/dublin-core-types.xml contains a dc-type block with <element>rights</element> and <qualifier>label</qualifier> (dc.rights.label restored)
- [ ] git diff HEAD origin/dtq-dev -- dspace/config/registries/local-types.xml dspace/config/registries/bitstream-formats.xml dspace/config/registries/dublin-core-types.xml prints 0 lines (or only v9-vanilla-additive lines, with no CLARIN field missing)
  - verify: `git show HEAD:dspace/config/registries/local-types.xml | grep -c '<dc-type>' ; git show HEAD:dspace/config/registries/bitstream-formats.xml | grep -c '<bitstream-type>'`

### BE-LIC-1 — Restore CLARIN default.license (Deposit Licence Agreement) — fix EN/CS inconsistency (M9) `[major]`
- [ ] git diff HEAD origin/dtq-dev -- dspace/config/default.license prints 0 lines
- [ ] The first non-blank line of dspace/config/default.license == 'Deposit Licence Agreement' (NOT 'NOTE: PLACE YOUR OWN LICENSE HERE')
  - verify: `git diff HEAD origin/dtq-dev -- dspace/config/default.license ; head -3 dspace/config/default.license`

### BE-EXP-1 — Finish MetadataExposureServiceImpl residual (submitter-sees-own-hidden hook) `[low]`
- [ ] git diff HEAD origin/dtq-dev -- dspace-api/src/main/java/org/dspace/app/util/MetadataExposureServiceImpl.java prints 0 lines
- [ ] mvn -q -pl dspace-api -am install -DskipTests exits 0 (no dangling static-reference compile break)
  - verify: `git diff HEAD origin/dtq-dev -- dspace-api/src/main/java/org/dspace/app/util/MetadataExposureServiceImpl.java ; mvn -q -pl dspace-api -am install -DskipTests`

## BE — auth, user registration, PAT (clarin_token), config wiring, and CLARIN vanilla-file deltas

### BE-CFG-1 — config-definition.xml: load tracked clarin-dspace.cfg (M13) `[major]`
- [ ] `git diff HEAD origin/dtq-dev -- dspace/config/config-definition.xml` prints nothing (files byte-identical)
- [ ] From a clean checkout whose local.cfg does NOT include clarin-dspace.cfg, BE boots and `dspace dsprop -p lr.pid.community.configurations` returns a non-empty value (property comes only from clarin-dspace.cfg)
  - verify: `docker exec clarinv9-dspace /dspace/bin/dspace dsprop -p lr.pid.community.configurations  # non-empty; and: git show HEAD:dspace/config/config-definition.xml | grep -c clarin-dspace.cfg  # must be 1`

### BE-CFG-2 — clarin.token.encryption.secret + max-expiration configured (M7 runtime prerequisite) `[major]`
- [ ] `dspace dsprop -p clarin.token.encryption.secret` returns a value that base64-decodes to 16/24/32 bytes (a valid AES key, e.g. the output of `clarin-token -g`)
- [ ] A token created by the CLI (BE-PAT-2) is subsequently decrypted by ClarinTokenService without throwing (proven by BE-PAT-1 authn round-trip returning 200) — i.e. the configured value is a functional AES key, not merely non-empty
- [ ] `dspace dsprop -p clarin.token.max.expiration.time.in.days` returns a positive integer
  - verify: `docker exec clarinv9-dspace /dspace/bin/dspace clarin-token -g  # capture; set as property; then docker exec clarinv9-dspace /dspace/bin/dspace dsprop -p clarin.token.encryption.secret`

### BE-PAT-2 — Register clarin-token CLI in launcher.xml (M7 CLI) `[major]`
- [ ] `dspace clarin-token -h` exits 0 and prints usage (not 'unknown command / Command line syntax error')
- [ ] `dspace clarin-token -c -e <email> -x 30d` inserts exactly one new row into clarin_token for that eperson_id (row count delta = +1)
  - verify: `before=$(docker exec clarinv9-dspacedb psql -U dspace -d dspace -tAc 'select count(*) from clarin_token'); docker exec clarinv9-dspace /dspace/bin/dspace clarin-token -c -e test@test.cz -x 30d; after=$(docker exec clarinv9-dspacedb psql -U dspace -d dspace -tAc 'select count(*) from clarin_token'); `

### BE-PAT-1 — clarin_token authenticates REST requests (M7 auth branch) `[major]`
- [ ] GET http://localhost:18080/server/api/authn/status with header 'Authorization: Bearer <clarin_token>' returns HTTP 200 and JSON authenticated=true resolving to the token's eperson
- [ ] Ported IT origin/dtq-dev:dspace-server-webapp/src/test/java/org/dspace/app/rest/authorization/ClarinTokenServiceIT.java passes (self-contained: its @Before setUp sets clarin.token.encryption.secret via configurationService.setProperty at line 64, so the IT does NOT require BE-CFG-2; only v9-adapted AbstractControllerIntegrationTest scaffolding, already present in baseline)
  - verify: `TOKEN=$(docker exec clarinv9-dspace /dspace/bin/dspace clarin-token -c -e test@test.cz -x 30d | tail -n1 | tr -d '\r'); curl -s -H "Authorization: Bearer $TOKEN" http://localhost:18080/server/api/authn/status | grep -o '"authenticated":true'`

### BE-AUTH-1 — EPerson admin-create writes a user_registration row (M8 REST hook) `[major]`
- [ ] POST http://localhost:18080/server/api/core/epersons with admin JWT creating eperson foo@test.cz → SELECT organization,confirmation FROM user_registration WHERE email='foo@test.cz' returns exactly one row with organization='Unknown' and confirmation=true and eperson_id equal to the new eperson uuid
- [ ] Ported EPerson-create IT covering user_registration side-effect passes
  - verify: `curl -s -X POST -H "Authorization: Bearer <admin_jwt>" -H 'Content-Type: application/json' -d '{"email":"foo@test.cz","metadata":{"eperson.firstname":[{"value":"Foo"}]},"canLogIn":true}' http://localhost:18080/server/api/core/epersons; docker exec clarinv9-dspacedb psql -U dspace -d dspace -tAc "sel`

### BE-AUTH-2 — CLI create-administrator writes a user_registration row + -o option (M8 CLI hook) `[major]`
- [ ] `dspace create-administrator -e a@t.cz -f A -l T -c en -p pw -o MyOrg` exits 0
- [ ] SELECT organization FROM user_registration WHERE email='a@t.cz' returns exactly one row with organization='MyOrg'
  - verify: `docker exec clarinv9-dspace /dspace/bin/dspace create-administrator -e a@t.cz -f A -l T -c en -p pw -o MyOrg; docker exec clarinv9-dspacedb psql -U dspace -d dspace -tAc "select organization from user_registration where email='a@t.cz'"`

### BE-MISC-1 — EPersonRest DTO welcomeInfo/canEditSubmissionMetadata field + converter + repo setters (feature port) `[medium]`
- [ ] GET /api/core/epersons/<uuid> JSON now contains keys welcomeInfo and canEditSubmissionMetadata
- [ ] PATCH/PUT setting welcomeInfo='hi' persists: SELECT welcome_info FROM eperson WHERE uuid=<uuid> returns 'hi' and the subsequent GET echoes welcomeInfo='hi'
  - verify: `curl -s -H 'Authorization: Bearer <admin_jwt>' http://localhost:18080/server/api/core/epersons/<uuid> | grep -oE '"(welcomeInfo|canEditSubmissionMetadata)"'`

### BE-CFG-3 — WebApplication.java: StrictHttpFirewall bean for encoded-slash handles + ISO/Shibboleth header values (M10) `[major]`
- [ ] A HandleResolver/identifier request whose path contains an encoded slash (e.g. GET /server/api/pid/find?id=<handle-with-%2F>) does NOT return HTTP 400 'The request was rejected because the URL contained a potentially malicious String'
- [ ] git grep -n 'HttpFirewall' HEAD -- dspace-server-webapp/src/main/java/org/dspace/app/rest/WebApplication.java returns the new @Bean
- [ ] OPTIONS preflight on /api/** with Access-Control-Request-Headers: x-recaptcha-token returns that header in Access-Control-Allow-Headers
  - verify: `curl -s -o /dev/null -w '%{http_code}' 'http://localhost:18080/server/api/pid/find?id=<community-handle-with-slash>'  # expect 200/404, never 400 firewall-reject`

### BE-CONV-1 — ItemConverter.updateItemDatesMetadata delta (M10) `[major]`
- [ ] git grep -c updateItemDatesMetadata HEAD -- dspace-server-webapp/src/main/java/org/dspace/app/rest/converter/ItemConverter.java returns >=1
- [ ] GET /api/core/items/<uuid> for an item with an approximate/partial issued date returns the normalized date metadata field that CLARIN populates (present in the JSON metadata map)
  - verify: `git show HEAD:dspace-server-webapp/src/main/java/org/dspace/app/rest/converter/ItemConverter.java | grep -c updateItemDatesMetadata`

### BE-INSTALL-1 — InstallItemServiceImpl.addLanguageNameToMetadata partial-port completion (M10) `[major]`
- [ ] git grep -c addLanguageNameToMetadata HEAD -- dspace-api/src/main/java/org/dspace/content/InstallItemServiceImpl.java returns 2 (declaration + call)
- [ ] After installing an item whose dc.language.iso='en', the item has the CLARIN language-name metadata value populated (verified via the item's metadata after archive)
  - verify: `git show HEAD:dspace-api/src/main/java/org/dspace/content/InstallItemServiceImpl.java | grep -c addLanguageNameToMetadata`

### BE-WSI-1 — WorkspaceItemRestRepository CLARIN license-granted/resource PATCH ops (M10) `[major]`
- [ ] PATCH /api/submission/workspaceitems/<id> with op replace path /sections/clarin-license/... containing a valid license name returns HTTP 200 and the workspace item's bitstreams become mapped to that clarin license (row in license_resource_mapping)
- [ ] git grep -c OPERATION_PATH_LICENSE_GRANTED HEAD -- dspace-server-webapp/src/main/java/org/dspace/app/rest/repository/WorkspaceItemRestRepository.java returns >=1
  - verify: `git show HEAD:dspace-server-webapp/src/main/java/org/dspace/app/rest/repository/WorkspaceItemRestRepository.java | grep -c OPERATION_PATH_LICENSE`

### BE-CFG-4 — default.license: real CLARIN Deposit Licence Agreement text (M9) `[major]`
- [ ] git diff HEAD origin/dtq-dev -- dspace/config/default.license prints nothing (byte-identical)
- [ ] head -1 of HEAD:dspace/config/default.license equals 'Deposit Licence Agreement' (not 'NOTE: PLACE YOUR OWN LICENSE HERE')
  - verify: `git show HEAD:dspace/config/default.license | head -1  # expect: Deposit Licence Agreement`

### BE-CFG-5 — orcid-authority-services.xml: register CachingOrcidRestConnector bean (ORCID authority NPE) `[medium]`
- [ ] git grep -c CachingOrcidRestConnector HEAD -- dspace/config/spring/api/orcid-authority-services.xml returns >=1
- [ ] With orcid authority enabled, BE boots without a Spring bean-creation/NoSuchBean error and an ORCID authority lookup request returns HTTP 200 (no NPE in log)
  - verify: `git show HEAD:dspace/config/spring/api/orcid-authority-services.xml | grep -c CachingOrcidRestConnector`

### BE-CFG-6 — authentication-shibboleth.cfg: CLARIN header mapping delta (env-tuned) `[medium]`
- [ ] dsprop -p authentication-shibboleth.netid-header returns 'eppn,persistent-id'
- [ ] dsprop -p authentication-shibboleth.email-header returns 'mail' and firstname-header='givenName' and lastname-header='sn'
- [ ] dsprop -p authentication-shibboleth.eperson.metadata returns the non-empty CLARIN mapping (not the vanilla commented-out default)
  - verify: `docker exec clarinv9-dspace /dspace/bin/dspace dsprop -p authentication-shibboleth.netid-header`

### BE-CFG-7 — Email templates: CLARIN dspace.name→dspace.shortname branding delta (low) `[low]`
- [ ] grep -L 'dspace.shortname' across HEAD:dspace/config/emails/{register,feedback,welcome} is empty (all three now reference shortname)
- [ ] No remaining ${config.get('dspace.name')} occurrences in the ~13 templates that dtq-dev changed (diff HEAD vs dtq-dev for those files is empty)
  - verify: `for f in register feedback welcome; do git show HEAD:dspace/config/emails/$f | grep -c "dspace.shortname"; done`

### BE-RECONCILE-1 — Systematic reconciliation of dropped CLARIN vanilla-file/config hunks (M10 backlog) `[major]`
- [ ] A committed manifest (CSV/JSON) enumerates all 222 java + 61 config dtq-modified files with, per file, its CLARIN marker tokens and present/absent/waived status
- [ ] Zero rows are absent-without-waiver for files assigned to this domain (auth, user_registration, config-definition, converters, InstallItem, WorkspaceItem license, orcid, shibboleth, emails, default.license)
- [ ] Re-running the triage script prints 0 unexplained-absent tokens for this domain's file set (exit 0)
  - verify: `git diff dspace-7.6.5 HEAD --name-only -- '*.java' 'dspace/config/**' | sort > /tmp/head_ported.txt; git diff dspace-7.6.5 origin/dtq-dev --name-only -- '*.java' 'dspace/config/**' | sort > /tmp/dtq_all.txt; comm -13 /tmp/head_ported.txt /tmp/dtq_all.txt  # residual list must be fully accounted for `

## FE — admin GUIs (license/handle/ePIC), ISO language facet, static pages, item-edit license tab

### FE-ADMIN-M1 — Add providers:[NgbActiveModal] to ClarinLicenseTableComponent so /licenses/manage-table renders (NG0201) `[major]`
- [ ] Static: `git grep -n 'providers:' src/app/clarin-licenses/clarin-license-table/clarin-license-table.component.ts` shows an array containing NgbActiveModal in the @Component decorator.
- [ ] As site admin, GET http://localhost:14000/licenses/manage-table renders the table with >=1 <tr> data row (BE /api/core/clarinlicenses returns >=8 type=clarinlicense entries).
- [ ] Browser devtools console shows zero NG0201 / 'No provider for NgbActiveModal' errors on that route.
- [ ] Clicking the add/define-license control opens the DefineLicenseFormComponent modal without error.
  - verify: `Serve FE prod build (npm run build:prod), log in as admin, open /licenses/manage-table; assert rows and clean console. BE data confirmed live: curl -s http://localhost:18080/server/api/core/clarinlicenses -> HTTP 200 with type=clarinlicense entries.`

### FE-ADMIN-M2 — Register Handle model in provide-core models[] so /handle-table deserializes (VALUE import) `[major]`
- [ ] Static: models[] in provide-core.ts contains `Handle` AND the import is a value import (not `import type`).
- [ ] As admin, GET http://localhost:14000/handle-table renders >=1 handle rows; handlesRD$ reaches hasSucceeded=true with no 'cannot deserialize type handle' error in console.
- [ ] Runtime: getResourceType('handle') / getClassForType('handle') resolves to the Handle constructor after bootstrap.
  - verify: `Load /handle-table as admin; assert rows and no deserialize error. BE confirmed live: curl -s 'http://localhost:18080/server/api/core/handles?size=1' -> "type":"handle", totalElements 8673; root HAL exposes rel "handles".`

### BE-ADMIN-M3 — Add EpicHandleRest.PLURAL_NAME and register controller bean as core.epichandles so ePIC GUI resolves its endpoint (BE PR #1339) `[major]`
- [ ] After BE redeploy: `curl -s http://localhost:18080/server/api | grep -c '"epichandles"'` returns >=1 (root HAL now exposes the plural rel; currently returns 0 — only "epichandle").
- [ ] As site-admin (Bearer JWT), GET http://localhost:18080/server/api/core/epichandles/{prefix} returns HTTP 200 (or the ePIC-service response) and NOT the current bean-resolution 404 — verifies getEndpoint resolves. Do NOT assert on the bare /api/core/epichandles base path (no handler method exists there; returns 405 regardless of the fix).
- [ ] As admin, /epic-handle-table/prefix loads without an endpoint-resolution error (endpoint$ emits a defined href) and the prefix/handle panel renders.
  - verify: `After BE redeploy, curl root api for 'epichandles' rel; load /epic-handle-table/prefix as admin. Confirmed current break live: root shows only 'epichandle'; /api/core/epichandles base->404, /api/core/epichandle base->405. NOTE: full GET-by-prefix returning real handle data depends on the external eP`

### FE-ADMIN-M4 — Add FilterType.isoLanguage + map it to SearchTextFilterComponent so the Language (ISO) facet renders `[major]`
- [ ] Static: search-filter-type-decorator.ts contains `filterTypeMap.set(FilterType.isoLanguage, SearchTextFilterComponent)` and filter-type.model.ts contains `isoLanguage = 'iso_language'`.
- [ ] GET http://localhost:14000/search shows a non-empty 'Language' facet listing clickable entries including 'English' and 'Czech'.
- [ ] Clicking a language entry adds an f.language filter to the URL and reloads narrowed results.
- [ ] Runtime/unit: renderFilterType(FilterType.isoLanguage) === SearchTextFilterComponent.
  - verify: `Open /search, expand Language facet, assert entries present and filter works. BE confirmed live: curl -s 'http://localhost:18080/server/api/discover/facets/language?size=5' -> facetType iso_language, labels English/Czech/German/Spanish.`

### FE-ADMIN-C4 — Port src/static-files/ (82 html, exact filenames) + add angular.json 'src/static-files' asset glob so /static/*.html serve `[critical]`
- [ ] Build produces exactly 82 html: `find dist/browser/static-files -name '*.html' | wc -l` == 82, and dist/browser/static-files/cs/about.html exists (cs/ subtree copied).
- [ ] GET http://localhost:14000/static-files/about.html (raw asset) returns HTTP 200 with html content.
- [ ] GET http://localhost:14000/static/about.html returns HTTP 200 and StaticPageComponent renders the about body — assert the response is NOT the error.html fallback.
- [ ] Both spellings resolve: GET /static/theaitre-license.html (American) AND GET /static/deep-sequoia-licence.html (British) each return HTTP 200 with licence text.
- [ ] Czech page resolves: GET /static/cs/faq.html returns HTTP 200.
  - verify: `After FE build+deploy: 'find dist/browser/static-files -name '*.html' | wc -l' and curl the URLs above. Confirmed current break: HEAD src/static-files has 0 files, angular.json has no 'static-files' asset entry, all /static/*.html 404.`

### FE-ADMIN-M6-FE — Wire item-edit license tab: add CanManageLicense FeatureID, a v9 FUNCTIONAL guard, the license route, and the .title i18n key `[major]`
- [ ] Static: feature-id.ts contains `CanManageLicense = 'canManageLicense'`; item-page-license-mapper.guard.ts exports `itemPageLicenseMapperGuard` built via `dsoPageSingleFeatureGuard(...)` (functional, no @Injectable class); edit-item-page-routes.ts has a `path: 'license'` child with `canActivate: [itemPageLicenseMapperGuard]`; en.json5 has `item.edit.tabs.license.title`.
- [ ] As admin, the item edit tab bar shows a 'License' tab (label from item.edit.tabs.license.head), and navigating to <item edit>/license renders ItemLicenseMapperComponent (a license <select> populated from /api/core/clarinlicenses).
- [ ] As a non-admin/anonymous user the guard blocks the route (redirect to login/403), i.e. dsoPageSingleFeatureGuard denies when canManageLicense is false.
- [ ] End-to-end (requires BE-ADMIN-M6): selecting a license and clicking Update issues PUT {root}/core/items/{uuid}/bundles?licenseID=<id> and returns HTTP 200.
  - verify: `npm run build:prod, log in as admin, open the item edit page, assert License tab present and /license renders the mapper; then test the guard as anon; then (with BE-ADMIN-M6 deployed) click Update and assert 200. FE state confirmed: CanManageLicense absent from HEAD feature-id.ts, no license route i`

### BE-ADMIN-M6 — Re-port ItemAddBundleController PUT updateLicenseForBundle(licenseID) write endpoint (BE PR #1339) `[major]`
- [ ] Static: `git grep -c 'licenseID' dspace-server-webapp/src/main/java/org/dspace/app/rest/ItemAddBundleController.java` on the branch returns >=1 and the file declares `@RequestMapping(method = RequestMethod.PUT)`.
- [ ] As admin (Bearer JWT), `curl -X PUT 'http://localhost:18080/server/api/core/items/{uuid}/bundles?licenseID={validId}'` returns HTTP 200 with an ItemRest ("type":"item").
- [ ] After that PUT, GET /api/core/items/{uuid} shows the CLARIN license metadata written by addLicenseMetadataToItem, and a clarin_license_resource_mapping DB row exists for each bitstream in the item's ORIGINAL bundle (SELECT count(*) FROM clarin_license_resource_mapping WHERE bitstream_id IN (...) > 0).
- [ ] PUT with licenseID=-1 returns 200 and leaves zero clarin_license_resource_mapping rows for those bitstreams (detach-only path).
  - verify: `After BE redeploy to PR #1339: git-grep the controller; obtain an admin JWT; PUT with a real licenseID and a real item uuid; assert 200 + metadata + DB rows via psql on localhost:15432. Confirmed missing at HEAD via git diff origin/dtq-dev HEAD -- ItemAddBundleController.java (full method removed).`

## FE — share submission, license selector, submission form glue (complex/type-bind), Czech i18n, meta tags, view-tracker

### FE-SHARE-1 — M5: Restore MyDSpace 'Share submission' button in workspaceitem-actions `[major]`
- [ ] After port, `git grep -c shareSubmission -- src/app/shared/mydspace-actions/workspaceitem/workspaceitem-actions.component.ts` > 0
- [ ] DOM: a button with id `share_<id>` renders inside `@if ((canEditItem$ | async))` for an in-progress workspaceitem the current user can edit
- [ ] Ported workspaceitem-actions.component.spec.ts passes, including the assertion that shareSubmission() sends a GetRequest to `${halRootHref}/submission/share?workspaceitemid=<id>` and, on a hasSucceeded RemoteData, calls router.navigate(['/share-submission'],{queryParams:{changeSubmitterLink: <payload.shareLink>}})
- [ ] Regression guard: `curl -s -o /dev/null -w '%{http_code}' 'http://localhost:18080/server/api/submission/share?workspaceitemid=1'` == 401 (FE targets a live route)
  - verify: `npm test -- --include='**/workspaceitem-actions.component.spec.ts'; manual: log in as submitter, open MyDSpace, confirm Share button appears on a workspace item and clicking it issues GET /submission/share (network tab) and redirects to /share-submission?changeSubmitterLink=...`

### FE-NEWVERSION-1 — Port 'New version' button for archived submission items (f46cb9cb3e) `[medium]`
- [ ] item-actions.component.ts imports[] contains AsyncPipe and BtnDisabledDirective (else template fails to compile / [dsBtnDisabled] does not bind)
- [ ] Ported item-actions.component.spec.ts passes with its 5 new it() blocks: 'should show the New version button when version creation is authorized', 'should hide the New version button when version creation is not authorized', 'should mark the New version button as disabled when version creation is disabled', 'should use getVersioningTooltipMessage to derive tooltip key' (asserts getVersioningTooltipMessage toHaveBeenCalledWith(object,'item.page.version.hasDraft','item.page.version.create')), 'should open the create version modal when the New version button is clicked' (asserts openCreateVersionModal toHaveBeenCalledWith(object))
- [ ] DOM: MyDSpace archived item shows a 'New version' button gated by canCreateVersion$; when isNewVersionButtonDisabled emits true the button carries [dsBtnDisabled] and the item.page.version.hasDraft tooltip, otherwise item.page.version.create
  - verify: `npm test -- --include='**/item-actions.component.spec.ts'; manual: as an authorized user open MyDSpace, confirm New version button appears on an archived item and enabled/disabled+tooltip state matches draft presence.`

### FE-VIEWTRACKER-1 — Restore dc_identifier property in ViewTrackerResolverService page_view event `[medium]`
- [ ] `git grep -c dc_identifier -- src/app/statistics/angulartics/dspace/view-tracker-resolver.service.ts` == 1 (or the property appears in the eventTrack properties object)
- [ ] Ported view-tracker-resolver.service.spec.ts passes, including the assertion that angulartics2.eventTrack.next is called with properties containing dc_identifier equal to the item's dc.identifier.uri
  - verify: `npm test -- --include='**/view-tracker-resolver.service.spec.ts'; manual: open an item page with DevTools, confirm the angulartics page_view payload includes dc_identifier.`

### FE-METATAGS-1 — Restore CLARIN dataset_* meta tags in MetadataService `[medium]`
- [ ] `git grep -c setDatasetIdentifierTag -- src/app/core/metadata/metadata.service.ts` > 0
- [ ] Ported metadata.service.spec.ts passes, including assertions that addMetaTag is called with 'dataset_identifier'/'dataset_url'/'dataset_creator' etc.
- [ ] Runtime: on any item page, document.querySelector('meta[name="dataset_identifier"]') and meta[name="dataset_url"] are non-null in <head>
  - verify: `npm test -- --include='**/metadata.service.spec.ts'; manual: load an item page and run 'document.querySelectorAll('meta[name^="dataset_"]').length' in console — expect 6.`

### FE-I18N-CS-1 — Merge the 598 missing fork-added Czech (cs.json5) keys `[medium]`
- [ ] After merge, the set { keys(dtq-dev cs.json5) − keys(dspace-7.6.3 cs.json5) } is a subset of keys(HEAD cs.json5) — i.e. 0 fork-added keys remain missing (comm -23 fork_added.keys HEAD.keys == empty)
- [ ] None of the 29 excluded vanilla-drift keys listed above are present in HEAD cs.json5 (grep each == 0)
- [ ] cs.json5 parses as valid JSON5 (build succeeds with NODE_ENV=production; no duplicate-key lint error)
  - verify: `Recompute deltas: extract keys from cs.json5 on HEAD/dtq/7.6.3, assert comm -23 (dtq−7.6.3) HEAD == empty and the 29 exclusions absent; then 'npm run build:prod' and spot-check a CLARIN page in Czech renders translated strings, not raw keys.`

### FE-COMPLEX-PARSER-SPEC-1 — Port complex-field-parser.spec.ts (form-glue test coverage) `[low]`
- [ ] src/app/shared/form/builder/parsers/complex-field-parser.spec.ts exists on the branch
- [ ] npm test runs the ported spec and it passes
  - verify: `npm test -- --include='**/complex-field-parser.spec.ts'`

### FE-TYPEBIND-1 — Restore CLARIN multi-field type-bind (Map storage) + late-load re-evaluation (96e594a5be) `[major]`
- [ ] SPEC GATE (FE-only, independent): the ported ds-dynamic-type-bind-relation.service.spec.ts (311 lines, 16 it() blocks) passes, INCLUDING the 7 new blocks from 96e594a5be: 'Should not push undefined bind models', 'Should return false for MATCH_VISIBLE when bind model is missing', 'Should return true for MATCH_HIDDEN matcher when bind model is missing', 'Should re-evaluate visibility when bind model becomes available after setup', 'Should re-evaluate hidden matcher when bind model becomes available after setup', 'Should react to late bind model value changes after registration', 'Should evaluate only once during setup when related model already exists'
- [ ] SPEC GATE: form-builder.service.spec.ts (full suite) passes as regression gate; form-builder.service.ts exposes getTypeBindModelUpdates():Observable<string> and setTypeBindModel() calls typeBindModelUpdates.next(model.id) (grep both)
- [ ] RUNTIME (blocked on BE-FORMS): on a submission form whose type-bind field is NOT dc.type, changing that field's value shows/hides the bound control
- [ ] RUNTIME (blocked on BE-FORMS): hard-refresh (F5) on an item edit with a type-bound language field produces NO duplicate language field
  - verify: `npm test -- --include='**/ds-dynamic-type-bind-relation.service.spec.ts' --include='**/form-builder.service.spec.ts' (spec gate, runs without BE); runtime checks deferred until BE submission-forms.xml (EN) defines a non-dc.type bind — then exercise the form and hard-refresh an edit page.`

### FE-LICENSE-SELECTOR-1 — OPEN license-selector popup: provision globals, bundle scripts, rewrite onLicenseSelected `[medium]`
- [ ] angular.json build target scripts[] contains, in order, node_modules/lodash/lodash.min.js, src/license-selector.js, src/license-selector-creation.js, and contains NO bootstrap*.js entry
- [ ] At runtime on the /submit clarin-license step, typeof window._ === 'function' and typeof window.jQuery === 'function' BEFORE the selector runs; document.getElementById('license-text') is non-null (creation.js ran without ReferenceError)
- [ ] No console error 'Cannot read properties of null (reading click)' from clickLicense() and no 'ReferenceError: _ is not defined' from license-selector.js load
- [ ] Clicking OPEN License Selector opens the overlay; selecting a license that matches a BE ClarinLicense sets #secret-selected-license-from-license-selector.value to that license's numeric id and clicking through updates section-license.component.selectedLicenseName to the chosen license name (assert via component state / dropdown value)
  - verify: `npm run build:prod; open a submission with the clarin-license section, click OPEN License Selector, confirm overlay opens, pick a license present in the BE clarin license registry, confirm it flows into the dropdown and console is clean. Note: unmatched (legacy-only) widget licenses will fall to the`

### FE-REFRESH-UPLOAD-1 — Refresh file listings/preview after bitstream upload (3a219e4613) `[medium]`
- [ ] SPEC GATE (FE-only, independent of BE): ported upload-bitstream.component.spec.ts and preview-section.component.spec.ts pass, including assertions that requestService.setStaleByHrefSubstring is called with '/api/core/metadatabitstreams/search/byHandle?handle=<encoded>&fileGrpType=ORIGINAL' and the raw-handle variant, plus '/api/core/items/<id>' and '/api/core/items/<id>/bundles'
- [ ] RUNTIME (blocked on C1): after uploading a bitstream and returning to the item, the file list, CLARIN files section, and preview reflect the new bitstream without a hard refresh — this requires the metadatabitstreams byHandle endpoint (C1) to return 200 rather than 404
  - verify: `npm test -- --include='**/upload-bitstream.component.spec.ts' --include='**/preview-section.component.spec.ts' (spec gate, no BE needed); runtime refresh check deferred until C1 (MetadataBitstreamRestRepository byHandle) is live — then upload a file and confirm the list/preview update in place.`

## DevOps — dtq-dev CI/CD workflows, Docker images, Matomo deploy infra, and full-stack local build/deploy (owns audit M11)

### DEV-RUN-1 — Author the canonical full-stack local build+deploy RUNBOOK (BE rebuild from PR HEAD + FE build:prod+serve) that every other domain's acceptance tests depend on `[major]`
- [ ] A runbook file (DSpace/CLARIN_LOCAL_RUNBOOK.md, committed to the branch) exists containing the exact BE build+up and FE build+serve command blocks, each with its expected success signal.
- [ ] Base-image precondition holds before build: `docker image inspect dspace/dspace-dependencies:dspace-9_x` exits 0 (documented as a required `docker pull` step).
- [ ] After the BE block: `docker exec clarinv9-dspace /dspace/bin/dspace version` prints a line matching `^9\.` AND `curl -s -o /dev/null -w '%{http_code}' http://localhost:18080/server/api` == 200.
- [ ] After the FE block: `curl -s -o /dev/null -w '%{http_code}' http://localhost:14000/` == 200 AND the served HTML PROVES i18n resolution (build:prod): `curl -s http://localhost:14000/ | grep -c 'menu.section.browse_global'` == 0 (raw key absent) AND `curl -s http://localhost:14000/ | grep -c 'Communities'` >= 1 (a known translated phrase present).
- [ ] The runbook explicitly states which change classes require a full `docker compose build dspace` (any dspace-*/src/**.java) vs a config-only `up -d --force-recreate` (dspace/config/**).
  - verify: `docker pull the dependencies base; run the runbook BE+FE blocks; assert dspace version ~ ^9., BE /api==200, FE :14000==200 with the raw-key-absent + translated-phrase-present greps.`

### DEV-RUN-2 — Seed a real assetstore so bitstream-content / preview / license-gate acceptance tests (C1, C2) can be exercised locally `[major]`
- [ ] For at least one item deposited/seeded locally, `curl -s -o /dev/null -w '%{http_code}' http://localhost:18080/server/api/core/bitstreams/<uuid>/content` == 200 (byte payload served), proving the assetstore path works end-to-end for C1/C2 testing.
- [ ] `docker exec clarinv9-dspace find /dspace/assetstore -type f | wc -l` is strictly greater than the current baseline of 1.
- [ ] The runbook (DEV-RUN-1) documents the tier-2 dev-5 assetstore import command and explicitly marks it ENV-GATED on tarball availability.
  - verify: `Deposit a test bitstream, fetch /content -> 200; assert file count > 1; if dev-5 tarball present, docker cp + chown + re-fetch /content on a known production handle.`

### DEV-DOCKER-1 — Restore the dtq-dev non-root uid-1100 runtime user on the v9 BE Dockerfile (security hardening delta) and align the base-image org with CI `[medium]`
- [ ] `git show HEAD:Dockerfile | grep -c 'useradd -u 1100'` >= 1 AND the final runtime stage ends with a `USER dspace` line (`git show HEAD:Dockerfile | grep -c '^USER dspace'` >= 1).
- [ ] `docker build -f Dockerfile --target '' -t clarin-dockerdelta-check .` (or `docker compose build dspace`) exits 0 — the added user does not break the build.
- [ ] ENV-GATED runtime proof: after rebuild, `docker exec clarinv9-dspace id -u` != 0 (server no longer runs as root) AND BE `curl -s -o /dev/null -w '%{http_code}' http://localhost:18080/server/api` still == 200 (no permission regression on config/assetstore).
  - verify: `grep the Dockerfile for useradd/USER; docker build to exit 0; rebuild+up and assert non-root uid + BE /api still 200 with bitstream /content still 200.`

### DEV-SCRIPTS-BE-1 — Port the BE scripts/ tree (fast-build, index-scripts, pre-commit, restart_debug, envs, and the CI-critical sourceversion.py) — excluding matomo (DEV-MATOMO-1) `[medium]`
- [ ] `git ls-tree -r --name-only HEAD -- scripts/sourceversion.py scripts/index-scripts/autoindexf.sh scripts/index-scripts/indexhandle.sh scripts/pre-commit/checkstyle.py` returns all 4 paths.
- [ ] `python scripts/sourceversion.py https://example/actions/runs/ 1` exits 0 and prints a non-empty line (proves the CI version-stamp script is functional for DEV-CI-BE-1).
- [ ] `git ls-tree -r --name-only HEAD -- scripts/ | grep -vc 'scripts/docker/matomo/' ` >= 24 (bulk of the non-matomo tree ported).
  - verify: `git ls-tree for the four load-bearing paths; run sourceversion.py to exit 0 with output; count non-matomo scripts >= 24.`

### DEV-SCRIPTS-FE-1 — Port the FE build-scripts/ tree (run/import helper scripts + README) — excluding the assetstore seed (DEV-RUN-2) `[low]`
- [ ] `git ls-tree -r --name-only HEAD -- build-scripts/run/README.md build-scripts/run/start.sh build-scripts/import/harvest.sh` returns all 3 paths.
- [ ] `git ls-tree -r --name-only HEAD -- build-scripts/ | grep -vc 'build-scripts/run/assetstore/'` >= 14.
  - verify: `git ls-tree for the three key paths and count of non-assetstore build-scripts >= 14.`

### DEV-MATOMO-1 — Port the Matomo dev-deploy compose infra (scripts/docker/matomo/*) and regression-guard the matomo config wiring `[low]`
- [ ] `git ls-tree -r --name-only HEAD -- scripts/docker/matomo/ | wc -l` == 5.
- [ ] `docker compose -f scripts/docker/matomo/matomo-w-db.yml config` exits 0 (valid compose manifest).
- [ ] Regression guard: `git show HEAD:dspace/config/dspace.cfg | grep -c 'module_dir}/matomo.cfg'` >= 1 (matomo module include preserved).
  - verify: `git ls-tree count == 5; docker compose config exits 0 on matomo-w-db.yml; grep dspace.cfg for the matomo include.`

### DEV-CI-BE-1 — Restore BE image-build CI so the fork publishes deployable images: docker.yml (dataquest org + lowercase fork guard) + reconcile reusable-docker-build.yml inputs + sourceversion.py `[major]`
- [ ] `git show HEAD:.github/workflows/docker.yml | grep -c "github.repository == 'dataquest-dev/dspace'"` >= 4 (fork guard restored on the image jobs).
- [ ] `git show HEAD:.github/workflows/docker.yml | grep -c 'dataquest/dspace'` >= 4 (dependencies, dspace, cli, solr image names restored).
- [ ] `git show HEAD:.github/workflows/reusable-docker-build.yml | grep -c 'run_python_version_script'` >= 1 AND `... | grep -c 'python_version_script_dest'` >= 1 (inputs reconciled so the dspace job's workflow_call is valid).
- [ ] `git ls-tree -r --name-only HEAD -- scripts/sourceversion.py` is non-empty (dependency landed) and `python scripts/sourceversion.py https://x/ 1` exits 0.
- [ ] `actionlint .github/workflows/docker.yml .github/workflows/reusable-docker-build.yml` exits 0.
- [ ] Local build proof: `docker pull dspace/dspace-dependencies:dspace-9_x && docker compose -f docker-compose.yml -f docker-compose.clarinv9.yml build dspace` exits 0.
  - verify: `grep the lowercase guard + dataquest image names + reconciled reusable inputs; actionlint both files to exit 0; run sourceversion.py; docker compose build dspace to exit 0.`

### DEV-CI-BE-2 — Restore BE release + fanout + PM-automation workflows (tag-release, trigger-builds, new_issue_assign, PM-label-review-process) + copilot-instructions; neutralize the obsolete 7.5-era migrate-docker `[medium]`
- [ ] `git ls-tree -r --name-only HEAD -- .github/workflows/tag-release.yml .github/workflows/trigger-builds.yml .github/workflows/new_issue_assign.yml .github/workflows/PM-label-review-process.yml .github/copilot-instructions.md` returns all 5 paths.
- [ ] `actionlint .github/workflows/tag-release.yml .github/workflows/trigger-builds.yml .github/workflows/new_issue_assign.yml .github/workflows/PM-label-review-process.yml` exits 0.
- [ ] migrate-docker.yml is neutralized: EITHER `git cat-file -e HEAD:.github/workflows/migrate-docker.yml` fails (not ported) OR `git show HEAD:.github/workflows/migrate-docker.yml | grep -cE '^\s*(push|pull_request):'` == 0 (triggers stripped to workflow_dispatch only).
  - verify: `git ls-tree the 5 paths; actionlint the 4 active workflows; assert migrate-docker is absent or has no push/pull_request triggers.`

### DEV-CI-FE-1 — Restore FE image-build CI: docker.yml re-adapted to dataquest/dspace-angular + lowercase fork guard + a resolvable cross-repo reusable-build ref `[major]`
- [ ] `git show HEAD:.github/workflows/docker.yml | grep -c "github.repository == 'dataquest-dev/dspace-angular'"` >= 1.
- [ ] `git show HEAD:.github/workflows/docker.yml | grep -c 'dataquest/dspace-angular'` >= 2 (image_name on both build jobs).
- [ ] The reusable-build ref resolves to an existing workflow: `git show HEAD:.github/workflows/docker.yml | grep -cE 'reusable-docker-build.yml@(main|ufal/clarin-dspace-upgrade-v9)'` >= 1.
- [ ] `actionlint .github/workflows/docker.yml` exits 0.
  - verify: `grep the lowercase guard + dataquest image name + a resolvable reusable ref; actionlint docker.yml to exit 0.`

### DEV-CI-FE-2 — Restore the remaining 10 FE dtq-dev workflows + 2 composite actions + copilot-instructions (deploy, import/erase DB, ui-tests, PM automation) `[medium]`
- [ ] `git ls-tree -r --name-only HEAD -- .github/workflows/create_bitstreams.yml .github/workflows/deploy.yml .github/workflows/erase_db.yml .github/workflows/import-weekly.yml .github/workflows/new_issue_assign.yml .github/workflows/new_issue_label.yml .github/workflows/playwright-tests.yml .github/workflows/tag-release.yml .github/workflows/trigger-builds.yml .github/workflows/trigger-ui-tests.yml` returns all 10 paths.
- [ ] `git ls-tree -r --name-only HEAD -- .github/actions/erase-db/action.yml .github/actions/import-db/action.yml .github/copilot-instructions.md` returns all 3 paths.
- [ ] `actionlint .github/workflows/deploy.yml .github/workflows/playwright-tests.yml .github/workflows/tag-release.yml .github/workflows/trigger-builds.yml .github/workflows/new_issue_label.yml` exits 0.
  - verify: `git ls-tree the 10 workflow + 2 action + copilot paths; actionlint the executable-syntax subset to exit 0.`

## Testing & acceptance strategy — the "done" gate

### TEST-PROVENANCE — Build-provenance gate: deployed stack SHA must equal PR #1339/#1316 HEAD before any live probe counts `[critical]`
- [ ] docker inspect of the running clarinv9 BE container yields org.opencontainers.image.revision equal to `git -C DSpace rev-parse HEAD` (PR #1339 tip); FE container label equals dspace-angular HEAD (PR #1316 tip)
- [ ] A guard script probe-provenance.sh exits 0 only on both equalities and is invoked as the first step of the TEST-GATE-0 aggregator; a mismatch aborts the live-probe gate with a non-zero code and a printed 'DEPLOYED != PR HEAD' error
- [ ] Re-running live byHandle probe AFTER a provenance-clean rebuild still returns 200 (proves the file is now genuinely in PR HEAD, not stale container state)
  - verify: `Run probe-provenance.sh: assert it prints the two SHAs, that they equal the two 'git rev-parse HEAD' values, and exits 0; deliberately point it at a stale image to confirm it exits non-zero.`

### TEST-BE-BUILDERS — Port CLARIN test builders + matchers + support fixtures and re-apply modified base-builder deltas (shared dependency for all CLARIN ITs) `[critical]`
- [ ] mvn -pl dspace-api,dspace-server-webapp -am test-compile completes with 0 errors including all 11 builders, 5 matchers, ProvenanceExpectedMessages and MockHarvestedCollectionServiceImpl
- [ ] AbstractBuilderCleanupUtil references every ported Clarin*/Handle/PreviewContent/VersionHistory builder (grep -c returns >=10)
- [ ] A smoke IT instantiating ClarinLicenseBuilder + ClarinLicenseResourceMappingBuilder + HandleBuilder + VersionHistoryBuilder then aborting the context leaves 0 orphan rows (SELECT count(*) on the four backing tables unchanged pre/post)
  - verify: `mvn --no-transfer-progress -pl dspace-server-webapp -am test-compile -Denforcer.skip=true -Dcheckstyle.skip=true (BUILD SUCCESS); grep -c -E 'Clarin|Handle|PreviewContent|VersionHistory' dspace-api/src/test/java/org/dspace/builder/util/AbstractBuilderCleanupUtil.java returns >=10.`

### TEST-BE-IT-CRIT — Port the critical/major-gating CLARIN BE integration tests (C1/C2/C3/M2/M3/M7 + preview + Shibboleth) `[critical]`
- [ ] mvn -pl dspace-server-webapp verify -Dit.test=MetadataBitstreamRestRepositoryIT passes (byHandle asserts status 200 + _embedded.metadatabitstreams array + canPreview + fileInfo hasSize>0)
- [ ] mvn -pl dspace-server-webapp verify -Dit.test=AuthorizationRestControllerIT passes (wrong-token=401/403, correct-token=200, expired-token denied)
- [ ] mvn verify -Dit.test=HandleRestRepositoryIT,EpicHandleRestControllerIT,PreviewContentServiceImplIT,PreviewContentRestRepositoryIT,FilePreviewIT,ClarinShibbolethLoginFilterIT all pass Failures:0 Errors:0
- [ ] All 9 preview binary resources exist on disk under src/test/resources (ls returns each) and each preview IT reads them without IOException
- [ ] Each IT is present under DSpace #1339 src/test and appears in target/failsafe-reports
  - verify: `mvn --no-transfer-progress -pl dspace-server-webapp -am verify -P-assembly -DskipUnitTests=true -DskipIntegrationTests=false -Denforcer.skip=true -Dcheckstyle.skip=true -Dlicense.skip=true -Dit.test='MetadataBitstreamRestRepositoryIT,AuthorizationRestControllerIT,HandleRestRepositoryIT,EpicHandleRes`

### TEST-BE-UNIT — Port the 24 fork-added CLARIN BE unit tests and run them under surefire (skipUnitTests=false) `[major]`
- [ ] mvn -pl dspace-api,dspace-server-webapp test (surefire, skipUnitTests unset/false) runs all ported *Test classes with Failures:0 Errors:0
- [ ] surefire report (target/surefire-reports) lists ClarinTokenServiceTest, RegexPasswordValidatorTest, EpicHandleServiceTest, CachingOrcidRestConnectorTest, CuratorReporterTest as executed (not skipped) with 0 failures
- [ ] Every env-gated unit test carries @Ignore("deferred: <finding-id>") and appears in the DoD deferral table
  - verify: `mvn --no-transfer-progress -pl dspace-api,dspace-server-webapp -am test -Denforcer.skip=true -Dcheckstyle.skip=true -Dtest='Clarin*Test,RegexPasswordValidatorTest,EpicHandleServiceTest,CachingOrcidRestConnectorTest,CuratorReporterTest,ACLTest,DCInputTest,ShibHeadersTest,LocalMetadataTest,Matomo*Test`

### TEST-BE-IT-REST — Port the remaining ~43 CLARIN BE ITs (license REST, user metadata/registration, import controllers, curation, provenance, matomo, submission, refbox, discovery) `[major]`
- [ ] All non-deferred ITs in this set pass under failsafe with Failures:0, Errors:0
- [ ] mvn verify -Dit.test=MetadataValueRestRepositoryIT passes and asserts /api/core/metadatavalues HTTP 200 (closes the live 404 gap)
- [ ] mvn verify -Dit.test=ClarinRefBoxControllerIT passes asserting citation response contains a BibTeX entry and a CMDI element
- [ ] Deferred ITs carry @Ignore("deferred: <finding-id>") and are enumerated in the DoD deferral table — no silent deletion; NO OAIpmhIT or dspace-oai/xoai test is added or claimed as CLARIN coverage
  - verify: `mvn --no-transfer-progress install -P-assembly -DskipUnitTests=true -DskipIntegrationTests=false -Denforcer.skip=true -Dcheckstyle.skip=true -Dlicense.skip=true; assert failsafe summary lists the ported Clarin ITs with 0 failures (deferred ones show skipped); grep the diff to confirm no dspace-oai t`

### TEST-OAI-ORACLE — OAI crosswalk oracle for C5 (WebLicht/VLO harvest) — new IT + provenance-gated live probe (no reusable fork IT exists) `[major]`
- [ ] provenance-gated live probe: curl 'http://localhost:18080/server/oai/request?verb=ListMetadataFormats' output contains cmdi AND olac AND metasharev2 AND elg (currently contains none)
- [ ] provenance-gated live probe: curl 'verb=GetRecord&metadataPrefix=cmdi&identifier=<seeded>' returns HTTP 200 and body contains no <error code="cannotDisseminateFormat">
- [ ] new OAI IT passes under failsafe once C5 crosswalk classes are live; while C5 is unported it is @Ignore("deferred: C5") and listed in the deferral table
  - verify: `After a provenance-clean rebuild: curl the two OAI URLs above and grep for cmdi/olac/metasharev2/elg and absence of cannotDisseminateFormat; mvn verify -Dit.test=<new OAI IT> BUILD SUCCESS.`

### TEST-BE-IT-MODVANILLA — Re-apply fork test-deltas to the modified-vanilla BE test files (M10 coverage) `[major]`
- [ ] For each modified-vanilla test file, the CLARIN assertion hunk from dtq-dev is present at HEAD (git diff HEAD origin/dtq-dev -- <file> shows no missing CLARIN hunk)
- [ ] mvn verify of WorkspaceItemRestRepositoryIT and ItemRestRepositoryIT passes including the license-patch and language-name assertions
- [ ] No wholesale reversion of v9 upstream changes (the file still compiles against v9 APIs)
  - verify: `git diff --name-only --diff-filter=M dspace-7.6.5 origin/dtq-dev -- test tree to enumerate; for each, git diff HEAD origin/dtq-dev -- <file> and confirm only non-CLARIN/v9-API lines differ; mvn verify -Dit.test=WorkspaceItemRestRepositoryIT,ItemRestRepositoryIT BUILD SUCCESS.`

### TEST-FE-KARMA — Port the 64 missing CLARIN FE karma specs into dspace-angular #1316 `[major]`
- [ ] npm run test (karma, headless Chrome, watch=false) executes with all ported CLARIN specs present and 0 FAILED
- [ ] Karma summary shows metadata-bitstream-data.service.spec, html-content.service.spec, static-page.component.spec and the isoLanguage-filter spec among executed specs with SUCCESS
- [ ] git diff --name-only --diff-filter=A dspace-7.6.3 origin/dtq-dev -- '*.spec.ts' cross-checked: every file either present at HEAD or explicitly listed as deferred in the DoD deferral table (target: >=60 of 64 ported)
  - verify: `cd dspace-angular && npx ng test --watch=false --browsers=ChromeHeadless 2>&1 | tail -40 — expect 'Executed N of N SUCCESS (0 FAILED)'; grep the karma spec list for the four priority specs.`

### TEST-PW-2 — Localize the dspace-ui-tests Playwright config + fixtures against the local clarinv9 stack (LOCAL run artifact, never pushed) `[major]`
- [ ] Running the suite with lindat_specific_tests=true produces 0 conditional gate-skips (the `if (!lindat_specific_tests) test.skip()` branch is never taken); only the unconditional upstream test.skip('different and clickable icons in item view') and test.skip('create item should work') remain skipped
- [ ] config.json in the dspace-ui-tests working tree resolves baseURL to http://localhost:14000 and API to http://localhost:18080/server
- [ ] No dspace-ui-tests change is staged for push to #1339 or #1316 (git status in that repo is irrelevant to the two PRs)
  - verify: `npx playwright test --list | count of skipped == count of unconditional test.skip only; grep config.json for lindat_specific_tests:true and localhost URLs.`

### TEST-PW-1 — Run the full CLARIN Playwright suite (gate ON) against a provenance-clean stack as the E2E acceptance oracle `[critical]`
- [ ] npx playwright test (chromium) reports 0 failed and 0 flaky; the ONLY skips are the 2-3 unconditional upstream test.skip cases (0 lindat_specific gate-skips)
- [ ] The itemPage CLARIN cases (file list visible, preview icon clickable, license-gated download) all PASS against a provenance-verified build
- [ ] A JUnit/JSON Playwright report is saved as a run artifact and referenced by TEST-GATE-0
  - verify: `probe-provenance.sh exits 0, THEN npx playwright test --reporter=list,json 2>&1 | tail -20 shows 'X passed' with skipped==unconditional-only; inspect the JSON report for 0 'unexpected'.`

### TEST-FE-KARMA-MODVANILLA — Re-apply fork spec deltas to modified-vanilla FE specs (search filter, item-page, submission) `[medium]`
- [ ] For each modified-vanilla spec, the CLARIN assertion hunk is present at HEAD (git diff HEAD origin/dtq-dev -- <file> shows no missing CLARIN hunk)
- [ ] npm run test executes these specs with 0 FAILED including the isoLanguage filter-selection assertion (M4)
  - verify: `git diff --name-only --diff-filter=M dspace-7.6.3 origin/dtq-dev -- '*.spec.ts' to list; for each git diff HEAD origin/dtq-dev shows only v9-API differences; ng test --watch=false 0 FAILED.`

### TEST-MANUAL-55 — Manual-spec sign-off sheet #55 — every scenario routed to an automated case, a CLI+DB probe, or an explicit deferral `[major]`
- [ ] A committed sign-off sheet (docs/CLARIN_ACCEPTANCE_55.md in DSpace #1339) lists every #55 row with bucket AUTOMATED/PROBE/DEFERRED and the exact oracle reference; 0 unmarked rows
- [ ] Every AUTOMATED row cites a test that actually passes; every PROBE row's command is runnable and its expected output stated
- [ ] The acquisition method for the #55 checklist is recorded (source URL / gh command) since dspace-customers is not local
  - verify: `Open the sheet; assert row count == #55 scenario count and 0 rows without a bucket; spot-run 3 PROBE commands and 3 AUTOMATED tests.`

### TEST-MANUAL-411 — Manual-spec sign-off sheet #411 — every scenario routed to an automated case, a CLI+DB probe, or an explicit deferral `[major]`
- [ ] A committed sign-off sheet (docs/CLARIN_ACCEPTANCE_411.md in DSpace #1339) covers every #411 row with a bucket and oracle reference; 0 unmarked rows
- [ ] Env-gated/deferred rows each carry a finding-id and reason and match the DoD deferral table exactly
- [ ] Acquisition method for #411 recorded
  - verify: `Open the sheet; row count == #411 count, 0 unmarked; deferral rows reconcile 1:1 with the DoD deferral table.`

### TEST-CI-VERIFY — Prove the ported tests actually EXECUTE in the PR CI (gate d), not just exist `[major]`
- [ ] The BE #1339 CI job log shows the ported Clarin*IT and Clarin*Test names executed with a non-zero test count
- [ ] The FE #1316 CI job log shows the ported *.spec.ts executed
- [ ] A canary: temporarily failing one ported assertion causes the corresponding PR check to report failure (proves the test is load-bearing in CI), then reverted
- [ ] Both PR CI checks are green with the full ported suite enabled (unit + IT + karma) — not with tests skipped
  - verify: `gh run view <latest #1339 run> --log | grep -E 'MetadataBitstreamRestRepositoryIT|ClarinTokenServiceTest' shows executed; gh run view <#1316> --log | grep spec.ts; confirm canary red then green.`

### TEST-GATE-0 — Master Definition-of-Done acceptance gate + runbook (the single 'done' check) `[critical]`
- [ ] A committed runbook (DSpace #1339 docs/CLARIN_ACCEPTANCE.md) lists every audit finding C1-C5/M1-M13/mediums with its exact verification probe and current PASS/FAIL
- [ ] Aggregator exits 0 only when: TEST-PROVENANCE passed; BE failsafe reports the ported Clarin ITs with 0 failures; BE surefire reports the 24 ported unit tests with 0 failures; FE karma reports the ported specs with 0 failures; Playwright chromium run reports 0 failed with 0 lindat_specific gate-skips
- [ ] Provenance-gated live probes assert: byHandle 200 (proven against PR-HEAD build, not stale container); /api/core/metadatavalues 200 (currently 404); OAI ListMetadataFormats contains cmdi+olac+metasharev2+elg (currently none); FE 200 on /static/about.html (NOTE: .html required — /static/about with no extension resolves to static-files/about, misses, and returns SSR 404 even after the fix)
- [ ] Both manual-spec sheets (#55, #411) attached with every scenario marked AUTOMATED/PROBE/DEFERRED and 0 unmarked rows
  - verify: `Run probe-provenance.sh (exit 0), then the aggregator; confirm it prints PASS for all 6 gates and a non-empty per-finding table; independently re-run the four live probes and confirm 200/200/formats-present/200-on-about.html.`

## Addendum GAP items

### GAP-1 — ClarinBitstreamServiceImpl ported `[critical]`
- [ ] git ls-files | grep ClarinBitstreamServiceImpl returns the path; mvn -o -q -pl dspace-api compile exit 0

### GAP-2 — positive token path `[critical]`
- [ ] Logged-in user completes the license agreement for a gated bitstream via REST (clarin user-metadata flow), then GET /content returns 200 with bytes (on a bitstream whose file exists, e.g. a freshly uploaded one)

### GAP-3 — FE preview viewer `[major]`
- [ ] Clicking Preview on an item file NEVER navigates to /home; for a zip with generated PreviewContent it opens the file-tree; for a plain file it shows content or a download fallback

### GAP-4 — fresh Solr provisioning `[major]`
- [ ] Fresh docker compose up + full reindex: search core has *_ac autocomplete + search_text copyfields; OAI core populated after oai import

### GAP-5 — OAI harvest stats decision `[medium]`
- [ ] Maintainer decision recorded in progress file (accept loss OR ClarinMatomoOAITracker re-ported with an IT)

## Definition-of-Done gates

- [ ] (a) runs-in-docker: the clarinv9 stack serves all restored endpoints — byHandle 200, /content 401 for gated, ListMetadataFormats with CLARIN prefixes, refbox bibtex 200, and the FE admin GUIs/facets/static pages render — without a rebuild that loses untracked work (BE-BASE-0 committed).
- [ ] (b) all features: every audit item C1-C5 and M1-M13 (plus the medium submission/OAI/i18n items) has its specific objective acceptance check passing — negative-gate curl probes on :18080 for env-gated bytes, and ITs for positive-byte paths.
- [ ] (c) tests: mvn -pl dspace-server-webapp/dspace-oai targeted Surefire suites (MetadataBitstream*, BitstreamByHandle*, Authorization*, Identifier*, MetadataBitstreamController*, plus the ~120 ported CLARIN ITs) run > 0 tests with 0 failures/errors, and the FE matomo-subscription-button and facet specs are green.
- [ ] (d) PRs green: BE #1339 and FE #1316 CI pass AND git ls-files proves the ported files are actually committed (not merely present in the working tree) — specifically the 6 C1 files, the 28 OAI classes, checksum tier, and FE static-files/models.
- [ ] (e) no unresolved critical: C1 (byHandle 200 + FE 2 rows), C2 (gated /content 401, open 200), C3 (private /pid/find 401), C4 (static pages 200), C5 (ListMetadataFormats CLARIN prefixes + refbox 200) all pass their machine checks.
- [ ] (f) progress-file honest: CLARIN_DSPACE_V9_PROGRESS.md rewritten to remove the false 'I2 END-TO-END green' and every other unbacked claim; each C/M line carries its objective acceptance status and explicitly flags the documented-deferred/env-gated items (assetstore, S3, Shibboleth/WAYF, Matomo PDF e2e, ItemVersionLinker CLI).

---

# 2026-07-09 — Acceptance-Criteria VALIDATION addendum (re-checked vs dtq-dev + vanilla v9)

> An 8-domain analyst+critic workflow re-validated every acceptance criterion against BOTH the CLARIN `origin/dtq-dev` source AND vanilla `dspace-9.3` (vanilla-first, zero-regression), then a hard readiness critic judged the whole handoff. **This addendum lists the corrections that OVERRIDE the criteria above where they conflict.** See CLARIN_V9_VANILLA_FIRST_MATRIX.md for the vanilla decisions.

## Readiness-critic verdict on the handoff

**Verdict: not-ready · would-reach-100%: False.**

Verified against the live clarinv9 stack (up) and git refs (all resolve: HEAD d27f85b1, dspace-9.3 56030860fc, origin/dtq-dev 22cfef58e6). Confirmed setup facts: the 6 C1 REST files + CLARIN_V9_ACCEPTANCE/REMEDIATION docs are untracked (??), PROGRESS is ' M' — a git clean destroys them exactly as warned; matomo-java-tracker is in origin/dtq-dev:dspace-api/pom.xml but ABSENT from every HEAD pom (real BE-OAI-10 compile blocker); native org.dspace.matomo present in HEAD while ClarinMatomo* trackers are absent (double-count is a governance decision, not a blind port).

But live probing exposes issues the prompt/ACs get WRONG, not just undone work: (1) Section 3 lists 'byHandle 200 live / download-by-handle 200' under 'facts to TRUST (do not re-litigate)', yet byHandle returns 404 live (both param variants) — the deployed container (up 17h) predates the untracked C1 build, so the executor is instructed to trust a broken endpoint, the precise trap that burned prior agents. (2) DoD (a)/(e) require 'open /content -> 200' and 'gated /content -> 401' as LIVE probes, but both f15230ca (open) and faf919b4 (gated) return 500 on file read due to the ~1-file assetstore — 'open -> 200' is unsatisfiable live and contradicts the prompt's own assetstore caveat. (3) dtoken is NOT wired into the vanilla BitstreamRestController /content path (@PreAuthorize is accessToken||hasPermission only; AuthorizationBitstreamUtils is called from MetadataBitstreamController/AuthorizationRestController/a patch op, not any BITSTREAM-READ permission evaluator), so request-a-copy's turnOffAuthorisationSystem() bypasses the CLARIN license gate — a compliance decision owned by no work item/AC. C5 CLARIN OAI prefixes are absent live (only vanilla didl/mods/ore/mets/xoai/dim/rioxx/uketd_dc/qdc/oai_dc/rdf/marc/etdms; expected, unported).

willReach100=false: real regressions/decisions (Matomo double-count + reporting-vs-tracker site mismatch, request-a-copy license bypass, captcha reCAPTCHA->Altcha, dtoken wiring, matomo-java-tracker dep, truncated BE-OAI-2/7 verify snippets, fresh-deploy OAI Solr provisioning, cs parity, missing spec files) sit OUTSIDE every acceptance checkbox. Mechanically flipping all existing boxes green would leave these silently broken — the exact green-but-broken failure this workflow exists to prevent. The holes are concrete and fixable; once folded in as owned ACs and the two stale/unsatisfiable DoD items are corrected, the prompt reaches ready. As presented for handoff, it is not-ready.

### Required fixes before handoff (folded into CLARIN_V9_EXECUTOR_PROMPT.md §6b and below)
- [ ] Correct the STALE 'facts to TRUST' block in Section 3: byHandle returns 404 live (deployed container predates the untracked C1 build). Change to 'the deployed container lacks C1; after BE-BASE-0 commit you MUST redeploy, then re-probe byHandle for 200' and remove the 'do not re-litigate' framing on any endpoint not currently green live.
- [ ] Reconcile DoD (a)/(e) content probes with the ~1-file assetstore: 'open /content -> 200' and 'gated -> 401' are unsatisfiable via curl (both 500 on file read). Re-specify these gates as (i) IT that deposits a fresh item into its own test assetstore for positive byte-delivery, and (ii) metadata-short-circuit negative gates for the live smoke test; state explicitly which is the durable oracle.
- [ ] Add an owned decision item + AC + IT for request-a-copy vs CLARIN license gate: verify HEAD BITSTREAM-READ path actually enforces the CLARIN allowance, and that turnOffAuthorisationSystem() on the accessToken path does NOT bypass the distribution-license agreement (assert valid accessToken->200, valid dtoken->200, neither->401, invalid->401 on one bitstream).
- [ ] Add an owned Matomo governance item (TEST-MATOMO-COLLISION): pick a single emitter on /content+OAI, repoint or wire the CLARIN per-collection/OAI-handle dimension into the retained emitter, re-add matomo-java-tracker to dspace-api/pom.xml only if the CLARIN OAI tracker is kept, wire its Spring beans, and assert exactly one hit per download in an IT; also resolve the reporting-layer site_id=5 vs native site mismatch.
- [ ] Add captcha reconciliation AC: FE registration/request-copy widgets must emit Altcha x-captcha-payload; confirm x-recaptcha-token is NOT re-added to CORS (BE-CFG-3).
- [ ] Fix the non-runnable verify snippets (BE-OAI-2 truncated command, BE-OAI-7 dangling pipe) and add the missing guards flagged by domain verdicts (oai_openaire.xsl vanilla-preservation, dc.identifier.openalex survival BE-REG-1, MetadataExposure CONFIG_PREFIX keep-HEAD guard, mappedToIfNotDefault BE-SUB-2).
- [ ] Add coverage for the currently-unowned FE specs (clarin-name-field-parser.spec, share-submission-page.component.spec, ds-dynamic-autocomplete/sponsor-autocomplete specs) and Czech parity (cs.json5 item.edit.tabs.license keys + cs static-page path).
- [ ] De-duplicate default.license ownership (BE-CFG-4 vs BE-LIC-1) to a single owner to avoid conflicting EN/CS ports.
- [ ] State the fresh-deploy OAI Solr configset/schema provisioning as an owned item, not just dev-5 core population.
- [ ] Add sidebar-render + write-flow-persistence ACs for M1/M2/M3 (current ACs only navigate by direct URL and assert render/deserialize, so a broken/unregistered admin menu or non-saving POST/PUT passes).

> NOTE (orchestrator, verified 2026-07-09): the critic's blocking claim that `byHandle` returns **404 live** is REFUTED — a direct probe returns **200** with the C1 build deployed. The critic likely hit the container mid-restart during its 65-min run. The remaining required fixes above are valid and are incorporated. All other critic points stand.

### Residual risks (carry into execution)
- Live positive byte-delivery is unverifiable: ~1-file assetstore makes both open and gated /content return 500 on file read, so DoD (a) 'open -> 200' and the gated 'short-circuit before file read' distinction cannot be proven by curl; only ITs that deposit a fresh item into their own test assetstore are durable proof.
- Matomo double-count / half-port: if executor re-ports ClarinMatomoBitstreamTracker it fires alongside vanilla MatomoUsageEventHandler on the same UsageEvent (double count); if not, CLARIN report-subscription layer (MatomoHelper/MatomoPDFExporter present in HEAD, lr.statistics.api.site_id=5) queries a Matomo site no tracker populates. No AC owns the pick-one-emitter decision or an IT asserting a single hit per download.
- Request-a-copy vs CLARIN license: dtoken is not in the vanilla /content evaluator; request-a-copy's BitstreamResourceAccessByToken.turnOffAuthorisationSystem() makes isAdmin true and bypasses the CLARIN distribution-license gate. Coexistence IT (valid accessToken->200, valid dtoken->200, neither->401, invalid->401) is unowned.
- Captcha reconciliation: HEAD inherited vanilla Altcha (x-captcha-payload); CLARIN reCAPTCHA (x-recaptcha-token) dropped. No AC ensures FE registration/request-copy emit Altcha payload and that x-recaptcha-token is NOT re-added to CORS — captcha-gated flows silently reject or bypass.
- PLURAL_NAME 404-but-compiles class (broke M2/M3/BE-CHK-5): any newly wired REST repo/@LinkRest using NAME instead of PLURAL_NAME will 404 despite green CI; needs an explicit endpoint-resolves probe per ported repo.
- Preview viewer still routes to /home (GAP-3/BE-PREVIEW-8): PreviewContent tree in _deferred must be ported + FE fixed; no live checkbox currently asserts the inline Preview button opens the file tree.
- Fresh-deploy provisioning: BE-OAI-8 populates the existing dev-5 oai core; CLARIN Solr configset/schema deltas for a genuinely fresh clone are unowned (plan line 897).
- AC machine-runnability: BE-OAI-2 verify snippet is truncated mid-command and BE-OAI-7 ends in a dangling pipe — not runnable as written.
- Untracked-file loss remains live: deployed container does not contain the C1 build (byHandle 404), so BE-BASE-0 commit alone is insufficient — a redeploy is required for DoD (a) byHandle 200, and any rebuild must preserve the untracked tree.
- Missing FE spec coverage (clarin-name-field-parser.spec, share-submission-page.component.spec, ds-dynamic-*autocomplete specs present on origin/dtq-dev, absent on HEAD, uncovered by any work item) and cs.json5 license-tab keys / Czech static-page parity.

## Per-domain AC corrections (these OVERRIDE the matching criteria above)

### BE — item file listing, preview, license download gate, restricted-access  _(needs-fixes)_

**BE-GATE-1** — AC verdict `weak`, vanilla=`none`, decision=`keep-clarin`, regression=`high`:
- [ ] REPLACE the mis-classified request-a-copy AC with a DECISION-gated pair: (a) DOCUMENT the current bypass — an IT/curl asserting GET /api/core/bitstreams/{uuid}/content?accessToken=<valid RequestItem token> for a license-gated bitstream returns 200 (bytes bypass the CLARIN gate via BitstreamResourceAccessByToken.turnOffAuthorisationSystem→isAdmin true→hook skipped); (b) after the product decision lands, the chosen AC is either (b1) request.item.enabled=false so no token path exists, OR (b2) an IT asserting the same token GET returns 403/redirect-to-license when the item's license has confirmation-required and the requester has no ClarinLicenseResourceUserAllowance (i.e. the license check is injected into requestItemService.authorizeAccessByAccessToken / BitstreamResourceAccessByToken).
- [ ] REPLACE my draft's generic 'thumbnail/media-filter must not 500' AC (redundant — those are auth-off, hook cannot fire) with a targeted one: an IT proving an in-request NON-auth-off read of a license-gated bitstream (e.g. anonymous OAI bitstream serve, or an in-request citation coverpage) either short-circuits to 401/403 cleanly or is explicitly excluded from the gate — NOT a 500/partial response.
- [ ] ADD landmine guard note (not an AC): the hook MUST remain guarded by !isAdmin(c,o); no fix may move it before authorizeAction or make it fire under ignoreAuthorization, or it breaks request-a-copy AND every turnOff-auth internal flow (media-filter/thumbnail/OAI/sitemap/curation).
- [ ] PROMOTE the ported AuthorizationRestControllerIT (self-seeded confirmation=1/confirmation=0 fixture) to PRIMARY durable proof; DEMOTE the faf919b4/f15230ca dev-5 curls to fast smoke only (they silently pass/fail if dev-5 refreshes).

**BE-FILES-3** — AC verdict `valid`, vanilla=`none`, decision=`keep-clarin`, regression=`low`:
- [ ] PROMOTE MetadataBitstreamRestRepositoryIT (self-seeded 2-file item, asserts length==2 + href regex + canPreview boolean) to PRIMARY proof; DEMOTE the handle 11858/00-097C length==2 curl and the FE-2-rows/db4dff24 check to smoke.

**BE-CHK-5** — AC verdict `wrong`, vanilla=`partial`, decision=`hybrid`, regression=`high`:
- [ ] CORRECT reuse/adaptation: map databaseChecksum to the EXISTING vanilla bitstream.checkSum (no port needed for that half); adapt activeStore to vanilla BitstreamStorageService.computeChecksum(context,bitstream); CONDITIONALLY skip the synchronizedStore branch (computeChecksumSpecStore) whenever no sync store is configured, deferring it with SyncBitstreamStorageServiceImpl until S3-sync lands. Do NOT @Autowire SyncBitstreamStorageServiceImpl (absent → won't compile).
- [ ] ADD FE-fallback AC: the item-page checksum indicator can bind to the native bitstream.checkSum.value from GET /api/core/bitstreams/{uuid} (already 200 today) WITHOUT the CLARIN /checksum endpoint — so the DB-checksum UX has zero dependency on this work item; the /checksum endpoint is only needed for the active/sync recompute display.
- [ ] ADD compile AC: mvn -q -pl dspace-server-webapp -am -DskipTests compile exits 0 (guards the removed SyncBitstreamStorageServiceImpl @Autowire).
- [ ] REPLACE the live 200+md5 AC (currently unannotated + pinned to faf919b4/8a4605be...) with (a) an explicit env-gated note that live /checksum returns 500 while the assetstore is unimported, and (b) a checksum IT that builds a bitstream in a test assetstore and asserts /checksum → 200 with databaseChecksum.value == the DB md5 AND activeStore.value == the computed md5.
- [ ] KEEP grep ACs: @Component uses BitstreamRest.PLURAL_NAME (NOT NAME) and BitstreamRest @LinkRest set adds name=BitstreamRest.CHECKSUM while still containing BUNDLE/ACCESS_STATUS/FORMAT/THUMBNAIL — both valid, guard the recurring M2/M3 bean bug (verified: dtq-dev uses NAME, v9 convention is PLURAL_NAME).

**BE-TEST-6** — AC verdict `valid`, vanilla=`none`, decision=`keep-clarin`, regression=`low`:
- [ ] ADD: the ported ITs are the PRIMARY machine-checks for this whole domain; each domain live-curl AC must name its backing IT so a dev-5 refresh cannot silently break the proof.
- [ ] ADD (dependent on the request-a-copy DECISION): a token-path IT case in AuthorizationRestControllerIT asserting the decided behaviour (200 documenting bypass, or 403 after license-check injection).

**BE-ZIP-7** — AC verdict `valid`, vanilla=`none`, decision=`keep-clarin`, regression=`medium`:
- [ ] CLARIFY the live AC is env-INDEPENDENT for the negative case: after GATE-1+ZIP-7, the gated-item allzip pre-pass 401/403 (no application/zip Content-Type, no Content-Disposition) is assertable on :18080 without the assetstore because the pre-pass authorizes without retrieving bytes; only the positive 200+zip path is deferred to the IT's test assetstore.

**BE-PREVIEW-8** — AC verdict `wrong`, vanilla=`none`, decision=`keep-clarin`, regression=`low`:
- [ ] REPLACE the launcher.xml grep AC with a real invocation check: `dspace file-preview -h` exits 0 (or, equivalently, an assertion that scriptService.getScriptConfiguration('file-preview') resolves the FilePreview bean) — the CLI is already wired via scripts.xml at HEAD, so no launcher.xml edit is needed and none must be required.
- [ ] KEEP the IT content-chain AC (test builds a text-bitstream item, generates preview, asserts byHandle element.fileInfo length>=1 with non-empty content) as the durable proof.
- [ ] KEEP the env-gated deferral note (live previewcontent fileInfo:[] is blocked by the un-imported assetstore, same deferral as O-assetstore, not a code defect — no live fileInfo assertion is gated on this domain).

_Missing work items / uncovered ACs in this domain:_
- [ ] DECISION ITEM (NEW, no work item covers it): request-a-copy-by-token vs the CLARIN license-agreement gate. Vanilla v9 adds a token download path (BitstreamRestController l.119 @PreAuthorize '#accessToken!=null||hasPermission'; l.149-155 authorizeAccessByAccessToken; served by utils/BitstreamResourceAccessByToken which turnOffAuthorisationSystem() l.76/114 before retrieve). Because isAdmin(c,e) returns true under ignoreAuthorization (AuthorizeServiceImpl l.457-469), the BE-GATE-1 hook guard '!isAdmin(c,o)' is FALSE → the CLARIN distribution-license agreement is BYPASSED. This capability did not exist in CLARIN 7.x (dtq-dev BitstreamRestController has 0 accessToken refs). Product must decide: (a) disable request-a-copy (request.item.enabled=false), (b) inject the ClarinLicenseResourceUserAllowance/verifyToken check into requestItemService.authorizeAccessByAccessToken or BitstreamResourceAccessByToken so token downloads still require license agreement, or (c) formally accept the compliance gap. Add the decided behaviour as an AC + IT.
- [ ] REGRESSION-COVERAGE GAP: no work item covers in-request NON-auth-off reads of license-gated bitstreams (e.g. anonymous OAI bitstream serve, in-request citation coverpage). Batch/internal reads are auth-off (hook skipped, cannot 500), but these in-request non-auth-off paths hit the hook and need an explicit IT proving clean 401/403 (not 500/partial). The current BE-GATE-1 'internal flows must not 500' AC mis-targets the auth-off batch paths.
- [ ] FE-BINDING NOTE (fold into BE-CHK-5 or FE domain): the DB-checksum indicator can bind to the native vanilla bitstream.checkSum.value (already returned by GET /api/core/bitstreams/{uuid}) with zero dependency on the CLARIN /checksum endpoint; only the active-store/sync recompute display needs BE-CHK-5. No item states this, risking an unnecessary hard block on the S3-deferred sync tier.

### BE — CLARIN OAI/CMDI/BibTeX crosswalk layer + ref-box + CMDI upload  _(needs-fixes)_

**BE-OAI-1** — AC verdict `valid`, vanilla=`none`, decision=`keep-clarin`, regression=`low`:
- [ ] Add: Saxon-HE is a TRANSITIVE dep only (grep 'saxon' over all HEAD pom.xml = 0 hits) — add an AC asserting it is explicitly declared/managed in dspace-oai (or dspace-bom) so the s9api ExtensionFunction API cannot silently drift on a future dependency bump.
- [ ] Strengthen runtime AC#5 (line 82): assert POSITIVELY that a GetRecord with a CLARIN prefix INVOKES an extension function (output contains a value only UriToLicenseFn/GetAvailableFn can produce), not merely 'no not-initialized warning' — the negative check passes even if registration silently no-ops.

**BE-OAI-2** — AC verdict `weak`, vanilla=`none`, decision=`hybrid`, regression=`medium`:
- [ ] AC#3 (line 88) STILL BRITTLE per critique: names no concrete item that actually HAS TEXT/THUMBNAIL/SWORD bundles. Add a deterministic locator: pick an item whose /server/api/core/items/{uuid}/bundles lists a TEXT or THUMBNAIL bundle (or assert generically that for EVERY ListRecords xoai record the bundles element name-attribute set excludes {TEXT,THUMBNAIL,SWORD}).
- [ ] AC#1 (line 86) already reworded off hardcoded 11234/1-3039 to 'any handle with non-empty required_info' — keep, but note it needs a live ListIdentifiers identifier so it is env-gated on BE-OAI-8.
- [ ] FIX MALFORMED VERIFY: the AC-doc verify at line 90 is truncated mid-command ('...&metadataPrefix=xoai&' with no closing quote/URL) — not machine-runnable; repair to a complete GetRecord curl.

**BE-OAI-3** — AC verdict `weak`, vanilla=`none`, decision=`hybrid`, regression=`medium`:
- [ ] AC#3 (line 95) STILL non-deterministic per critique — I previously said 'identify via Solr query or seed in IT' but supplied neither. Now concrete: locate the qualifying item by querying the search/discovery Solr core for `local.hidden:hidden` intersected with read-authorized items (fq=read:g0 or public), take its handle, derive its identifier from a live ListIdentifiers sample, assert GetRecord&metadataPrefix=oai_dc returns a real record (not status=deleted header). If dev-5 has no such item, SEED one in the ported IT rather than leaving the AC unlocatable.

**BE-OAI-8** — AC verdict `valid`, vanilla=`full`, decision=`na`, regression=`medium`:
- [ ] ELEVATE the open question to a BLOCKING precondition (per critique): before ANY runtime AC in BE-OAI-2/3/4/5/6/9 can run, the maintainer must confirm that `dspace oai import -c` (a DESTRUCTIVE clean reindex of the oai core only; postgres source untouched; core returns to numFound=0 mid-run) is permitted on dev-5 prod data. Without this go/no-go the entire downstream runtime-AC chain is un-runnable, not merely env-gated.
- [ ] FIX the contradictory v9-adaptation note: it says 'run without restarting/rebuilding the container' AND 'exercises the ported XOAI.java/ItemUtils.java'. These conflict — exercising the NEW Java REQUIRES a prior BE image redeploy carrying BE-OAI-1/2/3. Reword: the import CLI runs inside the already-running container (no restart), but a BE redeploy of the ported code must have happened first; on the current HEAD image the import produces vanilla xoai output.

**BE-OAI-4** — AC verdict `valid`, vanilla=`none`, decision=`hybrid`, regression=`medium`:
- [ ] AC#1 (line 105) vs verify-regex (line 110) INCONSISTENCY: AC#1 asserts 5 prefixes (cmdi,olac,oai_metasharev2,bibtex,elg) but the verify regex includes a 6th (oai_datacite) while the plan's own open question asks if oai_datacite/DataCite export is even in scope. Reconcile: either add oai_datacite to AC#1 + a GetRecord&metadataPrefix=oai_datacite success AC, OR drop it from the verify regex and mark DataCite export documented-deferred.
- [ ] AC#4 (line 108, ColComFilter excludes DH/teaching-community items) STILL names no concrete excluded item per critique. Add: name a handle/setSpec known to be in the excludeDhCom/excludeTeachingCom community on dev-5, or assert via a Solr count delta between ListRecords with and without the filter.
- [ ] FIX line reference: oai.sample.identifier is clarin-dspace.cfg line 337, not 328 (plan/domain-risk cite 328).
- [ ] Add AC: Identify verb's <sampleIdentifier> resolves to a real value, not the literal '${oai.sample.identifier}' placeholder — proves clarin-dspace.cfg (M13) is loaded on the target deploy.

**BE-OAI-9** — AC verdict `valid`, vanilla=`none`, decision=`hybrid`, regression=`medium`:
- [ ] KEEP AC#1 byte-identity-to-dtq (line 113) — my prior 'BRITTLE proxy that reverts v9 improvement' rejection was FALSE-PREMISED: oai_dc.xsl has ZERO vanilla drift so there is no v9 improvement to revert; byte-identity to dtq is the STRONGEST deterministic machine check. KEEP the content assertions AC#2/#3 as belt-and-suspenders, do NOT replace one with the other.
- [ ] ADD REGRESSION-GUARD AC (new, critique-surfaced): assert `git diff HEAD dspace-9.3 -- dspace/config/crosswalks/oai/metadataFormats/oai_openaire.xsl` is EMPTY and `git diff HEAD origin/dtq-dev -- .../oai_openaire.xsl` is NON-empty — i.e. oai_openaire.xsl must remain the vanilla v9 OpenAIRE4 crosswalk and must NOT be reconciled to the older dtq-dev (==7.6.5) version. This blocks an over-generalized 'make crosswalks match dtq' step from reverting the OpenAIRE4 upgrade.
- [ ] AC#2/#3 (lines 114-115) still need concrete dev-5 handles: name an item with >=1 ORIGINAL bitstream (for downloadable_files_count:N) and an item carrying BOTH dc.date.issued and dc.date.accessioned (to prove dc:date emits issued-only). Both are env-gated on a populated oai core.
- [ ] FIX plan wording (line 305): 'The HEAD file has only trivial vanilla drift' is wrong for oai_dc.xsl — drift is NIL; state ZERO drift so the byte-identity rationale is accurate.

**BE-OAI-5** — AC verdict `valid`, vanilla=`none`, decision=`keep-clarin`, regression=`low`:
- [ ] AC#1/#2 (lines 119-120) hardcode handle 11234/1-3039 — keep as an EXAMPLE only; require the ported IT (AC#3, line 121) to be the primary deterministic gate since it self-seeds its OAI index and does not depend on dev-5 data.
- [ ] AC is env-gated on BE-OAI-4 (formats wired) + BE-OAI-8 (populated core): state that the live curl AC only passes after both land; on the current stack it returns 'Unknown metadata format'.
- [ ] Verify snippet at line 122 greps for 'Unknown metadata format' (count 0 = pass) — correct polarity; keep.

**BE-OAI-6** — AC verdict `valid`, vanilla=`none`, decision=`keep-clarin`, regression=`low`:
- [ ] AC#4 (line 128) hardcodes GET /server/cmdi/oai-metadata for 'a valid handle/identifier' — supply a concrete dev-5 handle/identifier (sampled from a live ListIdentifiers after BE-OAI-8) so the runtime check is deterministic; mark env-gated on BE-OAI-4.
- [ ] Add AC: assert the ported CMDIRestController @RequestMapping path actually resolves to /cmdi (grep the mapping) — the FE CMDI viewer expects /server/cmdi/*; a jakarta rewrite that drops the mapping compiles green but 404s.

**BE-OAI-7** — AC verdict `valid`, vanilla=`none`, decision=`hybrid`, regression=`medium`:
- [ ] FIX MALFORMED VERIFY: the AC-doc verify at line 136 ends in a dangling pipe ('...MetadataValidation.java |') — not machine-runnable; complete it to `| grep -c 'local.hasCMDI'`.
- [ ] Add a cross-domain OWNERSHIP AC: assert MetadataValidation.java is reconciled ONCE — the local.hasCMDI CMDI hook (this item) and the type-bind late-load re-eval (submission domain) must both be present in the single HEAD file after merge (grep both markers), so the two domains don't clobber each other on BE PR #1339.

**BE-OAI-10** — AC verdict `weak`, vanilla=`partial`, decision=`hybrid`, regression=`medium`:
- [ ] CORRECT the scoping: my prior 'only the thin OAI subclass lands here; base belongs to a stats domain' is UNBUILDABLE — the base ClarinMatomoTracker MUST land WITH BE-OAI-10 (OAI subclass extends it) or dspace-oai fails to compile and the OAI ApplicationContext throws NoSuchBeanDefinitionException.
- [ ] ADD MISSING AC (compile blocker): git show HEAD:dspace-api/pom.xml must declare org.piwik.java.tracking:matomo-java-tracker-java11 — v9 removed it; BE-OAI-10 will not compile until re-added.
- [ ] ADD MISSING AC (bean wiring): HEAD dspace/config/spring/api/core-services.xml must register org.matomo.java.tracking.MatomoTracker (constructor-arg matomo.tracker.host.url) + ClarinMatomoOAITracker beans (mirror dtq lines 186-190); without them @Autowired ClarinMatomoOAITracker fails at context startup.
- [ ] KEEP AC (line 140) that DSpaceOAIDataProvider retains setUpHTMLTransformerFactory + is jakarta — correctly guards against a verbatim dtq (pre-v9, javax, no HTML feature) file replacement that would regress the v9 OAI HTML interface.
- [ ] SEPARATE the bitstream tracker as its own vanilla-first decision (NOT this item): ClarinMatomoBitstreamTracker's bitstream-download tracking IS covered by vanilla native MatomoUsageEventHandler/MatomoEventListener (already in HEAD). Vanilla-first is a real candidate BUT with a zero-regression caveat — vanilla is single-siteid + a different client (org.dspace.matomo.client) vs CLARIN per-source segmentation via org.matomo.java.tracking. Assign this to a download/stats domain; if that domain keeps the CLARIN bitstream tracker it reuses the base+dep landed by BE-OAI-10 (no duplicate).

_Missing work items / uncovered ACs in this domain:_
- [ ] matomo-java-tracker-java11 Maven dependency (org.piwik.java.tracking) — HARD compile blocker for BE-OAI-10, declared in dtq-dev dspace-api/pom.xml but ABSENT from every HEAD pom (vanilla v9 replaced it with its own org.dspace.matomo.client); not named in any work item's reuse-source or AC. Must be re-added to dspace-api/pom.xml.
- [ ] Spring bean wiring for the Matomo trackers in dspace/config/spring/api/core-services.xml — org.matomo.java.tracking.MatomoTracker (constructor-arg matomo.tracker.host.url) + ClarinMatomoOAITracker (dtq lines 186-190); without these the @Autowired ClarinMatomoOAITracker fails at OAI context startup. No work item covers it.
- [ ] ClarinMatomoTracker base-class port ownership — it is shared by BE-OAI-10's OAI subclass AND ClarinMatomoBitstreamTracker; must land with BE-OAI-10 (not deferred). Its explicit inclusion in BE-OAI-10's reuse-source is missing.
- [ ] ClarinMatomoBitstreamTracker vanilla-first decision — bitstream-download tracking is natively covered by vanilla org.dspace.matomo (MatomoUsageEventHandler/MatomoEventListener, already in HEAD) but with a per-siteid/client regression caveat; no work item owns the use-vanilla-vs-keep-clarin call. Belongs to a download/stats domain, but it reuses BE-OAI-10's base+dep+bean.
- [ ] oai_openaire.xsl vanilla-preservation guard — HEAD carries the vanilla v9 OpenAIRE4 rewrite (dtq-dev==7.6.5); no work item ensures a crosswalk-reconciliation step doesn't revert it to the older dtq version. Add a guard AC under BE-OAI-9.
- [ ] Fresh-deploy OAI Solr configset/core provisioning — BE-OAI-8 populates the EXISTING dev-5 oai core, but no item covers CLARIN Solr schema/configset deltas for a genuinely fresh clone (already flagged at plan line 897; still unowned).
- [ ] AC-doc verify-snippet repairs — BE-OAI-2 verify (line 90) is truncated mid-command and BE-OAI-7 verify (line 136) ends in a dangling pipe; neither is machine-runnable as written.

### BE — submission steps, complex fields, autocomplete, ACL, registries, licenses config  _(needs-fixes)_

**BE-SUB-1** — AC verdict `weak`, vanilla=`partial`, decision=`hybrid`, regression=`medium`:
- [ ] REPLACE the ACL AC's 'NON-admin/anonymous context' with an AUTHENTICATED NON-ADMIN JWT: anonymous returns 401 and NEVER reaches the form, so the 'absent-for-anon' half is unexercisable. Correct AC: acl read-deny fields ABSENT from .rows[].fields[].selectableMetadata[].metadata with a dspace.user.dev (non-admin) JWT and PRESENT with an admin JWT (@PreAuthorize AUTHENTICATED confirmed, so a non-admin CAN read the endpoint).
- [ ] KEY the acl-gated fields off their field names local.hidden / local.hasCMDI, NOT _cs line numbers 155-158/169-172 — the line pins are brittle to any submission-forms_cs.xml drift.

**BE-SUB-2** — AC verdict `weak`, vanilla=`partial`, decision=`hybrid`, regression=`medium`:
- [ ] Replace the misleading cross-check: SubmissionFormsControllerIT#findFieldWithTypeBindConfig is a VANILLA dc.type type-bind test that would pass on vanilla and does NOT exercise the CLARIN '=>' edm.type mapping — keep it only as a dc.type NON-regression guard; prove '=>' solely via REST (edm.type=Text -> bound-field required error; edm.type=Image -> error gone).
- [ ] Add AC for mappedToIfNotDefault: the funding openaire_id input carries mapped-to-if-not-default='dc.relation'; assert a non-default openaire_id writes dc.relation (getMappedToIfNotDefault/loadMappedToIfNotDefaultFromComplex) — currently NO AC covers this CLARIN feature.

**BE-SUB-4** — AC verdict `valid`, vanilla=`partial`, decision=`hybrid`, regression=`high`:
- [ ] Add regression AC (submission-forms.xml ONLY): after merge git show HEAD:submission-forms.xml must STILL contain the 5 v9-lowercase form names (grep -c 'name="openaire' >= 5) and must NOT reintroduce the dtq camelCase openAIRE* forms. CORRECTION to my prior draft: do NOT reference coarnotify or a 'v9 bitstream-metadata form' here — bitstream-metadata is a form in BOTH refs (not a regression concern), and coarnotify lives in item-submission.xml (guarded by BE-SUB-5).

**BE-SUB-5** — AC verdict `valid`, vanilla=`partial`, decision=`hybrid`, regression=`high`:
- [ ] CORRECTED regression AC (fixes my prior draft error): after merge git show HEAD:item-submission.xml grep -c 'coarnotify' >= 1 (v9-only, 4 refs at HEAD, 0 in dtq) AND retain the v9 entity submission-processes (Publication/Person/Project/OrgUnit/Journal). DROP the earlier 'bitstream-metadata >= 1' clause — grep for bitstream-metadata in item-submission.xml is 0; that token belongs to submission-forms.xml (BE-SUB-4), not this file.

**BE-SUB-6** — AC verdict `wrong`, vanilla=`none`, decision=`keep-clarin`, regression=`low`:
- [ ] DELETE the AC 'GET /api/core returns a _links object containing a metadatavalues href' — /api/core is a hard 404 in DSpace; there is NO /api/core index endpoint. This check can NEVER pass even with a correct port.
- [ ] Replace with: GET /api/core/metadatavalues/search/byValue?... returns 200 (NOT 404) [already an AC], AND the search endpoint self-link contains '/core/metadatavalues' (proving getTypePlural() returned 'metadatavalues' not 'metadatavalue').

**BE-SUB-7** — AC verdict `weak`, vanilla=`partial`, decision=`hybrid`, regression=`medium`:
- [ ] State the dependency chain in the AC: the .cmdi-upload functional test is NOT standalone — it requires (a) BE-SUB-2 to have ported CMDIFileBundleMaintainer (MetadataValidation:188 resolves) and (b) BE-SUB-4 to have landed the local.hasCMDI form field so a collection form actually exposes it. Add explicit precondition: run only after BE-SUB-2 and BE-SUB-4 land; otherwise the PATCH local.hasCMDI=true step has no field to set.
- [ ] Keep-v9 guard AC: after the merge git grep 'PRIMARY_FLAG_PATTERN' and 'getPrimaryBitstream' in UploadStep still present (proves the v9 primary-bitstream branch survived the MERGE and was not overwritten by pre-v9 dtq).

**BE-REG-1** — AC verdict `weak`, vanilla=`partial`, decision=`hybrid`, regression=`medium`:
- [ ] Add positive openalex-survival guard (currently NO AC guards the one v9-only field a strict dtq restore would delete): git show HEAD:dspace/config/registries/dublin-core-types.xml | grep -A2 '<element>identifier' | grep -c 'openalex' >= 1.
- [ ] FIX AC#4's internal contradiction ('0 lines (or only v9-vanilla-additive lines)'): replace with git diff origin/dtq-dev HEAD -- dublin-core-types.xml has NO dtq-deletion lines after rights.label is restored (deletion-line count == 0) AND exactly the v9-additive '+' blocks (doi scope_note + openalex) remain; positively assert BOTH dc.rights.label (restored from dtq) AND dc.identifier.openalex (kept from v9) are present.
- [ ] Apply the same no-deletion+positive-token guard to local-types.xml and bitstream-formats.xml so the '0-lines-vs-dtq' target does not silently drop any v9-additive registry entry.

**BE-LIC-1** — AC verdict `valid`, vanilla=`none`, decision=`keep-clarin`, regression=`low`:
- [ ] Add EN/CS consistency guard (item title cites M9 EN/CS inconsistency, but the 2 ACs only check EN): also assert dspace/config/default_cs.license, if present, carries the CLARIN CS deposit-agreement text and is not a stale placeholder — otherwise the EN/CS mismatch the item is meant to fix goes unverified.

**BE-EXP-1** — AC verdict `wrong`, vanilla=`none`, decision=`keep-clarin`, regression=`low`:
- [ ] DELETE AC#1 'git diff HEAD origin/dtq-dev == 0 lines' — it forces CONFIG_PREFIX back to 'protected final', which breaks compile of vanilla v9 DSpaceCSV.java:364/366 (static+public reference), directly contradicting AC#2 'mvn install exits 0'. The item is a no-op port: the submitter feature is already merged into HEAD.
- [ ] REWRITE as a keep-HEAD guard: (a) git show HEAD:MetadataExposureServiceImpl.java grep -c 'public static final String CONFIG_PREFIX' == 1 AND grep -c 'SUBMITTER_CONST' >= 1 AND grep -c 'submitterShouldSee' >= 1 (feature + visibility fix retained); (b) mvn -q -pl dspace-api,dspace-server-webapp -am install -DskipTests exits 0 (DSpaceCSV compiles against the public static field). Never re-apply the dtq line.

_Missing work items / uncovered ACs in this domain:_
- [ ] No whole CLARIN submission/registry/license/exposure file is uncovered: BE-SUB-1..7 + BE-REG-1 + BE-LIC-1 + BE-EXP-1 cover DCInput pipeline, DescribeStep/MetadataValidation/CMDIFileBundleMaintainer, dtd, submission-forms.xml(EN)+_cs, item-submission.xml, autocomplete REST cluster, UploadStep/UploadValidation, registries, default.license, MetadataExposureServiceImpl. Verified the submit/ tree, SubmissionConfigReader.java (85-line delta), AccessCondition*/PrimaryBitstream*/Notify* patch-ops, default_cs.license and SolrAuthority are PURE v9 version-drift (v9 LocalDate/TimeHelpers vs dtq SimpleDateFormat; v9 entity-config refactor) with zero CLARIN tokens in the dtq-only lines.
- [ ] Structurally-missing ACs now folded into corrected items (were genuine gaps): (a) BE-REG-1 had NO guard on dc.identifier.openalex survival — added; (b) BE-EXP-1 had NO guard preventing re-application of the harmful CONFIG_PREFIX dtq line — added a keep-HEAD compile guard; (c) BE-SUB-2 had NO AC for mappedToIfNotDefault (openaire_id -> dc.relation) — added; (d) BE-LIC-1 had NO CS-side (default_cs.license) check for the M9 EN/CS fix it names — added.
- [ ] Cross-item dependency ordering not stated in ACs but required: BE-SUB-1 (DCInput pipeline) is the linchpin for BE-SUB-2/4/5/7; BE-SUB-2 (CMDIFileBundleMaintainer) and BE-SUB-4 (local.hasCMDI form field) must precede BE-SUB-7's .cmdi-upload functional test; BE-SUB-4 (lowercase openaire forms) must precede/accompany BE-SUB-5 (item-submission.xml maps them). A handoff prompt should sequence BE-SUB-1 -> BE-SUB-3 -> BE-SUB-4 -> BE-SUB-2 -> BE-SUB-5 -> BE-SUB-7 -> BE-SUB-6 -> BE-REG-1/BE-LIC-1/BE-EXP-1(guards).

### BE — auth, user registration, PAT, config wiring, vanilla-file deltas  _(needs-fixes)_

**BE-CFG-1** — AC verdict `weak`, vanilla=`none`, decision=`keep-clarin`, regression=`medium`:
- [ ] PRIMARY static (reliable): git show HEAD:dspace/config/config-definition.xml | grep -c clarin-dspace.cfg == 1, and the <properties> entry is ordered AFTER local.cfg and modules/dspace.cfg (comment 'Overrides everything in modules and dspace.cfg, but not local.cfg').
- [ ] Replace the non-discriminating live dsprop probe: on a CLEAN checkout whose local.cfg does NOT include clarin-dspace.cfg, BE boots and a clarin-only key (lr.pid.community.configurations) resolves non-empty solely via config-definition — cannot be validated against the current live stack (its local.cfg already includes the file).

**BE-CFG-2** — AC verdict `valid`, vanilla=`none`, decision=`keep-clarin`, regression=`medium`:
- [ ] Add discoverability AC: tracked dspace/config/clarin-dspace.cfg carries COMMENTED placeholder lines for clarin.token.encryption.secret and clarin.token.max.expiration.time.in.days (neither branch commits them today, so an implementer has no in-repo hint they are required).

**BE-PAT-2** — AC verdict `missing-ac`, vanilla=`none`, decision=`keep-clarin`, regression=`low`:
- [ ] Add: git show HEAD:dspace/config/spring/rest/scripts.xml | grep -c 'id="clarin-token"' >= 1, class=org.dspace.administer.ClarinTokenConfiguration primary=true; POST /api/system/scripts/clarin-token/processes (admin JWT) returns 2xx and creates a Process row.
- [ ] Keep: dspace clarin-token -h exits 0 with usage; -c -e <email> -x 30d increments clarin_token by exactly 1.

**BE-AUTH-2** — AC verdict `missing-ac`, vanilla=`none`, decision=`hybrid`, regression=`medium`:
- [ ] Fix the port constraint: keep vanilla CreateAdministrator.java:91-92 fast-path condition (e&&f&&l&&c&&p, NO &&hasOption("o")); -o is optional with a default org.
- [ ] Add regression AC: `create-administrator -e x@t.cz -f A -l T -c cs -p pw` WITHOUT -o exits 0, creates exactly one eperson whose language column == 'cs' (proves -c honored, NOT default), and exactly one user_registration row with organization == the chosen default ('Unknown').
- [ ] Add: `... -o MyOrg` (all 6) still exits 0 and user_registration.organization == 'MyOrg'.
- [ ] Add: non-interactive path (no console) with -c present never enters the interactive while-loop and never blocks.

**BE-MISC-1** — AC verdict `valid`, vanilla=`none`, decision=`keep-clarin`, regression=`low`:
- [ ] Tighten: git grep -c 'welcomeInfo' HEAD -- dspace-server-webapp/.../model/EPersonRest.java >= 2 (field+accessor) AND EPersonConverter.java is no longer byte-identical to dspace-9.3 (blob != 4dcf085).
- [ ] Note in AC: no DB migration required (welcome_info / can_edit_submission_metadata already exist in HEAD EPerson entity).

**BE-CFG-3** — AC verdict `wrong`, vanilla=`partial`, decision=`hybrid`, regression=`medium`:
- [ ] Replace AC1/title: WebApplication.java declares @Bean HttpFirewall whose body calls StrictHttpFirewall.setAllowedHeaderValues(s->true) — git grep -c setAllowedHeaderValues HEAD -- .../WebApplication.java == 1; purpose is relaxing header VALUES (UTF-8/ISO Shibboleth), NOT encoded slashes. Drop the pid/find encoded-slash AC entirely (non-discriminating).
- [ ] Add functional AC: a Shibboleth login whose forwarded attribute header value contains a non-ASCII char is NOT rejected with 400 'potentially malicious String' (requires the bean).
- [ ] Replace AC3: WebApplication.java allowedHeaders for /api/** include 'Verification-Token' (git grep, so the wired ClarinShibbolethLoginFilter verification preflight passes); KEEP 'x-captcha-payload' (vanilla Altcha); do NOT add 'x-recaptcha-token' (superseded).

**BE-CONV-1** — AC verdict `valid`, vanilla=`none`, decision=`hybrid`, regression=`low`:
- [ ] Keep AC1 (grep -c updateItemDatesMetadata HEAD ItemConverter.java >= 1). Tighten AC2 to name the exact metadata field CLARIN writes (verify against ClarinItemServiceImpl.updateItemDatesMetadata) rather than 'the normalized date field'.

**BE-INSTALL-1** — AC verdict `valid`, vanilla=`none`, decision=`hybrid`, regression=`low`:
- [ ] Keep AC1 (grep -c addLanguageNameToMetadata HEAD InstallItemServiceImpl.java == 2). Add: git grep -c IsoLangCodes HEAD InstallItemServiceImpl.java >= 1 (import restored).

**BE-WSI-1** — AC verdict `valid`, vanilla=`none`, decision=`hybrid`, regression=`medium`:
- [ ] Keep both ACs. Add guard AC: existing vanilla WorkspaceItem patch ops (e.g. sections metadata replace) still return 200 after the clarin branches are added (no dispatch regression).

**BE-CFG-4** — AC verdict `valid`, vanilla=`none`, decision=`keep-clarin`, regression=`low`:
- [ ] Keep AC1+AC2. Add cross-ref: reconcile with BE-LIC-1 so only ONE item ports default.license and decides EN/CS variant handling.

**BE-CFG-5** — AC verdict `valid`, vanilla=`partial`, decision=`keep-clarin`, regression=`medium`:
- [ ] Keep AC1 (grep -c CachingOrcidRestConnector HEAD orcid-authority-services.xml >= 1). Tighten AC2: with the CLARIN SimpleORCIDAuthority configured as an authority, a choices/authority lookup request returns 200 and the log shows no NoSuchBeanDefinition/NullPointerException from SimpleORCIDAuthority:29.

**BE-CFG-6** — AC verdict `valid`, vanilla=`partial`, decision=`keep-clarin`, regression=`medium`:
- [ ] Keep all three ACs (they discriminate). Fix AC2's stated expectation to match dtq exactly: firstname-header=givenName, lastname-header=sn (confirmed dtq:131), email-header=mail. Note this file is env-tuned, so the ported values must equal dtq's committed values, not arbitrary.

**BE-CFG-7** — AC verdict `wrong`, vanilla=`partial`, decision=`hybrid`, regression=`low`:
- [ ] Fix the undercount: enumerate the exact 27-file set with per-file base (17 base=7.6.5, 3 base=v9, 7 already CLARIN-new).
- [ ] Replace AC2: for the 3 v9-rewritten files (subscriptions_content, request_item.rejected, request_item.granted) the port applies ONLY the dspace.name->dspace.shortname substitution onto the v9 body; assert NO other line differs from dspace-9.3 for those three (do NOT diff-empty against dtq).
- [ ] Keep AC1 (grep 'dspace.shortname' present in register, feedback, welcome).

**BE-RECONCILE-1** — AC verdict `missing-ac`, vanilla=`none`, decision=`na`, regression=`medium`:
- [ ] Replace AC3: the triage MUST be per-marker-token — for every dtq-modified file, grep each CLARIN marker token in the CURRENT HEAD blob and flag token-count regressions, INCLUDING files where HEAD blob != 7.6.5 AND != dtq (v9-rewritten partial ports). Name-set comm is a supplementary, not primary, gate.
- [ ] Add: the manifest explicitly lists the partial-port files (HEAD blob != 7.6.5 and != dtq) with per-token present/absent — seed with InstallItemServiceImpl (addLanguageNameToMetadata,IsoLangCodes) and ItemConverter (updateItemDatesMetadata).

_Missing work items / uncovered ACs in this domain:_
- [ ] MATOMO half-port regression (UNOWNED, cross-domain): HEAD dropped all CLARIN trackers (ClarinMatomoBitstreamTracker/OAITracker/Tracker ABSENT) but kept the CLARIN reporting layer (MatomoHelper/MatomoPDFExporter/MatomoReportSubscription PRESENT; clarin-dspace.cfg:154-160 lr.statistics.api.site_id=5 still active). Native MatomoEventListener tracks only isContentBitstream() views to matomo.request.siteid=1 (matomo.enabled=false by default, no OAI action tracking, no matomo.custom.dimension.handle.id). Net: CLARIN report-subscription statistics query a Matomo site the removed trackers no longer populate. Needs an explicit decision item: either re-port the CLARIN OAI/handle-dimension trackers, or repoint the reporting layer (lr.statistics.api.site_id + report queries) to the native-Matomo site. No BE-CFG/BE-* item currently owns this.
- [ ] CAPTCHA mechanism reconciliation (UNOWNED, mostly FE): HEAD inherited vanilla's Altcha superset (AltchaCaptchaServiceImpl + CaptchaServiceFactory + x-captcha-payload; RegistrationRestRepository/RequestItemRepository already read x-captcha-payload) which supersedes CLARIN's Google reCAPTCHA (x-recaptcha-token, dropped). No item documents that captcha.provider must be configured and the FE registration/request-copy widget must emit x-captcha-payload (Altcha) rather than reCAPTCHA — otherwise captcha-gated flows silently reject or bypass. x-recaptcha-token must NOT be re-added to CORS (BE-CFG-3 corrected).
- [ ] default.license DUPLICATE ownership: BE-CFG-4 (this domain) AND BE-LIC-1 (submission/licenses domain, 'Restore CLARIN default.license + fix EN/CS inconsistency') both target dspace/config/default.license. De-duplicate to one owner to avoid conflicting/double ports; decide the EN/CS variant handling in one place.

### FE — admin GUIs (license/handle/ePIC), ISO facet, static pages, item-edit license tab  _(needs-fixes)_

**FE-ADMIN-M1** — AC verdict `valid`, vanilla=`none`, decision=`keep-clarin`, regression=`none`:
- [ ] Soften AC 300: as site admin GET /licenses/manage-table renders >=1 <tr> data row; assert BE GET /api/core/clarinlicenses is HTTP 200 and the rendered row count equals its page.totalElements. Do NOT pin '>=8' (depends on dev-5 seeded licenses); state that dataset precondition explicitly if a floor is kept.
- [ ] Add persistence: create a license via the DefineLicenseFormComponent modal, assert POST /api/core/clarinlicenses returns 2xx and the new row appears after reload (Save persists, not just modal-opens).

**FE-ADMIN-M2** — AC verdict `valid`, vanilla=`none`, decision=`keep-clarin`, regression=`low`:
- [ ] Static (shared-file batch): the SAME provide-core.ts models[] edit adds BOTH `Handle` (M2) AND `MetadataBitstream` (C1) as VALUE imports (not `import type`); metadata-bitstream.model.ts already exists on HEAD but is absent from the array — batch to avoid a merge conflict.
- [ ] Static: getClassForType('handle') resolves to the Handle model at runtime and the handle admin listing renders with no deserialize error.
- [ ] Persistence: as admin create a handle via /handle-table/new-handle and confirm POST /api/core/handles returns 2xx and the new row appears on reload; likewise edit-handle (PUT) and change-prefix persist.

**BE-ADMIN-M3** — AC verdict `valid`, vanilla=`none`, decision=`keep-clarin`, regression=`none`:
- [ ] PRIMARY (env-independent): after BE redeploy `curl -s http://localhost:18080/server/api | grep -c '"epichandles"'` >= 1 (currently 0). This is the sole load-bearing gate.
- [ ] SECONDARY/unverifiable-env: AC 313/314 (GET /core/epichandles/{prefix} -> 200 and panel render) require (a) a concrete valid ePIC prefix substituted for {prefix} and (b) the external ePIC PID service configured; mark them unverifiable in this env and do NOT assert the bare /api/core/epichandles base (405 regardless).

**FE-ADMIN-M4** — AC verdict `weak`, vanilla=`partial`, decision=`hybrid`, regression=`none`:
- [ ] Soften AC 319: /search shows a non-empty 'Language' facet with >=1 clickable entry (do not hardcode English/Czech; those depend on dev-5 data).
- [ ] Functional: clicking any language entry adds an f.language filter to the URL and reloads narrowed results.
- [ ] Static (keep): search-filter-type-decorator.ts contains filterTypeMap.set(FilterType.isoLanguage, SearchTextFilterComponent) AND filter-type.model.ts contains isoLanguage='iso_language'.

**FE-ADMIN-C4** — AC verdict `wrong`, vanilla=`none`, decision=`keep-clarin`, regression=`low`:
- [ ] DROP my earlier added 'dist/browser/static-files/error.html must ship as fallback' AC and delete the 'NOT the error.html fallback' clause from AC 327 — v9 has no error.html fallback (unused constant + inline not-found state).
- [ ] Rewrite AC 327: GET http://localhost:14000/static/about.html returns HTTP 200 AND the rendered DOM contains a concrete known string from static-files/about.html (assert a specific heading/anchor text, not a negative).
- [ ] Add negative AC: GET /static/nonexistent.html renders the inline 'not-found' state (contentState='not-found') and SSR sets HTTP 404 via responseService.setNotFound() — no hard crash / no redirect to /home.
- [ ] Replace AC 329 (non-routable): assert (a) raw asset GET http://localhost:14000/static-files/cs/faq.html -> HTTP 200 html; AND (b) with app language set to cs, GET /static/faq.html renders the Czech body (exercises html-content.service.ts locale-first path static-files/cs/faq.html at getHmtlContentByPathAndLocale). Do NOT test /static/cs/faq.html (single-segment router truncates to 'cs').

**FE-ADMIN-M6-FE** — AC verdict `valid`, vanilla=`partial`, decision=`hybrid`, regression=`low`:
- [ ] Add: the new 'license' route child sets `data: { title: 'item.edit.tabs.license.title', showBreadcrumbs: true }` and `canActivate: [itemPageLicenseMapperGuard]` (functional) — consistent with every sibling; without the .title key the breadcrumb/page title renders the raw key.
- [ ] Add: as a non-admin, the 'License' tab does not render in the edit tab bar (page.enabled resolves false) IN ADDITION to the guard blocking direct navigation to <item edit>/license.
- [ ] Add (Czech parity): cs.json5 contains item.edit.tabs.license.title AND item.edit.tabs.license.head.

**BE-ADMIN-M6** — AC verdict `weak`, vanilla=`partial`, decision=`hybrid`, regression=`medium`:
- [ ] Keep AC 340 as the env-independent static gate: git grep -c 'licenseID' ItemAddBundleController.java >= 1 AND the file declares @RequestMapping(method = RequestMethod.PUT).
- [ ] State concrete precondition for AC 341/342/343: choose an editable item that HAS >=1 bitstream in its ORIGINAL bundle and a real clarinlicense id from GET /api/core/clarinlicenses; substitute both for {uuid}/{validId}. Then AC 341: PUT .../items/{uuid}/bundles?licenseID={realId} -> HTTP 200 with 'type':'item'; AC 342: GET /api/core/items/{uuid} shows the CLARIN license metadata AND psql :15432 count(*) FROM clarin_license_resource_mapping for those bitstreams > 0; AC 343: PUT licenseID=-1 -> 200 leaving 0 mapping rows (detach path).

_Missing work items / uncovered ACs in this domain:_
- [ ] Admin-sidebar render is uncovered domain-wide: ClarinAdminMenuProvider (src/app/shared/menu/providers/clarin-admin.menu.ts) IS present AND registered (app.menus.ts:14,77) with Handle/ePIC-handle/Licenses entries, but every M1/M2/M3 AC navigates by DIRECT URL, so a broken or unregistered provider would pass all ACs. Add: as admin the sidebar shows Handle / ePIC-handle / Licenses links pointing to /handle-table, /epic-handle-table/prefix, and the licenses manage-table path.
- [ ] Write-flow persistence is uncovered across M1/M2/M3: no AC verifies Handle create/edit/change-prefix (POST/PUT core/handles), EpicHandle create/update, or ClarinLicense/label CRUD (POST/PUT core/clarinlicenses) actually SAVE — the routes are fully wired at HEAD (app-routes.ts:273-282,299) but ACs only assert render/deserialize/endpoint-resolution. Per-item persistence ACs added above.
- [ ] Czech (cs) parity has no dedicated work item: cs.json5 lacks item.edit.tabs.license.title/.head (M6-FE) and the Czech static-page path needs the corrected locale AC (C4: raw asset /static-files/cs/faq.html + locale-cs /static/faq.html). Track Czech i18n/static parity explicitly or it silently ships English-only.

### FE — share submission, license selector, submission form glue, i18n  _(needs-fixes)_

**FE-SHARE-1** — AC verdict `wrong`, vanilla=`none`, decision=`keep-clarin`, regression=`low`:
- [ ] REPLACE AC#351: the ported dtq spec does NOT test share — implementer must AUTHOR a new it() block asserting shareSubmission() sends a GetRequest to `${halRootHref}/submission/share?workspaceitemid=<id>` and, on hasSucceeded RemoteData, calls router.navigate(['/share-submission'],{queryParams:{changeSubmitterLink:<payload.shareLink>}}); the 'including the assertion...' wording falsely implies the reuse-source spec covers it.
- [ ] FIX reuse-source note: origin/dtq-dev workspaceitem-actions.component.spec.ts has NO share test.
- [ ] ADD defensive route-registration guard (currently PASSES on HEAD; refutes critique orphan claim, keep as regression guard): `git grep -c "path: 'share-submission'" -- src/app/app-routes.ts` == 1 AND share-submission-routes.ts loadChildren target resolves '' to ShareSubmissionPageComponent (not RouterLink to wildcard/home).
- [ ] ADD: port share-submission-page.component.spec.ts (present on dtq, absent on HEAD) — see missingWorkItems.
- [ ] KEEP AC#350 (DOM id share_<id> inside @if((canEditItem$|async))) and AC#352 (curl /submission/share==401) — valid & machine-checkable.

**FE-VIEWTRACKER-1** — AC verdict `weak`, vanilla=`partial`, decision=`keep-clarin`, regression=`low`:
- [ ] FIX AC#362: change `git grep -c dc_identifier -- src/app/statistics/angulartics/dspace/view-tracker-resolver.service.ts` from `== 1` to `>= 1` (a faithful port yields 2: const declaration + property key). The existing escape-hatch clause '(or the property appears in the eventTrack properties object)' is fine but the literal threshold must not be 1.
- [ ] KEEP the spec-assertion AC (eventTrack.next called with properties.dc_identifier === item dc.identifier.uri) — valid & machine-checkable.

**FE-METATAGS-1** — AC verdict `wrong`, vanilla=`partial`, decision=`hybrid`, regression=`high`:
- [ ] RETARGET AC#367: grep must be on head-tag.service.ts, not metadata.service.ts — `git grep -c setDatasetIdentifierTag -- src/app/core/metadata/head-tag.service.ts` > 0.
- [ ] v9-adaptation: add the 6 setDataset*() private methods to head-tag.service.ts and their 6 invocations into setDSOMetaTags() alongside setCitation*Tag calls (~181-193); reuse existing head-tag getMetaTagValue()/addMetaTag().
- [ ] REPLACE AC#368: dtq metadata.service.spec.ts has ZERO dataset assertions — ADD dataset_ assertions to head-tag.service.spec.ts (which exists on HEAD and already tests citation_ tags); do NOT port them from metadata.service.spec.ts.
- [ ] AC#369 (runtime meta[name^=dataset_] length==6 on item page) is env-gated on a live item page — tag unverifiable-env.

**FE-I18N-CS-1** — AC verdict `valid`, vanilla=`none`, decision=`keep-clarin`, regression=`low`:
- [ ] OUT-OF-SCOPE flag (non-blocking): value-drift — cs keys present on BOTH HEAD and dtq may hold stale English values on HEAD; add-missing-keys scope does not refresh them. Decide if value-level cs reconciliation is in scope (see missingWorkItems).

**FE-COMPLEX-PARSER-SPEC-1** — AC verdict `valid`, vanilla=`none`, decision=`keep-clarin`, regression=`none`:
- [ ] SCOPE note: this item covers ONLY complex-field-parser.spec.ts. clarin-name-field-parser.spec.ts is a SEPARATE gap — its parser (clarin-name-field-parser.ts) is present on HEAD and wired in parser-factory.ts:5/134/137, but its spec (present on dtq) is absent and covered by no work item. See missingWorkItems.

**FE-TYPEBIND-1** — AC verdict `weak`, vanilla=`partial`, decision=`keep-clarin`, regression=`medium`:
- [ ] KEEP AC#384 (16-it type-bind-relation spec incl 7 late-load blocks) and AC#385 (form-builder.service.spec full suite + getTypeBindModelUpdates + typeBindModelUpdates.next greps) — these PROVE the Map + late-load and are machine-checkable.
- [ ] FIX AC#386: a single non-dc.type bind is satisfiable by pure-vanilla + a submit.type-bind.field config edit and does NOT prove the CLARIN Map. To prove the Map, the test form must define TWO concurrent type-binds on DISTINCT fields and toggling each independently shows/hides ONLY its own bound control. State this multi-bind assumption explicitly or the AC is not discriminating.
- [ ] AC#387 (F5 no duplicate language field) exercises late-load re-eval (96e594a5be) but NOT the multi-field Map — keep, tag unverifiable-env until BE-FORMS defines the binds.

**FE-LICENSE-SELECTOR-1** — AC verdict `weak`, vanilla=`none`, decision=`keep-clarin`, regression=`low`:
- [ ] DROP the 'PIN lodash / verify g.merge/g.cloneDeep / vendor 3.10.1' remediation — fictional; the widget never calls merge/cloneDeep. Bundle node_modules/lodash/lodash.min.js (4.x, already in tree) as the global _.
- [ ] KEEP AC#390 (angular.json scripts[] order incl lodash.min.js, no bootstrap*.js) — valid & machine-checkable.
- [ ] KEEP AC#391 (typeof window._==='function' && typeof window.jQuery==='function' before selector; #license-text non-null) — runtime.
- [ ] AC#392/#394 are runtime-only and env-gated on TWO unstated preconditions — ADD depends-on: BE-FORMS (item-submission.xml must re-add the clarin-license submission section — HEAD is byte-vanilla) AND M12 (clarin-license registry population). Tag unverifiable-env until both land.
- [ ] ADD: port clarin-license-resource/section-license.component.spec.ts (present on dtq, absent on HEAD) — see missingWorkItems.

**FE-REFRESH-UPLOAD-1** — AC verdict `valid`, vanilla=`partial`, decision=`keep-clarin`, regression=`low`:
- [ ] KEEP spec gate as-is (machine-checkable, no BE).
- [ ] Runtime AC correctly tagged blocked-on-C1 — keep unverifiable-env until C1 byHandle endpoint is live (already 200 in this workspace per established facts, but that repo change is UNTRACKED and must be committed).

_Missing work items / uncovered ACs in this domain:_
- [ ] clarin-name-field-parser.spec.ts: parser src/app/shared/form/builder/parsers/clarin-name-field-parser.ts IS present on HEAD and wired in parser-factory.ts:5/134/137, but its spec (present on origin/dtq-dev) is absent and covered by NO work item. FE-COMPLEX-PARSER-SPEC-1 ports only complex-field-parser.spec.ts. HEAD-vs-dtq parser diff is import-reorder + const/let + trailing-comma only (no behavior change) -> spec ports as cleanly as the complex-parser one; simply uncovered.
- [ ] share-submission-page.component.spec.ts: present on origin/dtq-dev (src/app/share-submission/share-submission-page/), ABSENT on HEAD, covered by no work item. Should be added under FE-SHARE-1 scope.
- [ ] ds-dynamic-autocomplete.component.spec.ts AND ds-dynamic-sponsor-autocomplete.component.spec.ts: both present on origin/dtq-dev (src/app/shared/form/builder/ds-dynamic-form-ui/models/{autocomplete,sponsor-autocomplete}/), ABSENT on HEAD, covered by no work item — fork autocomplete/sponsor test coverage lost.
- [ ] clarin-license-resource/section-license.component.spec.ts: the CLARIN section-license component (src/app/submission/sections/clarin-license-resource/section-license.component.ts) IS present on HEAD, but its spec (present on dtq) is absent (only the vanilla license/section-license.component.spec.ts is present). No work item covers it — should be added under FE-LICENSE-SELECTOR-1 scope.
- [ ] Czech cs.json5 VALUE-drift: FE-I18N-CS-1 only ADDS 598 missing keys; keys present on BOTH HEAD and dtq may still hold stale English values on HEAD (v9 vanilla defaults), which the add-missing-keys scope does not refresh. No work item reconciles cs values. Decide if value-level cs reconciliation is in scope.
- [ ] NOTE (NOT a gap — critique refuted): the critique alleged 'share-submission route registration missing / share-submission-routes.ts is an orphan'. Verified FALSE at source: HEAD src/app/app-routes.ts:285-289 registers share-submission via loadChildren -> ShareSubmissionPageComponent. No re-registration work item is needed; FE-SHARE-1 is functional at runtime once the trigger button is ported.

### DevOps — dtq-dev CI/CD workflows, Docker images, Matomo, full-stack build/deploy  _(needs-fixes)_

**DEV-RUN-1** — AC verdict `weak`, vanilla=`none`, decision=`na`, regression=`low`:
- [ ] AC line 408 (`curl :14000/ | grep -c 'Communities' >=1`) stays theme/dataset-brittle and remains an UNSTATED-ASSUMPTION AC: no concrete CLARIN/LINDAT SSR string is pinned. Before this AC can gate, capture the ACTUAL rendered homepage HTML from the running FE (curl :14000/) and hard-pin a literal phrase that is KNOWN present in the CLARIN theme (the LINDAT homepage may not render the word 'Communities'). Keep the raw-key-absent half (`grep -c 'menu.section.browse_global' ==0`) — that one is theme-independent and sound.
- [ ] AC line 409/410 ('runbook states which change classes need rebuild vs force-recreate') is prose, not machine-checkable — convert to a grep for named headings in CLARIN_LOCAL_RUNBOOK.md (e.g. `grep -cE 'rebuild|force-recreate' >=1` over specific section titles) or drop from the pass/fail gate.

**DEV-RUN-2** — AC verdict `unverifiable-env`, vanilla=`none`, decision=`na`, regression=`none`:
- [ ] AC line 414 hardcodes 'baseline of 1' in /dspace/assetstore — live-state/dataset-brittle. Replace with: capture `count_before = find /dspace/assetstore -type f | wc -l` at test start, deposit, then assert `count_after > count_before`. Do NOT pin the literal 1.

**DEV-DOCKER-1** — AC verdict `wrong`, vanilla=`full`, decision=`use-vanilla`, regression=`none`:
- [ ] REVERSED DECISION: vanilla root runtime is the ZERO-REGRESSION baseline (non-root is ops-hardening, NOT a CLARIN feature/UX/data). Per the mission rule, prefer vanilla → DEMOTE the non-root user to an OPTIONAL, documented-deferred hardening item, not a required v9 work item. DEV-DOCKER-1 as a REQUIRED gate should be dropped.
- [ ] AC line 419 is DEFECTIVE regardless: `grep -c '^USER dspace' >=1` FALSE-PASSES at HEAD today (build-stage l.24). My prior `>=2` fix is ALSO brittle (two build-stage USER lines would satisfy it while runtime stays root). IF the optional hardening is pursued, the ONLY sound discriminator is: awk that a `^USER dspace` line appears AFTER the LAST `FROM …eclipse-temurin` boundary, AND `grep -c 'useradd -u 1100' >=1` (genuinely absent@HEAD), AND a `chown -R dspace:dspace /dspace` precedes it (else bind-mounted /dspace/{config,assetstore,log} regress on writes).

**DEV-SCRIPTS-BE-1** — AC verdict `valid`, vanilla=`none`, decision=`keep-clarin`, regression=`none`:
- [ ] AC line 428 (`grep -vc 'scripts/docker/matomo/' >=24`) is an EXACT-boundary count (non-matomo = exactly 24): flag that dropping even one file fails the gate. Intentional (forces a full port) but a reviewer must treat 24 as all-or-nothing, not a loose lower bound.

**DEV-MATOMO-1** — AC verdict `weak`, vanilla=`full`, decision=`use-vanilla`, regression=`none`:
- [ ] Plan v9-adaptation claim ('matomo.cfg default 8081 matches the compose service') is WRONG for dtq-dev matomo-w-db.yml (it uses 8135). The VANILLA docker-compose-matomo.yml IS the config-wired tracking-test path (8081); the dtq-dev scripts/docker/matomo/* are a heritage/mandate port only.
- [ ] AC line 438 (`docker compose -f matomo-w-db.yml config` exits 0) proves NOTHING: the file is version:'3.5', which compose v2 treats as OBSOLETE — config exits 0 emitting only a deprecation WARNING regardless of port/service correctness. Trivially green. REPLACE with: `git cat-file -e HEAD:dspace/src/main/docker-compose/docker-compose-matomo.yml` (vanilla compose preserved) AND `git show HEAD:dspace/config/modules/matomo.cfg | grep -c 'localhost:8081' >=1` (tracker.url matches the vanilla published port).
- [ ] Do NOT port dtq-dev FE src/config/matomo-config.ts / docker/matomo-settings.ts as a vanilla-first duplicate — native FE matomo-config.interface.ts@HEAD supersedes them. Their ONLY remaining role would be a local dev Matomo server (cross-domain M11 open question), not app config.
- [ ] Add a NOTE (not an AC-pass gate): the CLARIN PDF-report engine (MatomoPDFExporter/MatomoHelper) has NO local dev server/config path in EITHER compose — it reads lr.statistics.api.* pointing at remote ufal/lindat. Its local reachability is a documented cross-domain gap, not a regression from choosing vanilla.

**DEV-CI-BE-1** — AC verdict `valid`, vanilla=`partial`, decision=`hybrid`, regression=`medium`:
- [ ] AC line 447 (`actionlint … exits 0`) is unverifiable-env: actionlint NOT installed on this host (`which actionlint` fails). The implementing agent must install actionlint (go install / release binary) or this gate cannot run.
- [ ] AC line 448 (`docker pull dspace/dspace-dependencies:dspace-9_x && docker compose build dspace` exits 0) is env-gated: multi-GB base pull (not cached) + ~10-20min in-image mvn. Flag heavy/network-dependent.
- [ ] RESOLVE the publish-org ambiguity: dtq-dev used `dataquest-dev` org (guard) + `dataquest/dspace*` image names; for v9 the fork branch is ufal/clarin-dspace-upgrade-v9. The AC greps for `dataquest-dev/dspace`/`dataquest/dspace` (line 443/444) but the actual publish org must be pinned to whatever the v9 fork will push to (dataquest vs ufal) — otherwise the guard never matches the real repo and the fork again publishes nothing.

**DEV-CI-BE-2** — AC verdict `valid`, vanilla=`none`, decision=`keep-clarin`, regression=`low`:
- [ ] AC line 453 (`actionlint … exits 0` on the 4 active workflows) is unverifiable-env (actionlint not installed) — same install requirement as DEV-CI-BE-1.
- [ ] AC line 454 (migrate-docker neutralized: absent OR triggers stripped to workflow_dispatch) is machine-checkable and sound — keep. migrate-docker.yml is 7.5-era; either don't port it OR strip push/pull_request triggers.

**DEV-CI-FE-1** — AC verdict `wrong`, vanilla=`partial`, decision=`hybrid`, regression=`medium`:
- [ ] DEFECTIVE AC line 459: replace the suffix-only grep with a full-ref assertion: `git show HEAD:.github/workflows/docker.yml | grep -cE '<org>/DSpace/\.github/workflows/reusable-docker-build\.yml@<branch>' >=2` where <org>/<branch> is the v9 BE fork (NOT DSpace/DSpace@main) — AND separately verify the referenced BE ref actually defines run_python_version_script + python_version_script_dest (cross-repo resolution). A green suffix grep on DSpace/DSpace@main means the dspace-angular image builds against an upstream reusable-build that silently drops CLARIN version-stamping.
- [ ] RESOLVE the same publish-org ambiguity as DEV-CI-BE-1: the FE reusable ref is CROSS-repo (unlike BE's same-repo './'); the target org/DSpace and branch must both exist and carry the reconciled inputs.
- [ ] AC lines 457/458 (guard + image_name greps) are sound but must be re-pinned to the final org (dataquest vs ufal).

**DEV-CI-FE-2** — AC verdict `valid`, vanilla=`none`, decision=`keep-clarin`, regression=`none`:
- [ ] AC line 467 (`actionlint … exits 0` on the executable subset) is unverifiable-env (actionlint not installed) — same install requirement.
- [ ] import-weekly.yml / create_bitstreams.yml / erase_db.yml + the 2 composite db actions reference dev-5 import infra and org secrets; when ported, validate they don't reference a repo/secret absent in the v9 fork (tie to publish-org resolution).

_Missing work items / uncovered ACs in this domain:_
- [ ] FE-side Matomo deploy reuse sources — dtq-dev dspace-angular docker/matomo-w-db.yml + docker/matomo-settings.ts (both present origin/dtq-dev, ABSENT@HEAD FE) are covered by NO DEV AC: DEV-MATOMO-1 is BE-only (scripts/docker/matomo/* = 5 files), DEV-SCRIPTS-FE-1 is build-scripts/ only (docker/ excluded). Plan line 177 flags them as an OPEN cross-domain question with no AC/owner. Decision required: native FE matomo-config.interface.ts@HEAD SUPERSEDES dtq-dev src/config/matomo-config.ts (do NOT re-port), but matomo-w-db.yml/matomo-settings.ts may still be needed as a LOCAL Matomo server so the vanilla docker-compose-matomo.yml (8081) tracking-test path has a real endpoint — assign an owner and a machine-checkable AC (or explicitly defer).
- [ ] CLARIN Matomo PDF-report LOCAL config path — MatomoPDFExporter/MatomoHelper@HEAD read lr.statistics.api.url/.cached.url/.auth.token/.site_id (clarin-dspace.cfg l.156-159, remote ufal/lindat). NEITHER the vanilla docker-compose-matomo.yml NOR the dtq-dev matomo-w-db.yml wires a local Matomo Reporting API + token for this surface. No AC provides a local report-test path; report reachability at /items/{uuid}/statistics is unverified. Cross-domain (CLARIN-feature-completion) — needs an explicit owner so it is not dropped between DevOps and the feature domain.
- [ ] Publish-org resolution (dataquest-dev vs ufal) — no AC pins the actual org/branch the v9 fork will push images to; DEV-CI-BE-1/BE-2/FE-1/FE-2 all grep for the dtq-dev-era `dataquest*` strings, but if the real v9 publish org differs, the fork guard never matches and publishes nothing (the exact failure the CI restore is meant to fix). Add a single canonical org/branch decision that all four CI ACs reference, including the cross-repo FE reusable-build target.
- [ ] actionlint toolchain provisioning — 4 ACs (DEV-CI-BE-1/BE-2/FE-1/FE-2) gate on `actionlint … exits 0` but actionlint is not installed in this environment; no work item owns installing/pinning the actionlint version the gates assume.
- [ ] ClarinMatomoOAITracker (OAI-PMH harvest tracking) — absent@HEAD, no native equivalent (native org.dspace.matomo tracks only UsageEvents), so VLO/WebLicht harvest counts vanish under native-only tracking. Cross-domain (owned by the OAI/native-features domain per plan lines 161/336), noted here as a DevOps-adjacent dependency, not a DevOps miss.

### Testing & acceptance strategy — the done gate  _(needs-fixes)_

**TEST-PROVENANCE** — AC verdict `valid`, vanilla=`partial`, decision=`hybrid`, regression=`none`:
- [ ] Provenance oracle (primary): `docker inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' clarinv9-dspace` == BE HEAD; same for clarinv9-dspace-angular == FE HEAD; script exits non-zero (fail-closed) if label absent.
- [ ] Provenance fallback (pinned, machine-checkable): `docker exec clarinv9-dspace sh -c 'find / -name MetadataBitstreamRestRepository.class 2>/dev/null | head -1'` returns a path -> proves the CLARIN REST layer is compiled into the running image; empty => FAIL. Do not accept the 200 from byHandle as proof of provenance (it is served today with the class ABSENT from HEAD).
- [ ] FE container asserted with the SAME script and SAME fail-closed behaviour as BE.

**TEST-BE-BUILDERS** — AC verdict `wrong`, vanilla=`none`, decision=`keep-clarin`, regression=`low`:
- [ ] Replace AC#2 with: `git diff HEAD origin/dtq-dev -- src/test/java/org/dspace/builder/util/AbstractBuilderCleanupUtil.java` shows the ONLY functional delta is the PreviewContentBuilder registration (rest = jakarta import churn); grep -c PreviewContentBuilder in ported file == 1.
- [ ] Add ClarinHandleBuilder.java to the ADDED-builder list (11, not 10).
- [ ] Keep the smoke IT (4 builders + abort -> 0 orphan rows) as the real cleanup oracle instead of any grep count.

**TEST-BE-IT-CRIT** — AC verdict `valid`, vanilla=`none`, decision=`keep-clarin`, regression=`none`:
- [ ] Add: `mvn dependency:tree -pl dspace-api | grep -E 'tukaani|commons-compress'` resolves both on the TEST classpath; else logos.7z/logos.xz/logos.tar.xz preview ITs throw at runtime (this is the preview domain's pom fix, but this test set needs it).
- [ ] Add ClarinTokenServiceIT (org.dspace.app.rest.authorization) to the explicit reuse list (currently prose-only).
- [ ] Add: AuthorizationRestControllerIT must pass in the SAME run as vanilla RequestItemRepositoryIT (see TEST-REQCOPY-COEXIST).

**TEST-BE-UNIT** — AC verdict `weak`, vanilla=`partial`, decision=`hybrid`, regression=`low`:
- [ ] Classify the *DAO*Test that extend AbstractIntegrationTestWithDatabase and assert they land in the failsafe (IT) run, not surefire, so -DskipIntegrationTests=true does not silently skip them.
- [ ] Re-tag the matomo unit subset partial/hybrid: MatomoHelperTest/ClarinMatomoBitstreamTrackerTest/MatomoReportSubscriptionServiceTest are ported AND the 10 vanilla org.dspace.matomo unit tests stay green in the same surefire run (assert both counts via test census).
- [ ] Flag the HEAD orphan: MatomoHelper.java+MatomoPDFExporter.java are present at HEAD (ABSENT in dspace-9.3) but MatomoHelperTest is ABSENT at HEAD -> either finish the port (add the test) or revert the partial injection; a DoD gate must forbid main-class-without-its-test.

**TEST-BE-IT-REST** — AC verdict `weak`, vanilla=`partial`, decision=`keep-clarin`, regression=`medium`:
- [ ] Replace the glob with an EXHAUSTIVE list = all 55 ADDED ITs minus TEST-BE-IT-CRIT's 13 minus the 3 curate ITs; explicitly include the 14 orphans above.
- [ ] Add gating precondition: TEST-CONFIG-DATA is merged BEFORE this item runs (else ITs pass on vanilla config = false green).
- [ ] Add: OAIPMHBundleExposureIT (org.dspace.app.oai) and SolrOAIReindexerIT (org.dspace.app.rest) are ported here — both are fork tests in dspace-server-webapp, so the 'do not touch dspace-oai tests' guard does not exclude them.
- [ ] Add: `mvn verify -Dit.test=ProvenanceServiceIT` passes (needs ProvenanceExpectedMessages fixture from TEST-BE-BUILDERS).
- [ ] Add: MatomoReportSubscriptionRestRepositoryIT passes alongside the vanilla matomo tests (see TEST-MATOMO-COLLISION).

**TEST-OAI-ORACLE** — AC verdict `wrong`, vanilla=`partial`, decision=`hybrid`, regression=`low`:
- [ ] Fix path reference: vanilla model is org/dspace/app/oai/OAIpmhIT.java.
- [ ] ListMetadataFormats AC: after CLARIN crosswalk config is ported, the format list includes cmdi, olac, metasharev2, elg in addition to the 13 vanilla formats.
- [ ] GetRecord AC (pinned): `curl 'http://localhost:18080/server/oai/request?verb=GetRecord&metadataPrefix=cmdi&identifier=oai:<dspace.oai.id.prefix>:11234/1-5419'` returns a record with NO <error code="cannotDisseminateFormat"> — 11234/1-5419 is the dev-5 sample known to have a cmdi crosswalk; if that handle lacks cmdi, substitute a pinned handle documented in the AC, never an unbound query.
- [ ] Do NOT edit dspace-oai/src/test (byte-vanilla); OAIPMHBundleExposureIT+SolrOAIReindexerIT are ported via TEST-BE-IT-REST.

**TEST-FE-SPECS** — AC verdict `weak`, vanilla=`none`, decision=`keep-clarin`, regression=`low`:
- [ ] Add a per-spec deferral census: for each of the 70 ADDED specs, either it runs green OR it is listed in a DoD deferral table with xdescribe/it.skip('deferred:<id>'); assert `grep -rE 'xdescribe|fdescribe|it.skip|fit\(' <ported spec set>` matches only the documented deferral list.
- [ ] Add test census guard: karma reports at least (vanilla FE spec count + 70) executed specs; a drop signals silently-dropped CLARIN specs.
- [ ] Cypress e2e is a SEPARATE tier -> TEST-CYPRESS-E2E (not covered by test:headless).

**TEST-CI-VERIFY** — AC verdict `wrong`, vanilla=`full`, decision=`use-vanilla`, regression=`medium`:
- [ ] Assert CI runs on the VANILLA build.yml/build.yml(FE): a PR touching a ported IT shows that IT executed in the failsafe report artifact (grep the run's target/failsafe-reports for the classname).
- [ ] DoD gate: do NOT reintroduce dataquest self-hosted-runner workflows; if present, CI must fail the DoD (`grep -L 'self-hosted' .github/workflows/*.yml` on the CLARIN test jobs).
- [ ] Test census AC: failsafe report ITs >= (vanilla IT count + 55 ported) and surefire tests >= (vanilla + 24); a shortfall fails the gate.

**TEST-MODVANILLA-BE** — AC verdict `weak`, vanilla=`partial`, decision=`hybrid`, regression=`medium`:
- [ ] Enumerate the exact 66-file diff-filter=M set in the handoff (`git diff --diff-filter=M --name-only dspace-7.6.5 origin/dtq-dev -- dspace-api/src/test dspace-server-webapp/src/test`).
- [ ] Per-file machine check: for each modified test, `git diff HEAD origin/dtq-dev -- <file>` after port shows the CLARIN hunk is present (non-empty CLARIN-attributable delta), OR the file is on a deferral table; a byte-vanilla file where dtq-dev has a hunk = FAIL.

**TEST-MODVANILLA-FE** — AC verdict `weak`, vanilla=`partial`, decision=`hybrid`, regression=`low`:
- [ ] Do NOT treat the 737 as a port unit; filter to specs whose diff hunk touches a CLARIN component/selector (`git diff dtq-dev-9-base origin/dtq-dev -- <spec> | grep -iE 'clarin|handle|preview|matomo|klaro|license'`) and route each to its component-port work item.
- [ ] Assert no CLARIN-behavioral spec hunk is dropped: sample-audit the filtered subset post-port.

**TEST-CONFIG-DATA** — AC verdict `missing-ac`, vanilla=`partial`, decision=`hybrid`, regression=`high`:
- [ ] Port the exact 8-file diff-filter=M set; per-file assert `git diff HEAD origin/dtq-dev -- <file>` post-port is empty of CLARIN-attributable hunks (i.e. the CLARIN delta is applied).
- [ ] Machine check the identifier swap: `grep -c ClarinVersionedHandleIdentifierProvider dspace-api/src/test/.../identifier-service.xml == 1`; ClarinVersionedHandleIdentifierProviderIT then passes.
- [ ] Declare this item a hard predecessor of TEST-BE-IT-CRIT, TEST-BE-IT-REST, TEST-BE-UNIT (DAO ITs).

**TEST-CYPRESS-E2E** — AC verdict `missing-ac`, vanilla=`none`, decision=`keep-clarin`, regression=`low`:
- [ ] Port the 4 CLARIN-added specs; `git ls-tree HEAD -- cypress/e2e | grep -E 'admin-menu|handle-page|submission-ui|tombstone'` returns all 4 post-port.
- [ ] Wire the nightly/e2e Cypress CI job to run them against the running FE+BE; assert each of the 4 executes (Cypress run summary lists the 4 spec files, none pending/skipped).
- [ ] Route the 33 MODIFIED e2e specs' CLARIN hunks with their component ports (do not blanket-port).

**TEST-MATOMO-COLLISION** — AC verdict `missing-ac`, vanilla=`partial`, decision=`hybrid`, regression=`high`:
- [ ] Coexistence test AC: a single simulated bitstream download produces exactly ONE Matomo track request (assert count==1 via mock/wiremock in an IT) — proves no double-count.
- [ ] OAI-harvest track retained: a simulated OAI harvest still emits a ClarinMatomoOAITracker request (vanilla native has no OAI tracker) — proves no regression.
- [ ] Spring guard: exactly one usage-event Matomo listener is active (`grep` active bean config: not both MatomoEventListener/MatomoUsageEventHandler AND ClarinMatomoTracker on the same UsageEvent); the 10 vanilla matomo unit tests stay green.
- [ ] Resolve the HEAD orphan MatomoHelper/MatomoPDFExporter: either complete the port (restore MatomoHelperTest) or revert the injection.

**TEST-REQCOPY-COEXIST** — AC verdict `missing-ac`, vanilla=`partial`, decision=`hybrid`, regression=`medium`:
- [ ] Coexistence AC: `mvn verify -Dit.test=RequestItemRepositoryIT,AuthorizationRestControllerIT` both pass in one run.
- [ ] Live probe: a valid vanilla accessToken download AND a valid CLARIN /api/authrn download-token both return 200 on GET .../bitstreams/{id}/content, and an invalid/absent token returns 403 for both paths.

_Missing work items / uncovered ACs in this domain:_
- [ ] No DoD 'test census' gate ties CI-executed test counts to the ported inventory (>= vanilla + 55 ITs, + 24 unit, + 70 FE specs, + 4 cypress e2e); without it, silently-skipped ports pass green.
- [ ] No item reconciles the HEAD-present PARTIAL Matomo port (MatomoHelper.java+MatomoPDFExporter.java at HEAD, ABSENT in dspace-9.3, MatomoHelperTest ABSENT at HEAD) — an orphan main-class-without-test that must be finished or reverted (cross-refs TEST-MATOMO-COLLISION / TEST-BE-UNIT).
- [ ] No item enables/schedules the FE Cypress e2e CI job in the ufal fork (the 4 specs can be committed yet never run if no workflow triggers them).
- [ ] ROR needs no CLARIN test work item (vanilla RorImportMetadataSourceServiceIT + ror-record(s).json fully cover it; no ClarinRor test exists) — should be explicitly recorded as 'use-vanilla, no port' so an implementer does not invent a redundant CLARIN ROR test.
- [ ] No item triages the 737 MODIFIED FE specs / 66 MODIFIED BE test .java to isolate CLARIN-behavioral hunks from churn; risk of a dropped hunk being invisible to a green run.
- [ ] Env/policy precondition unowned: the implementer must be permitted to rebuild+redeploy the clarinv9 Docker stack, else TEST-PROVENANCE and all live oracles cannot be exercised.
