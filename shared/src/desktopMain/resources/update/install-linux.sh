#!/bin/sh
# titan-ssh updater helper (ADR-0025). Started by the app right before it
# exits: waits for it to end, installs the verified package, writes the exit
# status to the result file and, if asked, opens the new version.
#
# usage: install-linux.sh KIND APP_PID PACKAGE SHA256 APP_ROOT LAUNCHER RESULT RELAUNCH LOG
#   KIND      tar.gz | deb | rpm
#   SHA256    the package's digest from the signed manifest, checked again here
#   APP_ROOT  the app image folder (tar.gz: replaced as a whole)
#   RELAUNCH  1 to open LAUNCHER once installed
#   LOG       helper.log next to update.log, which also gets this script's
#             output; the relaunched app's errors go to relaunch.log beside it
#
# Exit statuses written to RESULT besides the installer's own: 3 = the package
# no longer matches SHA256, 126 = the password was not given (pkexec).
kind=$1 pid=$2 pkg=$3 sha=$4 root=$5 launcher=$6 result=$7 relaunch=$8 log=$9

# jpackage's launcher marks the app's process with _JPACKAGE_LAUNCHER; a
# launcher that inherits it takes itself for a JVM restart and exits at once.
# The app already drops it; this covers a helper started some other way.
unset _JPACKAGE_LAUNCHER

note() { echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) $*"; }
note "installing $kind $pkg (relaunch=$relaunch)"

i=0
while kill -0 "$pid" 2>/dev/null && [ "$i" -lt 240 ]; do
    sleep 0.5
    i=$((i + 1))
done

matches() { [ "$(sha256sum "$1" | cut -d' ' -f1)" = "$2" ]; }

# Runs as root under pkexec: copies the package into a folder only root can
# write, checks that copy and installs that same copy, so nothing can swap
# the file between the check and the install.
root_install='
set -u
kind=$1 pkg=$2 sha=$3
dir=$(mktemp -d) || exit 1
trap "rm -rf \"$dir\"" EXIT
copy="$dir/$(basename "$pkg")"
cp -- "$pkg" "$copy" || exit 1
[ "$(sha256sum "$copy" | cut -d" " -f1)" = "$sha" ] || exit 3
case "$kind" in
    deb) DEBIAN_FRONTEND=noninteractive apt-get install -y "$copy" ;;
    rpm)
        if command -v dnf >/dev/null 2>&1; then dnf install -y "$copy"
        elif command -v zypper >/dev/null 2>&1; then zypper --non-interactive install --allow-unsigned-rpm "$copy"
        else rpm -U "$copy"
        fi ;;
esac
'

status=0
case "$kind" in
    tar.gz)
        # Unpack next to the current image, then swap the folders, keeping the
        # old one until the new one is in place. The tar.gz holds one folder
        # named like the launcher: titan-ssh, or titan-ssh-canary (ADR-0027).
        app=$(basename "$launcher")
        parent=$(dirname "$root")
        stage=$(mktemp -d "$parent/.$app-update.XXXXXX") || status=1
        if [ "$status" -eq 0 ]; then
            # The same user owns both files: checked once more, then unpacked.
            if matches "$pkg" "$sha"; then
                tar -xzf "$pkg" -C "$stage" >>"$log" 2>&1 || status=$?
            else
                status=3
            fi
        fi
        if [ "$status" -eq 0 ] && [ -x "$stage/$app/bin/$app" ]; then
            old="$root.old.$$"
            if mv "$root" "$old"; then
                if mv "$stage/$app" "$root"; then
                    rm -rf "$old"
                else
                    mv "$old" "$root"
                    status=1
                fi
            else
                status=1
            fi
        elif [ "$status" -eq 0 ]; then
            status=1
        fi
        [ -n "$stage" ] && rm -rf "$stage"
        ;;
    deb | rpm)
        # The system asks for the password: installing a package needs root.
        pkexec /bin/sh -c "$root_install" titan-ssh-update "$kind" "$pkg" "$sha" >>"$log" 2>&1 || status=$?
        ;;
    *)
        status=2
        ;;
esac
echo "$status" >"$result"
note "finished with status $status"

if [ "$relaunch" = 1 ] && [ "$status" -eq 0 ]; then
    relaunch_log="$(dirname "$log")/relaunch.log"
    note "relaunching $launcher"
    if command -v setsid >/dev/null 2>&1; then
        setsid "$launcher" >/dev/null 2>"$relaunch_log" </dev/null &
    else
        nohup "$launcher" >/dev/null 2>"$relaunch_log" </dev/null &
    fi
fi
