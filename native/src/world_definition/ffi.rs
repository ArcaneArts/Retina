//! Opaque world handles for hosts. Open/metadata/close do not initialize a GPU.
use super::{
    WorldDefinition,
    runtime::{ProgramExecution, WorldSession},
};
use crate::{ChunkRequest, boundary, region::codec::Compression, shared_engine};
use std::{
    collections::HashMap,
    sync::{Arc, LazyLock, Mutex, OnceLock},
};

struct OpenWorld {
    definition: Arc<WorldDefinition>,
    session: OnceLock<Result<Arc<WorldSession>, String>>,
}

#[derive(Default)]
struct Handles {
    next: u64,
    worlds: HashMap<u64, Arc<OpenWorld>>,
}
static HANDLES: LazyLock<Mutex<Handles>> = LazyLock::new(|| Mutex::new(Handles::default()));

fn world(handle: u64) -> Result<Arc<OpenWorld>, String> {
    HANDLES
        .lock()
        .map_err(|_| "world handle lock poisoned")?
        .worlds
        .get(&handle)
        .cloned()
        .ok_or_else(|| "unknown or closed world definition handle".into())
}

impl OpenWorld {
    fn bind(
        &self,
        compression: Compression,
        execution: Option<ProgramExecution>,
    ) -> Result<&Arc<WorldSession>, String> {
        let session = self
            .session
            .get_or_init(|| {
                WorldSession::bind_with_execution(
                    shared_engine()?.clone(),
                    self.definition.clone(),
                    compression,
                    execution,
                )
            })
            .as_ref()
            .map_err(Clone::clone)?;
        if session.compression() != compression {
            return Err("world is already bound with different output compression".into());
        }
        if execution.is_some() && session.execution() != execution {
            return Err("world is already bound with different program execution".into());
        }
        Ok(session)
    }

    fn session(&self) -> Result<&Arc<WorldSession>, String> {
        self.session
            .get()
            .ok_or("world definition has not been bound")?
            .as_ref()
            .map_err(Clone::clone)
    }
}

unsafe fn text<'a>(data: *const u8, length: u64) -> Result<&'a str, String> {
    if data.is_null() {
        return Err("null world definition string".into());
    }
    let length = usize::try_from(length).map_err(|_| "world definition string is too long")?;
    std::str::from_utf8(unsafe { std::slice::from_raw_parts(data, length) })
        .map_err(|error| format!("world definition string: {error}"))
}

unsafe fn bytes(value: Vec<u8>, output: *mut *mut u8, length: *mut u64) -> Result<(), String> {
    if output.is_null() || length.is_null() {
        return Err("null world definition output".into());
    }
    let value = value.into_boxed_slice();
    unsafe {
        *length = value.len() as u64;
        *output = Box::into_raw(value) as *mut u8;
    }
    Ok(())
}

/// Publish ownership only after file loading has completed successfully.
fn register(definition: WorldDefinition) -> Result<u64, String> {
    let mut handles = HANDLES.lock().map_err(|_| "world handle lock poisoned")?;
    let handle = handles
        .next
        .checked_add(1)
        .ok_or("world handle space exhausted")?;
    handles.next = handle;
    handles.worlds.insert(
        handle,
        Arc::new(OpenWorld {
            definition: Arc::new(definition),
            session: OnceLock::new(),
        }),
    );
    Ok(handle)
}

/// # Safety
/// path is length readable UTF-8 bytes; output is a writable u64.
/// A successful open of an absent file returns handle 0. Other errors fail.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn retina_open_world_definition(
    path: *const u8,
    length: u64,
    output: *mut u64,
) -> i32 {
    boundary(|| {
        if output.is_null() {
            return Err("null world definition handle output".into());
        }
        unsafe {
            *output = 0;
        }
        let Some(definition) = WorldDefinition::read_if_present(unsafe { text(path, length) }?)?
        else {
            return Ok(());
        };
        let handle = register(definition)?;
        unsafe {
            *output = handle;
        }
        Ok(())
    })
}

/// Materialize and atomically publish an ordinary seed-bound retina.dat. This
/// does not bind a GPU or retain an alternative in-memory generation profile.
/// # Safety
/// All strings are length-readable UTF-8 buffers for the duration of this call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn retina_materialize_world_definition(
    catalog: *const u8,
    catalog_length: u64,
    variant: *const u8,
    variant_length: u64,
    seed: i64,
    root: *const u8,
    root_length: u64,
) -> i32 {
    boundary(|| {
        let variant = unsafe { text(variant, variant_length) }?;
        let source = super::catalog::WorldCatalog::read_selected(
            unsafe { text(catalog, catalog_length) }?,
            variant,
        )?;
        source.publish_owned(variant, seed, unsafe { text(root, root_length) }?)?;
        Ok(())
    })
}

/// Create, validate from the synced file, publish without replacement, and open.
/// # Safety
/// All strings are length-readable UTF-8 buffers; output is a writable u64.
/// On success, close the returned handle with retina_close_world_definition.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn retina_create_world_definition(
    catalog: *const u8,
    catalog_length: u64,
    variant: *const u8,
    variant_length: u64,
    seed: i64,
    root: *const u8,
    root_length: u64,
    output: *mut u64,
) -> i32 {
    boundary(|| {
        if output.is_null() {
            return Err("null world definition handle output".into());
        }
        unsafe {
            *output = 0;
        }
        let variant = unsafe { text(variant, variant_length) }?;
        let source = super::catalog::WorldCatalog::read_selected(
            unsafe { text(catalog, catalog_length) }?,
            variant,
        )?;
        let (_, definition) =
            source.publish_open_owned(variant, seed, unsafe { text(root, root_length) }?)?;
        let handle = register(definition)?;
        unsafe {
            *output = handle;
        }
        Ok(())
    })
}

/// # Safety
/// output and length are writable. Free the returned JSON with retina_free_bytes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn retina_world_definition_metadata(
    handle: u64,
    output: *mut *mut u8,
    length: *mut u64,
) -> i32 {
    boundary(|| {
        let world = world(handle)?;
        let data = serde_json::to_vec(&world.definition.adapter_metadata())
            .map_err(|error| error.to_string())?;
        unsafe { bytes(data, output, length) }
    })
}

/// # Safety
/// key is length readable UTF-8 bytes; output is a writable u32 profile handle.
/// compression is an MCA codec ID (2=zlib, 4=LZ4Block).
#[unsafe(no_mangle)]
pub unsafe extern "C" fn retina_bind_world_dimension(
    handle: u64,
    key: *const u8,
    length: u64,
    compression: u32,
    output: *mut u32,
) -> i32 {
    boundary(|| unsafe { bind_dimension(handle, key, length, compression, None, output) })
}

/// # Safety
/// Same pointer contract as retina_bind_world_dimension. Execution: 0=auto,
/// 1=interpreter, 2=specialized. This option never changes the loaded definition.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn retina_bind_world_dimension_execution(
    handle: u64,
    key: *const u8,
    length: u64,
    compression: u32,
    execution: u32,
    output: *mut u32,
) -> i32 {
    boundary(|| {
        let mode = match execution {
            0 => ProgramExecution::Auto,
            1 => ProgramExecution::Interpreter,
            2 => ProgramExecution::Specialized,
            _ => return Err(format!("unsupported program execution {execution}")),
        };
        unsafe { bind_dimension(handle, key, length, compression, Some(mode), output) }
    })
}

unsafe fn bind_dimension(
    handle: u64,
    key: *const u8,
    length: u64,
    compression: u32,
    execution: Option<ProgramExecution>,
    output: *mut u32,
) -> Result<(), String> {
    if output.is_null() {
        return Err("null dimension profile output".into());
    }
    let world = world(handle)?;
    let key = unsafe { text(key, length) }?;
    let _stage = crate::startup::Stage::new(format!("bind dimension {key} world_handle={handle}"));
    world.definition.dimension(key)?.profile()?;
    let compression = match compression {
        2 => Compression::Zlib,
        4 => Compression::Lz4,
        _ => return Err(format!("unsupported MCA compression {compression}")),
    };
    let dimension = world.bind(compression, execution)?.dimension(key)?;
    unsafe {
        *output = dimension.profile_id();
    }
    Ok(())
}

/// # Safety
/// key is length readable UTF-8 bytes; output points to a writable ChunkRequest.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn retina_world_dimension_request(
    handle: u64,
    key: *const u8,
    length: u64,
    x: i32,
    z: i32,
    output: *mut ChunkRequest,
) -> i32 {
    boundary(|| {
        if output.is_null() {
            return Err("null dimension request output".into());
        }
        let world = world(handle)?;
        let request = world
            .session()?
            .dimension(unsafe { text(key, length) }?)?
            .request(x, z)?;
        unsafe {
            *output = request;
        }
        Ok(())
    })
}

/// # Safety
/// key is length readable UTF-8 bytes; output and size are writable. Free with retina_free_bytes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn retina_world_dimension_chunk(
    handle: u64,
    key: *const u8,
    length: u64,
    x: i32,
    z: i32,
    output: *mut *mut u8,
    size: *mut u64,
) -> i32 {
    boundary(|| {
        let owner = world(handle)?;
        let dimension = owner.session()?.dimension(unsafe { text(key, length) }?)?;
        unsafe { bytes(dimension.generate_chunk(x, z)?, output, size) }
    })
}

/// Generate one world-bound region without passing blocks through the host.
/// # Safety
/// key/path are readable UTF-8 buffers; report is a writable aligned RegionReport.
/// The caller exclusively owns destination region-file I/O for the call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn retina_world_dimension_region(
    handle: u64, key: *const u8, length: u64, x: i32, z: i32,
    path: *const u8, path_length: u64, report: *mut crate::region::RegionReport,
) -> i32 {
    boundary(|| {
        if report.is_null() { return Err("null dimension region report".into()); }
        let owner = world(handle)?;
        let dimension = owner.session()?.dimension(unsafe { text(key, length) }?)?;
        let destination = std::path::Path::new(unsafe { text(path, path_length) }?);
        let result = dimension.generate_region(x, z, destination)?;
        unsafe { *report = result; }
        Ok(())
    })
}

/// # Safety
/// key is length readable UTF-8 bytes; output and size are writable. Free with retina_free_bytes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn retina_world_definition_profile_json(
    handle: u64,
    key: *const u8,
    length: u64,
    output: *mut *mut u8,
    size: *mut u64,
) -> i32 {
    boundary(|| {
        let world = world(handle)?;
        let profile = world
            .definition
            .diagnostic_profile(unsafe { text(key, length) }?)?;
        let data = serde_json::to_vec(&profile).map_err(|error| error.to_string())?;
        unsafe { bytes(data, output, size) }
    })
}

/// The host must stop admitting work and drain generation before closing its
/// last world handle. In-flight Rust leases/jobs retain their own Arc owners.
#[unsafe(no_mangle)]
pub extern "C" fn retina_close_world_definition(handle: u64) -> i32 {
    boundary(|| {
        let removed = HANDLES
            .lock()
            .map_err(|_| "world handle lock poisoned")?
            .worlds
            .remove(&handle)
            .ok_or("unknown or closed world definition handle")?;
        drop(removed);
        Ok(())
    })
}

#[cfg(test)]
mod tests;
