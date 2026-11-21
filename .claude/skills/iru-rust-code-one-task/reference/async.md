# Async (tokio) — only when the project already uses it

Read this only if `tokio` is already in the crate's `[dependencies]`. A synchronous CLI stays synchronous: adding
tokio (and an async HTTP client) for one call costs startup time and binary size (contract §8) and is a dependency
decision the task must make explicitly — otherwise it's a blocker to report. Use what the crate already has for I/O
(a blocking client such as `ureq`, or `std`).

Verified with tokio 1.53.3 (features `rt`, `macros`, `signal`, `time`, `process`; `test-util` for paused-time
tests): the snippets compile clean under the recommended clippy set, a 1 s timeout exits 6, SIGTERM exits 143 and
SIGINT exits 130 on macOS, and the crate also compiles for `x86_64-pc-windows-msvc`.

## Runtime and `main`

```rust
#[tokio::main(flavor = "current_thread")]
async fn main() -> ExitCode {
    let work = tokio::time::sleep(Duration::from_millis(10));
    match run_with_limits(work, Duration::from_secs(30)).await {
        Outcome::Done(()) => ExitCode::SUCCESS,
        Outcome::TimedOut => ExitCode::from(6),
        Outcome::Interrupted => ExitCode::from(130),
        Outcome::Terminated => ExitCode::from(143),
    }
}
```

- A CLI usually wants the **current-thread** runtime (fast start, no worker pool); keep whatever flavor the crate
  already uses. `main` stays thin: the library exposes `async fn run(...) -> Exit` and `main` awaits it.
- Never create a second runtime or call `block_on` inside async code (it panics or deadlocks); never hold a
  `std::sync::Mutex` guard across an `.await` (use `tokio::sync::Mutex` only when the guard must live across one).
- Blocking work (CPU-heavy parsing, `std::fs` on large trees, a blocking client) goes in
  `tokio::task::spawn_blocking`; child processes through `tokio::process::Command` with `.kill_on_drop(true)` (the
  `security.md` argv rules still apply).

## Cancellation, timeouts, signals

```rust
/// Resolves when SIGTERM arrives; never on platforms without it.
async fn sigterm() {
    #[cfg(unix)]
    {
        use tokio::signal::unix::{SignalKind, signal};
        if let Ok(mut term) = signal(SignalKind::terminate()) {
            term.recv().await;
            return;
        }
    }
    std::future::pending::<()>().await;
}

/// Runs `work` until it finishes, `timeout` elapses, or a stop signal arrives — whichever is
/// first. Dropping `work` cancels it, so a pending network call never blocks Ctrl-C.
pub async fn run_with_limits<T>(work: impl Future<Output = T>, timeout: Duration) -> Outcome<T> {
    tokio::select! {
        result = tokio::time::timeout(timeout, work) => match result {
            Ok(value) => Outcome::Done(value),
            Err(_elapsed) => Outcome::TimedOut,
        },
        _ = tokio::signal::ctrl_c() => Outcome::Interrupted,
        () = sigterm() => Outcome::Terminated,
    }
}
```

- `--timeout` → `tokio::time::timeout` → exit 6 (`Retryable`, `retryable: true` in the envelope).
- `tokio::signal::ctrl_c()` → 130; `tokio::signal::unix` SIGTERM → 143 (Windows has `tokio::signal::windows::ctrl_c`/
  `ctrl_close` — `ctrl_c()` already covers Ctrl-C there). Clean up (temp files, partial output) after the `select!`
  returns, then print one line on stderr and return the exit — never `process::exit` from inside a task.
- Dropping a future cancels it at its next `.await`: make sure the code between awaits leaves no half-written file
  (write to a temp file, `persist` at the end).
- Long-lived background tasks get a `tokio_util::sync::CancellationToken` only if the crate already depends on
  `tokio-util`; otherwise a `tokio::sync::watch`/`oneshot` channel.

## Tests

- `#[tokio::test]` (current-thread by default); `#[tokio::test(start_paused = true)]` makes `sleep`/`timeout`
  instantaneous and deterministic — it needs tokio's `test-util` feature in `[dev-dependencies]` (verified: without
  it the attribute doesn't compile).
- Never sleep for real in a test; never depend on a real network — inject the client behind a trait or point it at a
  local fake started in the test.

```rust
#[tokio::test(start_paused = true)]
async fn finishes_or_times_out() {
    assert_eq!(run_with_limits(async { 7 }, Duration::from_secs(1)).await, Outcome::Done(7));
    let slow = tokio::time::sleep(Duration::from_secs(60));
    assert_eq!(run_with_limits(slow, Duration::from_secs(1)).await, Outcome::TimedOut);
}
```
