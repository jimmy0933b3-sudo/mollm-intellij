#!/usr/bin/env bash
set -Eeuo pipefail

if [[ $# -ne 1 || ! -f "$1" ]]; then
  echo 'Usage: sign_plugin.sh path/to/mollm-intellij.zip' >&2
  exit 2
fi
: "${PRIVATE_KEY:?Missing Actions secret PRIVATE_KEY}"
: "${CERTIFICATE_CHAIN:?Missing Actions secret CERTIFICATE_CHAIN}"
: "${PRIVATE_KEY_PASSWORD:?Missing Actions secret PRIVATE_KEY_PASSWORD}"

archive="$(realpath "$1")"
umask 077
workdir="$(mktemp -d)"
trap 'rm -rf "$workdir"' EXIT

write_pem() {
  local value="$1" target="$2"
  if [[ "$value" == *'-----BEGIN '* ]]; then
    printf '%s\n' "$value" > "$target"
  else
    if ! printf '%s' "$value" | base64 --decode > "$target"; then
      echo 'Invalid base64-encoded signing key or certificate chain' >&2
      exit 1
    fi
  fi
  if ! grep -q '^-----BEGIN ' "$target"; then
    echo 'Signing key or certificate chain is not PEM format' >&2
    exit 1
  fi
}

write_pem "$PRIVATE_KEY" "$workdir/private.pem"
write_pem "$CERTIFICATE_CHAIN" "$workdir/chain.crt"

# Immutable tool version and verified official release checksum.
signer_version='0.1.43'
signer_sha256='2958a0f42221d6062b50f22754e413ae5cc42f60c441467202c6700df2e22f44'
signer="$workdir/marketplace-zip-signer-cli.jar"
curl --fail --location --retry 3 --silent --show-error \
  "https://github.com/JetBrains/marketplace-zip-signer/releases/download/${signer_version}/marketplace-zip-signer-cli-${signer_version}.jar" \
  --output "$signer"
printf '%s  %s\n' "$signer_sha256" "$signer" | sha256sum --check --status || {
  echo 'Marketplace ZIP Signer SHA-256 mismatch' >&2
  exit 1
}

java -jar "$signer" sign \
  -in "$archive" \
  -out "$workdir/signed.zip" \
  -cert-file "$workdir/chain.crt" \
  -key-file "$workdir/private.pem" \
  -key-pass "$PRIVATE_KEY_PASSWORD"

test -s "$workdir/signed.zip"
unzip -tq "$workdir/signed.zip" >/dev/null
java -jar "$signer" verify -in "$workdir/signed.zip" -cert "$workdir/chain.crt"
mv "$workdir/signed.zip" "$archive"
echo 'JetBrains plugin ZIP signed with Actions Secrets.'
