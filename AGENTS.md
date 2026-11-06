dillway is an AI-powered Minecraft anti-cheat system: a Paper plugin and
a Python training pipeline sharing a Cloudflare R2 object store.

Tooling: Python via `uv`, the plugin via the gradle wrapper (`./gradlew`,
no npm/node). Critical rules that apply on every change live at
`~/.config/opencode/AGENTS.md` (build/test commands, commits, docs there).

For docs hygiene, see "Documentation rules" below. Component docs:
`plugin/README.md`, `dw-engine/README.md`; design docs in
`data-main/docs/`.

## Documentation rules

Docs exist for orientation: what the system is, how the components
relate, and the invariants that link them. Two ways they go stale, and
both are forbidden here:

- **No duplicating the code.** Do not restate what the code already
  states: exact chat message wording, button labels, command behavior
  details, config keys and defaults, env-var tables, produced file or
  object names, column/feature lists. Point at the source of truth
  instead (e.g. `config.yml`, `config.py`, a docstring) rather than
  transcribing it.
- **No mechanical doc updates.** When changing behavior, update docs
  only if the *concept* changed. Rephrased messages, renamed internals,
  tweaked values, or UI details do not require a README edit. If a
  change would force a doc edit for such a mechanical reason, the doc
  is over-specified — delete the detail from the doc instead.

Keep root and component READMEs short (roughly ≤ 50 lines each). Deeper
explanation belongs in `data-main/docs/`, code comments, or KDoc, never
in the READMEs.
