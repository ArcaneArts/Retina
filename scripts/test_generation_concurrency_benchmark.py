import importlib.util
from pathlib import Path
import unittest
from collections import Counter

spec = importlib.util.spec_from_file_location("concurrency_benchmark", Path(__file__).with_name("generation-concurrency-benchmark.py"))
benchmark = importlib.util.module_from_spec(spec)
spec.loader.exec_module(benchmark)


class ConcurrencyBenchmarkTest(unittest.TestCase):
    def test_all_cells_repeat_and_second_order_reverses_first(self):
        plan = benchmark.configurations([2,4,8], [1,2,4], [1,2], 2)
        self.assertEqual(len(plan), 36)
        self.assertEqual(set(Counter(tuple(row[1:]) for row in plan).values()), {2})
        self.assertEqual([r[1:] for r in plan[:18]], list(reversed([r[1:] for r in plan[18:]])))
        self.assertEqual(plan, benchmark.configurations([2,4,8], [1,2,4], [1,2], 2))

    def test_failed_or_incomplete_cells_cannot_improve_summary(self):
        row = {"regions":2,"assembly_threads":1,"compiler_threads":1,"exit_code":0,
               "measurement":{"chunks_per_second":100,"median_ms":10,"p95_region_ms":20,
                              "measured_process_usage":{"average_scheduled_cores":1.5}}}
        bad = {**row,"exit_code":1,"measurement":{**row["measurement"],"chunks_per_second":10000}}
        summary = benchmark.summarize_cells([row,bad,{"exit_code":0}])
        self.assertEqual(summary[0]["trials"], 1)
        self.assertEqual(summary[0]["median_chunks_per_second"], 100)


if __name__ == "__main__":
    unittest.main()
