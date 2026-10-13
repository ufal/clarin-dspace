# Design doc: retire `user_registration`, link Clarin license tables directly to `eperson`

Date: 2026-10-13

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

### Current schema (relevant subset)

```
eperson (uuid PK, email UNIQUE, firstname, lastname, ...)

user_registration (user_registration_id PK, eperson_id UUID NULL → eperson.uuid,
                    email, organization, confirmation)

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

- `eperson (1) — (0..1) user_registration` (the anonymous sentinel is the one row with no eperson)
- `user_registration (1) — (0..n) license_resource_user_allowance`
- `license_resource_user_allowance (1) — (0..n) user_metadata`
- `user_registration (1) — (0..n) user_metadata` (redundant with the previous two combined)

---

## 2. Goal

Remove `user_registration` as a distinct entity. Link `license_resource_user_allowance` directly
to `eperson`, drop the redundant FK on `user_metadata` entirely, and move `organization` (and a
decision on `confirmation`, see Section 5) onto `eperson` using DSpace's existing eperson-metadata
mechanism instead of a bespoke table.

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
```

Cardinalities after the redesign:

- `eperson (1) — (0..n) license_resource_user_allowance`, nullable FK = anonymous request
  (no sentinel row required — `NULL` means "no eperson," exactly as it does everywhere else in
  DSpace).
- `license_resource_mapping (1) — (0..n) license_resource_user_allowance` — unchanged.
- `license_resource_user_allowance (1) — (0..n) user_metadata` — unchanged, now the *only* path
  from a `user_metadata` row back to an eperson.

One hop instead of two. No sentinel row. No duplicate email. No redundant FK on `user_metadata`.

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
   - Register `eperson.organization` (and, if kept, `eperson.confirmed`) in the eperson metadata
     schema.
   - Migrate data: `INSERT INTO metadatavalue (...) SELECT <eperson metadata field id>,
     ur.eperson_id, ur.organization FROM user_registration ur WHERE ur.eperson_id IS NOT NULL
     AND ur.organization IS NOT NULL;` (same pattern for `confirmed`, if kept).
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

3. **Open item to verify before starting:** `dspace-angular` (frontend, separate repo) consumes
   `ClarinUserRegistrationRest` — need to check whether it reads `organization`/`confirmation`
   fields directly or via nested REST links before finalizing the REST API shape change. Not
   checked as part of this doc (backend-only repo).

4. **Pre-migration data check:** confirm there is no existing `user_metadata` row with
   `transaction_id IS NULL` — the redesign assumes every `user_metadata` row has a transaction to
   derive its eperson from. All current code paths set `transaction_id` at creation time, but this
   should be verified against production data before dropping the column irreversibly.

This is a larger, more invasive change than the earlier (already-decided) fix of guaranteeing the
anonymous sentinel row — it touches core identity linking across 3 tables, multiple REST endpoints,
and potentially the frontend. Treat as a separate follow-up effort, not bundled with the sentinel-row
fix.

---

## 5. The `confirmation` field

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
forward at all. This removes one more piece of dead state instead of relocating it. Recommended,
**pending confirmation that the frontend doesn't read `ClarinUserRegistrationRest.confirmation`**
(not checked here — separate repo).

**(b) Migrate it as `eperson.confirmed` metadata, alongside `eperson.organization`.** If there's
any appetite for eventually implementing the "postponed registration pending email confirmation"
flow the original comment hints at, preserve the historical data and the field itself as a second
new eperson metadata field, using the same mechanism as `organization`
(Section 3). This costs nothing extra migration-wise since it rides along with the `organization`
backfill, and keeps the option open without committing to new behavior now.

**Recommendation:** go with (a) unless the frontend check in Section 4 turns up a dependency,
in which case fall back to (b) rather than break the frontend contract.

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
