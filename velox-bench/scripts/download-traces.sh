#!/usr/bin/env bash
#
# Downloads the full, real cache-access traces the checked-in sample traces
# (docs/benchmarks/traces/*.trace) are only small synthetic stand-ins for.
# Not run automatically by anything in this project -- these traces are
# large (the Twitter set is tens of gigabytes across its clusters) and this
# repository does not assume network access, so fetching them is a manual,
# opt-in step. Run from the repository root:
#
#   bash velox-bench/scripts/download-traces.sh
#
set -euo pipefail

OUT_DIR="docs/benchmarks/traces/full"
mkdir -p "$OUT_DIR"

echo "== Twitter production cache traces (SOSP '20) =="
echo "Source: https://github.com/twitter/cache-trace -- a real, publicly documented"
echo "GitHub repository maintained by Twitter/X, described in Yang et al., 'A large-scale"
echo "analysis of hundreds of in-memory cache clusters at Twitter' (OSDI 2020). Each"
echo "cluster's trace is a separate compressed CSV: timestamp, anonymized key, key size,"
echo "value size, client id, operation, TTL. Follow that repository's own README for the"
echo "current per-cluster download links and format notes -- they are not mirrored or"
echo "guessed here, since the exact list of available clusters has changed over time."
echo
echo "Once downloaded, extract the key column into this project's plain trace format"
echo "(one key per line) and load it with TraceLoader.loadStringKeysAsDenseIds:"
echo '  zcat cluster<N>.csv.gz | cut -d"," -f2 > sample-twitter.trace'
echo

echo "== ARC / block-storage traces (Megiddo & Modha, 2003, and related) =="
echo "The traces the original ARC paper's own experiments used (P1-P14, S1-S3, DS1,"
echo "ConCat, Merge, OLTP) circulated for years from the authors' pages and mirrors run by"
echo "later cache-replacement researchers; those specific URLs have moved and gone stale"
echo "over that time, and this script does not guess at a current one. The UMass Trace"
echo "Repository (search \"UMass Trace Repository storage\") and the SNIA IOTTA repository"
echo "(iotta.snia.org) both host block-I/O traces suitable for the same purpose -- a"
echo "sequence of block numbers, one request at a time, which is exactly what"
echo "TraceLoader.loadIntegerKeys reads. Check either for what is currently mirrored."
echo
echo "Once downloaded, a trace already given as one block number per line needs no"
echo "conversion at all; TraceLoader.loadIntegerKeys reads it directly."

echo
echo "Neither dataset is required to use this project: every result in"
echo "docs/benchmarks/RESULTS.md comes from the synthetic Workloads generators, which"
echo "were built first specifically because they isolate one mechanism at a time by"
echo "construction. A real trace is a natural next step, not a replacement for that."
