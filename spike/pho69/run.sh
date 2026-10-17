#!/bin/zsh
# Drives one browser through the measurement matrix: open a URL, wait for its /report line,
# close the tab, next. Usage: run.sh "Google Chrome" | run.sh Safari
set -u
browser="$1"
port="${PORT:-8769}"
here="$(cd "$(dirname "$0")" && pwd)"
results="$here/results.jsonl"
touch "$results"
tag="$(echo "$browser" | tr -cd 'A-Za-z')"

wait_for() {  # run id, timeout seconds
  local id="$1" limit="$2" waited=0
  while ! grep -q "\"run\":\"$id\"" "$results"; do
    sleep 1; waited=$((waited+1))
    if [ "$waited" -ge "$limit" ]; then echo "  timeout: $id"; return 1; fi
  done
}

close_tabs() {
  if [ "$browser" = "Safari" ]; then
    osascript -e "tell application \"Safari\" to close (every tab of every window whose URL contains \"localhost:$port\")" >/dev/null 2>&1
  else
    osascript -e "tell application \"$browser\" to close (every tab of every window whose URL contains \"localhost:$port\")" >/dev/null 2>&1
  fi
}

run_one() {  # path, query, id
  local id="$3-$tag"
  echo "→ $id"
  open -a "$browser" "http://localhost:$port$1?$2&run=$id"
  wait_for "$id" "${TIMEOUT:-120}"
  sleep 1
  close_tabs
}

# Compose for Web: resolution 10 and 2 (81 cells) at 200 px; the 20 px inline case.
run_one /compose/ "res=10&size=200&style=squares&frames=300" compose-r10-200
run_one /compose/ "res=10&size=200&style=dots&frames=300"    compose-r10-200-dots
run_one /compose/ "res=2&size=200&style=dots&frames=300"     compose-r2-200
run_one /compose/ "res=10&size=20&style=dots&frames=300"     compose-r10-20
run_one /compose/ "res=2&size=20&style=dots&frames=300"      compose-r2-20

for target in js wasm; do
  run_one /dom-$target/ "res=10&size=200&style=dots&frames=300"    dom-$target-r10-200-dots
  run_one /dom-$target/ "res=10&size=200&style=squares&frames=300" dom-$target-r10-200-squares
  run_one /dom-$target/ "res=2&size=200&style=dots&frames=300"     dom-$target-r2-200-dots
  run_one /dom-$target/ "res=10&size=20&style=dots&frames=300"     dom-$target-r10-20-dots
  run_one /dom-$target/ "res=2&size=20&style=dots&tint=currentColor&frames=300" dom-$target-r2-20-dots
done

for target in js wasm; do
  run_one /trace-$target/ "fixture=idle-20s" trace-$target
done
echo "done: $browser"
