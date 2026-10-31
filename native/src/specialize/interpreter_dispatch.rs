//! Shared compact interpreter entrypoints. Preserve stage bodies and dispatch
//! geometry; only the uniform stage selector and pipeline binding differ.
use std::fmt::Write;

pub(super) fn grouped() -> bool {
    static GROUPED: std::sync::LazyLock<bool> = std::sync::LazyLock::new(|| {
        std::env::var("RETINA_INTERPRETER_GROUPS").as_deref() == Ok("2")
    });
    *GROUPED
}
pub(super) struct Group<'a> {
    pub wrapper: String,
    pub entries: Vec<&'a str>,
}
pub(super) struct Plan<'a> {
    pub groups: Vec<Group<'a>>,
}
impl<'a> Plan<'a> {
    pub fn new(entries: &[&'a str], wrapper: &str, grouped: bool) -> Self {
        if !grouped {
            return Self {
                groups: vec![Group {
                    wrapper: wrapper.into(),
                    entries: entries.to_vec(),
                }],
            };
        }
        let cave = wrapper == "retina_cave_dispatch";
        let mut first = Vec::new();
        let mut second = Vec::new();
        for &entry in entries {
            let primary = if cave {
                !entry.starts_with("material_")
            } else {
                matches!(
                    entry,
                    "climate_nodes" | "height_nodes" | "density_nodes" | "horizontal_nodes"
                ) || entry.starts_with("interpolation_nodes_")
            };
            if primary {
                first.push(entry);
            } else {
                second.push(entry);
            }
        }
        let names = if cave {
            ["density", "materials"]
        } else {
            ["fields", "queries"]
        };
        Self {
            groups: [first, second]
                .into_iter()
                .zip(names)
                .filter(|(entries, _)| !entries.is_empty())
                .map(|(entries, name)| Group {
                    wrapper: format!("{wrapper}_{name}"),
                    entries,
                })
                .collect(),
        }
    }
    pub fn source(&self, mut source: String) -> Result<String, String> {
        for name in self.groups.iter().flat_map(|g| &g.entries) {
            let function = source
                .find(&format!("fn {name}("))
                .ok_or_else(|| format!("missing shared interpreter stage {name}"))?;
            let attributes = source[..function]
                .rfind("@compute")
                .ok_or_else(|| format!("missing compute attributes for {name}"))?;
            if source[attributes..function]
                .split_whitespace()
                .collect::<String>()
                != "@compute@workgroup_size(64)"
            {
                return Err(format!(
                    "shared interpreter stage {name} requires a 64-lane entrypoint"
                ));
            }
            let body = source[function..]
                .find('{')
                .ok_or_else(|| format!("missing shared interpreter stage body {name}"))?
                + function;
            let declaration = source[function..body].replace("@builtin(global_invocation_id)", "");
            source.replace_range(attributes..body, &declaration);
        }
        source.push_str(
        "\nstruct RetinaDispatch { mode:u32 };\nvar<immediate> retina_dispatch:RetinaDispatch;\n",
    );
        for group in &self.groups {
            writeln!(source, "@compute @workgroup_size(64) fn {}(@builtin(global_invocation_id) id:vec3<u32>){{switch retina_dispatch.mode{{", group.wrapper).unwrap();
            for (mode, name) in group.entries.iter().enumerate() {
                writeln!(source, "case {mode}u:{{{name}(id);}}").unwrap();
            }
            source.push_str("default:{}\n}}\n");
        }
        Ok(source)
    }
}

#[cfg(test)]
pub(super) fn source(source: String, entries: &[&str], entry: &str) -> Result<String, String> {
    Plan::new(entries, entry, false).source(source)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn shared_entrypoints_keep_stage_bodies_and_other_workgroups() {
        let program = crate::program::interpreter_source_density(64, 1, false);
        for (prefix, suffix, entries, wrapper) in [
            (
                include_str!("../simplex.wgsl"),
                include_str!("../noise3.wgsl").to_owned(),
                vec![
                    "height_nodes",
                    "density_nodes",
                    "surface_columns",
                    "lake_density",
                    "lake_nodes",
                    "interpolation_nodes_1",
                ],
                "retina_world_dispatch",
            ),
            (
                include_str!("../caves.wgsl"),
                format!(
                    "{}\n{}",
                    include_str!("../aquifers.wgsl"),
                    include_str!("../materials.wgsl")
                ),
                vec![
                    "cave_nodes",
                    "cave_exterior",
                    "cave_mask",
                    "aquifer_mask",
                    "material_counts",
                    "material_emit",
                ],
                "retina_cave_dispatch",
            ),
        ] {
            let original = format!(
                "{prefix}\n{}\n{program}\n{suffix}",
                include_str!("../climate.wgsl")
            );
            for grouped in [false, true] {
                let plan = Plan::new(&entries, wrapper, grouped);
                assert_eq!(plan.groups.len(), if grouped { 2 } else { 1 });
                let shared = if grouped {
                    plan.source(original.clone()).unwrap()
                } else {
                    source(original.clone(), &entries, wrapper).unwrap()
                };
                let module = wgpu::naga::front::wgsl::parse_str(&shared).unwrap();
                wgpu::naga::valid::Validator::new(
                    wgpu::naga::valid::ValidationFlags::all(),
                    wgpu::naga::valid::Capabilities::all(),
                )
                .validate(&module)
                .unwrap();
                for &name in &entries {
                    let body = |text: &str| {
                        let start = text.find(&format!("fn {name}(")).unwrap();
                        let body = start + text[start..].find('{').unwrap();
                        let mut depth = 0;
                        for (n, ch) in text[body..].char_indices() {
                            match ch {
                                '{' => depth += 1,
                                '}' => depth -= 1,
                                _ => (),
                            }
                            if depth == 0 {
                                return text[body..=body + n].to_owned();
                            }
                        }
                        panic!("unclosed function")
                    };
                    assert_eq!(body(&original), body(&shared));
                    assert!(!module.entry_points.iter().any(|e| e.name == name));
                }
                for group in &plan.groups {
                    assert!(
                        module
                            .entry_points
                            .iter()
                            .any(|e| e.name == group.wrapper && e.workgroup_size == [64, 1, 1])
                    );
                }
                if wrapper == "retina_cave_dispatch" {
                    assert!(
                        module
                            .entry_points
                            .iter()
                            .any(|e| e.name == "material_prefix_blocks"
                                && e.workgroup_size == [256, 1, 1])
                    );
                }
            }
        }
    }
}
