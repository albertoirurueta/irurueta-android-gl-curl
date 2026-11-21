---
name: iru-setup-java-springboot-platform
description: "Invoked as `/iru-setup-java-springboot-platform` (args: `stack-file`, `mode`, `on-existing`), normally by `iru-setup-java-springboot`. Generates OpenTofu infrastructure-as-code in `infra/` to deploy a Spring Boot service to AWS or Google Cloud with managed backing services, plus rollout infrastructure when rolling deployments are enabled."
model: sonnet
---

# Setup Java Spring Boot Platform

Generate the OpenTofu configuration that deploys this service. Five rules govern everything:

1. **Managed services over self-packaged containers.** The compose stack from
   `iru-setup-java-springboot-testcontainers` exists for local development and integration tests. Deployed
   environments use the provider's managed offering — RDS not a Postgres container, MSK not a Kafka container —
   because backups, failover, patching, and encryption-at-rest are the actual work, and running them yourself is
   a permanent cost.
2. **No secret in any file.** Not in a `.tf`, not in a `.tfvars`, not in a default. Secrets are created empty (or
   out-of-band) in the provider's secret manager and referenced by ARN/name; the value is populated by a human or
   a separate process. State this in the report.
3. **Remote, encrypted, locked state.** `tofu` state contains resource attributes that are effectively secrets
   (connection strings, generated passwords, certificate metadata). Local state in a repository is a credential
   leak waiting to happen.
4. **Keyless CI authentication.** GitHub Actions authenticates via OIDC to a role/service account with a trust
   policy scoped to this repository. No long-lived access key is ever created.
5. **Nothing is applied by this skill.** It writes configuration and runs `tofu validate`/`plan` at most. Every
   `apply` is the user's explicit decision, because every `apply` costs money.

## Step 0 — Resolve inputs

Parse `args` for `key: value` lines: `stack-file: <path>`, defaulting to `springboot-stack.yml`; `mode`
(`new`/`existing`, informational — it never means "update silently"); and `on-existing`
(`ask`/`keep`/`update`/`replace`, default `ask`). Also resolve **rolling deployments**: `deploy.rolling` in the
manifest (`enabled`, `strategy`, `advance`, `steps`, `bakeMinutes`) — the manifest is the source of truth when
`stack-file` is given; the `args` lines `rolling: yes|no`, `deployment-strategy:`, `advance:`, `steps:` (comma list)
and `bake-minutes:` apply when there is no manifest block or when the caller passes them explicitly. Absent or
`rolling: no` means **rolling disabled: nothing in this skill changes** — generate exactly the infrastructure the
rest of this file describes and skip every "Rolling deployments" paragraph below. Read the manifest for `stack.cloud`, the
database engines, caches, messaging, metrics/tracing, `stack.dynamicConfig`, and whether the service exposes REST
and/or gRPC. That tells you what infrastructure is needed. It does **not** tell you the operational parameters —
Step 1 asks those.

Check whether `tofu` (or `terraform`) is on the PATH; if not, note it, write the files anyway, and skip Step 6's
validation with an explicit note. Check for an existing `infra/` directory and say whether this run is
**generating the infrastructure code from scratch** or **updating an existing `infra/`** (list the modules and
`envs/*` found). Never touch a `.tfstate`, whatever is chosen below. For each existing file group this skill owns
(per module under `infra/modules/`, per environment under `infra/envs/`, the backend configuration), warn and —
unless `on-existing` says otherwise — ask with `AskUserQuestion` (up to 4 per call, one question each):

- **Update — fill gaps (Recommended)** — today's behaviour: add missing modules/resources/variables/outputs for
  technologies newly in the manifest and correct provider/version pins this skill owns; never rewrite an existing
  resource block that may manage live infrastructure. Customizations survive.
- **Keep as is** — leave the group untouched; it may lack infrastructure for newly added technologies — Step 7
  lists each gap.
- **Replace** — regenerate the group from this skill's templates; customizations are lost (recoverable only from
  git history), and **a regenerated resource block can produce a destructive plan against live infrastructure**
  (renamed addresses mean destroy-and-recreate). Only offer it with that warning, ask for an explicit second
  confirmation for any environment that has remote state, and treat an `on-existing: replace` from `args` as
  Update for those environments (say so) — a scripted run must not be able to replace live-resource
  configuration unattended.

## Step 1 — Ask what cannot be inferred

Ask these directly; each genuinely changes the generated configuration. Use `AskUserQuestion` for the bounded ones.

- **Environments** — which to generate (`dev`, `staging`, `prod` is the common set; a single `prod` is valid for a
  small service). Each gets its own directory and its own state, so a `dev` mistake can't reach `prod`.
- **Region** (per environment, defaulting to the same for all) — and for `prod`, whether multi-AZ/multi-zone
  redundancy is required. Multi-AZ roughly doubles managed-database cost; it's the user's call, not a default to
  assume.
- **Sizing per environment** — replica count (min/max for autoscaling), CPU, and memory per instance. Offer
  starting points and say they're starting points: a small Spring Boot service typically wants 1 vCPU / 1–2 GiB and
  2 replicas in `prod`, 0.5 vCPU / 1 GiB and 1 replica in `dev`. Ask for the JVM heap intent too, since container
  memory must exceed heap plus metaspace plus native overhead — a container sized exactly to `-Xmx` gets
  OOM-killed.
- **Compute runtime** (`AskUserQuestion`):
  - AWS: **ECS Fargate** (recommended — no cluster to operate, and enough for a single service) or **EKS**
    (choose when Kubernetes is already the org standard, or `stack.dynamicConfig` is `kubernetes`, which requires
    it).
  - Google Cloud: **Cloud Run** (recommended — scales to zero, simplest) or **GKE Autopilot** (choose when
    Kubernetes is the org standard or `stack.dynamicConfig` is `kubernetes`).
  - If `stack.dynamicConfig` is `kubernetes` and the user picks a non-Kubernetes runtime, flag the contradiction
    and make them resolve it rather than generating a configuration that can't work.
- **Existing versus new infrastructure** — for **each** database, cache, and Kafka cluster: create it here, or
  reference one that already exists? Referencing an existing resource means a `data` source and a variable for its
  identifier, which is very different configuration from creating it. Ask per resource; teams commonly share one
  database cluster across services.
- **Public exposure** — is the service internet-facing or internal only? If internet-facing: the domain name, and
  whether the DNS zone already exists in Route 53 / Cloud DNS or must be created. TLS certificates come from ACM
  or Google-managed certificates; note that DNS validation requires the zone to be authoritative, which is a
  manual step if the registrar is elsewhere.
- **State backend** — the S3 bucket + DynamoDB lock table, or the GCS bucket, to use. If none exists, generate a
  separate tiny `infra/bootstrap/` configuration that creates just the backend (applied once, with local state,
  then migrated) and say clearly that this is the one chicken-and-egg step.
- **Cost acknowledgement** — state plainly that applying this will create billable resources, name the ones that
  bill continuously regardless of traffic (managed databases, NAT gateways, load balancers, MSK/Managed Kafka
  clusters, provisioned OpenSearch), and confirm the user wants them generated. NAT gateways and idle Kafka
  clusters are the two line items that most often surprise people.

## Step 2 — Directory layout

```
infra/
  README.md                    # how to plan/apply, what is manual, what each variable means
  bootstrap/                   # only if the state backend must be created
  modules/
    network/                   # VPC/subnets/security groups, or the GCP equivalent
    service/                   # the compute runtime + its IAM + secret wiring
    database-<engine>/         # one per engine in the stack
    cache-redis/
    messaging-kafka/
    observability/
    dns/
  envs/
    dev/
      main.tf  variables.tf  outputs.tf  backend.tf  terraform.tfvars
    prod/
      ...
```

- **One state file per environment**, configured in that environment's `backend.tf`. Never a shared state.
- Modules take inputs and return outputs; they contain no environment-specific literals. The environment
  directories are thin — provider config, backend config, module calls, and values.
- Write `.tfvars.example` files with every variable documented, and git-ignore the real `.tfvars` (which may
  contain account ids and hostnames that aren't secrets but aren't worth publishing either). Confirm `.gitignore`
  covers `.terraform/`, `*.tfstate`, `*.tfstate.*`, `.terraform.lock.hcl` is **committed** (it pins provider
  versions — that's a feature), `*.tfvars`, and `crash.log`.
- Pin the OpenTofu version (`required_version`) and every provider version (`required_providers` with `~>`
  constraints). An unpinned provider will eventually change a resource's default and produce a destructive plan.

## Step 3 — What to generate, per cloud

Look up each resource type's current arguments in the provider documentation as you write — provider schemas
change, and a plausible-looking argument that doesn't exist fails `validate` with a confusing message. Generate
only what the stack actually needs.

### Common to both clouds

- **State backend** — remote, versioned, encrypted, with locking.
- **OIDC deployment identity** — a role (AWS) or service account + workload identity pool (Google Cloud) whose
  trust policy is scoped to `repo:<owner>/<repo>:ref:refs/heads/<branch>` or
  `repo:<owner>/<repo>:environment:<env>`. **Scope it to the environment**, not to `repo:<owner>/<repo>:*` — a
  wildcard subject lets any branch or PR in the repository deploy to production. Grant only the permissions the
  deploy needs (push image, update service, read secrets), not administrator.
- **Container registry** — ECR or Artifact Registry, with an immutable-tag policy and a lifecycle rule that expires
  untagged images.
- **The service itself** — with the container image reference as a variable (the workflow passes the built tag),
  the environment variables and secret references from Step 4, health-check paths matching the actuator probes
  (`/actuator/health/liveness`, `/actuator/health/readiness` on the management port), a graceful-shutdown period
  longer than the app's shutdown timeout, and autoscaling on CPU/request concurrency.
- **Secrets** — one secret per credential in Secrets Manager / Secret Manager, created with **no value** (or a
  placeholder that cannot work), with `lifecycle { ignore_changes = [...] }` on the value so a later `apply`
  never reverts a rotated secret. This pattern is what lets IaC manage the secret's existence and IAM without ever
  seeing its content.
- **Logging and metrics sinks** — CloudWatch log group with a retention period (the default of "never expire" is a
  cost trap), or Cloud Logging with a retention policy; and the Prometheus scrape path. If `stack.metrics` is
  true, generate the managed metrics target: Amazon Managed Service for Prometheus + Managed Grafana, or Google
  Cloud Managed Service for Prometheus. Note that the local compose Prometheus/Grafana are for development only.

### AWS specifics

| Need | Managed service | Notes to put in the generated comments |
|---|---|---|
| Compute | ECS Fargate service + ALB target group, or EKS deployment | Fargate needs no capacity management |
| MongoDB | **MongoDB Atlas** (via the `mongodbatlas` provider), not DocumentDB | DocumentDB emulates an older MongoDB wire protocol and lacks features Spring Data and Flamingock use; if the user insists on staying in-account, flag the compatibility risk explicitly and test the migration tooling against it early |
| PostgreSQL | RDS or Aurora PostgreSQL | `pgvector` is available as an extension — must be enabled per database; confirm the engine version supports the needed version |
| MariaDB | RDS MariaDB | — |
| Couchbase | **Couchbase Capella** (its own provider) | no first-party AWS equivalent |
| Elasticsearch | Amazon OpenSearch Service, or Elastic Cloud | OpenSearch has diverged from Elasticsearch; the Spring Data Elasticsearch client may need the Elastic-compatible endpoint or a version pin — flag this |
| Neo4j | **Neo4j Aura**, not Neptune | Neptune is not Neo4j: no Cypher-over-Bolt compatibility that Spring Data Neo4j expects |
| Qdrant | **Qdrant Cloud** | — |
| Redis | ElastiCache for Valkey/Redis, or MemoryDB if durability is required | ElastiCache is a cache; MemoryDB is a durable data store — pick per intent |
| Kafka | Amazon MSK (or MSK Serverless) | MSK has **no managed schema registry** — AVRO needs AWS Glue Schema Registry (a different client configuration than Confluent) or a self-hosted/Confluent Cloud registry. This is a real decision, not a detail: raise it explicitly and record which was chosen |
| Secrets | AWS Secrets Manager (+ Parameter Store for non-secrets) | required by `dynamicConfig: aws-secrets-manager` |
| Load balancer / DNS / TLS | ALB (REST) — gRPC needs the target group protocol set to `HTTP2`/`gRPC`; Route 53; ACM | an ALB with the wrong protocol version silently fails gRPC |
| Network | VPC, private subnets, NAT gateway, security groups | one NAT gateway per AZ is the cost/HA tradeoff — call it out |

### Google Cloud specifics

| Need | Managed service | Notes |
|---|---|---|
| Compute | Cloud Run service, or GKE Autopilot deployment | Cloud Run scales to zero — excellent for `dev`; note cold starts, and that `min-instances: 1` avoids them at a cost |
| MongoDB | MongoDB Atlas on GCP | Firestore is not MongoDB-compatible enough for Spring Data MongoDB + Flamingock |
| PostgreSQL | Cloud SQL for PostgreSQL, or AlloyDB | `pgvector` supported; AlloyDB for heavier analytical load |
| MariaDB | Cloud SQL for MySQL (MariaDB-compatible) | not a true MariaDB — flag the driver/compatibility implication |
| Couchbase | Couchbase Capella | — |
| Elasticsearch | Elastic Cloud on GCP | — |
| Neo4j | Neo4j Aura on GCP | — |
| Qdrant | Qdrant Cloud | — |
| Redis | Memorystore for Redis / Valkey | — |
| Kafka | Google Cloud Managed Service for Apache Kafka, or Confluent Cloud | same schema-registry gap as MSK — Confluent Cloud includes one, the managed service may not; check and record |
| Secrets | Secret Manager | required by `dynamicConfig: kubernetes` (mounted) or read directly |
| Load balancer / DNS / TLS | Global external Application Load Balancer, Cloud DNS, Google-managed certificates | Cloud Run + gRPC needs HTTP/2 end-to-end |
| Network | VPC, Serverless VPC Access connector (Cloud Run → private database), firewall rules | a Cloud Run service **cannot** reach a private Cloud SQL/Memorystore instance without the connector — this is the most common omission |

### Rolling deployments (only when rolling is enabled)

Design (decided up front, see Step 7's report): **the workflow drives the shifting and the rollout state lives in the
cloud**, so `promote.yml` is stateless — it reads the current split from the platform and sets the next one. Per
runtime the OpenTofu configuration therefore creates the *capability* to hold two versions and to split traffic
between them, but never the split itself: ignore the live weights/traffic/step in `lifecycle { ignore_changes }`
so a later `tofu apply` for an unrelated change cannot reset an in-flight rollout. New versions always deploy at
**0 % traffic**. Strategy `rolling` has no second environment: use the runtime's native rolling update
(ECS `deployment_configuration` with `minimum_healthy_percent`/`maximum_percent` and the deployment circuit breaker
with rollback enabled; Cloud Run's normal revision rollout; a plain Kubernetes `Deployment` with `RollingUpdate`),
`advance: auto`, health-check gated, with the documented weaker rollback (re-deploy the previous image).

Every environment's `outputs.tf` exposes (alongside the existing `service_health_url`):

| Output | Meaning |
|---|---|
| `rollout_candidate_url` | URL of the green version at 0 % production traffic (ALB test listener / Cloud Run tag URL / preview Service) |
| `rollout_gate` | `cloudwatch` \| `cloud-monitoring` \| `prometheus` |
| `rollout_alarm_names` | ECS: list of CloudWatch alarm names the gate watches |
| `rollout_target_groups` | ECS: the blue and green target group names (and ARNs for the listener) |
| `rollout_name` | Kubernetes: the Argo `Rollout` name |

**AWS — ECS Fargate** (`modules/service`):

- Two target groups (`blue`, `green`) with identical health checks (`/actuator/health/readiness` on the management
  port) and, per the gRPC note in the AWS table, the right protocol version. Two ECS services (`blue`, `green`
  slots), each registered with its own target group, so that either slot can hold the stable version; the workflow
  deploys the new image into the *idle* slot (image tag passed as a per-slot variable) and the other slot keeps
  serving. The idle slot is found by reading the listener weights, never from state.
- The ALB production listener has a weighted `forward` action with both target groups, initially 100/0
  (`ignore_changes` on the weights), plus a **test listener** (a separate port, security-group-restricted to the
  CI runners' egress or an allow-listed CIDR variable, or a header/path rule) that forwards 100 % to the green
  target group — its DNS name + port is `rollout_candidate_url`. **An ALB is required**; if the service is exposed
  through anything else (NLB only, API Gateway), flag it and stop rather than generate a canary that cannot split
  traffic. `deployment_controller { type = "ECS" }` (rolling-update controller) on both services — the workflow, not
  ECS, moves the traffic.
- CloudWatch alarms as the metric gate: ALB 5xx rate on the green target group, target-response-time p95 above a
  threshold variable, and unhealthy-host count > 0 — names listed in `rollout_alarm_names`, thresholds as variables
  with documented defaults to tune.
- Outputs per the table above. The OIDC deploy role (Common section) additionally gets, scoped to the listener, rules
  and target groups of this service: `elasticloadbalancing:ModifyListener`, `ModifyRule`, `DescribeListeners`,
  `DescribeRules`, `DescribeTargetHealth`, `AddTags`, `RemoveTags`, `DescribeTags` (the deploy/promote workflows keep "which slot is the candidate" in a `rollout-candidate` tag on the production listener), `cloudwatch:DescribeAlarms`, and `ecs:UpdateService`/`DescribeServices`
  for both services — never `*` resources.
- **Native ECS strategies — verified decision.** Checked against the current AWS developer guide ("Amazon ECS
  blue/green deployments") when this skill was authored: ECS now offers a native blue/green deployment type with a
  test listener, lifecycle hooks (Lambda or pause hooks continued with `ContinueServiceDeployment`), bake time and
  alarm-based rollback, and ECS itself performs the traffic shift. It was **not** chosen as the primary mechanism
  because the workflow-driven model gives `promote.yml` the same shape on ECS, Cloud Run and Kubernetes and supports
  an unbounded number of manual steps without a Lambda. It is the documented alternative for `advance: auto` only.
  Re-check the guide for the exact `deployment_configuration` arguments in the provider schema before offering it;
  if the AWS provider version pinned here lacks them, say so instead of guessing.

**Google Cloud — Cloud Run** (`modules/service`):

- `google_cloud_run_v2_service` with an explicit `traffic` block pinned to the **stable revision** (type
  `TRAFFIC_TARGET_ALLOCATION_TYPE_REVISION`, 100 %), with `ignore_changes` on `traffic`, so a new revision created by
  `tofu apply` receives **0 %** instead of `LATEST` taking everything. A revision **tag** gives the candidate URL
  (`rollout_candidate_url`), so the green revision is reachable before it takes traffic.
- Metric gate: a Cloud Monitoring alert policy (5xx ratio, p95 latency from `run.googleapis.com/request_*`) scoped to
  the service, whose name is exported; `rollout_gate = "cloud-monitoring"`. The workflow also runs the same MQL/
  PromQL query directly (the alert policy is for humans and for the audit trail).
- Deploy service account: `roles/run.developer`, or a custom role, bound **on the service** (not the project), plus
  `roles/iam.serviceAccountUser` on the runtime service account; `run.admin` scoped to this one service only when
  `run.services.setIamPolicy` is really needed — say which and why in the generated comment. Never project-wide.

**EKS / GKE Autopilot** (Kubernetes manifests in the `service` module):

- **Argo Rollouts controller** — installed with a `helm_release` (pinned chart version, own namespace) or, when the
  org already runs it, verified with a `data` lookup and a documented prerequisite; ask which (`AskUserQuestion`)
  because a cluster-wide controller may be a platform-team decision. **Flagger is the documented alternative and is
  not generated.**
- The `Deployment` becomes an Argo `Rollout` (`kubernetes_manifest`) whose canary steps **start with `setWeight: 0` then `pause: {}`** (so a new version deploys at 0 % and the first `promote` moves it to step 1 — the workflows rely on it) with `strategy.canary` (stable/canary
  Services, `steps` taken from the manifest: `setWeight` + a `pause` per step) or `strategy.blueGreen` (active/
  preview Services, `autoPromotionEnabled: false`, `previewReplicaCount`). For `advance: manual|hybrid` the human
  step is `pause: {}` (indefinite), resumed by `kubectl argo rollouts promote` from `promote.yml`; for `auto` use
  timed pauses of `bakeMinutes`. Preview/canary Service DNS is `rollout_candidate_url`; the `Rollout` name is
  `rollout_name`.
- `AnalysisTemplate` (+ `ClusterAnalysisTemplate` when shared) querying Prometheus for error rate and p95 latency,
  referenced from the steps for `auto`/`hybrid`; `rollout_gate = "prometheus"`. Requires the Prometheus address as
  a variable (managed Prometheus when `stack.metrics`).
- Cluster RBAC for the deploy identity to `get`/`list`/`patch` `rollouts` and `rollouts/status` in the service
  namespace only.

### Dynamic configuration wiring

- **`kubernetes`** — create the ConfigMap and Secret the service reads, plus a Role/RoleBinding granting the
  service account `get`/`list`/`watch` on ConfigMaps and Secrets in its namespace. Without the `watch`
  permission, `spring.cloud.kubernetes.reload` silently never fires, which looks like an application bug.
- **`aws-secrets-manager`** — the secrets, plus IAM allowing `secretsmanager:GetSecretValue` on exactly those ARNs
  (never `*`).
- **`spring-cloud-config`** — note that a Config Server is a separate deployable this configuration does not
  create; generate the client's environment variables and say the server's URL is an input.

## Step 4 — Connect the service to its dependencies

For each managed dependency, generate:

- the connection endpoint as an environment variable on the service (matching the placeholder names the `dev`/`prod`
  Spring profiles from `iru-setup-java-springboot-modules` already reference — read those files so the names
  actually line up, rather than inventing new ones);
- the credential as a **secret reference**, injected by the runtime, never as a plain environment variable value;
- the network path — security group rule / firewall rule / VPC connector — allowing the service to reach it, scoped
  to the service's own identity rather than to a CIDR where possible;
- IAM for the service's own workload identity to read its secrets and write its logs and metrics.

Generate an `outputs.tf` per environment exposing the endpoints and secret names, so the workflow and a human
operator can find them without opening the console.

## Step 5 — Document the manual steps

Write `infra/README.md` covering, concretely rather than generically:

- The bootstrap order: create the state backend, then `tofu init -migrate-state`, then per environment
  `tofu init && tofu plan && tofu apply`.
- **Every step OpenTofu cannot do**, each with instructions:
  - registering a domain, or delegating a subdomain when the registrar is outside the cloud provider;
  - populating each secret's value (the exact CLI command per secret, with the value redacted);
  - third-party accounts and their API keys — MongoDB Atlas, Couchbase Capella, Neo4j Aura, Qdrant Cloud,
    Confluent Cloud, Elastic Cloud — each of which needs an organization, a payment method, and a provider
    credential before its OpenTofu provider can do anything;
  - the schema registry's compatibility mode;
  - any account-level quota increase or service enablement (GCP API enablement can be generated; some AWS
    service quotas cannot);
  - SSO/OAuth provider configuration if `stack.security.enabled`.
- What each variable means and its blast radius.
- **Rolling deployments prerequisites (only when rolling is enabled)**: an ALB is required for ECS blue/green,
  canary and linear (weighted target groups); the service's metrics endpoint must be scraped by Prometheus for the
  Kubernetes `AnalysisTemplate` (and the Prometheus address supplied); the CloudWatch alarm / Cloud Monitoring
  thresholds are starting points to tune against real traffic; the Argo Rollouts controller must exist on the
  cluster (installed by the Helm release or by the platform team); the test listener / candidate URL must be
  reachable from the CI runners; and **cost** — blue/green and canary temporarily run both versions, roughly
  doubling the service's compute during a rollout, plus the extra target group/listener/alarms. Also document how to
  inspect and reset the cloud-side split by hand (listener weights, `gcloud run services update-traffic`,
  `kubectl argo rollouts status|abort`).
- How to destroy an environment safely, and what has deletion protection on purpose (production databases should;
  say so).

## Step 6 — Validate

Never `apply`. Run, per environment:

```bash
tofu -chdir=infra/envs/<env> init -backend=false
tofu -chdir=infra/envs/<env> validate
tofu fmt -recursive infra/
```

When rolling is enabled this covers the rollout additions too: both target groups and the weighted listener, the
alarms, the Cloud Run traffic/tag, and the Helm release / `Rollout` / `AnalysisTemplate` — `kubernetes_manifest`
resources need cluster access at plan time, so validate their schema and report any that only a real `plan` can
check as unverified rather than assuming. Confirm that `ignore_changes` is present on every weight/traffic
attribute (a second `plan` after the first must show no traffic change).

`-backend=false` lets validation run before the backend exists. Fix every error — an argument that doesn't exist
in the provider schema, a missing required argument, a type mismatch.

Offer to run `tofu plan` against real credentials **only if the user has them configured and asks for it**, and be
explicit that `plan` is read-only but does authenticate to the account. Report the resource counts (`to add`) and
call out anything the plan says it would destroy — in a fresh environment nothing should be destroyed, so a
destroy in the plan means the configuration is fighting something that already exists.

## Step 7 — Report

Summarize:

- Whether `infra/` was generated from scratch or updated, and per file group the action taken (**created /
  updated / replaced / kept as is**), with each gap left in a kept group.
- The cloud, environments, regions, and sizing generated.
- Whether rolling deployments are **enabled or disabled** (strategy, advance, steps, bake minutes), the rollout
  resources created per runtime, the `rollout_*` outputs the deploy/promote workflows consume, the design decision
  (workflow-driven shifting, state in the cloud; ECS native strategies as the `advance: auto` alternative) and the
  rollout prerequisites from Step 5.
- The compute runtime chosen, and every managed service the configuration will create versus reference as
  existing.
- Where the service's **third-party** dependencies land (Atlas, Capella, Aura, Qdrant Cloud, Confluent/Elastic
  Cloud) and that each needs its own account, billing, and provider credential.
- The OIDC identity created and exactly what it's scoped to.
- The secret names created empty, and the command to populate each.
- Every compatibility caveat you flagged — DocumentDB versus MongoDB, OpenSearch versus Elasticsearch, Neptune
  versus Neo4j, Cloud SQL MySQL versus MariaDB, MSK's missing schema registry, gRPC's HTTP/2 load-balancer
  requirement, Cloud Run's VPC connector requirement.
- The result of Step 6.
- The consolidated list of manual steps from Step 5.

Warn explicitly:

- **`tofu apply` creates billable resources.** Read the full plan first. Name the always-on cost items again here.
- **Nothing was applied and no secret value was written anywhere.** The service will not start until each secret
  is populated out-of-band — that is the design.
- **State must be remote and encrypted before the first real apply**, and the state file must be treated as
  sensitive.
- **The undeploy workflow (`iru-setup-java-springboot-github-workflows`) can destroy these resources.** Confirm
  deletion protection is enabled on anything holding data before that workflow exists in a state where someone
  can run it. If the manifest's `ci.enabled` is `false`, no deploy/undeploy workflow is generated: every
  `tofu apply`/`tofu destroy` is a manual run from a workstation, so the same deletion-protection advice applies
  to whoever runs it, and the OIDC identity created for CI is not needed until workflows are added — say so.
- Provider versions are pinned; upgrading one is a reviewed change, because a provider major version can produce a
  destructive plan for unchanged configuration.
