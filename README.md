# Axiom Paper Plugin (Folia)

Serverside component for Axiom, with **Folia support**.

This is a maintained fork of [Moulberry/AxiomPaperPlugin](https://github.com/Moulberry/AxiomPaperPlugin)
built on top of Paper's modern scheduler API. The result runs on **both Paper and
Folia** from the same jar.

- **Default branch**: `folia` (Folia-supported build)
- **`master`**: unmodified upstream mirror, kept purely as the rebase base
- Upstream changelog: <https://github.com/Moulberry/AxiomPaperPlugin>

## What changed (Folia support)

On Folia there is no "main thread": every region (and the world's global region)
ticks on its own thread, and the classic Bukkit scheduler (`BukkitRunnable`,
`scheduleSyncRepeatingTask`, `server.execute(...)`) does not exist / throws.

The fork replaces all of that with the **Paper Scheduler API**
(`GlobalRegionScheduler` / `RegionScheduler` / `EntityScheduler`), which behaves
identically on Paper and Folia:

- The plugin tick now runs on the `GlobalRegionScheduler` fixed-rate task.
- World work (chunk resend/relight, block buffers, biome writes, marker sync) is
  scheduled **per region**, so a task only ever touches chunks it owns.
- Entity operations (spawn / manipulate / delete / request data / marker NBT) hop
  to the **entity's own region**.
- Blueprint uploads write files on the async scheduler and update the registry on
  the global region thread.
- Many permission checks moved to **UUID-based** lookups so they work without a
  Bukkit entity reference on another region's thread.

Scheduling helpers live in `com.moulberry.axiom.Environment`
(`runGlobal`, `runGlobalFixedRate`, `runOnRegion`, `runOnEntityRegion`).
Folia is detected at runtime via `io.papermc.paper.threadedregions.RegionizedData`.

### Known limitations on Folia

- `tick_blocks` and direct reads of far, already-loaded chunks outside the
  player's region can still cross regions and be rejected by Folia (rare).
- Marker gizmo sync (`send-markers`) reads marker data per-entity; see
  `WorldExtension.scheduleMarkerTick`.

## Download

Release builds (default branch): **Releases** tab on the right, or the link below.
The released jar is a fully shaded (shadow) jar — just drop it into `plugins/`.

https://github.com/LapisWorks/AxiomPaperPlugin-Folia/releases

## Building

Requires a JDK 25 toolchain and Gradle 9.x (wrapper included).

```sh
./gradlew shadowJar
# jar output: build/libs/AxiomPaper-all.jar
```

Local-only machine paths (e.g. `org.gradle.java.home=...`) belong in a
**gitignored** `gradle.properties` — do not commit them.

## Developer guide

This fork uses a **rebase-based** workflow so it can track upstream with minimal
conflict surface. Every Folia change is split into small, focused commits
(patches); upstream updates are replayed underneath them.

### Branch layout

```
upstream/master (Moulberry)      ← pure upstream, fetch-only
   └─ folia (default)            ← = upstream + Folia patches
```

Never merge upstream into `folia` — always rebase.

### Updating from upstream

```bash
git fetch upstream          # get the latest Moulberry commits
git checkout folia
git rebase upstream/master  # replay every Folia commit on top of the new code
```

If a commit fails to apply, git stops on it:

```bash
# fix conflicts in the reported files
git add <files>
git rebase --continue
```

Then push the rewritten branch (rebase rewrites commit hashes):

```bash
git push --force-with-lease origin folia
```

`--force-with-lease` refuses the push if somebody else updated `origin/folia`,
protecting you from overwriting others' work.

### When modifying the fork

- Keep `master` clean — never commit fork-specific changes to it.
- Keep Folia changes in **small, logical commits** so future rebases stay
  one-file, one-commit conflicts.
- Prefer the `Environment` helpers over raw scheduler calls so changes stay in
  one compatibility layer and are easy to rebase.

## FAQ

**Axiom works in singleplayer but not when I connect to a multiplayer server
running the Axiom Paper Plugin. What gives?**

First, the player must be an op on the server. If the player does not have op
permissions, run `/op <playername>`. This player must then disconnect from the
server and reconnect.

If you're using an alternative solution for permission management, you must give
players the `axiom.default` permission.

If players continue to have issues, they can run the `/whynoaxiom` command for
more information.

## License / Credits

All credit for Axiom and the paper plugin goes to [Moulberry](https://github.com/Moulberry).
See the upstream repository for the original documentation and config reference.