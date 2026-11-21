// Shared by nav-fix/check-nav-dead-ends.mjs and site-metrics/measure.mjs: the HTML of the first
// <nav class="nav-menu"> of a built Antora page, or null. Node built-ins only.
export function extractNav (html) {
  const start = html.search(/<nav class="nav-menu"[^>]*>/)
  if (start < 0) return null
  const re = /<\/?nav\b[^>]*>/g
  re.lastIndex = start
  let depth = 0
  let m
  while ((m = re.exec(html))) {
    depth += m[0].startsWith('</') ? -1 : 1
    if (depth === 0) return html.slice(start, m.index + m[0].length)
  }
  return null
}
