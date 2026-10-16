#!/usr/bin/env bash
# Writes and signs the update manifest of a Release (ADR-0025): titan-update.json
# lists the version and, for each package the app can install, its kind, name,
# size and SHA-256; titan-update.json.sig is its raw Ed25519 signature.
#
# usage: update-manifest.sh DIST_DIR VERSION KEYS_FILE
#   DIST_DIR   the Release assets; the two files are written there
#   KEYS_FILE  shared/update-keys.txt, the public keys the app trusts
# The private key (PEM, as `openssl genpkey -algorithm ed25519` writes it)
# comes from the TITAN_UPDATE_SIGNING_KEY environment variable and is never
# written anywhere but a temporary file that is removed on exit.
#
# Needs OpenSSL 3 (pkeyutl -rawin) and jq.
set -euo pipefail

dist=$1 version=$2 keys=$3

if [[ -z "${TITAN_UPDATE_SIGNING_KEY:-}" ]]; then
    echo "::error::Secret TITAN_UPDATE_SIGNING_KEY is not set; the apps could not verify this release" >&2
    exit 1
fi

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
key="$tmp/key.pem"
(umask 077 && printf '%s\n' "$TITAN_UPDATE_SIGNING_KEY" >"$key")

# The signing key must be one the apps already trust, or no install could
# verify this release.
openssl pkey -in "$key" -pubout -out "$tmp/pub.pem"
public=$(openssl pkey -pubin -in "$tmp/pub.pem" -outform DER | tail -c 32 | base64 | tr -d '\n')
if ! sed 's/#.*//' "$keys" | tr -d ' \t\r' | grep -qxF "$public"; then
    echo "::error::The signing key ($public) is not listed in $keys" >&2
    exit 1
fi

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
    if [[ -n "${seen[$kind]:-}" ]]; then
        echo "::error::Two $kind packages: ${seen[$kind]} and $file" >&2
        exit 1
    fi
    seen[$kind]=$file
    sha=$(sha256sum -- "$file" | cut -d' ' -f1)
    size=$(stat -c %s -- "$file")
    artifacts+=("$(jq -cn --arg kind "$kind" --arg name "$file" --argjson size "$size" --arg sha "$sha" \
        '{kind: $kind, name: $name, size: $size, sha256: $sha}')")
done
for kind in msi apk deb rpm tar.gz; do
    if [[ -z "${seen[$kind]:-}" ]]; then
        echo "::error::No $kind package in $dist" >&2
        exit 1
    fi
done

printf '%s\n' "${artifacts[@]}" | jq -s --arg version "$version" '{schema: 1, version: $version, artifacts: .}' \
    >titan-update.json
openssl pkeyutl -sign -rawin -inkey "$key" -in titan-update.json -out titan-update.json.sig
openssl pkeyutl -verify -rawin -pubin -inkey "$tmp/pub.pem" -in titan-update.json -sigfile titan-update.json.sig
cat titan-update.json
