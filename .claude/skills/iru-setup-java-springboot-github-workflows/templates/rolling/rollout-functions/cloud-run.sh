# --- Cloud Run: revision tags + traffic percentages ----------------------------------------------------------------
run_describe() { gcloud run services describe "$CLOUD_RUN_SERVICE" --region "$GCP_REGION" --format=json; }

rollout_init() {
  local j
  j="$(run_describe)"
  CAND_REV="$(jq -r '[.status.traffic[]? | select(.tag == "candidate")][0].revisionName // empty' <<<"$j")"
  STABLE_REV="$(jq -r '[.status.traffic[]? | select(.tag == "stable")][0].revisionName // empty' <<<"$j")"
  if [ -z "$STABLE_REV" ]; then # first rollout after a plain deploy: the revision with 100 % is the stable one
    STABLE_REV="$(jq -r '[.status.traffic[]? | select(.percent == 100)][0].revisionName // empty' <<<"$j")"
  fi
}

rollout_active() { [ -n "$CAND_REV" ]; }
rollout_current() { run_describe | jq -r '[.status.traffic[]? | select(.tag == "candidate")][0].percent // 0'; }
rollout_candidate_url() { run_describe | jq -r '[.status.traffic[]? | select(.tag == "candidate")][0].url // empty'; }

# deploy.yml, after `tofu apply` created the new revision at 0 %: tag stable + candidate.
rollout_mark_candidate() {
  CAND_REV="$(gcloud run services describe "$CLOUD_RUN_SERVICE" --region "$GCP_REGION" --format='value(status.latestCreatedRevisionName)')"
  gcloud run services update-traffic "$CLOUD_RUN_SERVICE" --region "$GCP_REGION" \
    --update-tags "stable=$STABLE_REV,candidate=$CAND_REV" >/dev/null
}

rollout_set() {
  local p="$1"
  if [ "$p" -ge 100 ]; then
    gcloud run services update-traffic "$CLOUD_RUN_SERVICE" --region "$GCP_REGION" --to-tags "candidate=100" >/dev/null
  else
    gcloud run services update-traffic "$CLOUD_RUN_SERVICE" --region "$GCP_REGION" --to-tags "candidate=$p,stable=$((100 - p))" >/dev/null
  fi
}

# Healthy = candidate 5xx ratio over the last 5 minutes <= ERROR_RATE_MAX (PromQL on Cloud Monitoring); no traffic = healthy.
rollout_gate_ok() {
  local q ratio
  q="sum(rate(run_googleapis_com:request_count{monitored_resource=\"cloud_run_revision\",service_name=\"$CLOUD_RUN_SERVICE\",revision_name=\"$CAND_REV\",response_code_class=\"5xx\"}[5m])) / sum(rate(run_googleapis_com:request_count{monitored_resource=\"cloud_run_revision\",service_name=\"$CLOUD_RUN_SERVICE\",revision_name=\"$CAND_REV\"}[5m]))"
  ratio="$(curl -fsS -H "Authorization: Bearer $(gcloud auth print-access-token)" \
    --data-urlencode "query=$q" \
    "https://monitoring.googleapis.com/v1/projects/$GCP_PROJECT_ID/location/global/prometheus/api/v1/query" |
    jq -r '.data.result[0].value[1] // "0"')"
  awk -v r="$ratio" -v m="${ERROR_RATE_MAX:-0.02}" 'BEGIN { exit !(r == "NaN" || r + 0 <= m + 0) }'
}

rollout_finalise() {
  gcloud run services update-traffic "$CLOUD_RUN_SERVICE" --region "$GCP_REGION" --to-revisions "$CAND_REV=100" >/dev/null
  gcloud run services update-traffic "$CLOUD_RUN_SERVICE" --region "$GCP_REGION" --remove-tags candidate,stable >/dev/null
  gcloud run services update-traffic "$CLOUD_RUN_SERVICE" --region "$GCP_REGION" --update-tags "stable=$CAND_REV" >/dev/null
}

rollout_rollback() {
  gcloud run services update-traffic "$CLOUD_RUN_SERVICE" --region "$GCP_REGION" --to-revisions "$STABLE_REV=100" >/dev/null
  gcloud run services update-traffic "$CLOUD_RUN_SERVICE" --region "$GCP_REGION" --remove-tags candidate >/dev/null
}

rollout_abort() { rollout_rollback; }
