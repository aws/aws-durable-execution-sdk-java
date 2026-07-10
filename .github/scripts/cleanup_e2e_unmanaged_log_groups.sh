#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: cleanup_e2e_unmanaged_log_groups.sh [--execute] [--region REGION] [--stack-name NAME ...]

Deletes Lambda CloudWatch log groups for e2e test stacks only when those log
groups are not already managed as AWS::Logs::LogGroup resources by the stack.

This is intended for the one-time migration from Lambda auto-created log groups
to CloudFormation-managed log groups with explicit retention. The default mode is
a dry run. Pass --execute to delete matching unmanaged log groups.
USAGE
}

execute=false
region="${AWS_REGION:-}"
stacks=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --execute)
      execute=true
      shift
      ;;
    --region)
      region="${2:?--region requires a value}"
      shift 2
      ;;
    --stack-name)
      stacks+=("${2:?--stack-name requires a value}")
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ ${#stacks[@]} -eq 0 ]]; then
  stacks=(
    Java17-JavaSDKCloudBasedIntegrationTestStack
    Java21-JavaSDKCloudBasedIntegrationTestStack
    Java25-JavaSDKCloudBasedIntegrationTestStack
  )
fi

aws_args=()
if [[ -n "$region" ]]; then
  aws_args+=(--region "$region")
fi

log_group_exists() {
  local target="$1"
  local found

  found=$(aws "${aws_args[@]}" logs describe-log-groups \
    --log-group-name-prefix "$target" \
    --query 'logGroups[].logGroupName' \
    --output text)

  for log_group_name in $found; do
    if [[ "$log_group_name" == "$target" ]]; then
      return 0
    fi
  done

  return 1
}

is_managed_log_group() {
  local target="$1"
  shift

  for managed_log_group in "$@"; do
    if [[ "$managed_log_group" == "$target" ]]; then
      return 0
    fi
  done

  return 1
}

for stack in "${stacks[@]}"; do
  if ! aws "${aws_args[@]}" cloudformation describe-stacks --stack-name "$stack" >/dev/null 2>&1; then
    echo "Skipping missing stack: $stack"
    continue
  fi

  function_names_text=$(aws "${aws_args[@]}" cloudformation list-stack-resources \
    --stack-name "$stack" \
    --query "StackResourceSummaries[?ResourceType=='AWS::Lambda::Function'].PhysicalResourceId" \
    --output text)
  managed_log_groups_text=$(aws "${aws_args[@]}" cloudformation list-stack-resources \
    --stack-name "$stack" \
    --query "StackResourceSummaries[?ResourceType=='AWS::Logs::LogGroup'].PhysicalResourceId" \
    --output text)

  function_names=($function_names_text)
  managed_log_groups=($managed_log_groups_text)

  for function_name in "${function_names[@]}"; do
    log_group="/aws/lambda/${function_name}"

    if is_managed_log_group "$log_group" "${managed_log_groups[@]}"; then
      echo "Keeping managed log group: $log_group"
      continue
    fi

    if ! log_group_exists "$log_group"; then
      echo "No unmanaged log group found: $log_group"
      continue
    fi

    if [[ "$execute" == true ]]; then
      echo "Deleting unmanaged log group: $log_group"
      aws "${aws_args[@]}" logs delete-log-group --log-group-name "$log_group"
    else
      echo "Would delete unmanaged log group: $log_group"
    fi
  done
done

if [[ "$execute" != true ]]; then
  echo "Dry run complete. Re-run with --execute to delete the unmanaged log groups listed above."
fi
