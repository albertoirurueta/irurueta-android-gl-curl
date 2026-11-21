Notes specific to this template:

- `replace_dependency_snippets`'s regex matches a JSON-style `"version": "..."` snippet (the way an npm
  install snippet is usually shown in prose docs) rather than an XML `<version>` tag like the Java template —
  adjust it if this repository's README/Antora pages show install instructions in a different shape (e.g. a
  fenced `npm install <package>@<version>` line instead of a JSON snippet); resolve the actual wording in
  Step 1 before writing the real file.
- `update_antora_component_version`/`update_antora_pages` both no-op cleanly if `docs/antora.yml` or
  `docs/modules/ROOT/pages` don't exist, so this script is safe to write even before `iru-setup-antora` has
  run.
