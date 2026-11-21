import { readFileSync, writeFileSync, mkdirSync, copyFileSync } from 'node:fs';
import { buildSchema, introspectionFromSchema } from 'graphql';

const [, , sdlPath, outDir, title] = process.argv;
// Voyager requires the `data` half of an introspection response — an object with a
// top-level __schema key, which is exactly what introspectionFromSchema returns.
const introspection = introspectionFromSchema(buildSchema(readFileSync(sdlPath, 'utf8')));

mkdirSync(outDir, { recursive: true });
for (const f of ['voyager.standalone.js', 'voyager.css']) {
  copyFileSync(`node_modules/graphql-voyager/dist/${f}`, `${outDir}/${f}`);
}
writeFileSync(`${outDir}/index.html`, `<!doctype html>
<html><head><meta charset="utf-8"><title>${title}</title>
<link rel="stylesheet" href="voyager.css" />
<style>body,html,#voyager{height:100%;margin:0;overflow:hidden}</style>
</head><body>
<div id="voyager">Loading…</div>
<script src="voyager.standalone.js"></script>
<script>
  GraphQLVoyager.renderVoyager(document.getElementById('voyager'), {
    introspection: ${JSON.stringify(introspection)}
  });
</script>
</body></html>
`);
