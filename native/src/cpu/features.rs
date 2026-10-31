//! Batched CPU feature sampling uses the same loaded rules as GPU batches.
use crate::decoration::{
    counts::{BatchSamples, Noise, Query},
    provider_noise::Program,
};
pub(crate) fn sample(
    noise: Option<&Noise>,
    counts: &[Query],
    programs: &[Program],
    points: &[[i32; 4]],
) -> Result<BatchSamples, String> {
    let counts = if counts.is_empty() {
        Vec::new()
    } else {
        let noise = noise.ok_or("profile has no registered placement noise")?;
        counts
            .iter()
            .map(|query| noise.count(query) as u32)
            .collect()
    };
    let providers = points
        .iter()
        .map(|&[x, y, z, program]| {
            let program = usize::try_from(program)
                .ok()
                .and_then(|id| programs.get(id))
                .ok_or_else(|| format!("invalid provider noise program {program}"))?;
            Ok(program.sample([x, y, z]).to_bits())
        })
        .collect::<Result<Vec<_>, String>>()?;
    Ok(BatchSamples {
        counts,
        providers,
        upload: 0,
        readback: 0,
        device_nanos: [None; 2],
    })
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn empty_batches_need_no_gpu_or_noise_and_invalid_queries_report_their_id() {
        let sample = sample(None, &[], &[], &[]).unwrap();
        assert!(sample.counts.is_empty() && sample.providers.is_empty());
        assert_eq!(sample.upload + sample.readback, 0);
        assert_eq!(sample.device_nanos, [None; 2]);
        assert!(
            super::sample(None, &[], &[], &[[1, 2, 3, -7]])
                .err()
                .unwrap()
                .contains("-7")
        );
    }
}
