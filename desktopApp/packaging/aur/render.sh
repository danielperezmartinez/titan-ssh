#!/usr/bin/env bash
# Renders the AUR PKGBUILD of titan-ssh-bin for a published release.
#
#   render.sh <version> <sha256 of the Linux tar.gz> <output dir>
#
# <version> is the release version without the `v` (0.1.0 or 0.1.0-beta.2).
# The .SRCINFO is generated afterwards with `makepkg --printsrcinfo`, which
# needs Arch and a non-root user (see .github/workflows/release.yml).
set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo "usage: $0 <version> <sha256> <output dir>" >&2
  exit 2
fi
version="$1" sha256="$2" out="$3"

if ! [[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-(alpha|beta|rc)\.[0-9]+)?$ ]]; then
  echo "Invalid version '$version': expected X.Y.Z or X.Y.Z-(alpha|beta|rc).N" >&2
  exit 1
fi
if ! [[ "$sha256" =~ ^[0-9a-f]{64}$ ]]; then
  echo "Invalid SHA-256 '$sha256'" >&2
  exit 1
fi

# Pre-releases go to their own package, so stable users never get a beta.
if [[ "$version" == *-* ]]; then
  pkgname=titan-ssh-beta-bin
  pkgdesc='Resilient multi-platform SSH client (pre-release builds)'
else
  pkgname=titan-ssh-bin
  pkgdesc='Resilient multi-platform SSH client'
fi

mkdir -p "$out"
sed -e "s/@PKGNAME@/$pkgname/g" \
    -e "s/@PKGDESC@/$pkgdesc/g" \
    -e "s/@VERSION@/$version/g" \
    -e "s/@PKGVER@/${version//-/}/g" \
    -e "s/@SHA256@/$sha256/g" \
    "$(dirname "$0")/PKGBUILD.in" > "$out/PKGBUILD"
