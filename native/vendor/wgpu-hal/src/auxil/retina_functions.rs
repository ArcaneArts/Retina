//! Retina's large interpreter bodies deliberately have a void, zero-argument
//! ABI. Preserve those boundaries through HLSL/SPIR-V lowering; ordinary
//! helpers and specialized shaders retain the backend's normal optimization.
extern crate alloc;
use alloc::{string::String, vec::Vec};

const PREFIX: &str = "retina_noinline_";

pub fn has_bodies(module: &naga::Module) -> bool {
    module.functions.iter().any(|(_, f)| f.name.as_deref().is_some_and(|n| n.starts_with(PREFIX)))
}

/// Naga emits these zero-argument declarations on their own unindented line.
/// Match the complete shape so call statements / other functions are untouched.
pub fn hlsl(source: &mut String) -> usize {
    let mut patched = String::with_capacity(source.len());
    let mut count = 0;
    for line in source.split_inclusive('\n') {
        if line.strip_prefix("void ").and_then(|s| s.strip_suffix("()\n"))
            .is_some_and(|name| name.starts_with(PREFIX) && name.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_')) {
            patched.push_str("[noinline] ");
            count += 1;
        }
        patched.push_str(line);
    }
    if count != 0 { *source = patched; }
    count
}

/// OpName identifies only our outlined bodies. Set DontInline on their
/// OpFunction instructions; IDs, layouts, arithmetic and all other flags stay
/// intact. The caller enables Naga's DEBUG names only for these modules.
pub fn spirv(words: &mut [u32]) -> usize {
    const OP_NAME: u32 = 5;
    const OP_FUNCTION: u32 = 54;
    let mut functions = Vec::new();
    let mut at = 5; // SPIR-V header
    while at < words.len() {
        let length = (words[at] >> 16) as usize;
        assert!(length != 0 && at + length <= words.len(), "invalid Naga SPIR-V instruction");
        if words[at] & 0xffff == OP_NAME && length >= 3 {
            let name: Vec<_> = words[at+2..at+length].iter().flat_map(|w| w.to_le_bytes()).take_while(|b| *b != 0).collect();
            if name.starts_with(PREFIX.as_bytes()) { functions.push(words[at+1]); }
        }
        at += length;
    }
    let mut count = 0;
    at = 5;
    while at < words.len() {
        let length = (words[at] >> 16) as usize;
        if words[at] & 0xffff == OP_FUNCTION && length == 5 && functions.contains(&words[at+2]) {
            // Inline and DontInline are mutually exclusive.
            words[at+3] = (words[at+3] & !1) | 2;
            count += 1;
        }
        at += length;
    }
    count
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn hlsl_only_marks_void_interpreter_definitions() {
        let mut source = String::from("void ordinary()\n{\n    retina_noinline_run_program();\n}\nvoid retina_noinline_run_program()\n{\n}\nfloat retina_noinline_other()\n");
        assert_eq!(hlsl(&mut source), 1);
        assert!(source.contains("[noinline] void retina_noinline_run_program()"));
        assert_eq!(source.matches("[noinline]").count(), 1);
        assert_eq!(hlsl(&mut source), 0);
    }
    #[test]
    fn spirv_retains_other_functions_flags_and_operands() {
        let mut words = vec![0x07230203, 0x00010000, 0, 32, 0];
        let mut name = b"retina_noinline_run_program\0".to_vec();
        while name.len() % 4 != 0 { name.push(0); }
        let length = 2 + name.len()/4;
        words.extend([(length as u32)<<16 | 5, 17]);
        words.extend(name.chunks_exact(4).map(|c| u32::from_le_bytes(c.try_into().unwrap())));
        let first = words.len();
        words.extend([5<<16 | 54, 1, 17, 5, 2]); // Inline + Pure
        words.extend([5<<16 | 54, 1, 18, 0, 2]);
        let before = words.clone();
        assert_eq!(spirv(&mut words), 1);
        assert_eq!(words[first+3], 6); // DontInline + Pure
        words[first+3] = before[first+3];
        assert_eq!(words, before);
    }
}
