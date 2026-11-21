# --- Kubernetes: Argo Rollouts --------------------------------------------------------------------------------------
rollout_init() {
  ROLLOUT="$(tfout rollout_name | jq -r .)"
  RJ="$(kubectl get rollout "$ROLLOUT" -n "$K8S_NAMESPACE" -o json)"
}

# In progress = paused (or progressing) at a step before the last one, or a blue/green preview waiting for promotion.
rollout_active() {
  RJ="$(kubectl get rollout "$ROLLOUT" -n "$K8S_NAMESPACE" -o json)"
  jq -e '(.status.phase // "") as $p
         | if .spec.strategy.blueGreen != null then $p == "Paused"
           else ((.spec.strategy.canary.steps // []) | length) as $n
                | ($p == "Paused" or $p == "Progressing") and ((.status.currentStepIndex // 0) < $n) end' <<<"$RJ" >/dev/null
}

rollout_current() {
  RJ="$(kubectl get rollout "$ROLLOUT" -n "$K8S_NAMESPACE" -o json)"
  jq -r 'if .spec.strategy.blueGreen != null then (if (.status.phase // "") == "Paused" then 0 else 100 end)
         else ((.spec.strategy.canary.steps // []) as $s | (.status.currentStepIndex // 0) as $i
               | if $i >= ($s | length) then 100 else ([$s[:$i][] | .setWeight | select(. != null)] | last // 0) end) end' <<<"$RJ"
}

# Move to <pct> by promoting step by step (each promote resumes the indefinite pause and stops at the next one).
rollout_set() {
  local p="$1" cur
  if [ "$p" -ge 100 ]; then
    kubectl argo rollouts promote "$ROLLOUT" -n "$K8S_NAMESPACE" --full
    return
  fi
  # Only promote while the Rollout is Paused: promoting a running step would skip it (and its analysis). Give up after
  # 15 minutes, or as soon as the Rollout degrades, rather than looping until the runner's time limit.
  local deadline=$(( $(date +%s) + 900 ))
  cur="$(rollout_current)"
  while [ "$cur" -lt "$p" ]; do
    rollout_gate_ok || { echo "::error::Rollout degraded or aborted at ${cur} %"; return 1; }
    if [ "$(date +%s)" -ge "$deadline" ]; then
      echo "::error::Rollout did not reach ${p} % within 15 minutes (still at ${cur} %)"
      return 1
    fi
    if [ "$(jq -r '.status.phase // ""' <<<"$RJ")" = Paused ]; then
      kubectl argo rollouts promote "$ROLLOUT" -n "$K8S_NAMESPACE"
    fi
    sleep 5
    cur="$(rollout_current)"
  done
}

rollout_gate_ok() {
  RJ="$(kubectl get rollout "$ROLLOUT" -n "$K8S_NAMESPACE" -o json)"
  jq -e '(.status.phase // "") != "Degraded" and ((.status.abort // false) | not)' <<<"$RJ" >/dev/null
}

rollout_finalise() { kubectl argo rollouts status "$ROLLOUT" -n "$K8S_NAMESPACE" --timeout 15m; }

# `abort` returns traffic to the stable ReplicaSet and scales the new one down; the Rollout reads Degraded until a fixed
# image is deployed (or `kubectl argo rollouts retry`). The next `tofu apply` re-applies the image in the state, so
# fix forward or deploy the previous tag.
rollout_rollback() { kubectl argo rollouts abort "$ROLLOUT" -n "$K8S_NAMESPACE"; }
rollout_abort() { rollout_rollback; }
