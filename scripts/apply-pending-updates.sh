#!/usr/bin/env bash
set -euo pipefail

server_dir=${1:?server directory is required}
update_dir="$server_dir/update"
pending="$update_dir/pending.tsv"
plugins_dir="$server_dir/plugins"

[ -f "$pending" ] || exit 0
command -v sha512sum >/dev/null || { echo "sha512sum is required to apply plugin updates" >&2; exit 1; }

batch=$(date -u +%Y-%m-%dT%H-%M-%SZ)
backup_dir="$server_dir/update-backup/$batch"
mkdir -p "$backup_dir"
declare -a applied=()
target_pattern='^[A-Za-z0-9._+ -]+[.]jar$'

rollback() {
  for target in "${applied[@]}"; do
    [ -f "$backup_dir/$target" ] || continue
    rm -f -- "$plugins_dir/$target"
    mv -- "$backup_dir/$target" "$plugins_dir/$target"
  done
}
trap rollback ERR

while IFS=$'\t' read -r id target hash version; do
  [ -n "$id" ] || continue
  [[ "$id" =~ ^[A-Za-z0-9._-]+$ ]] || { echo "Unsafe update id: $id" >&2; exit 1; }
  [[ "$target" =~ $target_pattern ]] || { echo "Unsafe target file: $target" >&2; exit 1; }
  [[ "$hash" =~ ^[A-Fa-f0-9]{128}$ ]] || { echo "Invalid SHA-512 for $id" >&2; exit 1; }
  staged="$update_dir/$target"
  active="$plugins_dir/$target"
  [ -f "$staged" ] || { echo "Missing staged JAR: $staged" >&2; exit 1; }
  [ -f "$active" ] || { echo "Active JAR is missing: $active" >&2; exit 1; }
  actual=$(sha512sum "$staged" | awk '{print $1}')
  [ "$actual" = "$hash" ] || { echo "SHA-512 mismatch: $target" >&2; exit 1; }
  unzip -tqq "$staged" >/dev/null || { echo "Invalid JAR: $target" >&2; exit 1; }
done < "$pending"

while IFS=$'\t' read -r id target hash version; do
  [ -n "$id" ] || continue
  mv -- "$plugins_dir/$target" "$backup_dir/$target"
  applied+=("$target")
  mv -- "$update_dir/$target" "$plugins_dir/$target"
done < "$pending"

rm -f -- "$pending"
trap - ERR
echo "Applied ${#applied[@]} pending plugin update(s); backups: $backup_dir"
