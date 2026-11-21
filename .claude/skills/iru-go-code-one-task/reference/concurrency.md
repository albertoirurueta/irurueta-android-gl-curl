# Concurrency

Most CLI commands need none: do the work sequentially unless the task asks for parallelism or a measured latency
problem demands it. When concurrency is needed, these rules keep it leak-free, cancellable, and race-free under
`go test -race` (which the group runs on the full suite).

## Structured concurrency with errgroup

```go
// HashAll processes files concurrently with a bounded errgroup.
func HashAll(ctx context.Context, fsys fs.FS, names []string) ([]int, error) {
    sizes := make([]int, len(names)) // each goroutine writes only its own index: no mutex needed
    g, ctx := errgroup.WithContext(ctx) // first error cancels ctx for the others
    g.SetLimit(4)                       // bound the fan-out
    for i, name := range names {
        g.Go(func() error {
            if err := ctx.Err(); err != nil {
                return err
            }
            b, err := fs.ReadFile(fsys, name)
            if err != nil {
                return fmt.Errorf("read %s: %w", name, err)
            }
            sizes[i] = len(b)
            return nil
        })
    }
    if err := g.Wait(); err != nil {
        return nil, fmt.Errorf("hash: %w", err)
    }
    return sizes, nil
}
```

- `golang.org/x/sync/errgroup` (a new dependency if the module doesn't have it — the task must allow it; otherwise a
  `sync.WaitGroup` + error slice, or `wg.Go` on Go 1.25+, without the cancellation). Every goroutine the command
  starts is joined before the command returns — no fire-and-forget.
- Results go into pre-sized slices by index or through a channel the caller drains; output order is deterministic
  (sort before printing — `cli-contract.md` §4), never "whichever finished first".
- Don't write to stdout from worker goroutines; collect, then print from the command goroutine.

## Context cancellation

- Every blocking call takes the context: `exec.CommandContext`, `http.NewRequestWithContext`, `db.QueryContext`,
  `time.After` replaced by `select { case <-ctx.Done(): … case <-t.C: … }`.
- Loops that may run long check `ctx.Err()` per iteration. A goroutine that sends on a channel selects on
  `ctx.Done()` too, or it leaks when the receiver stops early.
- `--timeout` becomes `context.WithTimeout(cmd.Context(), d)` with `defer cancel()`; `DeadlineExceeded` maps to exit 6.
- The signal context (`output-and-terminal.md`) is the root of every command's context — so Ctrl-C cancels all of it.

## No goroutine leaks

- Every goroutine has an owner that knows how it ends (context cancelled, channel closed, `Wait` returned).
- Unbuffered sends without a select on `ctx.Done()`, and `time.Tick` in a function that returns, are the classic
  leaks; use `time.NewTicker` + `defer t.Stop()`.
- Tests: `t.Context()` is cancelled when the test ends; if a test starts goroutines, it waits for them (the code under
  test should expose a `Wait`/`Close`). `go.uber.org/goleak` only if the module already uses it.

## Shared state

- Prefer passing data over sharing it. When sharing: a `sync.Mutex` next to the fields it guards (comment which),
  `sync.Once`/`sync.OnceValue` for lazy init, `atomic.Int64` etc. for counters.
- Never copy a struct containing a mutex (`govet` `copylocks`). Don't hold a lock across I/O or a channel send.
- Package-level mutable variables are shared by parallel tests — avoid them.

## The race detector

`go test -race` needs cgo (`CGO_ENABLED=1` and a C toolchain); the group's `iru-go-test` defaults `race: yes` when cgo is
available and reports when it isn't. Write tests that exercise concurrent paths in parallel so a race can actually be
observed; a reported race is a bug in this task's code, never something to suppress.
