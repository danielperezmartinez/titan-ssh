#!/bin/sh
# titan-ssh updater helper (ADR-0025). Started by the app right before it
# exits: waits for it to end, installs the verified package, writes the exit
# status to the result file and, if asked, opens the new version.
#
# usage: install-linux.sh KIND APP_PID PACKAGE APP_ROOT LAUNCHER RESULT RELAUNCH LOG
#   KIND      tar.gz | deb | rpm
#   APP_ROOT  the app image folder (tar.gz: replaced as a whole)
#   RELAUNCH  1 to open LAUNCHER once installed
kind=$1 pid=$2 pkg=$3 root=$4 launcher=$5 result=$6 relaunch=$7 log=$8

i=0
while kill -0 "$pid" 2>/dev/null && [ "$i" -lt 240 ]; do
    sleep 0.5
    i=$((i + 1))
done

status=0
case "$kind" in
    tar.gz)
        # Unpack next to the current image, then swap the folders, keeping the
        # old one until the new one is in place.
        parent=$(dirname "$root")
        stage=$(mktemp -d "$parent/.titan-ssh-update.XXXXXX") || status=1
        if [ "$status" -eq 0 ]; then
            tar -xzf "$pkg" -C "$stage" >>"$log" 2>&1 || status=$?
        fi
        if [ "$status" -eq 0 ] && [ -x "$stage/titan-ssh/bin/titan-ssh" ]; then
            old="$root.old.$$"
            if mv "$root" "$old"; then
                if mv "$stage/titan-ssh" "$root"; then
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
    deb)
        # The system asks for the password: installing a .deb needs root.
        pkexec env DEBIAN_FRONTEND=noninteractive apt-get install -y "$pkg" >>"$log" 2>&1 || status=$?
        ;;
    rpm)
        if command -v dnf >/dev/null 2>&1; then
            pkexec dnf install -y "$pkg" >>"$log" 2>&1 || status=$?
        elif command -v zypper >/dev/null 2>&1; then
            pkexec zypper --non-interactive install --allow-unsigned-rpm "$pkg" >>"$log" 2>&1 || status=$?
        else
            pkexec rpm -U "$pkg" >>"$log" 2>&1 || status=$?
        fi
        ;;
    *)
        status=2
        ;;
esac
echo "$status" >"$result"

if [ "$relaunch" = 1 ] && [ "$status" -eq 0 ]; then
    if command -v setsid >/dev/null 2>&1; then
        setsid "$launcher" >/dev/null 2>&1 </dev/null &
    else
        nohup "$launcher" >/dev/null 2>&1 </dev/null &
    fi
fi
