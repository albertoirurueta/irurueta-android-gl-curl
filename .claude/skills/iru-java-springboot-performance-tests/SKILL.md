---
name: iru-java-springboot-performance-tests
description: "Invoke as `/iru-java-springboot-performance-tests`, or with a `stack-file:` arg from another skill. Generates Apache JMeter load tests for a Spring Boot service in a standalone `performance/` project (baseline, saturation and ramp profiles) covering the main happy-path use cases, recording response times plus server CPU and memory per concurrency level. Use when a service has real endpoints and load behavior is the question."
model: sonnet
---

# Java Spring Boot Performance Tests

Produce a performance suite that answers one question: **as the number of concurrent users rises, what happens to
response time, CPU, and memory?** Everything below serves that correlation — a load test that reports only
response times tells you the service got slower but not whether it ran out of heap, saturated a core, or spent
the run in GC.

Three rules shape the whole suite:

1. **Happy paths of the main use cases only.** Performance testing is about the flows that carry the traffic. Error
   paths, edge cases, and rarely-used admin endpoints belong in the functional suite; putting them here dilutes the
   signal and inflates the maintenance cost of a suite that is already expensive to keep truthful.
2. **The suite lives outside the Maven reactor.** `performance/` has its own `pom.xml` and is never listed as a
   `<module>` of the service's root pom. A load test that runs during `mvn verify` is a load test that gets
   disabled within a week.
3. **One test plan, three scenario profiles.** The plan is parameterized entirely through JMeter properties
   (`${__P(name,default)}`); a scenario is a `.properties` file, not a copy of the plan. Three copies of a JMX file
   drift apart on the first endpoint change.

## Step 0 — Resolve inputs and check prerequisites

Parse `args` for `stack-file: <path>`, defaulting to `springboot-stack.yml` at the repository root. If it exists,
read it for `project.artifactId`, `project.basePackage`, `stack.restServer`, `stack.grpcServer`,
`stack.messaging`, `stack.security`, `stack.metrics`, and `stack.concurrency`. If it doesn't, detect the same
facts from the repository itself (root `pom.xml`, `apis/`, the module tree) — this skill must work on a Spring
Boot service that was never scaffolded by `iru-setup-java-springboot`.

Then check, and report anything missing before writing anything:

| Check | Why it matters |
|---|---|
| `mvn -v` | The recommended runner is `jmeter-maven-plugin`, which downloads JMeter and its extensions itself — no local JMeter install needed. |
| `jmeter --version` | Optional. Only needed for the standalone CLI path and for opening the plan in the GUI to edit it. |
| `java -version` | JMeter needs a JRE; the load generator's JVM is separate from the service's. |
| `python3 --version` | Used by the results summarizer in Step 7. If absent, still write the script and say it needs Python 3. |
| Actuator reachable | `curl -s localhost:<mgmt-port>/actuator/health`. Server-side CPU/memory capture in Step 5 depends on it. |
| `docker info` | Only needed if the service under test runs in a container and container-level stats are wanted. |

If `performance/` already exists, do **not** overwrite it wholesale. Read what's there, treat this run as a
gaps-to-fill pass (add a missing scenario, add a use case to the existing plan), and say so in the report. A JMX
file that has been hand-tuned in the GUI carries real work.

## Step 1 — Discover the main use cases and their happy paths

Do not ask the user to list the use cases from memory — find them, then confirm. Search, in this order:

- **`apis/rest-server/*.yaml`** — the OpenAPI contracts. Each `operationId` with a `2xx` response is a candidate.
  The spec also gives the request body shape, the required fields and their constraints, and the auth scheme.
- **`apis/grpc-server/*.proto`** — each `rpc` in each `service` is a candidate.
- **`api/rest-server/`, `api/grpc-server/`, `api/consumers/`** — the hand-written adapters, which reveal which
  generated operations are actually implemented (a spec can be ahead of the code).
- **`application/`** — the use-case/command-handler classes. This is the best source for *which* flows are the
  main ones: a use case that orchestrates several ports and writes to the domain is a main flow; a passthrough
  read of a lookup table usually isn't.
- **`infrastructure/metrics/`** — if the service already defines functional metrics, whatever it chose to count is
  a strong statement about what matters.
- **README, documentation pages, and the git history** — the endpoints that change most often are the ones under
  real use.

Delegate this survey to the `iru-explore` skill (or an `Explore` agent) when the service is large, and ask for the
candidate list rather than the file contents.

Turn the findings into a **shortlist of 3–7 use cases**. Fewer than three and the suite doesn't represent the
service; more than seven and it becomes unmaintainable and each scenario's concurrency is spread too thin to say
anything. For each, work out and record:

- The **happy-path journey**: the ordered HTTP/gRPC calls a real user makes, not one isolated request. A "place an
  order" use case that only exercises `POST /orders` while ignoring the `GET /products` that precedes it measures
  something no user ever does.
- **What varies per user** — ids, search terms, payload bodies — which becomes a CSV data set in Step 4.
- **Which calls mutate state**, and what cleanup or unique-key strategy that needs.
- **The read/write mix and the relative weight** of this use case in real traffic, as a percentage. If the service
  is live, take it from access logs or the existing metrics rather than guessing; if it isn't, say plainly in the
  report that the weights are an assumption.
- **A response-time budget (p95)** per use case, if the user or the documentation has one.

Present the shortlist to the user with the journey and the proposed weight for each, and ask them to confirm,
drop, add, or re-weight. **Nothing is written before this confirmation** — a performance suite aimed at the wrong
flows is worse than none, because it produces confident numbers about something nobody cares about.

## Step 2 — Agree the workload model

Use `AskUserQuestion` for the numbers, offering these as defaults and stating clearly that they are placeholders
that must be justified against expected production traffic:

| Scenario | Purpose | Default shape |
|---|---|---|
| `low-load` | The baseline. Establishes reference response times with no contention, and is the run you can afford to do often (including in CI). | 10 concurrent users, 1 min ramp, 5 min steady. |
| `high-load` | Sustained saturation. Answers whether the service holds a target concurrency for a realistic period without degrading, leaking, or throwing. | 200 concurrent users, 5 min ramp, 15 min steady. |
| `scale-up-down` | The shaped run. Steps concurrency up, holds each level, then steps symmetrically back down. This is the scenario that produces the response-time/CPU/memory *curve* against concurrency, exercises autoscaling, and exposes leaks — memory should return near its baseline on the way down; if it doesn't, that's a leak, not noise. | 4 steps up to the high-load level, 3 min hold per step, then the same 4 steps down. |

Also settle:

- **Where the load is aimed.** A local `docker compose` stack, a deployed test environment, or (needing explicit
  approval) something closer to production. Capture it as `target.host`/`target.port`/`target.protocol` properties
  — never hardcode a hostname into the plan.
- **Whether the load generator is on the same machine as the service.** If it is, say clearly that the CPU and
  memory numbers are contaminated — JMeter competes with the service for the same cores — and that the results are
  usable for relative comparison only. Recommend a separate machine for any run whose numbers will be quoted.
- **What stands in for the downstream services.** If the service has REST/gRPC clients, the local stack answers
  them with the API mock (`stack.apiMock` — Microcks or WireMock). A load run against a mock measures this
  service in isolation, which is a legitimate and often preferable thing to measure — but the mock answers in
  single-digit milliseconds, so any use case whose real latency is dominated by a downstream call will look far
  faster than production. Say which it is, and if downstream latency matters, add a fixed response delay to the
  mock (both tools support one) set to the downstream's real p95 rather than leaving it at zero. Also watch the
  mock's own CPU: at high concurrency it can saturate before the service does, and then the numbers are about the
  mock.
- **Auth.** If `stack.security.enabled`, how a test user authenticates: a client-credentials token fetch, a
  pre-issued token supplied as a property, or a test profile with security relaxed. A token must come from a
  property or an environment variable — **never committed to the repository**.

## Step 3 — Create the `performance/` project

```
performance/
  README.md                       # how to run everything — written in Step 8
  pom.xml                         # standalone; NOT a module of the service reactor
  thresholds.properties           # per-use-case p95 budgets and the overall error-rate budget
  profiles/
    low-load.properties
    high-load.properties
    scale-up-down.properties
  src/test/jmeter/
    <artifactId>-performance.jmx  # the single parameterized plan
    user.properties               # JMeter/result-writer settings applied to every run
  src/test/resources/data/
    <use-case>.csv                # one data set per use case that needs varying input
  scripts/
    summarize.py                  # JTL + server metrics -> per-concurrency summary, pass/fail
    collect-container-stats.sh    # optional container-level CPU/memory sampler
  results/                        # git-ignored run output
```

Write `performance/pom.xml` as a standalone Maven project (its own `<groupId>`/`<artifactId>`, e.g.
`<artifactId>-performance`, packaging `jar`, no parent from the service reactor). Configure
`com.lazerycode.jmeter:jmeter-maven-plugin`:

- Pin the plugin version and pin `<jmeterVersion>` — an unpinned JMeter turns an unrelated Tuesday into a
  regression report.
- `<jmeterExtensions>` with `kg.apc:jmeter-plugins-casutg` (the Ultimate Thread Group used by the shaped
  scenario) and its `kg.apc:jmeter-plugins-cmn-jmeter` dependency. The plugin resolves these from Maven, which is
  the whole reason to prefer this runner: no manual Plugins Manager step, and CI gets the same versions.
- A `<scenario>` property defaulting to `low-load`, and
  `<customPropertiesFiles><file>${project.basedir}/profiles/${scenario}.properties</file></customPropertiesFiles>`
  so one command selects a scenario.
- `<propertiesUser>` for the target host/port/protocol, each defaulting from a Maven property so `-Dtarget.host=…`
  works.
- `<generateReports>true</generateReports>` for the HTML dashboard, and `<resultsFileFormat>csv</resultsFileFormat>`.
- Bind the plugin's `jmeter-tests` goal to `verify`, and **leave the default `<testFilesIncluded>`** so the single
  plan is picked up.
- Raise the JMeter JVM heap via `<jMeterProcessJVMSettings>` (`-Xms1g -Xmx4g` for the high-load scenario) — a load
  generator that GCs under load reports the client's latency as the server's.

Add `performance/results/`, `performance/target/`, and `performance/src/test/jmeter/*.jtl` to the repository's
root `.gitignore`. Result files are large, are not reviewable, and go stale immediately.

## Step 4 — Build the test plan

Write one `.jmx` covering every confirmed use case. JMX is XML that JMeter is strict about — build it carefully
and prove it loads with the smoke run in Step 9 before believing it. Structure:

```
Test Plan  (functional mode OFF; "Run tearDown after shutdown" ON)
├── User Defined Variables — every tunable as ${__P(name,default)}
├── HTTP Request Defaults — protocol/host/port, connect timeout 5000, response timeout 30000
├── HTTP Header Manager — Content-Type / Accept, and Authorization: Bearer ${token}
├── HTTP Cookie Manager — "Clear cookies each iteration" ON
├── setUp Thread Group
│   ├── auth: fetch a token once, store with __setProperty
│   └── warm-up: a few iterations of each journey, discarded from the report
├── Thread Group "uc-<name>"                      ← one per use case
│   ├── CSV Data Set Config — recycle on EOF, sharing mode "All threads"
│   └── Transaction Controller "<use case>"       ← the number the SLA is about
│       ├── HTTP Request (step 1)
│       │   ├── JSON Extractor — carry ids into the next step
│       │   ├── Response Assertion — response code matches 2xx
│       │   └── Duration Assertion — ${__P(sla.<uc>.ms,...)}
│       ├── Uniform Random Timer — think time
│       └── HTTP Request (step 2) …
├── Thread Group "METRICS-server"                 ← Step 5
├── tearDown Thread Group — delete what the run created
└── Simple Data Writer → ${__P(results.file)}     ← the only listener enabled for a real run
```

Points that decide whether the numbers mean anything:

- **Concurrency is distributed per use case through properties.** Give each use-case thread group
  `${__P(uc.<name>.threads,0)}` threads. A scenario profile then sets the split (`uc.search.threads=140`,
  `uc.checkout.threads=60`) to match the weights agreed in Step 1. A thread group with 0 threads simply doesn't
  run, so one plan can serve a scenario that exercises a subset.
- **Flat thread groups use the scheduler**, not a loop count: infinite loops, `duration=${__P(duration,300)}`,
  `ramp-up=${__P(rampup,60)}`. A loop count makes the run length depend on how fast the service answers, so a
  slower service is tested for longer — the opposite of what you want.
- **Think time is not optional.** Without a timer, 10 JMeter threads generate the load of hundreds of real users
  and "concurrent users" stops meaning anything. Use a `Uniform Random Timer` between the steps of a journey with
  a delay that reflects how a person actually uses the flow, and say in the README what was assumed.
- **A `Transaction Controller` per journey** ("Generate parent sample" on) gives one end-to-end number per use
  case alongside the per-request ones. That parent sample is what the SLA and the summary table are about.
- **Assert on every request.** A load test with no assertions happily reports excellent response times for a wall
  of 500s. A `Response Assertion` on the status code is the minimum; add a small body assertion where a 200 can
  still be a failure. Keep assertions cheap — regex over a large response body burns load-generator CPU.
- **Correlate, never replay.** Ids, tokens, and ETags come from a `JSON Extractor` on the previous response. A
  hardcoded id makes every virtual user hit the same row, which is a cache test.
- **Unique data per iteration** for anything that writes: `${__UUID()}`, `${__threadNum}`, `${__time()}`, or a CSV
  large enough not to recycle within a run. Otherwise the run fails on duplicate-key errors partway in, or worse,
  succeeds while every user contends on one row.
- **No `View Results Tree`, no `Aggregate Report`, no assertion-heavy listeners in the saved plan.** They are GUI
  tools; enabled during a CLI run they consume memory proportional to the sample count and will eventually kill
  the run. The `Simple Data Writer` writing a CSV JTL is the only listener that belongs.
- **gRPC use cases**, if `stack.grpcServer` is true, need the `vn.zalopay.benchmark:jmeter-grpc-request` sampler
  added to `<jmeterExtensions>`; point it at the `.proto` in `apis/grpc-server/`. Say in the report that this
  sampler is a third-party plugin with a slower release cadence than JMeter itself.
- **Messaging use cases**, if `stack.messaging.enabled`, are driven either by a JSR223 sampler using the Kafka
  producer client, or — usually better — by loading the REST/gRPC entry point that publishes the event and
  measuring consumer lag separately from JMeter. Recommend the latter and explain why: JMeter measures
  request/response latency, and a fire-and-forget produce has no response worth timing.

Write `src/test/jmeter/user.properties` with the result-writer settings every run needs:

```properties
jmeter.save.saveservice.output_format=csv
jmeter.save.saveservice.timestamp_format=ms
jmeter.save.saveservice.thread_counts=true      # required: this is the "concurrent users" column
jmeter.save.saveservice.latency=true
jmeter.save.saveservice.connect_time=true
jmeter.save.saveservice.response_data=false     # never save bodies during a load run
jmeter.save.saveservice.samplerData=false
summariser.interval=30
# Keep the polled server metrics out of the response-time dashboard.
jmeter.reportgenerator.sample_filter=^(?!METRICS-).*
jmeter.reportgenerator.overall_granularity=5000
```

`thread_counts=true` is the single most important line in that file — without it the JTL has no record of how many
users were active when each sample was taken, and the entire concurrency correlation this suite exists for becomes
impossible to reconstruct.

## Step 5 — Measure the server, not just the client

JMeter measures the client's view. CPU and memory belong to the service, and JMeter has no idea about them unless
you collect them. Add a dedicated `METRICS-server` thread group: **1 thread**, infinite loops, the same
`duration` as the scenario, with a `Constant Timer` of 5000 ms.

Prefer a **single scrape of `/actuator/prometheus`** per poll when the Prometheus registry is present
(`stack.metrics: true`): one request, then `Regular Expression Extractor`s for the series you want. Fall back to
individual `/actuator/metrics/<name>` calls with `JSON Extractor`s on `measurements[0].value` when it isn't.
Collect at minimum:

| Metric | Actuator name / Prometheus series | What it tells you |
|---|---|---|
| Process CPU | `process.cpu.usage` / `process_cpu_usage` | The service's own share of the host's CPU (0–1). The primary saturation signal. |
| System CPU | `system.cpu.usage` / `system_cpu_usage` | Whether something *else* on the box is the problem. |
| Heap used | `jvm.memory.used{area:heap}` / `jvm_memory_used_bytes` | Growth that never returns to baseline after ramp-down is a leak. |
| Heap committed / max | `jvm.memory.committed`, `jvm.memory.max` | Heap used means nothing without the ceiling. |
| GC pause | `jvm.gc.pause` (count and total) | High p99 with normal p50 is usually GC, not the code. |
| Live threads | `jvm.threads.live` | Thread-pool exhaustion under blocking I/O. |
| Server-side request timing | `http.server.requests` (count/total/max) | The gap between this and JMeter's number is queueing and network. |

Then attach a **JSR223 PostProcessor** to the scrape that appends one row per poll to
`${__P(metrics.file)}` — a CSV with `timestamp`, the current total active thread count from
`JMeterContextService.getNumberOfThreads()`, and every extracted value. **That active-thread column is what makes
the CPU and memory numbers answer "based on amount of concurrent users"** rather than being an undated list of
readings. Open the file in append mode once per run and flush each line.

Name every sampler in this thread group with the `METRICS-` prefix so the `sample_filter` in `user.properties`
keeps them out of the response-time dashboard. A 5-second poll that takes 3 ms would otherwise flatter every
percentile in the report.

Two things to state plainly:

- **Actuator must be exposed on the environment under test** (`management.endpoints.web.exposure.include` covering
  `metrics`/`prometheus`) and reachable from the load generator. If it's on a separate management port, that's a
  separate `metrics.host`/`metrics.port` property — don't assume it shares the application port.
- **If the service runs in a container, also collect cgroup-level stats.** The JVM's view and the container's
  limit are different numbers, and it's the container limit that gets the process OOM-killed. Write
  `scripts/collect-container-stats.sh` — a loop over
  `docker stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}}'` at the same 5-second cadence,
  timestamped into `results/<scenario>-container-stats.csv` — and note it must be started alongside the run and
  stopped after it.

## Step 6 — The three scenario profiles

Each profile is only properties. Nothing about the journeys changes between them.

`profiles/low-load.properties` — the baseline:

```properties
scenario=low-load
threads.total=10
rampup=60
duration=300
uc.<name>.threads=<share of 10, per the agreed weights>
results.file=results/low-load.jtl
metrics.file=results/low-load-server-metrics.csv
```

`profiles/high-load.properties` — sustained saturation: the same keys with the high-load numbers (200 / 300 / 900
by default) and a longer ramp. A long ramp matters here: arriving at 200 users instantly measures cold-start and
connection-pool warm-up, not steady-state behaviour.

`profiles/scale-up-down.properties` — the shaped run. The flat `Thread Group`s can't express it, so this scenario
uses an **Ultimate Thread Group** (`kg.apc:jmeter-plugins-casutg`) per use case, whose schedule rows are
themselves properties:

```properties
scenario=scale-up-down
# Each row: start-threads, initial-delay, startup-time, hold-time, shutdown-time (seconds)
step1.threads=50
step1.hold=180
step2.threads=50    # cumulative 100
...
rampdown.hold=180
duration=2400
```

Give the ramp-down the **same step size and hold time as the ramp-up**. The symmetry is the point: comparing
response time, CPU, and heap at 100 users on the way up against the same 100 users on the way down is how the
suite distinguishes a service that recovers from one that has degraded — a leak, an unbounded cache, a
connection pool that never shrinks, or a thread pool still working through a backlog.

Add a warm-up to every scenario and exclude it from the reported window. A JVM's first minute is JIT compilation
and class loading; including it makes every run look worse than the service is, and makes two runs incomparable.

## Step 7 — Results, thresholds, and the summary

Every run produces three artifacts under `results/`: the JTL, the server-metrics CSV, and JMeter's HTML dashboard
(`-e -o`, or the Maven plugin's `generateReports`). The dashboard is good for a human reading one run; it says
nothing about CPU or memory, and nothing about how either moved with concurrency. That's what
`scripts/summarize.py` is for.

Write it in Python 3 using only the standard library (`csv`, `statistics`, `argparse`), taking the JTL and the
metrics CSV and producing a Markdown table plus an exit code:

- **Bucket every sample by the `allThreads` column** of the JTL (rounded to the nearest step for the shaped
  scenario), and join the server metrics on the nearest timestamp within the poll interval.
- Emit one row per concurrency level per use case: `concurrency | samples | error % | p50 | p90 | p95 | p99 | max
  | throughput req/s | heap avg/max MB | process CPU avg/max % | GC pause total ms`.
- For `scale-up-down`, emit the up and down legs as separate columns at the same concurrency level, so the
  recovery comparison is one glance rather than an exercise.
- Compare the p95 of each use case's transaction against `thresholds.properties`, and the overall error rate
  against its budget (default 1%). **Exit non-zero on a breach** — that exit code is what makes this usable as a
  gate; without it the suite is a report nobody reads.
- Print the point where p95 crosses the budget and the concurrency at which CPU passes ~80% — the two numbers
  anyone actually asks for after a load test.

Percentiles come from the raw samples, never from averaging JMeter's per-interval aggregates. And read
percentiles, not the mean: a mean response time hides exactly the tail that the load test was run to find.

## Step 8 — Write `performance/README.md`

The commands from Step 10, verbatim and runnable, plus: what each scenario is for and its current numbers, which
use cases are covered and the weight each carries, the think-time assumptions, the response-time budgets and where
they came from, how to point a run at a different environment, and how to read the summary. State that the
generated numbers are meaningless without a named environment and that every reported result should carry the
environment, the date, the service version, and the scenario.

## Step 9 — Verify

Delegate each to `iru-gate-runner` and ask only for a compact result — a load-test log is enormous.

```bash
# 1. The plan parses and every sampler is wired: one user, one iteration.
jmeter -n -t performance/src/test/jmeter/<artifactId>-performance.jmx \
       -Juc.<first-uc>.threads=1 -Jduration=10 -l /tmp/smoke.jtl

# 2. The real path, shortest scenario, against a running service.
mvn -f performance/pom.xml verify -Dscenario=low-load -Dduration=60

# 3. The summarizer runs and its thresholds bite.
python3 performance/scripts/summarize.py \
        --jtl performance/results/low-load.jtl \
        --metrics performance/results/low-load-server-metrics.csv \
        --thresholds performance/thresholds.properties
```

The service must be up for 2 and 3 (`docker compose up -d` plus the app under its `local` profile, or a deployed
test environment). If it can't be started, still write every file and say plainly in the report that the suite is
**unverified** — do not claim a passing smoke run that didn't happen.

Expect to iterate on: a JSON Extractor path that doesn't match the real response, an assertion that fails because
the endpoint needs auth the setUp group didn't provide, the metrics thread group appearing in the dashboard
(`sample_filter` wrong), and an empty `allThreads` column (`thread_counts` not set). Check the smoke run's JTL has
zero errors before running anything longer — a scenario that runs 15 minutes to report 100% failures wastes both
the time and the environment.

## Step 10 — Report and tell the user how to run them

Summarize: the use cases covered and the journey behind each, the weights and where they came from (measured or
assumed), the three scenarios and their numbers, the metrics collected server-side, the thresholds set, and the
result of each Step 9 command.

Then give the run instructions explicitly — this is the part the user asked for, so make it copy-pasteable:

**Before any run**, bring up the target and confirm it's healthy:

```bash
docker compose up -d                                   # if testing against the local stack
curl -sf localhost:8080/actuator/health                # must be UP before load starts
```

**Recommended — Maven, no JMeter install required** (downloads JMeter and its plugins on first run):

```bash
mvn -f performance/pom.xml verify -Dscenario=low-load
mvn -f performance/pom.xml verify -Dscenario=high-load
mvn -f performance/pom.xml verify -Dscenario=scale-up-down

# Point it somewhere else, or override any number in the profile:
mvn -f performance/pom.xml verify -Dscenario=high-load \
    -Dtarget.host=orders.test.example.com -Dtarget.port=443 -Dtarget.protocol=https \
    -Dduration=600 -Dthreads.total=300
```

**Standalone JMeter CLI**, if JMeter is installed locally (needs the casutg plugin via the Plugins Manager for the
shaped scenario):

```bash
jmeter -n -t performance/src/test/jmeter/<artifactId>-performance.jmx \
       -q performance/profiles/high-load.properties \
       -p performance/src/test/jmeter/user.properties \
       -l performance/results/high-load.jtl \
       -e -o performance/results/high-load-report
```

**Summarize and gate** (this is where CPU and memory meet response time):

```bash
python3 performance/scripts/summarize.py \
        --jtl performance/results/high-load.jtl \
        --metrics performance/results/high-load-server-metrics.csv \
        --thresholds performance/thresholds.properties
echo $?    # non-zero means a threshold was breached
```

**Read the output**: `performance/results/<scenario>-report/index.html` for JMeter's dashboard (response times,
throughput, errors over time), the summarizer's Markdown table for the concurrency/CPU/memory correlation, and —
if metrics are enabled — Grafana at `localhost:3000` for a live view while a run is in progress.

**Edit the plan** in the GUI, never run a load test from it:

```bash
jmeter -t performance/src/test/jmeter/<artifactId>-performance.jmx
```

Finish with the warnings that matter:

- **Never run `high-load` or `scale-up-down` against production, or against a shared environment, without
  explicit approval from whoever owns it.** A load test is indistinguishable from an outage to everyone else
  using that environment, and to most alerting.
- **The generated numbers are placeholders until someone justifies them.** 200 users is a number this skill
  invented; the real one comes from expected traffic, and the whole suite is only as credible as that figure.
- **Results are only comparable within one environment.** A laptop's numbers say nothing about a Fargate task with
  a 1 vCPU limit. Always record environment, service version, date, and scenario alongside any figure that gets
  quoted.
- **Check the load generator isn't the bottleneck** before believing a poor result: if JMeter's own host is at
  high CPU, or hits its file-descriptor or ephemeral-port limits, you are measuring the client. Watch JMeter's own
  process, raise `ulimit -n`, and split across machines before concluding the service is slow.
- **Container CPU limits change everything.** A service capped at 1 vCPU saturates at a concurrency that looks
  absurd next to an unconstrained local run — compare against the cgroup limit, not the host's core count.
- **Data grows.** Every write-path run leaves rows behind. The tearDown group cleans up what it can; verify the
  target environment doesn't accumulate a run's worth of data indefinitely, because a database that grows across
  runs quietly makes each run slower than the last and looks exactly like a regression.
- **The suite goes stale silently.** It keeps passing against endpoints that no longer matter. Revisit the use
  case shortlist whenever the service's main flows change, and re-run this skill to add or drop one.
