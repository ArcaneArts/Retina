//! A mapped GPU byte slice does not establish Rust alignment for typed loads.
//! Borrow aligned data; copy unaligned records without changing their bits.
use bytemuck::Pod;
use std::borrow::Cow;

pub(crate) fn pod_slice<T: Pod>(bytes: &[u8]) -> Result<Cow<'_, [T]>, String> {
    let size = std::mem::size_of::<T>();
    if size == 0 || bytes.len() % size != 0 {
        return Err(format!(
            "GPU readback length {} is not a whole number of {size}-byte records",
            bytes.len()
        ));
    }
    match bytemuck::try_cast_slice(bytes) {
        Ok(records) => Ok(Cow::Borrowed(records)),
        Err(_) => Ok(Cow::Owned(
            bytes
                .chunks_exact(size)
                .map(bytemuck::pod_read_unaligned)
                .collect(),
        )),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn mapped_columns_masks_and_timestamps_decode_at_every_byte_alignment() {
        fn check<T: Pod + std::fmt::Debug + PartialEq>(expected: &[T]) {
            // The backing allocation is aligned; each offset deliberately
            // exercises a different host address, independently of a driver.
            let mut backing = [0u64; 32];
            let bytes = bytemuck::cast_slice_mut::<u64, u8>(&mut backing);
            let payload = bytemuck::cast_slice(expected);
            for offset in 0..8 {
                bytes[offset..offset + payload.len()].copy_from_slice(payload);
                let result = pod_slice::<T>(&bytes[offset..offset + payload.len()]).unwrap();
                assert_eq!(result.as_ref(), expected);
                assert_eq!(
                    matches!(result, Cow::Borrowed(_)),
                    offset % std::mem::align_of::<T>() == 0
                );
            }
        }
        check(&[
            crate::profile::Column {
                height: -64,
                packed: 0x89abcdef,
                materials: 0xfedc0123,
            },
            crate::profile::Column::default(),
        ]);
        check(&[0u32, u32::MAX, 0x89abcdef]);
        check(&[0u64, u64::MAX, 0x0123456789abcdef]);
    }
    #[test]
    fn truncated_records_return_errors_instead_of_panicking() {
        assert!(pod_slice::<u64>(&[0; 15]).unwrap_err().contains("15"));
        assert!(pod_slice::<crate::profile::Column>(&[0; 11]).is_err());
        assert!(pod_slice::<u32>(&[]).unwrap().is_empty());
    }
}
