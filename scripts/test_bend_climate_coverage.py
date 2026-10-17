"""Small independent-reference/fixture checks; no compiler, engine or GPU.

These checks do not validate the changed Bend executable. The persistent worker
checks in test_bend_registry_climate.py must run before runtime parity is claimed.
"""
import copy
import unittest

from test_bend_climate import expected, f32
from test_bend_registry_climate import projected, force_biome


def biome(center=None, flags=0):
    result = dict(flags=flags)
    if center is not None: result['climate'] = center
    return result


def interval(index, center=None):
    center = [0.]*4 if center is None else center
    return dict(min=center, max=center, weirdness=[0.,0.], depth=[0.,0.], offset=0., biome=index)


class ClimateCoverageTest(unittest.TestCase):
    def test_unmapped_center_competes_with_explicit_interval(self):
        source = dict(biomes=[biome(),biome([0.]*4)],climate_targets=[interval(0)])
        model = projected(source)
        for mode in (0,8):
            choice,score,_ = expected(model,[0.]*6,mode)
            self.assertEqual(1,choice['biome'])
            self.assertEqual(-f32(.03),score)
        self.assertEqual(0,expected(model,[0.]*6,2)[0]['biome'])
        self.assertIsNone(expected(model,[0.]*6,16))

    def test_unmapped_cave_and_shore_are_excluded_with_intervals(self):
        source = dict(biomes=[biome(),biome(flags=16),biome(flags=64)],climate_targets=[interval(0)])
        self.assertEqual([0],[t['biome'] for t in projected(source)])

    def test_any_reference_prevents_a_second_center_candidate(self):
        source = dict(biomes=[biome(flags=16),biome([0.]*4)],climate_targets=[interval(0),interval(1,[8.]*4)])
        self.assertFalse(any(t.get('unmapped') for t in projected(source)))

    def test_only_four_climate_axes_affect_unmapped_surface(self):
        source = dict(biomes=[biome([0.]*4)],climate_targets=[])
        model = projected(source)
        self.assertEqual(expected(model,[0.]*6,0),expected(model,[0.,0.,0.,0.,1000.,-1000.],0))

    def test_fallback_wins_exact_interval_tie(self):
        # At this exactly representable distance, F32 subtraction rounds away
        # the small niche bias. Registered order 0 must not displace fallback 1.
        center = [4096.,0.,0.,0.]
        source = dict(biomes=[biome(),biome(center)],climate_targets=[interval(0,center)])
        model = projected(source)
        self.assertEqual(1,expected(model,[0.]*6,0)[0]['biome'])

    def test_fallback_ties_preserve_original_biome_index(self):
        source = dict(biomes=[biome(),biome([0.]*4),biome([0.]*4)],climate_targets=[interval(0)])
        model = list(reversed(projected(source)))
        self.assertEqual(1,expected(model,[0.]*6,0)[0]['biome'])

    def test_weighted_bias_and_ocean_penalty(self):
        source = dict(biomes=[biome(),biome([0.]*4,flags=4)],climate_targets=[interval(0,[8.]*4)])
        model = projected(source)
        self.assertEqual(-f32(.12),expected(model,[0.]*6,1)[1])
        self.assertEqual(f32(8.-f32(.12)),expected(model,[0.]*6,5)[1])

    def test_empty_source_uses_centers_without_bias_or_underground_fallback(self):
        source = dict(biomes=[biome([1.]*4),biome([0.]*4,flags=64),biome(flags=16)])
        model = projected(source)
        self.assertEqual((1,0.),(expected(model,[0.]*6,0)[0]['biome'],expected(model,[0.]*6,0)[1]))
        self.assertEqual(0,expected(model,[0.]*6,8)[0]['biome'])
        self.assertIsNone(expected(model,[0.]*6,2))
        self.assertIsNone(expected(model,[0.]*6,16))

    def test_unmapped_center_must_be_finite_numeric_four_vector(self):
        for center in (None,[],[0.]*3,[0.]*5,[True,0.,0.,0.],[1.e200,0.,0.,0.],[float('inf'),0.,0.,0.]):
            with self.assertRaises(ValueError): projected(dict(biomes=[biome(center)]))

    def test_only_cave_targets_leave_surface_centers_unbiased(self):
        source = dict(biomes=[biome(flags=16),biome([0.]*4)],climate_targets=[interval(0)])
        model = projected(source)
        self.assertEqual((1,0.),(expected(model,[0.]*6,0)[0]['biome'],expected(model,[0.]*6,0)[1]))
        self.assertEqual(0,expected(model,[0.]*6,2)[0]['biome'])

    def test_single_biome_control_does_not_enable_unrelated_niches(self):
        source = dict(biomes=[biome([0.]*4),biome([0.]*4),biome([0.]*4,flags=16)])
        for selected in (0,1):
            control = copy.deepcopy(source)
            force_biome(control,selected)
            model = projected(control)
            self.assertFalse(any(t.get('unmapped') for t in model))
            self.assertEqual(selected,expected(model,[0.]*6,0)[0]['biome'])


if __name__ == '__main__':
    unittest.main()
