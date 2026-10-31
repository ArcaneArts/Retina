import unittest
from unittest.mock import patch
import process_usage
from startup_report import summarize


class StartupReportTest(unittest.TestCase):
    def test_nested_completed_and_unfinished_work_are_separate(self):
        report = summarize("""[Retina startup] BEGIN #1 compile world pipelines [thread=compiler]
[Retina startup] BEGIN #2 driver phase vkCreateComputePipelines pipeline_id=#1 entry=world [thread=compiler]
[Retina startup] END #2 driver phase vkCreateComputePipelines pipeline_id=#1 entry=world [thread=compiler] elapsed=95.322s thread_id=1 thread_cpu_ms=94125.0
[Retina startup] Vulkan pipeline feedback pipeline_id=#1 entry=world supported=true valid=true cache_supplied=true cache_hit=no driver_ms=95321.582
[Retina startup] Pipeline cache loaded lane=compiler bytes=10 path=cache.bin
[Retina startup] Region GPU timings {"job":1,"stage_ms":{"height":25.0}}
""")
        self.assertEqual([r["id"] for r in report["unfinished_stages"]], [1])
        self.assertEqual(report["completed_stages"][0]["thread_cpu_ms"], 94125)
        self.assertEqual(report["pipeline_feedback"][0]["cache_hit"], "no")
        self.assertEqual(report["regions"][0]["stage_ms"]["height"], 25)
        self.assertEqual(report["driver_phases"]["driver phase vkCreateComputePipelines"]["completed_calls"], 1)

    def test_malformed_machine_record_is_reported(self):
        with self.assertRaisesRegex(ValueError, "line 1"):
            summarize("Region GPU timings {broken}")

    def test_partial_specialization_is_not_complete_startup(self):
        report = summarize('''[Retina startup] BEGIN #3 compile world pipelines [thread=compiler]
[Retina startup] Specialization cohort {"cohort":"interpolation-fields","ready_groups":1,"total_groups":3}
''')
        self.assertEqual(report["regions"][0]["kind"], "Specialization cohort")
        self.assertEqual(report["regions"][0]["ready_groups"], 1)
        self.assertEqual(report["unfinished_stages"][0]["id"], 3)
        self.assertEqual(report["completed_stages"], [])

    def test_compiler_input_processing_is_distinct_from_driver_creation(self):
        report = summarize('''[Retina startup] Compiler input {"entry":"world","applied":true,"processing_ms":110.0,"input":{"words":100},"output":{"words":90}}
[Retina startup] END #2 driver phase vkCreateComputePipelines pipeline_id=#1 entry=world [thread=compiler] elapsed=95.0s
''')
        self.assertEqual(report["regions"][0]["kind"], "Compiler input")
        self.assertEqual(report["regions"][0]["processing_ms"], 110)
        self.assertEqual(report["driver_phases"]["driver phase vkCreateComputePipelines"]["cumulative_ms"], 95000)

    def test_process_counters_without_resource_keep_unavailable_values_null(self):
        with patch.object(process_usage, "resource", None):
            before, after = process_usage.snapshot(), process_usage.snapshot()
        self.assertGreaterEqual(after.ru_utime, before.ru_utime)
        self.assertIsNone(process_usage.difference(after, before, "ru_nvcsw"))
        self.assertIsNone(process_usage.peak_rss_bytes(after))


if __name__ == "__main__":
    unittest.main()
