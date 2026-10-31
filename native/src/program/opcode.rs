//! Wire opcodes shared by validation and native execution. GPU coverage is checked
//! against the actual interpreter cases, not a second handwritten shader list.
macro_rules! opcodes {
    ($($name:ident = $value:literal),+ $(,)?) => {
        #[derive(Clone, Copy, Debug, PartialEq, Eq)]
        pub enum Opcode { $($name = $value),+ }
        impl Opcode {
            pub const ALL: &'static [Self] = &[$(Self::$name),+];
            pub fn from_code(code: u32) -> Result<Self, String> {
                match code { $($value => Ok(Self::$name),)+ _ => Err(format!("unknown generation opcode {code}")) }
            }
        }
    }
}
opcodes! {
    Constant=0, Noise=1, Shift=2, AxisGradient=3, Add=4, Subtract=5,
    Multiply=6, Divide=7, Minimum=8, Maximum=9, Power=10, Absolute=11,
    Square=12, Cube=13, HalfNegative=14, QuarterNegative=15, Squeeze=16,
    Reciprocal=17, Negate=18, SquareRoot=19, Logarithm=20, Sign=21,
    Clamp=22, RangeChoice=23, Lerp=24, Spline=25, OldBlended=26,
    ShiftedNoise=27, Interpolate=28, Coordinate=29, ValueGradient=30,
    ShiftedOldBlended=31, And=40, Or=41, Terracotta=42, Not=43,
    InRange=44, StoneDepth=45, Water=46, VerticalAnchor=47,
    VerticalChance=48, Steep=49, Hole=50, Temperature=51, NoiseCount=52,
    Coverage=53, PreliminarySurface=54,
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn every_shader_instruction_has_a_native_contract() {
        let shader = include_str!("../program.wgsl")
            .split_once("switch op {")
            .unwrap()
            .1
            .split_once("default:{}")
            .unwrap()
            .0;
        let actual: std::collections::BTreeSet<u32> = shader
            .split("case ")
            .skip(1)
            .map(|part| part.split_once("u:").unwrap().0.parse().unwrap())
            .collect();
        let expected = Opcode::ALL.iter().map(|op| *op as u32).collect();
        assert_eq!(actual, expected);
        assert!(Opcode::from_code(32).unwrap_err().contains("32"));
    }
}
