dillway is an AI-powered Minecraft anti-cheat system: a Paper plugin and
a Python training pipeline sharing a Cloudflare R2 object store.

Tooling: Python via `uv`; the plugin via the gradle wrapper (`./gradlew`).

Component docs: `dw-agent/README.md`, `dw-engine/README.md`.

## Documentation rules

Docs serve a reader with zero context beyond this repo: what the system
is, how the components relate, and the invariants that link them.

- **Don't restate the code.** Skip exact chat message wording, button
  labels, command behavior details, config keys and defaults, env-var
  tables, produced file names, column/feature lists. Point at the
  source of truth (a config file, a docstring) instead of
  transcribing it.
- **No mechanical doc updates.** Update docs only when the *concept*
  changes. Rephrased messages, renamed internals, or tweaked values do
  not require a doc edit — if one would, the doc is over-specified;
  delete the detail from the doc instead of editing it.
- **No conversation artifacts.** Never carry content out of the working
  conversation — reasoning steps, session examples, names or shorthand
  only someone in this conversation could parse. If a sentence needs
  the session to make sense, it does not belong in the repo; assume
  the next reader is a fresh agent that has only the files.
