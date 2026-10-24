//! Fixed-size attribution of the outermost sampled column call's actual path.
//! A cold cell supplied by another callback is not proof that this call blocked.
use super::*;

const N: usize = 14;
const NAMES: [&str; N] = [
    "unclassified", "interior_unresolved", "interior_ready", "interior_initialized", "interior_became_ready",
    "published_tile_unresolved", "published_tile_ready", "published_tile_initialized", "published_tile_became_ready",
    "pending_tile_unresolved", "pending_tile_ready", "pending_tile_initialized", "pending_tile_became_ready",
    "neighbor_archive",
];
#[derive(Clone, Copy, Default)]
#[repr(usize)]
pub(in crate::decoration::dependencies) enum Path {
    #[default]
    Unclassified = 0,
    Interior = 1,
    PublishedTile = 5,
    PendingTile = 9,
    NeighborArchive = 13,
}
#[derive(Clone, Copy, Default)]
struct Counts { calls: u64, own: u64, inclusive: u64 }
#[derive(Clone, Default)]
pub(super) struct Totals { rows: [Counts; N], touched: u16, overflow: bool }
impl Totals {
    pub(super) fn record(&mut self, path: usize, own: Option<u64>, inclusive: Option<u64>) {
        self.touched |= 1 << path;
        self.rows[path] = Counts { calls: 1, own: own.unwrap_or(0), inclusive: inclusive.unwrap_or(0) };
    }
    pub(super) fn add(&mut self, other: &Self) -> bool {
        let mut pending = other.touched;
        while pending != 0 {
            let index = pending.trailing_zeros() as usize;
            pending &= pending - 1;
            let (a, b) = (&mut self.rows[index], &other.rows[index]);
            for (a,b) in [(&mut a.calls,b.calls),(&mut a.own,b.own),(&mut a.inclusive,b.inclusive)] {
                if let Some(sum) = a.checked_add(b) { *a = sum; } else { self.overflow = true; }
            }
        }
        self.touched |= other.touched;
        self.overflow |= other.overflow;
        self.overflow
    }
    pub(super) fn reconciles(&self, calls: u64, own: u64, inclusive: u64) -> bool {
        let mut sums = [Some(0u64); 3];
        for row in &self.rows {
            for (sum, value) in sums.iter_mut().zip([row.calls,row.own,row.inclusive]) {
                *sum = sum.and_then(|n| n.checked_add(value));
            }
        }
        !self.overflow && sums == [Some(calls),Some(own),Some(inclusive)]
    }
    pub(super) fn json(&self, valid: Option<bool>) -> Value {
        Value::Array(self.rows.iter().enumerate().filter(|(i,_)| self.touched & (1 << i) != 0)
            .map(|(i,row)| json!({"path":NAMES[i],"calls":row.calls,
                "load_own_cpu_ns":(valid==Some(true)).then_some(row.own),
                "load_inclusive_cpu_ns":(valid==Some(true)).then_some(row.inclusive)})).collect())
    }
}
pub(super) const SCOPE: &str = "Partition of the SAME outermost sampled Planner.column calls and their existing CPU intervals. Interior selects completed region versions. Published/pending tile selects the actual tile map, not whether its column cell is ready; these routes also cover unfinished interior versions. Initialized means this call ran that cell's initializer; ready means the cell was populated at the observation before get_or_init; became_ready means it was observed empty but another callback supplied it (not proof of blocking). Neighbor archive is the direct completed-neighbor hit; an initialized cell can itself use an exterior archive. Unresolved/unclassified preserve early errors and synthetic observations. Recursive calls in the same anchor cannot replace the outer path; nested anchors retain their own paths and CPU is subtracted once. No extra clocks, per-call history, or generation owners. Path sums reconcile with existing load totals; CPU stays null when those totals are invalid/unavailable. Source/feature CPU and wall-time shares cannot be inferred from these rows.";
