import difflib, json, pathlib, re, subprocess, sys

args = sys.argv[1:]
dry, force = "--dry-run" in args, "--force" in args
flavor = next((a.split("=", 1)[1] for a in args if a.startswith("--flavor=")), "auto")
args = [a for a in args if not a.startswith("--")]
new = args[0] if args else None          # None => --list
root = pathlib.Path(".")
is_release = new is not None and "-" not in new


def read(p):
    return p.read_text(encoding="utf-8")


def header(p, size=8192):
    return read(p)[:size]                 # WordPress reads only the first 8 KB for file headers


def tracked(p):
    try:
        r = subprocess.run(["git", "ls-files", "--error-unmatch", str(p)], capture_output=True)
        return r.returncode == 0 if r.returncode in (0, 1) else True
    except FileNotFoundError:
        return True                       # no git: treat an existing file as tracked


def composer():
    p = root / "composer.json"
    return json.loads(read(p)) if p.exists() else {}


def detect():
    c = composer()
    if "laravel/framework" in c.get("require", {}) and (root / "artisan").exists():
        return "laravel", None
    for p in sorted(root.glob("*.php")):
        if re.search(r"^[ \t/*#@]*Plugin Name:", header(p), re.M):
            return "wordpress-plugin", p
    css = root / "style.css"
    if css.exists() and re.search(r"^[ \t/*#@]*Theme Name:", header(css), re.M):
        return "wordpress-theme", css
    for p in sorted(root.glob("*.php")):
        t = read(p)
        if re.search(r"\bclass\s+\w+\s+extends\s+(Module|PaymentModule|CarrierModule)\b", t) and "$this->version" in t:
            return "prestashop-module", p
    return "library", None


# Each rule: (file, compiled regex with groups pre/value/post, release_only, note)
def rules(kind, main):
    out = []
    if kind == "laravel":
        out.append((root / "config/app.php",
                    re.compile(r"""(['"]version['"]\s*=>\s*(?:env\(\s*['"]APP_VERSION['"]\s*,\s*)?['"])([^'"]*)(['"])"""),
                    False, "config/app.php 'version'"))
    elif kind in ("wordpress-plugin", "wordpress-theme"):
        out.append((main, re.compile(r"^([ \t/*#@]*Version:[ \t]*)(\S+)()", re.M), False, "header Version:"))
        const_file = main if kind == "wordpress-plugin" else root / "functions.php"
        out.append((const_file,
                    re.compile(r"""(define\(\s*['"][A-Z][A-Z0-9_]*_VERSION['"]\s*,\s*['"])([^'"]+)(['"])"""
                               r"""|(const\s+[A-Z][A-Z0-9_]*_VERSION\s*=\s*['"])([^'"]+)(['"])"""),
                    False, "<PREFIX>_VERSION constant"))
        out.append((root / "readme.txt", re.compile(r"^(Stable tag:[ \t]*)(\S+)()", re.M | re.I), True,
                    "readme.txt Stable tag (release versions only)"))
    elif kind == "prestashop-module":
        out.append((main, re.compile(r"""(\$this->version\s*=\s*['"])([^'"]+)(['"])"""), False, "$this->version"))
        xml = root / "config.xml"
        if xml.exists() and tracked(xml):
            out.append((xml, re.compile(r"(<version>\s*(?:<!\[CDATA\[)?)([^<\]]+)((?:\]\]>)?\s*</version>)"),
                        False, "config.xml <version>"))
    return out


def groups(m):
    g = m.groups()
    if len(g) == 6 and g[0] is None:      # second alternative of the constant regex
        g = g[3:]
    return g[:3]


kind, main = (flavor, None) if flavor != "auto" else detect()
if flavor != "auto" and kind != "library":
    _, main = detect()
print(f"flavor: {kind}" + (f" (main file: {main.as_posix()})" if main else ""))

c = composer()
if "version" in c:
    print(f"WARNING: composer.json has a \"version\" field ({c['version']}); Composer recommends omitting it — "
          "it is not part of this convention and is left untouched", file=sys.stderr)

if kind == "library":
    print("library: no in-tree version — the git tag is the version; nothing to change")
    sys.exit(0)
if kind in ("wordpress-plugin", "wordpress-theme", "prestashop-module") and main is None:
    sys.exit(f"Could not find the main file for flavor {kind}")

found, new_texts = [], {}
for path, rx, release_only, label in rules(kind, main):
    if not path.exists():
        continue
    text = new_texts.get(path, read(path))
    hits = list(rx.finditer(text[:8192] if label == "header Version:" else text))
    if label in ("header Version:", "readme.txt Stable tag (release versions only)"):
        hits = hits[:1]
    elif label == "<PREFIX>_VERSION constant" and len(hits) > 1:
        # Several *_VERSION constants (e.g. ACME_DB_VERSION): the plugin/theme version is the shortest name.
        name = lambda m: re.search(r"[A-Z][A-Z0-9_]*_VERSION", m.group(0)).group(0)
        keep = min(hits, key=lambda m: len(name(m)))
        for m in hits:
            if m is not keep:
                print(f"note: ignoring {name(m)} in {path.as_posix()} (not the {name(keep)} version constant)")
        hits = [keep]
    for m in hits:
        pre, value, post = groups(m)
        found.append((path.as_posix(), text.count("\n", 0, m.start()) + 1, value, label, release_only))
    if new is None or not hits or (release_only and not is_release):
        continue
    def repl(m):
        pre, _, post = groups(m)
        return pre + new + post
    for m in reversed(hits):              # only the selected occurrences, never every regex match
        text = text[:m.start()] + repl(m) + text[m.end():]
    new_texts[path] = text

pkg = root / "package.json"
if pkg.exists() and kind != "laravel":
    v = json.loads(read(pkg)).get("version")
    if v is not None:
        found.append(("package.json", 0, v, "package.json version (written with npm, not by this script)", False))

for path, line, value, label, release_only in found:
    print(f"{path}:{line}: {value}    [{label}]")
required = {"laravel": "config/app.php 'version'", "wordpress-plugin": "header Version:",
            "wordpress-theme": "header Version:", "prestashop-module": "$this->version"}[kind]
if not any(f[3] == required for f in found):
    sys.exit(f"No {required} found — the version location this flavor's convention requires is missing")
values = {f[2] for f in found if not f[4]}
if len(values) > 1:
    print(f"WARNING: version locations disagree: {sorted(values)}", file=sys.stderr)
    if new is not None and not force:
        sys.exit("Refusing to write: confirm with the user, then re-run with --force to align every location")
if new is None:
    sys.exit(0)
for path, text in new_texts.items():
    old = read(path)
    if text == old:
        continue
    if dry:
        sys.stdout.writelines(difflib.unified_diff(old.splitlines(True), text.splitlines(True), str(path), str(path)))
    else:
        path.write_text(text, encoding="utf-8")
        print(f"updated {path.as_posix()}")
if any(f[4] for f in found) and not is_release:
    print("note: readme.txt Stable tag left unchanged (only release versions are written there)")
if kind == "prestashop-module" and is_release and not (root / "upgrade" / f"upgrade-{new}.php").exists():
    print(f"note: no upgrade/upgrade-{new}.php — add one if this release changes the schema, configuration or hooks")
if pkg.exists() and kind != "laravel" and any(f[0] == "package.json" for f in found):
    print(f"next: npm version {new} --no-git-tag-version --allow-same-version  (package.json + lockfile)")
