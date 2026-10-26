#!/usr/bin/env bash
set -Eeuo pipefail

if [[ $# -ne 1 || ! -f "$1" ]]; then
  echo 'Usage: sign_plugin.sh path/to/mollm-intellij.zip' >&2
  exit 2
fi

archive="$(realpath "$1")"
umask 077
workdir="$(mktemp -d)"
trap 'rm -rf "$workdir"' EXIT

write_pem() {
  local value="$1" target="$2"
  if [[ "$value" == *'-----BEGIN '* ]]; then
    printf '%s\n' "$value" > "$target"
  else
    printf '%s' "$value" | tr -d '\r\n\t ' | base64 --decode > "$target" || {
      echo 'Invalid base64 signing PEM' >&2
      exit 1
    }
  fi
  grep -q '^-----BEGIN ' "$target" || {
    echo 'Signing PEM missing BEGIN header' >&2
    exit 1
  }
}

# Support the four fixed-release signing secrets used by the owner's Android CI.
# JetBrains ZIP signing uses the same private key and X.509 certificate from the keystore.
# Convert to a temporary PKCS12 so a different key password is also supported.
if [[ -n "${KEYSTORE_BASE64:-}" ]]; then
  for secret in KEYSTORE_BASE64 KEYSTORE_PASSWORD KEY_ALIAS KEY_PASSWORD; do
    if [[ -z "${!secret:-}" ]]; then
      echo "::error::Missing Actions Secret $secret (required for KeyStore signing)." >&2
      exit 1
    fi
  done

  printf '%s' "$KEYSTORE_BASE64" | tr -d '\r\n\t ' | base64 --decode > "$workdir/release.keystore" || {
    echo 'Invalid Actions KeyStore Base64 data' >&2
    exit 1
  }
  test -s "$workdir/release.keystore"
  keytool -list -keystore "$workdir/release.keystore" \
    -storepass:env KEYSTORE_PASSWORD -alias "$KEY_ALIAS" >/dev/null || {
      echo 'Cannot read Actions keystore with KEYSTORE_PASSWORD / KEY_ALIAS' >&2
      exit 1
    }

  export EXPORT_PASSWORD
  EXPORT_PASSWORD="$(openssl rand -hex 32)"
  echo "::add-mask::$EXPORT_PASSWORD"
  keytool -importkeystore \
    -srckeystore "$workdir/release.keystore" \
    -srcstorepass:env KEYSTORE_PASSWORD \
    -srcalias "$KEY_ALIAS" \
    -srckeypass:env KEY_PASSWORD \
    -destkeystore "$workdir/signing.p12" \
    -deststoretype PKCS12 \
    -deststorepass:env EXPORT_PASSWORD \
    -destkeypass:env EXPORT_PASSWORD \
    -destalias "$KEY_ALIAS" -noprompt >/dev/null 2>&1 || {
      echo 'Unable to convert Actions keystore. Check KEY_PASSWORD and KEY_ALIAS.' >&2
      exit 1
    }
  keytool -exportcert -rfc \
    -keystore "$workdir/signing.p12" \
    -storepass:env EXPORT_PASSWORD \
    -alias "$KEY_ALIAS" \
    -file "$workdir/verify.crt" >/dev/null 2>&1
  sign_args=(-ks "$workdir/signing.p12" -ks-pass "$EXPORT_PASSWORD" -ks-key-alias "$KEY_ALIAS" -ks-type PKCS12)
  echo 'Using fixed KeyStore signing credentials from GitHub Actions.'
elif [[ -n "${PRIVATE_KEY:-}" || -n "${CERTIFICATE_CHAIN:-}" ]]; then
  : "${PRIVATE_KEY:?Missing Actions Secret PRIVATE_KEY}"
  : "${CERTIFICATE_CHAIN:?Missing Actions Secret CERTIFICATE_CHAIN}"
  write_pem "$PRIVATE_KEY" "$workdir/private.pem"
  write_pem "$CERTIFICATE_CHAIN" "$workdir/verify.crt"
  sign_args=(-cert-file "$workdir/verify.crt" -key-file "$workdir/private.pem")
  if [[ -n "${PRIVATE_KEY_PASSWORD:-}" ]]; then
    sign_args+=(-key-pass "$PRIVATE_KEY_PASSWORD")
  fi
  echo 'Using PEM signing credentials from GitHub Actions.'
else
  echo '::error::No plugin signing key found. Set KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD as Repository Actions Secrets, or PRIVATE_KEY + CERTIFICATE_CHAIN.' >&2
  exit 1
fi

# Pinned official JetBrains Marketplace ZIP Signer and verified release checksum.
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

java -jar "$signer" sign -in "$archive" -out "$workdir/signed.zip" "${sign_args[@]}"
test -s "$workdir/signed.zip"
unzip -tq "$workdir/signed.zip" >/dev/null
java -jar "$signer" verify -in "$workdir/signed.zip" -cert "$workdir/verify.crt"
mv "$workdir/signed.zip" "$archive"
echo 'Signed and verified IntelliJ plugin ZIP using GitHub Actions Secrets.'
