# Contract smoke checks — run against the built release binary

Step 7 runs these after the cargo gates. They exercise the real executable the way a script, a pipe and a person
at a terminal do. Run them from the repository root after `cargo build --release --locked`, with `B=./target/release/<bin>`
(`.exe` on Windows; the PTY part is Unix-only). Never write either script into the repository — extract them to a
scratch directory outside it.

## Pipes and exit codes (`smoke.sh`)

```bash
#!/usr/bin/env bash
# usage: smoke.sh <path-to-binary>; prints one line per check, exits 1 on the first failed expectation
set -u; B=$1; fail=0
code() { "$B" "$@" </dev/null >/tmp/smoke.out 2>/tmp/smoke.err; echo $?; }
expect() { if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1 (got $2, want $3)"; fail=1; fi; }
expect "--version exits 0"            "$(code --version)" 0
expect "--help exits 0"               "$(code --help)" 0
expect "--help writes nothing to stderr" "$(wc -c </tmp/smoke.err | tr -d ' ')" 0
expect "unknown command exits 2"      "$(code no-such-command)" 2
expect "usage error writes no stdout" "$(wc -c </tmp/smoke.out | tr -d ' ')" 0
expect "greet exits 0"                "$(code greet Ada)" 0
expect "empty name exits 3"           "$(code greet '')" 3
expect "--ask without a TTY exits 2"  "$(code greet --ask)" 2
expect "unknown schema command exits 4" "$(code schema no-such-command)" 4
"$B" greet Ada --json </dev/null | jq -e '.schemaVersion == 1' >/dev/null; expect "greet --json | jq .schemaVersion" $? 0
"$B" greet '' --json </dev/null 2>&1 >/dev/null | jq -e '.error.exitCode == 3 and .error.code == "VALIDATION"' >/dev/null
expect "JSON error envelope on stderr" $? 0
esc=0; for args in "greet Ada" "greet ''" "no-such-command" "schema" "--help"; do
  esc=$((esc + $(eval "\"$B\"" $args </dev/null 2>&1 | LC_ALL=C grep -c $'\x1b')))
done
expect "no ESC bytes when piped"      "$esc" 0
expect "NO_COLOR beats FORCE_COLOR"   "$(NO_COLOR=1 FORCE_COLOR=1 "$B" greet Ada | LC_ALL=C grep -c $'\x1b')" 0
python3 -I -c "import os,subprocess,sys; r,w=os.pipe(); os.close(r); p=subprocess.run([sys.argv[1],'schema'],stdout=w,stderr=subprocess.PIPE); sys.exit(0 if p.returncode==0 and not p.stderr else 1)" "$B"
expect "closed stdout pipe: exit 0, silent" $? 0
cmp -s <("$B" greet Ada --json) tests/golden/greet-ada.json; expect "greet --json equals the golden file" $? 0
exit $fail
```

With `ui: tui` add `expect "browse without a TTY exits 2" "$(code browse)" 2`.

## A real terminal: prompts, signals, the TUI (`pty_smoke.py`, Unix only)

`python3 -I pty_smoke.py ./target/release/<bin> [tui]` — spawns the binary on a pseudo-terminal (80×24; with a 0×0
size Ratatui draws nothing) and checks: `greet --ask` prompts on stderr and reads the answer; SIGINT/SIGTERM while
blocked at the prompt end the process at once with 130/143; `<BIN>_NO_INPUT=1` refuses to prompt (exit 2) and `=0`
doesn't; with `tui`, `browse` draws the list, Down + Enter prints the second name's greeting after leaving the
alternate screen (`ESC[?1049l`), `q` exits 0, Ctrl-C exits 130, and SIGTERM during the TUI is deferred, restores the
terminal, prints `<bin>: terminated` and exits 143.

```python
"""PTY smoke checks for a scaffolded Rust CLI: prompts, signals and the TUI on a real pseudo-terminal."""
import fcntl, os, pty, signal, struct, sys, termios, time, select

BIN = sys.argv[1]
TUI = len(sys.argv) > 2 and sys.argv[2] == "tui"

def spawn(args, env=None):
    pid, fd = pty.fork()
    if pid == 0:
        e = dict(os.environ); e.pop("CI", None); e.pop("NO_COLOR", None); e["TERM"] = "xterm-256color"
        if env: e.update(env)
        fcntl.ioctl(0, termios.TIOCSWINSZ, struct.pack('HHHH', 24, 80, 0, 0))  # a real size, or nothing is drawn
        os.execve(BIN, [BIN] + args, e)
    return pid, fd

def read_all(fd, timeout=1.0):
    out = b""; end = time.time() + timeout
    while time.time() < end:
        r, _, _ = select.select([fd], [], [], 0.05)
        if r:
            try:
                chunk = os.read(fd, 65536)
            except OSError:
                break
            if not chunk: break
            out += chunk
    return out

def wait(pid, fd):
    out = read_all(fd, 1.5)
    _, status = os.waitpid(pid, 0)
    return os.waitstatus_to_exitcode(status), out

results = []
# 1. greet --ask on a TTY prompts and reads the answer.
pid, fd = spawn(["greet", "--ask"]); time.sleep(0.3); prompt = read_all(fd, 0.3)
os.write(fd, b"Grace\r"); code, out = wait(pid, fd)
results.append(("ask prompts on a tty", code == 0 and b"Name?" in prompt and b"Grace" in out, code))
# 2./3. a signal while blocked at the prompt ends the process at once with 130/143.
for sig, want in ((signal.SIGINT, 130), (signal.SIGTERM, 143)):
    pid, fd = spawn(["greet", "--ask"]); time.sleep(0.3); read_all(fd, 0.2)
    os.kill(pid, sig); code, _ = wait(pid, fd)
    results.append((f"{sig.name} at the prompt -> {want}", code == want, code))
# 4. <BIN>_NO_INPUT=1 disables the prompt even on a terminal (exit 2); =0 doesn't.
env_name = os.path.basename(BIN).upper().replace("-", "_") + "_NO_INPUT"
pid, fd = spawn(["greet", "--ask"], {env_name: "1"}); code, _ = wait(pid, fd)
results.append((f"{env_name}=1 on a tty -> 2", code == 2, code))
pid, fd = spawn(["greet", "--ask"], {env_name: "0"}); time.sleep(0.3); prompt = read_all(fd, 0.3)
os.write(fd, b"Ada\r"); code, _ = wait(pid, fd)
results.append((f"{env_name}=0 on a tty still prompts", code == 0 and b"Name?" in prompt, code))
if TUI:
    # 4. browse: Down + Enter selects the second name, restores the terminal, prints the greeting.
    pid, fd = spawn(["browse", "Ada", "Grace"]); time.sleep(0.5); screen = read_all(fd, 0.3)
    os.write(fd, b"\x1b[B"); time.sleep(0.2); os.write(fd, b"\r"); code, out = wait(pid, fd)
    results.append(("browse selects Grace", code == 0 and b"Pick" in screen and b"> Ada" in screen and b"Grace" in out and b"\x1b[?1049l" in out, code))
    # 5. q quits with 0, Ctrl-C with 130.
    for key, want in ((b"q", 0), (b"\x03", 130)):
        pid, fd = spawn(["browse"]); time.sleep(0.5); read_all(fd, 0.2)
        os.write(fd, key); code, out = wait(pid, fd)
        results.append((f"browse key {key!r} -> {want}", code == want and b"\x1b[?1049l" in out, code))
    # 6. SIGTERM during the TUI: deferred, terminal restored, 143 and one line.
    pid, fd = spawn(["browse"]); time.sleep(0.5); read_all(fd, 0.2)
    os.kill(pid, signal.SIGTERM); code, out = wait(pid, fd)
    results.append(("SIGTERM in browse -> 143, terminal restored", code == 143 and b"\x1b[?1049l" in out and b"terminated" in out, code))
ok = True
for name, passed, code in results:
    ok &= passed
    print(f"{'PASS' if passed else 'FAIL'}  {name} (exit {code})")
sys.exit(0 if ok else 1)
```
