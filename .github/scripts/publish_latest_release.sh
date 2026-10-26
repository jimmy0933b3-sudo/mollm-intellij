#!/usr/bin/env bash
set -Eeuo pipefail

: "${GH_TOKEN:?Missing GitHub token}"
: "${GITHUB_REPOSITORY:?Missing GitHub repository}"
: "${GITHUB_SHA:?Missing commit SHA}"
: "${GITHUB_RUN_NUMBER:?Missing run number}"
: "${GITHUB_RUN_ATTEMPT:?Missing run attempt}"
: "${PLUGIN_VERSION:?Missing plugin version}"

dist="${1:-dist}"
archive="mollm-intellij-${PLUGIN_VERSION}.zip"
test -s "$dist/$archive"
test -s "$dist/$archive.sha256"
(cd "$dist" && sha256sum --check "$archive.sha256")
unzip -tq "$dist/$archive" >/dev/null

tag="ci-${GITHUB_RUN_NUMBER}-${GITHUB_RUN_ATTEMPT}-${GITHUB_SHA:0:7}"
name="MoLLM IntelliJ v${PLUGIN_VERSION} (CI #${GITHUB_RUN_NUMBER})"
notes="Signed build from master commit ${GITHUB_SHA}. Install the ZIP using Settings > Plugins > Install Plugin from Disk."

gh release create "$tag" "$dist/$archive" "$dist/$archive.sha256" \
  --repo "$GITHUB_REPOSITORY" \
  --target "$GITHUB_SHA" \
  --title "$name" \
  --notes "$notes" \
  --latest

echo "Created and uploaded release $tag; now pruning older Releases."
# Materialize the entire release list before deletions to avoid pagination shifts.
releases="$(gh api --paginate --jq '.[] | [.id, .tag_name] | @tsv' \
  "repos/$GITHUB_REPOSITORY/releases?per_page=100")"
while IFS=$'\t' read -r release_id old_tag; do
  [[ -n "$release_id" && "$old_tag" != "$tag" ]] || continue
  [[ "$release_id" =~ ^[0-9]+$ ]] || {
    echo 'Unexpected release ID; aborting cleanup' >&2
    exit 1
  }
  gh api -X DELETE "repos/$GITHUB_REPOSITORY/releases/$release_id" >/dev/null
  echo "Removed old Release $old_tag"
  # Never delete manually managed version tags.
  if [[ "$old_tag" =~ ^ci-[0-9]+-[0-9]+-[0-9a-f]{7}$ ]]; then
    gh api -X DELETE "repos/$GITHUB_REPOSITORY/git/refs/tags/$old_tag" >/dev/null
    echo "Removed old CI tag $old_tag"
  fi
done <<< "$releases"

gh release view "$tag" --repo "$GITHUB_REPOSITORY" --json url --jq '.url'
