# Design doc: retire `user_registration`, link Clarin license tables directly to `eperson`

Date: 2026-10-13 (updated 2026-10-14: frontend scope + `license_definition` findings)

Status: proposal / not yet implemented

---

## 1. Problem

The Clarin license subsystem (`license_definition`, `license_resource_mapping`,
`license_resource_user_allowance`, `user_metadata`, `user_registration`) models a user via a
bespoke shadow table, `user_registration`, instead of linking directly to DSpace's own `eperson`
table. This creates several problems:

1. **Redundant data.** `user_registration.email` duplicates `eperson.email` (already `UNIQUE`).
   The two can drift out of sync since nothing keeps them in lockstep after creation.
2. **Dead field.** `user_registration.confirmation` is set to `true` on every live creation path
   (Shib login, admin CLI tools, admin REST eperson creation) and is never read as a gate anywhere
   in the backend — see [Section 5](#5-the-confirmation-field) for the full trace.
3. **Fragile anonymous sentinel.** Anonymous (not-logged-in) license click-throughs are supposed to
   be attributed to one specific hand-provisioned row (`email = 'anonymous'`,
   `organization = 'anonymous'`, `eperson_id = NULL`). Nothing in any SQL migration or bootstrap
   code creates this row — it must have been inserted manually at some point. If it's ever missing
   (fresh install, dropped by accident), the code silently falls back to `user_registration_id = NULL`
   on `license_resource_user_allowance`, which happens to be treated the same way by every reader
   today, but only by convention, not by design.
4. **Unnecessary indirection.** `license_resource_user_allowance` and `user_metadata` both reference
   `user_registration`, which in turn references `eperson` (0..1 — the anonymous row has
   `eperson_id = NULL`). That's two hops to get from an allowance/metadata record to the actual
   DSpace identity, when one would do.
5. **Denormalized duplicate on `user_metadata`.** `user_metadata.user_registration_id` duplicates
   `user_metadata.transaction_id → license_resource_user_allowance.user_registration_id` — every
   creation path sets both from the same object. See [Section 6](#6-usermetadatauser_registration_id-is-redundant).
6. **The same indirection reaches a third table: `license_definition`.** `license_definition` also
   has a `user_registration_id` FK (Java field misleadingly named `eperson`, typed
   `ClarinUserRegistration`, not `EPerson`) recording who created the license. It's write-only —
   never surfaced in the REST response — but `ClarinLicenseRestRepository.createAndReturn()` has a
   hard runtime dependency on it: it looks up a `user_registration` row for the current admin and
   throws `UnprocessableEntityException` if none exists. Retiring `user_registration` requires
   rewiring this table too, not just the two already covered above. See
   [Section 7](#7-license_definition-needs-the-same-treatment).

### Current schema (relevant subset)

```
eperson (uuid PK, email UNIQUE, firstname, lastname, ...)

user_registration (user_registration_id PK, eperson_id UUID NULL → eperson.uuid,
                    email, organization, confirmation)

license_definition (license_id PK, name, definition,
                     user_registration_id → user_registration.user_registration_id (nullable, "created by"),
                     label_id (dead, see Section 7), created_on (dead, see Section 7),
                     confirmation, required_info)

license_resource_mapping (mapping_id PK, bitstream_uuid → bitstream.uuid,
                           license_id → license_definition.license_id)

license_resource_user_allowance (transaction_id PK,
                                  mapping_id → license_resource_mapping.mapping_id,
                                  user_registration_id → user_registration.user_registration_id (nullable),
                                  created_on, token)

user_metadata (user_metadata_id PK,
               user_registration_id → user_registration.user_registration_id (nullable),
               transaction_id → license_resource_user_allowance.transaction_id (nullable),
               metadata_key, metadata_value)
```

Cardinalities as they exist today:

- `eperson (1) — (0..n) user_registration`, **not** `(0..1)` as originally assumed here — confirmed
  against production data that a single `eperson` can have *multiple* `user_registration` rows
  (e.g. one created via standard REST eperson creation with `organization = 'Unknown'`, a second
  created later via Shib login with a real IdP entityID as `organization`). Nothing in the schema
  or application code enforces a 1:1 relationship; this matters for the `organization` backfill
  (Section 4), which must collapse/rank multiple source rows per eperson rather than assume exactly
  one.
- `user_registration (1) — (0..n) license_resource_user_allowance`
- `license_resource_user_allowance (1) — (0..n) user_metadata`
- `user_registration (1) — (0..n) user_metadata` (redundant with the previous two combined)
- `user_registration (1) — (0..n) license_definition` (the "created by" owner link, write-only —
  see Section 7)

---

## 2. Goal

Remove `user_registration` as a distinct entity. Link `license_resource_user_allowance` and
`license_definition` directly to `eperson`, drop the redundant FK on `user_metadata` entirely, and
move `organization` (and a decision on `confirmation`, see Section 5) onto `eperson` using DSpace's
existing eperson-metadata mechanism instead of a bespoke table. Along the way, also drop two dead
columns found on `license_definition` while touching that table (Section 7) and update
`dspace-angular` to match the new REST shape (Section 8) — both were open items in the original
version of this doc and are now resolved findings folded into the plan.

### Why eperson metadata, not a new eperson column

`EPerson` already supports arbitrary metadata fields via the standard DSpace metadata framework —
schema `eperson` (`dspace/config/registries/eperson-types.xml`), currently holding
`eperson.firstname/lastname/phone/language/orcid(.scope)`. `ClarinShibAuthentication` already
dynamically registers new fields on this schema at runtime (`autoCreateEpersonMetadataField()`).

Adding a dedicated column to the core `eperson` table is avoidable and worth avoiding: this is a
fork of upstream DSpace, and schema changes to core tables increase the odds of migration conflicts
on future upstream merges. A metadata field is the officially-supported extension point and needs
no change to core DSpace schema or Java entities.

---

## 3. Proposed schema

```
eperson (unchanged)
  └─ eperson_metadata: eperson.organization   (new)
  └─ eperson_metadata: eperson.confirmed      (new — see Section 5 for alternative: drop instead)

license_resource_user_allowance (transaction_id PK,
                                  mapping_id → license_resource_mapping.mapping_id,
                                  eperson_id UUID NULL → eperson.uuid,   -- was user_registration_id
                                  created_on, token)

user_metadata (user_metadata_id PK,
               transaction_id → license_resource_user_allowance.transaction_id,  -- NOT NULL
               metadata_key, metadata_value)
               -- user_registration_id / eperson_id column dropped entirely

license_definition (license_id PK, name, definition,
                     eperson_id UUID NULL → eperson.uuid,   -- was user_registration_id
                     confirmation, required_info)
                     -- label_id, created_on columns dropped entirely (dead, see Section 7)
```

Cardinalities after the redesign:

- `eperson (1) — (0..n) license_resource_user_allowance`, nullable FK = anonymous request
  (no sentinel row required — `NULL` means "no eperson," exactly as it does everywhere else in
  DSpace).
- `license_resource_mapping (1) — (0..n) license_resource_user_allowance` — unchanged.
- `license_resource_user_allowance (1) — (0..n) user_metadata` — unchanged, now the *only* path
  from a `user_metadata` row back to an eperson.
- `eperson (1) — (0..n) license_definition`, nullable FK = owner/"created by" link (write-only,
  never surfaced over REST today — see Section 7).

One hop instead of two. No sentinel row. No duplicate email. No redundant FK on `user_metadata`.
`license_definition` collapses from two hops (`→ user_registration → eperson`) to one, same as the
other two tables.

### Important caveat: this does not solve "anonymous vs. anonymized"

A `NULL eperson_id` on `license_resource_user_allowance` will still be ambiguous between
"this was always an anonymous request" and "this belonged to a real eperson that was later
deleted/anonymized" (DSpace does support eperson deletion in some flows). This redesign doesn't
address that distinction — it was discussed separately as an orthogonal fix (an explicit
`anonymous` generated column, or a `requester_type` enum) and can be layered on top of this
redesign later if/when that distinction becomes operationally necessary. Not in scope here.

---

## 4. Migration plan

1. **Schema**
   - Add nullable `eperson_id UUID` to `license_resource_user_allowance`.
   - Backfill: `UPDATE license_resource_user_allowance lrua SET eperson_id = ur.eperson_id
     FROM user_registration ur WHERE lrua.user_registration_id = ur.user_registration_id;`
     (Rows pointing at the anonymous sentinel have `ur.eperson_id IS NULL`, so they backfill to
     `NULL` automatically — the anonymous meaning is preserved for free.)
   - Add FK `eperson_id → eperson(uuid)`.
   - Drop `user_registration_id` column + FK from `license_resource_user_allowance`.
   - Drop `user_registration_id` column + FK from `user_metadata` entirely (no replacement column —
     see Section 6).
   - Register `eperson.organization` in the eperson metadata schema (idempotent — safe to run even
     if the field already exists) and backfill from `user_registration.organization`. Because a
     single eperson can have multiple `user_registration` rows (corrected cardinality, see Section
     1), this can't be a naive 1:1 copy: placeholder/noise values must be excluded first
     (`'Unknown'` — the hardcoded constant from generic REST eperson creation — and the `'anonymous'`
     sentinel), remaining values deduped per eperson, and — when more than one *real* distinct value
     remains for the same eperson — ranked by recency (there's no `created_on` on `user_registration`,
     so the surrogate `user_registration_id` is the only ordering signal available) so the most
     recent becomes the primary (`place = 0`) value and older ones survive as additional repeated
     metadata values rather than being silently discarded:

     ```sql
     -- 1. Register the field (idempotent)
     INSERT INTO metadatafieldregistry (metadata_schema_id, element, qualifier, scope_note)
     SELECT msr.metadata_schema_id, 'organization', NULL, NULL
     FROM metadataschemaregistry msr
     WHERE msr.short_id = 'eperson'
       AND NOT EXISTS (
         SELECT 1 FROM metadatafieldregistry mfr
         WHERE mfr.metadata_schema_id = msr.metadata_schema_id
           AND mfr.element = 'organization'
           AND mfr.qualifier IS NULL
       );

     -- 2. Backfill, collapsing multiple user_registration rows per eperson
     WITH org_field AS (
       SELECT mfr.metadata_field_id
       FROM metadatafieldregistry mfr
       JOIN metadataschemaregistry msr ON msr.metadata_schema_id = mfr.metadata_schema_id
       WHERE msr.short_id = 'eperson'
         AND mfr.element = 'organization'
         AND mfr.qualifier IS NULL
     ),
     candidate AS (
       SELECT eperson_id, organization, MAX(user_registration_id) AS latest_ur_id
       FROM user_registration
       WHERE eperson_id IS NOT NULL
         AND organization IS NOT NULL
         AND trim(organization) <> ''
         AND organization NOT IN ('Unknown', 'anonymous')
       GROUP BY eperson_id, organization
     ),
     ranked AS (
       SELECT eperson_id, organization,
              row_number() OVER (PARTITION BY eperson_id ORDER BY latest_ur_id DESC) - 1 AS place
       FROM candidate
     )
     INSERT INTO metadatavalue (metadata_field_id, text_value, text_lang, place, authority,
                                 confidence, dspace_object_id)
     SELECT org_field.metadata_field_id, ranked.organization, NULL, ranked.place, NULL, -1,
            ranked.eperson_id
     FROM ranked, org_field;
     ```

     Worked example from production data: eperson `3d47e27e...` has two `user_registration` rows —
     `organization = 'Unknown'` (excluded by the placeholder filter) and
     `organization = 'https://idp.clarin.eu'` (kept) — so it backfills cleanly to one metadata value,
     no conflict. `confirmed` is not migrated at all — see Section 5's decision to drop it outright.
   - **`license_definition` (new, Section 7):**
     - Add nullable `eperson_id UUID` to `license_definition`.
     - Backfill: `UPDATE license_definition ld SET eperson_id = ur.eperson_id FROM user_registration
       ur WHERE ld.user_registration_id = ur.user_registration_id;`
     - Add FK `eperson_id → eperson(uuid)`.
     - Drop `user_registration_id` column + FK from `license_definition`.
     - Drop the dead `label_id` column (its FK was already commented out at table-creation time and
       it has no ORM mapping — labels are modeled entirely through `license_label_extended_mapping`).
     - Drop the dead `created_on` column (no ORM mapping in `ClarinLicense.java`, unlike the
       structurally identical and actively-mapped `created_on` on `license_resource_user_allowance`).
     - Leave the `confirmation` column untouched — it is a live 4-state access-control enum
       (`NOT_REQUIRED`/`ASK_ONLY_ONCE`/`ASK_ALWAYS`/`ALLOW_ANONYMOUS`) enforced in
       `AuthorizationBitstreamUtils` and unrelated to `user_registration.confirmation` beyond
       sharing a column name — see Section 5's clarifying note.
     - Not touching the misnamed `license_definition_license_id_key` index (it's actually on `name`)
       or the no-op `V7.6_2024.10.25__insert_default_licenses.sql` migration (201 lines, 100%
       commented out) — both are harmless housekeeping items, out of scope for this redesign, and
       the latter must not be deleted once shipped (Flyway validates checksums of already-applied
       migrations against environments that ran it).
   - Drop `user_registration` table and its sequence.

2. **Java / REST**
   - Retire `ClarinUserRegistration` entity, `ClarinUserRegistrationDAO`/`ServiceImpl`,
     `ClarinUserRegistrationRest`/`Converter`.
   - `ClarinLicenseResourceUserAllowance.userRegistration` → `EPerson eperson` (direct
     `@ManyToOne` / nullable `@JoinColumn(name = "eperson_id")`).
   - `ClarinUserMetadata` loses its `eperson` field and `user_registration_id` column entirely;
     callers resolve the requester via `metadata.getTransaction().getEperson()` instead.
   - Rewrite `ClarinUserMetadataDAOImpl.findByUserRegistrationAndBitstream()` to join through
     `cum.transaction.eperson` instead of a direct `cum.eperson` join.
   - Rewrite `ClarinUserMetadataServiceImpl.authorizeClarinUserMetadataAction()` similarly
     (`userMetadatum.getTransaction().getEperson()`).
   - Update `ClarinUserMetadataRestController.processSignedInUser()` /
     `processNonSignedInUser()`: drop the `findByEmail("anonymous")` + organization-string-filter
     lookup; for signed-in users resolve `currentUser` (`EPerson`) directly; for anonymous, simply
     leave `eperson` unset (`NULL`) — no sentinel lookup needed at all.
   - Update link repositories: `CLRUAUUserRegistrationLinkRepository` →
     `CLRUAEPersonLinkRepository` (or extend the existing eperson link repository pattern),
     `ClarinUserRegistrationUserMetadataLinkRepository`, `CUserRegistrationCLicenseLinkRepository`
     — all need to be re-pointed at `EPerson` or retired if redundant with existing core eperson
     REST endpoints.
   - Fix `ClarinUserMetadataImportController.java:180`'s inconsistent `setEperson()` call (it
     currently only re-points list index `0`) while touching this code — becomes moot once
     `user_metadata` has no `eperson`/`user_registration` field to set at all.
   - **`license_definition` (new, Section 7):** retype `ClarinLicense.eperson` from
     `ClarinUserRegistration` to `EPerson` (field name is already `eperson`, just wrongly typed —
     the getter/setter names don't need to change). `@JoinColumn(name = "user_registration_id")` →
     `@JoinColumn(name = "eperson_id")`. Rewrite
     `ClarinLicenseRestRepository.createAndReturn()`/`.put()` to set the owner from
     `context.getCurrentUser()` directly instead of
     `userRegistrationService.findByEPersonUUID(...).get(0)` — this also removes the hard failure
     mode where license creation throws `UnprocessableEntityException` if the admin's `EPerson`
     happens to have no `user_registration` row, since an `EPerson` always exists for a logged-in
     context. Update `ClarinLicenseImportControllerIT.java:166`, the one test asserting
     `license.getEperson().getID() == userRegistration.getID()`, to compare against the `EPerson`
     directly instead.

3. **Frontend (`dspace-angular`) — resolved, in scope** (see Section 8 for full detail):
   checked the frontend directly. `organization`/`confirmation` on `ClarinUserRegistrationRest` are
   declared on the TS model but have zero consumers — safe to drop per Section 5 option (a). The
   resource *itself*, however, is in active use by `clarin-license-agreement-page.component.ts`
   (searches `byEPerson`, then queries `ClarinUserMetadata` by the returned registration's `id`) and
   by `clrua.model.ts` (`_links.userRegistration` HAL link). Both need updating to key off `eperson`
   instead once the REST resource is retired — this is now part of this redesign, not a separate
   follow-up.

4. **Pre-migration data check:** confirm there is no existing `user_metadata` row with
   `transaction_id IS NULL` — the redesign assumes every `user_metadata` row has a transaction to
   derive its eperson from. All current code paths set `transaction_id` at creation time, but this
   should be verified against production data before dropping the column irreversibly. Also confirm
   there is no `license_definition` row whose `user_registration_id` points at a `user_registration`
   row with `eperson_id IS NULL` other than the anonymous sentinel (i.e. no license was ever created
   "by" the anonymous placeholder) — `ClarinLicenseRestRepository.createAndReturn()` always
   populates it from a real logged-in admin today, but this should be verified against production
   data before the backfill.

This is a larger, more invasive change than the earlier (already-decided) fix of guaranteeing the
anonymous sentinel row — it touches core identity linking across 3 backend tables, multiple REST
endpoints, and the frontend repo (Section 8). All of that is now bundled into one plan rather than
split into a separate follow-up, since the frontend changes are small and tightly coupled to the
REST shape change.

---

## 5. The `confirmation` field

> **Not to be confused with `license_definition.confirmation`.** That's a separate, *live* column
> on an unrelated table — a 4-state access-control enum that gates bitstream download authorization
> (`NOT_REQUIRED`/`ASK_ONLY_ONCE`/`ASK_ALWAYS`/`ALLOW_ANONYMOUS`, see Section 7). The two columns
> share a name purely by coincidence (`integer DEFAULT 0` enum ordinal vs. `boolean`, different
> tables). Nothing in this section applies to it — it is explicitly untouched by this redesign.

### What it does today

Traced every creation path in the backend (`ShibAuthentication`, `ClarinShibAuthentication`,
`CreateAdministrator`, `EPersonCLITool`, `EPersonRestRepository`): every one of them sets
`confirmation = true` immediately upon creating a `ClarinUserRegistration`. The only path that can
produce `confirmation = false` is `ClarinEPersonImportController`, which just carries over whatever
value an imported payload supplies.

No code anywhere reads `isConfirmation()` as a gate on login, access, or license behavior — the only
reads are: REST conversion (`ClarinUserRegistrationConverter`, pass-through), and an equality check
in the import controller used purely to decide whether to overwrite the value during import
(bookkeeping, not business logic).

The one hint of original intent is a comment in `ClarinShibAuthentication`: *"if no email the
registration is postponed after entering and confirming mail"* — suggesting the field was meant to
distinguish a fully-set-up registration from one still waiting on the user to supply/confirm an
email. That flow does not appear to be implemented today; registration is simply skipped (not
created with `confirmation = false`) when there's no email yet.

### Where to move it

Two options, in order of preference:

**(a) Drop it.** Given it gates nothing in the backend and the "postponed registration" flow it was
apparently built for isn't implemented, the simplest and most honest move is to not carry it
forward at all. This removes one more piece of dead state instead of relocating it.

**(b) Migrate it as `eperson.confirmed` metadata, alongside `eperson.organization`.** If there's
any appetite for eventually implementing the "postponed registration pending email confirmation"
flow the original comment hints at, preserve the historical data and the field itself as a second
new eperson metadata field, using the same mechanism as `organization`
(Section 3). This costs nothing extra migration-wise since it rides along with the `organization`
backfill, and keeps the option open without committing to new behavior now.

**Decision: go with (a).** The frontend check (Section 8) confirms `ClarinUserRegistration.confirmation`
is declared on the TS model but has zero reads anywhere in `dspace-angular` — no template binding, no
gate logic. Safe to drop with no frontend contract to break.

---

## 6. `user_metadata.user_registration_id` is redundant

Every creation path (`ClarinUserMetadataRestController.processSignedInUser()` /
`processNonSignedInUser()`) sets `user_metadata.eperson` and
`license_resource_user_allowance.userRegistration` from the *same* `clarinUserRegistration` object:

```java
clarinUserMetadata.setEperson(clarinUserRegistration);
...
clrua.setUserRegistration(clarinUserRegistration);
clarinUserMetadata.setTransaction(clrua);
```

So `user_metadata.user_registration_id` is a flattened duplicate of
`user_metadata.transaction_id → license_resource_user_allowance.user_registration_id`, written
twice from the same source on every insert. Confirmed redundant, not independently meaningful:

- `ClarinUserMetadataDAOImpl.findByUserRegistrationAndBitstream()` already joins both `cum.eperson`
  *and* `cum.transaction → clrua.licenseResourceMapping` in the same query — the first join is
  unnecessary once the second is present.
- `ClarinUserMetadataServiceImpl.authorizeClarinUserMetadataAction()` reads `getEperson()` directly;
  `getTransaction().getUserRegistration()` (post-redesign: `.getEperson()`) gives the identical
  value.

One wrinkle, not a counter-argument: `ClarinUserMetadataImportController.java:180` re-points
`eperson` on only the first (`get(0)`) item of a created metadata list after the normal flow already
set it on all items — the only spot where the two copies could theoretically diverge today. This
reads as an overlooked inconsistency in the import path rather than evidence the second column is
needed; it becomes moot once the column is removed (Section 4).

**Conclusion:** drop the column entirely (Section 3/4), do not replace it with an `eperson_id`
column on `user_metadata` — resolve the requester via `transaction.eperson` everywhere.

---

## 7. `license_definition` needs the same treatment

`license_definition` wasn't in the original scope of this doc, but it has the identical
`user_registration` two-hop indirection problem, plus two unrelated dead columns discovered while
tracing it. Bundling it in here since the fix is the same shape and touches the same migration.

### 7.1 The `user_registration_id` owner FK

`license_definition.user_registration_id` records who created the license. In Java it's
`ClarinLicense.eperson`, `@ManyToOne @JoinColumn(name = "user_registration_id")` — note the field
is named `eperson` but typed `ClarinUserRegistration`, not `EPerson`; there is no `eperson_id`
column on this table today, only the two-hop path `license_definition.user_registration_id →
user_registration.eperson_id → eperson.uuid`.

Unlike `license_resource_user_allowance.user_registration_id` (read constantly, on every
authorization check), this one is **write-only**: `ClarinLicenseConverter.convert()` never includes
it in `ClarinLicenseRest`, so no REST client ever sees it. But it's not inert — license creation has
a hard runtime dependency on it:

```java
// ClarinLicenseRestRepository.createAndReturn()
List<ClarinUserRegistration> userRegistrations = userRegistrationService.findByEPersonUUID(context,
        context.getCurrentUser().getID());
if (CollectionUtils.isEmpty(userRegistrations)) {
    throw new UnprocessableEntityException("Clarin License user registration, cannot be null");
}
ClarinUserRegistration userRegistration = userRegistrations.get(0);
...
clarinLicense.setEperson(userRegistration);
```

If the currently-logged-in admin's `EPerson` has no matching `user_registration` row, license
creation fails outright. Once `user_registration` is retired this lookup has nothing to query —
the fix is to set the owner from `context.getCurrentUser()` (an `EPerson`) directly, which also
deletes this failure mode entirely rather than relocating it.

The migration (full SQL in Section 4): add `eperson_id UUID` FK to `eperson.uuid`, backfill via
`user_registration.eperson_id`, drop `user_registration_id`. Retype `ClarinLicense.eperson` to
`EPerson`, rewrite `ClarinLicenseRestRepository.createAndReturn()`/`.put()`, and update
`ClarinLicenseImportControllerIT.java:166` (the one test touching this field).

### 7.2 Two dead columns, unrelated to the `user_registration` retirement

Found while tracing the table — both harmless to drop in the same migration since it's already
rewriting this table's schema:

- **`label_id`** — its FK was commented out at table-creation time in the original migration
  (`V7.2_2022.07.28__Upgrade_to_Lindat_Clarin_schema.sql`) and it has no field in `ClarinLicense.java`
  at all. All label associations go through the separate `license_label_extended_mapping` join
  table instead. Pure leftover column, never read or written by the ORM.
- **`created_on`** — likewise has no field in `ClarinLicense.java` (confirmed by grep — contrast
  with `license_resource_user_allowance.created_on`, which *is* mapped, on
  `ClarinLicenseResourceUserAllowance.createdOn`). Never populated, never read.

### 7.3 Explicitly not touched

- **`confirmation`** (see the callout at the top of Section 5) — a live 4-state enum
  (`NOT_REQUIRED`/`ASK_ONLY_ONCE`/`ASK_ALWAYS`/`ALLOW_ANONYMOUS`) read by
  `AuthorizationBitstreamUtils.authorizeBitstream()`/`authorizeLicenseWithUser()` and
  `ClarinLicenseResourceMappingServiceImpl.getLicenseToAgree()` to gate bitstream download
  authorization and anonymous access. This is real, load-bearing access-control logic, not dead
  state — out of scope for this redesign entirely.
- **`required_info`** (free-text `varchar(256)`, checked via `.contains("SEND_TOKEN")` in
  `ClarinUserMetadataRestController`) — a stringly-typed config flag, structurally odd but
  functionally alive and tightly coupled to `confirmation`. Worth a follow-up to make it a proper
  typed field some day, but unrelated to this redesign — not touched here.
- The misnamed `license_definition_license_id_key` index (it's actually a unique index on `name`)
  and the no-op `V7.6_2024.10.25__insert_default_licenses.sql` migration (100% commented out) —
  both harmless, both out of scope.

---

## 8. Frontend changes (`dspace-angular`)

Checked directly (this doc originally deferred this as "backend-only, not checked" — that turned
out to be wrong once the REST resource itself, not just its `organization`/`confirmation` fields,
was traced through the frontend). Only 7 files in `dspace-angular` reference
`ClarinUserRegistration` at all.

### 8.1 What's safe as-is

`organization` and `confirmation` are declared on the `ClarinUserRegistration` TS model
(`src/app/core/shared/clarin/clarin-user-registration.model.ts:42-46`) but have **zero consumers**
— no template binding, no component logic reads either field. This clears Section 5's pending
item: dropping `confirmation` (and not moving `organization` anywhere the frontend would notice,
beyond it becoming eperson metadata) breaks nothing.

### 8.2 What needs to change

The resource type itself, and two of its relationships, are in live use and will break once
`ClarinUserRegistrationRest` is retired:

- **`src/app/bitstream-page/clarin-license-agreement-page/clarin-license-agreement-page.component.ts`**
  — `loadUserRegistrationAndUserMetadata()` (lines 377-401) calls
  `clarinUserRegistrationService.searchBy('byEPerson', ...)`, takes the first result, then uses
  `userRegistration.id` to query `ClarinUserMetadata` via `byUserRegistrationAndBitstream`. The
  template (`clarin-license-agreement-page.component.html:42`) only displays
  `(userRegistration$ | async)?.email`. **Rewrite:** drop the `ClarinUserRegistration` lookup
  entirely — use the current `EPerson` (already available from the core auth/eperson service) for
  both the `.email` display and the `ClarinUserMetadata` lookup. The backend-side
  `byUserRegistrationAndBitstream` search method on `ClarinUserMetadataDAOImpl` is being rewritten
  to join through `transaction.eperson` anyway (Section 4); rename/expose it as
  `byEPersonAndBitstream` (or equivalent) so the frontend call site updates in lockstep.
- **`src/app/core/shared/clarin/clrua.model.ts`** (lines 44, 50-51) — `ClruaModel` has a
  `_links.userRegistration` HAL link and
  `@link(CLARIN_USER_REGISTRATION) userRegistration?: Observable<RemoteData<ClarinUserRegistration>>`.
  **Rewrite:** point this at the core `EPerson` HAL link/model instead
  (`_links.eperson` / `@link(EPERSON) eperson?: Observable<RemoteData<EPerson>>`), matching the
  backend's `ClarinLicenseResourceUserAllowance.userRegistration` → `.eperson` rename (Section 4).

### 8.3 Retire entirely

Once 8.2 is done, nothing references these anymore — delete:

- `src/app/core/shared/clarin/clarin-user-registration.model.ts`
- `src/app/core/shared/clarin/clarin-user-registration.resource-type.ts`
- `src/app/core/data/clarin/clarin-user-registration.service.ts`
- The corresponding provider registrations in `src/app/core/core.module.ts:193,234`.

### 8.4 Untouched

`clarin-license-table.component.ts` and `define-license-form.component.ts` read
`ClarinLicense.confirmation` (the license-level enum, Section 7.3) — a completely different field
on a different model (`ClarinLicense`, not `ClarinUserRegistration`). Not affected by this redesign
at all; flagging only so it isn't confused with the field being dropped in Section 5.
