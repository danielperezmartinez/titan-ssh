#!/bin/sh
# A big clock that changes every second, so the screen always has damage.
while true; do
  printf '\033[2J\033[H\n\n   %s\n' "$(date +%H:%M:%S)"
  sleep 1
done
