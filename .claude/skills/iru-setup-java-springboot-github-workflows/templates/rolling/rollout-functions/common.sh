set -euo pipefail

# --- runtime independent -----------------------------------------------------------------------------------------
# Smallest manifest step strictly greater than $1 (empty when $1 is already the last step).
next_step() {
  for s in $STEPS; do
    if [ "$s" -gt "$1" ]; then echo "$s"; return; fi
  done
}

# Hold the gate for BAKE_MINUTES: fail on the first breach, succeed only if it is still healthy at the end.
gate_wait() {
  local end=$(( $(date +%s) + BAKE_MINUTES * 60 ))
  while [ "$(date +%s)" -lt "$end" ]; do
    rollout_gate_ok || { echo "::error::Metric gate breached"; return 1; }
    sleep 30
  done
  rollout_gate_ok
}

tfout() { tofu -chdir="$TF_DIR" output -json "$1"; }

# True when the OpenTofu state has output $1, false when it does not. When the state itself cannot be read (backend,
# lock or permission error) it fails the step instead, so such an error is never mistaken for "not deployed yet".
has_output() {
  local all
  all="$(tofu -chdir="$TF_DIR" output -json)" || { echo "::error::Cannot read the OpenTofu outputs in $TF_DIR"; exit 1; }
  jq -e --arg k "$1" 'has($k)' <<<"$all" >/dev/null
}
