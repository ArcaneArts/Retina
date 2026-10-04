#!/usr/bin/env python3
"""Report bytecode value lifetimes in exported native registry profiles.

This diagnostic does not gate generation or change profiles. It models the current
eager GPU interpreter, including unused instructions and implicit root-zero outputs.
It reports scratch values, not the backend's physical hardware register count.
"""
import argparse
import heapq
import json
from pathlib import Path


def dependencies(node, points):
    op, a, b, c = (node[k] for k in ("op", "a", "b", "c"))
    if op in (1, 23, 24, 27, 31):
        result = [a, b, c]
    elif op in tuple(range(4, 11)) + (40, 41):
        result = [a, b]
    elif op in tuple(range(11, 23)) + (25, 30, 43, 44, 53):
        result = [a]
    else:
        result = []
    if op == 25:
        result.extend(int(p[2]) for p in points[b:b+c])
    return result


def allocation(program, points):
    count = len(program["nodes"])
    last = list(range(count))
    inputs = [dependencies(node, points) for node in program["nodes"]]
    for i, children in enumerate(inputs):
        for child in children:
            if child < 0 or child >= i:
                raise ValueError(f"instruction {i} has a non-earlier dependency {child}")
            last[child] = max(last[child], i)
    roots = program["roots"] + ([0] if len(program["roots"]) < 6 else [])
    for root in roots:
        last[root] = count
    release = [[] for _ in range(count+1)]
    available, slots, owners = [], [], {}
    capacity = peak = 0
    for i, children in enumerate(inputs):
        for slot in release[i]:
            del owners[slot]
            heapq.heappush(available, slot)
        # Preserve inputs through the instruction's output write. This conservative
        # allocator does not rely on in-place overwrites inside an opcode body.
        slot = heapq.heappop(available) if available else capacity
        if slot == capacity:
            capacity += 1
        for child in children:
            if owners.get(slots[child]) != child:
                raise AssertionError(f"dependency {child} overwritten before instruction {i}")
        slots.append(slot)
        owners[slot] = i
        release[last[i]+1 if last[i] < count else count].append(slot)
        peak = max(peak, len(owners))
    for root in roots:
        if owners.get(slots[root]) != root:
            raise AssertionError(f"output root {root} overwritten")
    return dict(nodes=count, maximum_live_values=peak, capacity=capacity, slots=slots)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("profiles", type=Path, nargs="+")
    parser.add_argument("--include-slots", action="store_true")
    args = parser.parse_args()
    reports = []
    for path in args.profiles:
        registry = json.loads(path.read_text())["registry_program"]
        programs = []
        for i, program in enumerate(registry["programs"]):
            report = allocation(program, registry["points"])
            if not args.include_slots:
                del report["slots"]
            programs.append(dict(program=i, **report))
        reports.append(dict(profile=str(path), maximum_live_values=max(p["maximum_live_values"] for p in programs),
                            instructions=sum(p["nodes"] for p in programs), programs=programs))
    print(json.dumps(reports, indent=2))


if __name__ == "__main__":
    main()
