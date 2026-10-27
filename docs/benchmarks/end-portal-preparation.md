# End entry and return preparation (26.3)

End portal travel now prepares foreground regions before vanilla resolves the destination. The arena spans four regions around the origin. Coverage includes the fight's radius-nine chunk ticket, the complete outer chunk, ticking neighbors, and the arrival platform. Minecraft still regenerates that platform and owns the dragon, death animation, podium, egg, gateway progression, credits and respawn selection.

The accepted instant portal remains pending while publication completes, including when an entity moves off the portal on the next tick. First-exit credits are a separate vanilla path: `EndPortalBlock.entityInside` calls `ServerPlayer.showEndCredits` directly. Preparation therefore precedes that method as well, with server ticks continuing until ready. A dimension change or loss of portal eligibility cancels the pending credits action. Actual generation failures propagate.

Return preparation includes the configured bed/anchor and shared-spawn fallback without querying or consuming either spawn block. Shared-spawn coverage follows `PlayerSpawnFinder`'s actual candidate domain: at most 1,024 positions, plus its final fallback, with collision and ticking coverage. Large respawn radii do not cause preparation of an enormous square. Minecraft chooses the actual candidate and retains its random permutation, border behavior and missing-spawn handling. Nonplayer returns retain `Entity.adjustSpawnLocation` behavior.

## Validation

- Existing 19 Nether boundary cases pass. Thirty End spawn cases check positive/negative boundaries and radii through 100,000 against the loaded Minecraft `PlayerSpawnFinder` candidate permutation. Arena coverage includes all four regions.
- Common, Fabric client/server and NeoForge sources compile.
- An actual Fabric integrated client entered through portal collision into a cold End, with all four arena regions published. It checked the regenerated platform, ten seeded crystals and two cages, damaged crystals and the dragon through vanilla handlers, waited for vanilla dying/death progression, checked the egg, active exit podium and one unlocked central gateway, returned through the portal and normal credits response, disconnected, reopened, and repeated entry/return with persisted fight state.
- That single live observation took 418.4 ms from portal positioning to server arrival; foreground publication reported 249.5 ms. These are diagnostic observations, not final readiness/performance acceptance.
- The first attempt used an obsolete QA storage path and failed an assertion despite successful publication. The corrected scenario uses `DimensionType.getStorageFolder`; its fresh-world and reopen run passed. Disposable region files from that failed attempt were hashed and removed.
- NeoForge gameplay remains unproven in this cycle: its client closed at the title screen. A new cold missing-bed fallback scenario was added after the successful Fabric run and remains for the combined follow-up.

Gateways, pearl transfer, dragon respawning, dedicated-server gameplay, pending-work unload, automatic End activation and final performance acceptance remain outstanding. Production End eligibility is still disabled. Do not interpret this focused delivery as complete End support.

Further GUI validation reuses the existing muted instance and preserves its settings. Combine remaining gameplay scenarios instead of repeating successful creation/reopen campaigns.
