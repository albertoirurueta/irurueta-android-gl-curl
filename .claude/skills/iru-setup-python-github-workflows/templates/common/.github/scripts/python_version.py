import difflib, pathlib, re, subprocess, sys

args = sys.argv[1:]
dry, force, antora = "--dry-run" in args, "--force" in args, "--antora" in args
validate = "--validate" in args
args = [a for a in args if not a.startswith("--")]
new = args[0] if args else None          # None => --list
root = pathlib.Path(".")

# PEP 440 (the public-version pattern from the spec, verbose form) + canonical rendering.
PEP440 = re.compile(r"""^\s*v?(?:(?P<epoch>[0-9]+)!)?(?P<release>[0-9]+(?:\.[0-9]+)*)
    (?P<pre>[-_.]?(?P<pre_l>alpha|a|beta|b|preview|pre|c|rc)[-_.]?(?P<pre_n>[0-9]+)?)?
    (?P<post>(?:-(?P<post_n1>[0-9]+))|(?:[-_.]?(?P<post_l>post|rev|r)[-_.]?(?P<post_n2>[0-9]+)?))?
    (?P<dev>[-_.]?(?P<dev_l>dev)[-_.]?(?P<dev_n>[0-9]+)?)?
    (?:\+(?P<local>[a-z0-9]+(?:[-_.][a-z0-9]+)*))?\s*$""", re.X | re.I)


def canonical(v):
    m = PEP440.match(v)
    if not m:
        return None
    s = (m["epoch"] + "!" if m["epoch"] and int(m["epoch"]) else "") + ".".join(str(int(x)) for x in m["release"].split("."))
    if m["pre"]:
        lab = {"alpha": "a", "beta": "b", "c": "rc", "pre": "rc", "preview": "rc"}.get(m["pre_l"].lower(), m["pre_l"].lower())
        s += lab + str(int(m["pre_n"] or 0))
    if m["post"]:
        s += ".post" + str(int(m["post_n1"] or m["post_n2"] or 0))
    if m["dev"]:
        s += ".dev" + str(int(m["dev_n"] or 0))
    if m["local"]:
        s += "+" + ".".join(p if not p.isdigit() else str(int(p)) for p in re.split(r"[-_.]", m["local"].lower()))
    return s


if validate:
    c = canonical(new or "")
    if c is None:
        sys.exit(f"invalid: {new!r} is not a PEP 440 version")
    print(f"canonical: {c}")
    if c != new:
        print(f"WARNING: {new} is valid PEP 440 but not canonical — packaging tools will normalize it to {c}")
    if not re.fullmatch(r"\d+\.\d+\.\d+(\.dev\d+)?", c):
        print("WARNING: not in this catalog's X.Y.Z / X.Y.Z.devN convention")
    sys.exit(0)

is_release = new is not None and canonical(new) is not None and re.fullmatch(r"[0-9.]+", canonical(new)) is not None


def read(p):
    return p.read_text(encoding="utf-8")


def ignored(p):
    try:
        return subprocess.run(["git", "check-ignore", "-q", str(p)], capture_output=True).returncode == 0
    except FileNotFoundError:
        return False


def tables(text):
    """Map TOML table name -> (start, end) offsets of its body (regex-based; no tomllib needed on Python < 3.11)."""
    heads = [(m.group(1).strip(), m.start(), m.end()) for m in re.finditer(r"(?m)^[ \t]*\[\[?([^\[\]\n]+)\]\]?[ \t]*(?:#.*)?$", text)]
    out = {}
    for i, (name, _, end) in enumerate(heads):
        out.setdefault(name, (end, heads[i + 1][1] if i + 1 < len(heads) else len(text)))
    return out


def key_in(text, span, key):
    rx = re.compile(r"(?m)^([ \t]*" + re.escape(key) + r"[ \t]*=[ \t]*)([\"'])([^\"'\n]*)(\2)")
    return [m for m in rx.finditer(text, *span)] if span else []


VERSION_ASSIGN = re.compile(r"""(?m)^(__version__[ \t]*(?::[ \t]*str[ \t]*)?=[ \t]*)(["'])([^"'\n]*)(\2)""")
SETTINGS_ASSIGN = re.compile(r"""(?m)^((?:APP_)?VERSION[ \t]*(?::[ \t]*str[ \t]*)?=[ \t]*)(["'])([^"'\n]*)(\2)""")
SETTINGS_ENV = re.compile(r"""(?m)^((?:APP_)?VERSION[ \t]*(?::[ \t]*str[ \t]*)?=[ \t]*(?:os\.environ\.get|os\.getenv|env\.str|env)\([ \t]*["'](?:APP_)?VERSION["'][ \t]*,[ \t]*(?:default[ \t]*=[ \t]*)?)(["'])([^"'\n]*)(\2)""")
FASTAPI_CTOR = re.compile(r"""(FastAPI\((?:[^()]|\([^()]*\))*?\bversion[ \t]*=[ \t]*)(["'])([^"'\n]*)(\2)""", re.S)
SKIP = {".venv", "venv", ".git", "node_modules", "build", "dist", "docs", "tests", "test", "migrations", ".tox", ".nox", "site-packages"}


def walk(globs):
    seen = []
    for g in globs:
        for p in sorted(root.glob(g)):
            if p.is_file() and not (set(p.parts) & SKIP) and p not in seen and not ignored(p):
                seen.append(p)
    return seen


locs = []      # (path, regex, kind, role) role: primary|mirror|conditional|antora
notes = []
pp = root / "pyproject.toml"
text = read(pp) if pp.exists() else ""
tb = tables(text)
name = None
if "project" in tb:
    n = key_in(text, tb["project"], "name")
    name = n[0].group(3) if n else None

dynamic = False
if "project" in tb:
    d = re.search(r"(?ms)^[ \t]*dynamic[ \t]*=[ \t]*\[(.*?)\]", text[tb["project"][0]:tb["project"][1]])
    dynamic = bool(d and re.search(r"[\"']version[\"']", d.group(1)))
backend = ""
if "build-system" in tb:
    b = key_in(text, tb["build-system"], "build-backend")
    backend = b[0].group(3) if b else ""
requires = text[tb["build-system"][0]:tb["build-system"][1]] if "build-system" in tb else ""


def module_file(dotted):
    parts = dotted.split(".")
    for base in (root / "src", root):
        for cand in (base.joinpath(*parts).with_suffix(".py"), base.joinpath(*parts, "__init__.py")):
            if cand.exists():
                return cand
    return None


def stop_vcs(why):
    print(f"version: dynamic — {why}; the version is derived from git tags, nothing to change")
    sys.exit(0)


if dynamic:
    hv = tb.get("tool.hatch.version")
    sd = tb.get("tool.setuptools.dynamic")
    pv = tb.get("tool.pdm.version")
    if hv and key_in(text, hv, "source") and key_in(text, hv, "source")[0].group(3) == "vcs":
        stop_vcs("[tool.hatch.version] source = \"vcs\" (hatch-vcs)")
    if pv and key_in(text, pv, "source") and key_in(text, pv, "source")[0].group(3) == "scm":
        stop_vcs("[tool.pdm.version] source = \"scm\"")
    if re.search(r"setuptools[-_]scm|hatch-vcs|versioningit|dynamic-versioning|uv-dynamic-versioning", requires + text, re.I) \
            or "tool.setuptools_scm" in tb:
        stop_vcs("a VCS versioning plugin (setuptools-scm / hatch-vcs / versioningit / *-dynamic-versioning) is configured")
    target = None
    if hv and key_in(text, hv, "path"):
        target = root / key_in(text, hv, "path")[0].group(3)
    elif pv and key_in(text, pv, "path"):
        target = root / key_in(text, pv, "path")[0].group(3)
    elif sd:
        body = text[sd[0]:sd[1]]
        a = re.search(r"""version[ \t]*=[ \t]*\{[^}]*attr[ \t]*=[ \t]*["']([^"']+)["']""", body)
        f = re.search(r"""version[ \t]*=[ \t]*\{[^}]*file[ \t]*=[ \t]*\[?[ \t]*["']([^"']+)["']""", body)
        if a:
            mod, _, attr = a.group(1).rpartition(".")
            target = module_file(mod)
            if attr != "__version__":
                notes.append(f"attr {a.group(1)}: only __version__ literals are rewritten — check {attr} by hand")
        elif f:
            vf = root / f.group(1)
            locs.append((vf, re.compile(r"\A()()([^\s]+)()"), "VERSION file (setuptools dynamic file)", "primary"))
    elif "flit" in backend and name:
        target = module_file(re.sub(r"[-.]+", "_", name).lower())
    if target is not None:
        if not target.exists():
            sys.exit(f"dynamic version points at {target.as_posix()}, which does not exist")
        locs.append((target, VERSION_ASSIGN, "__version__ (dynamic source)", "primary"))
    if not locs:
        sys.exit("dynamic version with an unrecognized source — explain to the user and stop; nothing was changed")
else:
    if "project" in tb and key_in(text, tb["project"], "version"):
        locs.append((pp, None, "[project].version", "primary"))

if "tool.poetry" in tb and key_in(text, tb["tool.poetry"], "version"):
    locs.append((pp, None, "[tool.poetry].version", "primary"))

cfg = root / "setup.cfg"
if cfg.exists():
    m = re.search(r"(?ms)^\[metadata\][^\[]*?^(version[ \t]*=[ \t]*)()([^\s]+)()", read(cfg))
    if m and not m.group(3).startswith(("attr:", "file:")):
        locs.append((cfg, re.compile(r"(?ms)(^\[metadata\][^\[]*?^version[ \t]*=[ \t]*)()([^\s]+)()"), "setup.cfg [metadata] version", "primary"))
spy = root / "setup.py"
if spy.exists() and re.search(r"""\bversion[ \t]*=[ \t]*["'][^"']+["']""", read(spy)):
    locs.append((spy, re.compile(r"""(\bversion[ \t]*=[ \t]*)(["'])([^"'\n]+)(\2)"""), "setup.py version=", "primary"))

dyn_targets = {l[0] for l in locs if l[2].startswith("__version__")}
for p in walk(["src/*/__init__.py", "src/*/_version.py", "src/*/__about__.py", "src/*/version.py",
               "*/__init__.py", "*/_version.py", "*/__about__.py", "*/version.py"]):
    t = read(p)
    if p not in dyn_targets and VERSION_ASSIGN.search(t):
        locs.append((p, VERSION_ASSIGN, "__version__ literal", "primary"))
    elif p not in dyn_targets and re.search(r"(?m)^__version__\s*=", t):
        notes.append(f"{p.as_posix()}: __version__ is computed (e.g. importlib.metadata.version()) — follows pyproject, untouched")
for p in walk(["*/settings.py", "*/settings/*.py", "*/*/settings.py", "*/*/settings/*.py"]):
    t = read(p)
    if SETTINGS_ENV.search(t):
        locs.append((p, SETTINGS_ENV, "settings VERSION env default", "primary"))
    elif SETTINGS_ASSIGN.search(t):
        locs.append((p, SETTINGS_ASSIGN, "settings VERSION literal", "primary"))
for p in walk(["app/*.py", "src/*/*.py", "src/*/*/*.py", "*/main.py"]):
    if FASTAPI_CTOR.search(read(p)):
        locs.append((p, FASTAPI_CTOR, "FastAPI(version=) literal — written only when it equals the package version", "conditional"))
ay = root / "docs" / "antora.yml"
if antora and ay.exists():
    locs.append((ay, re.compile(r"(?m)^(version:[ \t]*)(['\"]?)([^\s'\"#]+)(\2)"), "docs/antora.yml version (release versions only)", "antora"))

if not locs:
    sys.exit("No version location found (no [project].version, [tool.poetry].version, __version__ literal, setup.cfg/setup.py version)")

found, new_texts = [], {}
for path, rx, label, role in locs:
    t = new_texts.get(path, read(path))
    if rx is None:      # pyproject table keys
        span = tables(t)["project" if label.startswith("[project]") else "tool.poetry"]
        hits = key_in(t, span, "version")[:1]
    else:
        hits = list(rx.finditer(t))[:1]
    for m in hits:
        found.append([path.as_posix(), t.count("\n", 0, m.start(3)) + 1, m.group(3), label, role, path, m])
for f in found:
    print(f"{f[0]}:{f[1]}: {f[2]}    [{f[3]}]")
for n in notes:
    print(f"note: {n}")
primary = [f for f in found if f[4] == "primary"]
if not primary:
    sys.exit("No primary version location found — only mirrors/conditional ones")
values = {f[2] for f in primary}
current = primary[0][2]
print(f"current: {current}")
if len(values) > 1:
    print(f"WARNING: version locations disagree: {sorted(values)}", file=sys.stderr)
    if new is not None and not force:
        sys.exit("Refusing to write: confirm with the user, then re-run with --force to align every location")
if new is None:
    sys.exit(0)

for f in sorted(found, key=lambda f: -f[6].start()):  # rewrite back to front per file
    path, m, role = f[5], f[6], f[4]
    if role == "conditional" and f[2] != current:
        print(f"note: {f[0]}:{f[1]} FastAPI version {f[2]} differs from the package version — left alone (separate API version)")
        continue
    if role == "antora" and (not is_release or f[2] in ("~", "true", "false", "")):
        print(f"note: {f[0]} version left at {f[2]} (Antora's component version names releases only)")
        continue
    t = new_texts.get(path, read(path))
    new_texts[path] = t[:m.start(3)] + new + t[m.end(3):]
for path, t in new_texts.items():
    old = read(path)
    if t == old:
        continue
    if dry:
        sys.stdout.writelines(difflib.unified_diff(old.splitlines(True), t.splitlines(True), str(path), str(path)))
    else:
        path.write_text(t, encoding="utf-8")
        print(f"updated {path.as_posix()}")
lock = root / "uv.lock"
if lock.exists() and name and re.search(r'(?m)^name = "' + re.escape(re.sub(r"[-_.]+", "-", name).lower()) + r'"\nversion = ', read(lock)):
    print("next: uv lock   (refreshes this project's own version entry in uv.lock)")
