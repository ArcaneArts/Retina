//! Java 17 transport. The C ABI owns validation, world handles and engine work.
use super::ffi::*;
use crate::{
    ChunkRequest, gpu_backend::retina_configure_gpu_backend, queries::retina_query_points,
    region::RegionReport, retina_flush_pipeline_caches, retina_free_bytes, retina_last_error,
    retina_sample_biomes_u16, startup::retina_configure_debug,
};
use jni::{
    JNIEnv,
    objects::{JByteArray, JClass, JIntArray},
    sys::{jbyteArray, jfloat, jint, jintArray, jlong, jlongArray, jshortArray},
};
use std::{
    error::Error,
    mem::MaybeUninit,
    panic::{AssertUnwindSafe, catch_unwind},
    ptr,
};

type Result<T> = std::result::Result<T, Box<dyn Error>>;

fn call<'local, T>(
    mut env: JNIEnv<'local>,
    fallback: T,
    invoke: impl FnOnce(&mut JNIEnv<'local>) -> Result<T>,
) -> T {
    let message = match catch_unwind(AssertUnwindSafe(|| invoke(&mut env))) {
        Ok(Ok(value)) => return value,
        Ok(Err(error)) => error.to_string(),
        Err(_) => "Retina JNI call panicked".to_owned(),
    };
    // Preserve JVM errors such as a pending OutOfMemoryError on array allocation.
    if matches!(env.exception_check(), Ok(false)) {
        let _ = env.throw_new("java/lang/IllegalStateException", message);
    }
    fallback
}

fn check(status: i32) -> Result<()> {
    if status == 0 {
        return Ok(());
    }
    let mut bytes = [0; 4096];
    // The C boundary records an error in this calling thread's slot.
    let len = unsafe { retina_last_error(bytes.as_mut_ptr(), bytes.len() as u64) } as usize;
    Err(format!(
        "Retina definition call failed: {}",
        String::from_utf8_lossy(&bytes[..len])
    )
    .into())
}

fn output<T>(invoke: impl FnOnce(*mut T) -> i32) -> Result<T> {
    let mut result = MaybeUninit::uninit();
    check(invoke(result.as_mut_ptr()))?;
    // All callers supply C operations that initialize the complete output on success.
    Ok(unsafe { result.assume_init() })
}

struct NativeBytes {
    data: *mut u8,
    len: u64,
}

impl Drop for NativeBytes {
    fn drop(&mut self) {
        unsafe { retina_free_bytes(self.data, self.len) };
    }
}

fn byte_result(
    env: &JNIEnv,
    invoke: impl FnOnce(*mut *mut u8, *mut u64) -> i32,
) -> Result<jbyteArray> {
    let mut bytes = NativeBytes {
        data: ptr::null_mut(),
        len: 0,
    };
    check(invoke(&mut bytes.data, &mut bytes.len))?;
    if bytes.len > i32::MAX as u64 {
        return Err("Retina result exceeds Java array capacity".into());
    }
    let slice = if bytes.len == 0 {
        &[]
    } else {
        // The C call returned a live allocation. The JVM copies it before Drop.
        unsafe { std::slice::from_raw_parts(bytes.data, bytes.len as usize) }
    };
    Ok(env.byte_array_from_slice(slice)?.into_raw())
}

fn longs(env: &JNIEnv, values: &[jlong]) -> Result<jlongArray> {
    let result = env.new_long_array(values.len() as jint)?;
    env.set_long_array_region(&result, 0, values)?;
    Ok(result.into_raw())
}

#[unsafe(no_mangle)]
extern "system" fn Java_art_arcane_retina_core_JniDefinitionTransport_configureBackend(
    _env: JNIEnv,
    _class: JClass,
    code: jint,
) -> jint {
    retina_configure_gpu_backend(code as u32)
}

#[unsafe(no_mangle)]
extern "system" fn Java_art_arcane_retina_core_JniDefinitionTransport_configureDebug(
    _env: JNIEnv,
    _class: JClass,
    enabled: jint,
) {
    retina_configure_debug(enabled);
}

#[unsafe(no_mangle)]
extern "system" fn Java_art_arcane_retina_core_JniDefinitionTransport_flushPipelineCaches(
    env: JNIEnv,
    _class: JClass,
) {
    call(env, (), |_| {
        retina_flush_pipeline_caches();
        Ok(())
    });
}

#[unsafe(no_mangle)]
extern "system" fn Java_art_arcane_retina_core_JniDefinitionTransport_openNative(
    env: JNIEnv,
    _class: JClass,
    path: JByteArray,
) -> jlong {
    call(env, 0, |env| {
        let path = env.convert_byte_array(&path)?;
        let handle = output(|out| unsafe {
            retina_open_world_definition(path.as_ptr(), path.len() as u64, out)
        })?;
        Ok(handle as jlong)
    })
}

#[unsafe(no_mangle)]
extern "system" fn Java_art_arcane_retina_core_JniDefinitionTransport_createNative(
    env: JNIEnv,
    _class: JClass,
    catalog: JByteArray,
    variant: JByteArray,
    seed: jlong,
    root: JByteArray,
) -> jlong {
    call(env, 0, |env| {
        let catalog = env.convert_byte_array(&catalog)?;
        let variant = env.convert_byte_array(&variant)?;
        let root = env.convert_byte_array(&root)?;
        let handle = output(|out| unsafe {
            retina_create_world_definition(
                catalog.as_ptr(),
                catalog.len() as u64,
                variant.as_ptr(),
                variant.len() as u64,
                seed,
                root.as_ptr(),
                root.len() as u64,
                out,
            )
        })?;
        Ok(handle as jlong)
    })
}

#[unsafe(no_mangle)]
extern "system" fn Java_art_arcane_retina_core_JniDefinitionTransport_metadataNative(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jbyteArray {
    call(env, ptr::null_mut(), |env| {
        byte_result(env, |out, len| unsafe {
            retina_world_definition_metadata(handle as u64, out, len)
        })
    })
}

#[unsafe(no_mangle)]
extern "system" fn Java_art_arcane_retina_core_JniDefinitionTransport_bindNative(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    key: JByteArray,
    compression: jint,
    execution: jint,
) -> jint {
    call(env, 0, |env| {
        let key = env.convert_byte_array(&key)?;
        let profile = output(|out| unsafe {
            if execution == -1 {
                retina_bind_world_dimension(
                    handle as u64,
                    key.as_ptr(),
                    key.len() as u64,
                    compression as u32,
                    out,
                )
            } else {
                retina_bind_world_dimension_execution(
                    handle as u64,
                    key.as_ptr(),
                    key.len() as u64,
                    compression as u32,
                    execution as u32,
                    out,
                )
            }
        })?;
        Ok(profile as jint)
    })
}

#[unsafe(no_mangle)]
extern "system" fn Java_art_arcane_retina_core_JniDefinitionTransport_requestNative(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    key: JByteArray,
    x: jint,
    z: jint,
) -> jlongArray {
    call(env, ptr::null_mut(), |env| {
        let key = env.convert_byte_array(&key)?;
        let request = output(|out| unsafe {
            retina_world_dimension_request(handle as u64, key.as_ptr(), key.len() as u64, x, z, out)
        })?;
        longs(
            env,
            &[
                request.seed as jlong,
                request.chunk_x.into(),
                request.chunk_z.into(),
                request.min_y.into(),
                request.height.into(),
                request.base_height.to_bits().into(),
                request.amplitude.to_bits().into(),
                request.frequency.to_bits().into(),
                request.reserved.into(),
            ],
        )
    })
}

#[unsafe(no_mangle)]
extern "system" fn Java_art_arcane_retina_core_JniDefinitionTransport_regionNative(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    key: JByteArray,
    x: jint,
    z: jint,
    destination: JByteArray,
) -> jlongArray {
    call(env, ptr::null_mut(), |env| {
        let key = env.convert_byte_array(&key)?;
        let destination = env.convert_byte_array(&destination)?;
        let report: RegionReport = output(|out| unsafe {
            retina_world_dimension_region(
                handle as u64,
                key.as_ptr(),
                key.len() as u64,
                x,
                z,
                destination.as_ptr(),
                destination.len() as u64,
                out,
            )
        })?;
        longs(
            env,
            &[
                report.generated.into(),
                report.preserved.into(),
                report.bytes as jlong,
            ],
        )
    })
}

#[unsafe(no_mangle)]
extern "system" fn Java_art_arcane_retina_core_JniDefinitionTransport_publishRegionNative(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    key: JByteArray,
    x: jint,
    z: jint,
    preview: JByteArray,
    destination: JByteArray,
) -> jlongArray {
    call(env, ptr::null_mut(), |env| {
        let key = env.convert_byte_array(&key)?;
        let preview = env.convert_byte_array(&preview)?;
        let destination = env.convert_byte_array(&destination)?;
        let report: RegionReport = output(|out| unsafe {
            retina_world_dimension_publish_region(
                handle as u64, key.as_ptr(), key.len() as u64, x, z,
                preview.as_ptr(), preview.len() as u64,
                destination.as_ptr(), destination.len() as u64, out,
            )
        })?;
        longs(env, &[report.generated.into(), report.preserved.into(), report.bytes as jlong])
    })
}

#[unsafe(no_mangle)]
extern "system" fn Java_art_arcane_retina_core_JniDefinitionTransport_chunkNative(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    key: JByteArray,
    x: jint,
    z: jint,
) -> jbyteArray {
    call(env, ptr::null_mut(), |env| {
        let key = env.convert_byte_array(&key)?;
        byte_result(env, |out, len| unsafe {
            retina_world_dimension_chunk(
                handle as u64,
                key.as_ptr(),
                key.len() as u64,
                x,
                z,
                out,
                len,
            )
        })
    })
}

#[unsafe(no_mangle)]
extern "system" fn Java_art_arcane_retina_core_JniDefinitionTransport_biomesNative(
    env: JNIEnv,
    _class: JClass,
    seed: jlong,
    x: jint,
    z: jint,
    min_y: jint,
    height: jint,
    base_height: jfloat,
    amplitude: jfloat,
    frequency: jfloat,
    profile: jint,
) -> jshortArray {
    call(env, ptr::null_mut(), |env| {
        let request = ChunkRequest {
            seed: seed as u64,
            chunk_x: x,
            chunk_z: z,
            min_y,
            height: height as u32,
            base_height,
            amplitude,
            frequency,
            reserved: profile as u32,
        };
        request.validate()?;
        let count = height * 4;
        // A Java short carries the same bits as a native u16 palette index.
        let mut biomes = vec![0i16; count as usize];
        check(unsafe {
            retina_sample_biomes_u16(&request, biomes.as_mut_ptr().cast(), count as u64)
        })?;
        let result = env.new_short_array(count)?;
        env.set_short_array_region(&result, 0, &biomes)?;
        Ok(result.into_raw())
    })
}

#[unsafe(no_mangle)]
extern "system" fn Java_art_arcane_retina_core_JniDefinitionTransport_pointsNative(
    env: JNIEnv,
    _class: JClass,
    seed: jlong,
    x: jint,
    z: jint,
    min_y: jint,
    height: jint,
    base_height: jfloat,
    amplitude: jfloat,
    frequency: jfloat,
    profile: jint,
    xyz: JIntArray,
) -> jintArray {
    call(env, ptr::null_mut(), |env| {
        let length = env.get_array_length(&xyz)?;
        if length % 3 != 0 || length > 3 * 1024 {
            return Err("Invalid biome point batch".into());
        }
        let request = ChunkRequest {
            seed: seed as u64,
            chunk_x: x,
            chunk_z: z,
            min_y,
            height: height as u32,
            base_height,
            amplitude,
            frequency,
            reserved: profile as u32,
        };
        let count = length / 3;
        let mut points = vec![0; length as usize];
        env.get_int_array_region(&xyz, 0, &mut points)?;
        let mut biomes = vec![0; count as usize];
        // Point is a repr(C) triple of i32. The slice stays live for the call.
        check(unsafe {
            retina_query_points(
                &request,
                points.as_ptr().cast(),
                count as u64,
                0,
                biomes.as_mut_ptr(),
            )
        })?;
        let result = env.new_int_array(count)?;
        env.set_int_array_region(&result, 0, &biomes)?;
        Ok(result.into_raw())
    })
}

#[unsafe(no_mangle)]
extern "system" fn Java_art_arcane_retina_core_JniDefinitionTransport_profileNative(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    key: JByteArray,
) -> jbyteArray {
    call(env, ptr::null_mut(), |env| {
        let key = env.convert_byte_array(&key)?;
        byte_result(env, |out, len| unsafe {
            retina_world_definition_profile_json(
                handle as u64,
                key.as_ptr(),
                key.len() as u64,
                out,
                len,
            )
        })
    })
}

#[unsafe(no_mangle)]
extern "system" fn Java_art_arcane_retina_core_JniDefinitionTransport_closeNative(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    call(env, (), |_| {
        check(retina_close_world_definition(handle as u64))
    });
}
