#!/usr/bin/env bash
# Writes and signs the update manifest of a Release (ADR-0025): titan-update.json
# lists the version and, for each package the app can install, its kind, name,
# size and SHA-256; titan-update.json.sig is its raw Ed25519 signature.
#
# usage: update-manifest.sh DIST_DIR VERSION KEYS_FILE CHANNEL
#   DIST_DIR   the Release assets; the two files are written there
#   KEYS_FILE  shared/update-keys.txt, the public keys the app trusts
#   CHANNEL    stable | canary (ADR-0027). It is signed with the rest, so a
#              canary manifest never passes for a stable one; the canary
#              ships only the APK, the MSI and the tar.gz.
# The private key (PEM, as `openssl genpkey -algorithm ed25519` writes it)
# comes from the TITAN_UPDATE_SIGNING_KEY environment variable and is never
# written anywhere but a temporary file that is removed on exit.
#
# Needs OpenSSL 3 (pkeyutl -rawin) and jq.
set -euo pipefail

dist=$1 version=$2 keys=$3 channel=$4

# Errors go to stdout: GitHub turns ::error:: into an annotation only there.
fail() {
    echo "::error::$1"
    exit 1
}

case "$channel" in
    stable) required=(msi apk deb rpm tar.gz) ;;
    canary) required=(msi apk tar.gz) ;;
    *) fail "Unknown channel '$channel': expected stable or canary" ;;
esac

if [[ -z "${TITAN_UPDATE_SIGNING_KEY:-}" ]]; then
    fail "Secret TITAN_UPDATE_SIGNING_KEY is not set; the apps could not verify this release"
fi

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
key="$tmp/key.pem"
# The secret is pasted by hand: rebuild the PEM from its base64 body, so a
# copy that lost its line breaks or gained quotes or spaces still reads.
body=$(printf '%s' "$TITAN_UPDATE_SIGNING_KEY" | tr -d '\r"'"'" |
    sed -e 's/-----BEGIN PRIVATE KEY-----//' -e 's/-----END PRIVATE KEY-----//' | tr -d ' \t\n')
(umask 077 && printf -- '-----BEGIN PRIVATE KEY-----\n%s\n-----END PRIVATE KEY-----\n' "$body" >"$key")
openssl pkey -in "$key" -noout 2>/dev/null ||
    fail "TITAN_UPDATE_SIGNING_KEY is not an Ed25519 private key in PEM (openssl genpkey -algorithm ed25519)"

# The signing key must be one the apps already trust, or no install could
# verify this release.
openssl pkey -in "$key" -pubout -out "$tmp/pub.pem"
public=$(openssl pkey -pubin -in "$tmp/pub.pem" -outform DER | tail -c 32 | base64 | tr -d '\n')
listed=$(sed 's/#.*//' "$keys" | tr -d ' \t\r')
grep -qxF "$public" <<<"$listed" || fail "The signing key ($public) is not listed in $keys"

cd "$dist"
artifacts=()
declare -A seen=()
for file in *; do
    case "$file" in
        *-windows-x64.msi) kind=msi ;;
        *.apk) kind=apk ;;
        *.deb) kind=deb ;;
        *.rpm) kind=rpm ;;
        *-linux-x64.tar.gz) kind=tar.gz ;;
        *) continue ;;
    esac
    [[ -z "${seen[$kind]:-}" ]] || fail "Two $kind packages: ${seen[$kind]} and $file"
    seen[$kind]=$file
    sha=$(sha256sum -- "$file" | cut -d' ' -f1)
    size=$(stat -c %s -- "$file")
    artifacts+=("$(jq -cn --arg kind "$kind" --arg name "$file" --argjson size "$size" --arg sha "$sha" \
        '{kind: $kind, name: $name, size: $size, sha256: $sha}')")
done
for kind in "${required[@]}"; do
    [[ -n "${seen[$kind]:-}" ]] || fail "No $kind package in $dist"
done

printf '%s\n' "${artifacts[@]}" | jq -s --arg version "$version" --arg channel "$channel" \
    '{schema: 1, version: $version, channel: $channel, artifacts: .}' \
    >titan-update.json
openssl pkeyutl -sign -rawin -inkey "$key" -in titan-update.json -out titan-update.json.sig
openssl pkeyutl -verify -rawin -pubin -inkey "$tmp/pub.pem" -in titan-update.json -sigfile titan-update.json.sig
cat titan-update.json
