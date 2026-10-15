#!/usr/bin/env bash
# Collects what a hung Gradle step leaves behind: memory, the process table, and a thread
# dump of every JVM still alive (Gradle daemon, Kotlin daemon, test workers). CI uploads
# the directory as an artifact. Safe to run after an ordinary failure; it just finds less.
set -u
mkdir -p hang-diagnostics
free -m > hang-diagnostics/memory.txt 2>&1 || true
ps -eo pid,etimes,rss,comm,args --sort=-rss | head -40 > hang-diagnostics/processes.txt 2>&1 || true
jps -lv > hang-diagnostics/jps.txt 2>&1 || true
for pid in $(jps -q); do
  jstack "$pid" > "hang-diagnostics/jstack-$pid.txt" 2>&1 || true
done
echo "collected: $(ls hang-diagnostics | tr '\n' ' ')"
