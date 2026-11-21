
<!-- dspace-skills:begin -->
## Private team playbooks (dataquest)

This repo vendors dataquest's private AI knowledge base as the `.dspace-skills/` git submodule. **If
`.dspace-skills/` is present**, treat **`.dspace-skills/AGENTS.md`** as the authoritative agent guide for this repo:
read it first, then load the matching profile (`.dspace-skills/profiles/frontend.md` for dspace-angular,
`.dspace-skills/profiles/backend.md` for DSpace) and pull skills from `.dspace-skills/skills/` on demand. Start any
PR/backport/test task from `.dspace-skills/SKILLS.md`.

If `.dspace-skills/` is empty (you don't have access, e.g. an outside contributor), ignore this section
and proceed with the public project conventions.

To enable: `git submodule update --init .dspace-skills` (requires access to
`dataquest-dev/dspace-skills`).
<!-- dspace-skills:end -->
