# --- ECS Fargate: weighted target groups on the production ALB listener ------------------------------------------
slot_arn() { if [ "$1" = blue ]; then echo "$BLUE_ARN"; else echo "$GREEN_ARN"; fi; }
other_slot() { if [ "$1" = blue ]; then echo green; else echo blue; fi; }

weight_of() {
  aws elbv2 describe-listeners --listener-arns "$LISTENER" --output json |
    jq -r --arg tg "$1" '[.Listeners[0].DefaultActions[0].ForwardConfig.TargetGroups[] | select(.TargetGroupArn == $tg) | .Weight][0] // 0'
}

rollout_init() {
  TGS="$(tfout rollout_target_groups)"
  ALARMS="$(tfout rollout_alarm_names)"
  BLUE_ARN="$(jq -r .blue.arn <<<"$TGS")"
  GREEN_ARN="$(jq -r .green.arn <<<"$TGS")"
  local lb
  lb="$(aws elbv2 describe-target-groups --target-group-arns "$BLUE_ARN" --query 'TargetGroups[0].LoadBalancerArns[0]' --output text)"
  # The production listener is the one forwarding to BOTH target groups (the test listener forwards to green only).
  LISTENER="$(aws elbv2 describe-listeners --load-balancer-arn "$lb" --output json |
    jq -r --arg b "$BLUE_ARN" --arg g "$GREEN_ARN" '
      [.Listeners[]
       | select(([.DefaultActions[0].ForwardConfig.TargetGroups[]?.TargetGroupArn] | index($b)) != null
            and ([.DefaultActions[0].ForwardConfig.TargetGroups[]?.TargetGroupArn] | index($g)) != null)
       | .ListenerArn][0] // empty')"
  [ -n "$LISTENER" ] || { echo "::error::Production listener not found"; return 1; }
  CAND_SLOT="$(aws elbv2 describe-tags --resource-arns "$LISTENER" \
    --query "TagDescriptions[0].Tags[?Key=='rollout-candidate'].Value | [0]" --output text)"
  if [ "$CAND_SLOT" = None ]; then CAND_SLOT=""; fi
  if [ -n "$CAND_SLOT" ]; then
    STABLE_SLOT="$(other_slot "$CAND_SLOT")"
  elif [ "$(weight_of "$BLUE_ARN")" -ge 50 ]; then
    STABLE_SLOT=blue
  else
    STABLE_SLOT=green
  fi
  IDLE_SLOT="$(other_slot "$STABLE_SLOT")"
}

rollout_active() { [ -n "$CAND_SLOT" ]; }
rollout_current() { if rollout_active; then weight_of "$(slot_arn "$CAND_SLOT")"; else echo 0; fi; }

# Called by deploy.yml before it touches anything, so a second deploy cannot start while this one is unfinished.
rollout_mark_candidate() {
  CAND_SLOT="$IDLE_SLOT"
  aws elbv2 add-tags --resource-arns "$LISTENER" --tags "Key=rollout-candidate,Value=$CAND_SLOT"
}

# Image tag a slot's ECS service is running now (the stable slot keeps its tag across the candidate's apply).
slot_image_tag() {
  local td
  td="$(aws ecs describe-services --cluster "$ECS_CLUSTER" --services "$ECS_SERVICE-$1" --query 'services[0].taskDefinition' --output text)"
  aws ecs describe-task-definition --task-definition "$td" --query 'taskDefinition.containerDefinitions[0].image' --output text | sed 's/.*://'
}

rollout_set() {
  local p="$1"
  aws elbv2 modify-listener --listener-arn "$LISTENER" --default-actions "$(jq -nc \
    --arg c "$(slot_arn "$CAND_SLOT")" --arg s "$(slot_arn "$STABLE_SLOT")" --argjson p "$p" \
    '[{Type: "forward", ForwardConfig: {TargetGroups: [{TargetGroupArn: $s, Weight: (100 - $p)}, {TargetGroupArn: $c, Weight: $p}]}}]')" >/dev/null
}

# Healthy = none of the alarms exported as rollout_alarm_names is in ALARM. INSUFFICIENT_DATA is reported but not a
# breach (a candidate at a few percent may have no datapoints yet); the alarms should use treat_missing_data = notBreaching.
rollout_gate_ok() {
  local names state
  names="$(jq -r '.[]' <<<"$ALARMS")"
  # shellcheck disable=SC2086
  state="$(aws cloudwatch describe-alarms --alarm-names $names --output json)"
  if jq -e '[.MetricAlarms[] | select(.StateValue == "INSUFFICIENT_DATA")] | length > 0' <<<"$state" >/dev/null; then
    echo "::warning::Some rollout alarms report INSUFFICIENT_DATA"
  fi
  ! jq -e '[.MetricAlarms[] | select(.StateValue == "ALARM")] | length > 0' <<<"$state" >/dev/null
}

scale_slot_down() { # scale slot $1 to zero and clear the marker
  aws ecs update-service --cluster "$ECS_CLUSTER" --service "$ECS_SERVICE-$1" --desired-count 0 >/dev/null
  aws elbv2 remove-tags --resource-arns "$LISTENER" --tag-keys rollout-candidate
}

rollout_finalise() { # candidate is now 100 %: retire the old stable slot
  rollout_set 100
  scale_slot_down "$STABLE_SLOT"
}

rollout_rollback() {
  rollout_set 0
  scale_slot_down "$CAND_SLOT"
}

rollout_abort() {
  rollout_set 0
  aws elbv2 remove-tags --resource-arns "$LISTENER" --tag-keys rollout-candidate
}
