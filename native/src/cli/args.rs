use retina_worldgen::region::codec::Compression;
use std::{collections::HashSet, ffi::OsString, path::PathBuf};

pub const HELP: &str = "Retina standalone region generation

Usage:
  retina generate --definition PATH --dimension NAMESPACE:PATH \\
    --region-min=X,Z --region-max=X,Z --output DIRECTORY [--compression zlib|lz4]

Region coordinates are inclusive at both ends. Each region is 32x32 chunks.
The saved definition supplies the exact seed and all generation settings.
Existing chunk records are preserved; missing records are generated.
Use an offline output directory with the matching generation definition.
Ctrl-C/SIGTERM finishes the active region, stops, and exits with status 130.
Output contains region data; initializing a playable save is outside this command.
A usable permitted GPU is preferred; native CPU generation is automatic otherwise.
Set RETINA_GPU_BACKEND=cpu to select CPU explicitly (auto, cpu, vulkan, dx12, metal).
";

#[derive(Debug, PartialEq, Eq)]
pub struct Generate {
    pub definition: PathBuf,
    pub dimension: String,
    pub min: [i32; 2],
    pub max: [i32; 2],
    pub output: PathBuf,
    pub compression: Compression,
}

#[derive(Debug)]
pub enum Command {
    Help,
    Generate(Generate),
}

pub fn parse(args: impl IntoIterator<Item = OsString>) -> Result<Command, String> {
    let mut args = args.into_iter();
    let Some(command) = args.next() else {
        return Err("missing command; use --help".into());
    };
    if command == "--help" || command == "-h" {
        return Ok(Command::Help);
    }
    if command != "generate" {
        return Err(format!("unknown command {:?}; use generate", command));
    }
    let mut definition = None;
    let mut dimension = None;
    let mut min = None;
    let mut max = None;
    let mut output = None;
    let mut compression = Compression::Zlib;
    let mut seen = HashSet::new();
    while let Some(argument) = args.next() {
        if argument == "--help" || argument == "-h" {
            return Ok(Command::Help);
        }
        // Paths supplied separately retain their OS encoding. Equals-form flags
        // and numeric/identifier arguments use UTF-8, like Minecraft identifiers.
        let text = argument.to_str().ok_or("option names must be UTF-8")?;
        let (flag, inline) = text
            .split_once('=')
            .map_or((text, None), |(key, value)| (key, Some(value)));
        if !matches!(
            flag,
            "--definition"
                | "--dimension"
                | "--region-min"
                | "--region-max"
                | "--output"
                | "--compression"
        ) {
            return Err(format!("unknown option {flag}"));
        }
        if !seen.insert(flag.to_owned()) {
            return Err(format!("duplicate option {flag}"));
        }
        let value = inline
            .map(OsString::from)
            .or_else(|| args.next())
            .ok_or_else(|| format!("missing value for {flag}"))?;
        if value.is_empty() {
            return Err(format!("empty value for {flag}"));
        }
        match flag {
            "--definition" => definition = Some(PathBuf::from(value)),
            "--output" => output = Some(PathBuf::from(value)),
            "--dimension" => {
                dimension = Some(value.into_string().map_err(|_| "dimension must be UTF-8")?)
            }
            "--region-min" => min = Some(coordinate(&value)?),
            "--region-max" => max = Some(coordinate(&value)?),
            "--compression" => {
                compression = match value.to_str() {
                    Some("zlib") => Compression::Zlib,
                    Some("lz4") => Compression::Lz4,
                    _ => return Err("compression must be zlib or lz4".into()),
                }
            }
            _ => unreachable!(),
        }
    }
    let min = min.ok_or("missing --region-min")?;
    let max = max.ok_or("missing --region-max")?;
    if min[0] > max[0] || min[1] > max[1] {
        return Err("region-min must not exceed region-max on either axis".into());
    }
    Ok(Command::Generate(Generate {
        definition: definition.ok_or("missing --definition")?,
        dimension: dimension.ok_or("missing --dimension")?,
        min,
        max,
        output: output.ok_or("missing --output")?,
        compression,
    }))
}

fn coordinate(value: &OsString) -> Result<[i32; 2], String> {
    let text = value.to_str().ok_or("region coordinates must be UTF-8")?;
    let (x, z) = text
        .split_once(',')
        .ok_or("region coordinates must be X,Z")?;
    Ok([
        x.parse().map_err(|_| format!("invalid region X: {x}"))?,
        z.parse().map_err(|_| format!("invalid region Z: {z}"))?,
    ])
}

impl Generate {
    pub fn count(&self) -> u128 {
        (self.max[0] as i64 - self.min[0] as i64 + 1) as u128
            * (self.max[1] as i64 - self.min[1] as i64 + 1) as u128
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn command(extra: &str) -> Result<Command, String> {
        parse(format!("generate --definition retina.dat --dimension minecraft:overworld --region-min=-4,-2 --region-max=1,3 --output out {extra}")
            .split_whitespace().map(OsString::from))
    }
    #[test]
    fn inclusive_negative_range_and_codec() {
        let Command::Generate(options) = command("--compression lz4").unwrap() else {
            panic!()
        };
        assert_eq!(options.min, [-4, -2]);
        assert_eq!(options.max, [1, 3]);
        assert_eq!(options.count(), 36);
        assert_eq!(options.compression, Compression::Lz4);
    }
    #[test]
    fn invalid_options_are_actual_usage_errors() {
        for option in [
            "--seed 1",
            "--compression gzip",
            "--region-min=5,5",
            "--unexpected",
        ] {
            assert!(command(option).is_err(), "{option}");
        }
        assert!(
            parse(["generate", "--region-min=2,0", "--region-max=1,0"].map(OsString::from))
                .unwrap_err()
                .contains("region-min")
        );
        assert!(coordinate(&OsString::from("0,1,2")).is_err());
        assert!(coordinate(&OsString::from("2147483648,0")).is_err());
        assert!(matches!(
            parse([OsString::from("--help")]).unwrap(),
            Command::Help
        ));
    }
}
