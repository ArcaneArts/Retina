"""Summarize real startup logs without adding nested or overlapping stages."""
import argparse
import collections
import json
from pathlib import Path
import re

STAGE = re.compile(r"END #(\d+) (.*?) \[thread=([^]]+)\] elapsed=([\d.]+)s(?: thread_id=\d+ thread_cpu_ms=([\d.]+))?")
FEEDBACK = re.compile(r"Vulkan pipeline feedback pipeline_id=#(\d+) entry=(\S+).*?valid=(\w+) cache_supplied=(\w+) cache_hit=(\w+) driver_ms=([\d.]+)")


def summarize(text):
    completed, feedback, regions, caches = [], [], [], []
    started = {}
    for number, line in enumerate(text.splitlines(), 1):
        begin = re.search(r"BEGIN #(\d+) (.*?) \[thread=([^]]+)\]", line)
        if begin:
            started[begin[1]] = {"id": int(begin[1]), "label": begin[2], "thread": begin[3]}
        end = STAGE.search(line)
        if end:
            started.pop(end[1], None)
            completed.append({"id": int(end[1]), "label": end[2], "thread": end[3],
                              "elapsed_ms": float(end[4])*1000,
                              "thread_cpu_ms": float(end[5]) if end[5] else None})
        hit = FEEDBACK.search(line)
        if hit:
            feedback.append({"id": int(hit[1]), "entry": hit[2], "valid": hit[3] == "true",
                             "cache_supplied": hit[4] == "true", "cache_hit": hit[5],
                             "driver_ms": float(hit[6])})
        for prefix in ("Region GPU timings ", "Region generation timings "):
            if prefix in line:
                try:
                    regions.append({"kind": prefix.strip(), **json.loads(line.split(prefix, 1)[1])})
                except (ValueError, TypeError) as error:
                    raise ValueError(f"Malformed region diagnostic on line {number}") from error
        if "Pipeline cache " in line and any(f"cache {state}" in line for state in
                                               ("cold", "loaded", "unavailable", "ignored", "persisted", "checkpoint")):
            caches.append(line.split("[Retina startup] ")[-1])
    phases = collections.defaultdict(list)
    for row in completed:
        if row["label"].startswith("driver phase "):
            phases[row["label"].split(" pipeline_id=")[0]].append(row["elapsed_ms"])
    return {"schema": "retina.startup-report", "schema_version": 1,
            "completed_stages": completed, "unfinished_stages": list(started.values()),
            "driver_phases": {name: {"completed_calls": len(values), "cumulative_ms": sum(values),
                                      "maximum_ms": max(values)} for name, values in phases.items()},
            "pipeline_feedback": feedback, "cache_events": caches, "regions": regions,
            "scope": "Nested stages and concurrent threads overlap. Cumulative phase times are not startup wall time. Missing END means unfinished in this excerpt, not failed. A loaded cache or faster repeat does not establish a reported application-cache hit."}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("logs", nargs="+", type=Path)
    parser.add_argument("--out", type=Path)
    args = parser.parse_args()
    result = {str(path): summarize(path.read_text(errors="replace")) for path in args.logs}
    rendered = json.dumps(result, indent=2)
    if args.out:
        args.out.write_text(rendered + "\n")
    else:
        print(rendered)
