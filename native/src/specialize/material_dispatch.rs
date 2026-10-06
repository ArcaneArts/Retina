//! Count and emit scan the same columns. Keep one dynamic emit flag so a
//! specialized backend need not compile two copies of the material evaluator.
pub(super) const ENTRY: &str = "retina_material_runs";

pub(super) fn source(source: String) -> Result<String, String> {
    let declaration = "@compute @workgroup_size(64)\nfn material_counts(@builtin(global_invocation_id) id:vec3<u32>)";
    let header = "if id.x==0u {let base=material_base_offset(r);";
    let call = "material_column(id.x,false,r);";
    if !source.contains(declaration) || !source.contains(header) || !source.contains(call) {
        return Err("missing material count evaluator for shared dispatch".into());
    }
    let mut shared = source
        .replace(declaration, "fn material_counts(id:vec3<u32>,emit:bool)")
        .replace(
            header,
            "if !emit && id.x==0u {let base=material_base_offset(r);",
        )
        .replace(call, "material_column(id.x,emit,r);");
    shared.push_str("\nstruct RetinaMaterialDispatch { emit:u32 };\nvar<immediate> retina_material_dispatch:RetinaMaterialDispatch;\n");
    shared.push_str("@compute @workgroup_size(64) fn retina_material_runs(@builtin(global_invocation_id) id:vec3<u32>){material_counts(id,retina_material_dispatch.emit!=0u);}\n");
    Ok(shared)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn shared_materials_preserve_prefix_and_dynamic_scan() {
        let source = format!(
            "{}\n{}\n{}\n{}\n{}",
            include_str!("../caves.wgsl"),
            include_str!("../climate.wgsl"),
            crate::program::interpreter_source(64),
            include_str!("../aquifers.wgsl"),
            include_str!("../materials.wgsl")
        );
        let shared = super::source(source).unwrap();
        let module = wgpu::naga::front::wgsl::parse_str(&shared).unwrap();
        wgpu::naga::valid::Validator::new(
            wgpu::naga::valid::ValidationFlags::all(),
            wgpu::naga::valid::Capabilities::all(),
        )
        .validate(&module)
        .unwrap();
        assert!(
            !module
                .entry_points
                .iter()
                .any(|e| e.name == "material_counts")
        );
        assert!(
            module
                .entry_points
                .iter()
                .any(|e| e.name == ENTRY && e.workgroup_size == [64, 1, 1])
        );
        assert!(
            module
                .entry_points
                .iter()
                .any(|e| e.name == "material_prefix_blocks" && e.workgroup_size == [256, 1, 1])
        );
        assert!(shared.contains("material_column(id.x,emit,r);"));
        assert!(shared.contains("if !emit && id.x==0u"));
    }
}
