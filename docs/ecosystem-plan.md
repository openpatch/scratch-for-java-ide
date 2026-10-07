# Scratch for Java ecosystem plan

Created: 2026-10-07

## Goal

Make Scratch for Java a dependable learning path: start from Scratch or a browser
lesson, continue the same project in Studio, and use ordinary Java tools as the
student progresses. Prioritize compatibility, reproducible validation, project
transfer, and classroom use.

## Current status (2026-10-07)

All repository implementation items are complete. Milestones 1.1–1.3, 2, 3 and
5 are implemented and validated; Milestone 4's documentation and offline course
are implemented. Linux packaged validation passes with bundled Java and both
flavors. Two acceptance activities remain open because they require external
evidence: native Windows/macOS execution and a student/teacher classroom pilot.
The OS smoke matrix and pilot protocol are prepared. Neither is recorded as a
passing check. Detailed commands/results follow in Batches 4–7.

## Repositories and responsibilities

| Repository | Responsibility |
| --- | --- |
| `scratch-for-java` | Java API, desktop runtime, reference documentation, shared release metadata |
| `scratch-for-java-ide` | Desktop authoring, visual editors, Scratch import, project/application export |
| `online-ide` | Browser compiler/runtime, embedded exercises, browser implementation of the API |
| `hyperbook-informatik` | Curriculum, lesson projects, checkpoints, teacher resources |

Keep student projects as ordinary Java source and asset files. Metadata should
remain optional for running them in Java. Use the existing standard/NRW library
flavors and preserve both through all transfer and validation workflows.

## Investigation baseline

- Library and Studio currently use Scratch for Java 5.7.0.
- The browser port lacks the new clone lifecycle, variable monitors, and game
  pause/speed APIs. Its current clone implementation creates a base Sprite.
- Thirteen generated interactive reference pages call APIs missing from the
  browser port. Compilation coverage against desktop Java does not catch this.
- The browser release/playground workflows and curriculum deployment workflow
  build without running the existing test suites or lesson checkers.
- The curriculum browser checker defaults to OOP and Fotofilter pages and misses
  Spielwerkstatt. It relies on fixed delays and reading the compiler error tab.
- Studio exchanges Java folders/ZIPs; the embedded IDE saves workspace JSON.
  Curriculum checkpoints bridge prepared examples, but a supported round trip
  for a student's edited project is still needed.
- Several generation and synchronization tools already exist. Studio bundles
  seven tutorials and 21 demos; the API index contains 488 entries. The generated
  browser parity fixture covers 32 of the desktop probe's 82 values.
- Setup documentation omits Studio and some descriptions of missing editor
  functionality have become outdated across the ecosystem.

Completed baseline validation: 19 Studio core tests, 72 browser runtime unit
tests, all 18 Spielwerkstatt archives, the static Java lesson checker across
158 pages, tutorial template comparison, and parity-fixture freshness check.
The broader browser compiler/interpreter test run stalled and was stopped.
Full interactive lesson execution remains to be validated.

## Milestone 1 — Compatibility and publishing gates

Priority: immediate. Start implementation here.

### 1.1 Reliable compiler and runtime checks

- [x] Diagnose the stalled browser compiler/interpreter test run and make the
  test harness fail clearly on missing compiler output, unexpected errors,
  assertion failures, or tests that cannot finish.
- [x] Add a noninteractive browser test command suitable for CI.
- [x] Run browser unit/compiler tests and relevant build checks on pull requests.
- [x] Require passing validation before publishing the embedded IDE/playground.
- [x] Run the Java lesson and desktop checkpoint static checks before publishing.
- [x] Extend the curriculum publishing gate to the remaining static checks and
  the full interactive Java page check after resolving existing failures.
- [x] Discover every built page containing an embedded Java IDE, including
  Spielwerkstatt, rather than maintaining a short list of project folders.
- [x] Pin the curriculum's Hyperbook and browser-test toolchain and provide a
  documented, reproducible local/CI setup.

Acceptance: a broken runtime test or invalid lesson stops publishing; a newly
added Java project is included automatically; the same checks run locally and
in CI without a personal `/tmp/pw` installation.

### 1.2 Explicit compatibility contract

- [x] Publish a machine-readable contract identifying the desktop library
  version, browser implementation version/revision, standard/NRW support, and
  API availability (implemented, desktop-only, or awaiting browser support).
- [x] Give the browser port an actual compatibility version instead of returning
  a descriptive label from `Window.getLibraryVersion()`.
- [x] Generate a human-readable compatibility table from the contract.
- [x] Compile generated interactive reference examples with the browser
  compiler. Distinguish code examples from runnable examples when an API is
  unavailable, and explain availability clearly.
- [x] Make parity-fixture checks strict in CI when a required checkout is missing.

Acceptance: a newly documented API cannot silently become a broken embedded
example; intentional desktop limitations are visible; compatibility information
is available to documentation, Studio, and support reports.

### 1.3 Close the current 5.7.0 browser gaps

- [x] Port clone behavior: preserve the student's subclass and instance fields,
  initialize independent mutable runtime state, register the clone on the
  original stage, call `whenStartsAsClone`, and support clone deletion/status.
- [x] Port stage/sprite variable monitors with callable value suppliers.
- [x] Port game pause/resume/step/speed and game-time semantics consistently
  across timers, gliding, animation, and timed speech.
- [x] Test behavior in a real browser/WebGL environment as well as unit tests.
- [x] Add matched behavioral probes for clones, timers, broadcasts, and sprite
  removal; compare behavior and geometry across runtimes, allowing renderer
  differences where appropriate.
- [x] Review the additional overload gaps exposed by the contract, including
  Pen/Text constructors, the Shaders copy constructor, Color.hashCode, and the
  HtmlColor/Sorting constructors. Keep individual examples runnable only when
  their used overloads compile and their demonstrated behavior is supported.

Acceptance: the current 13 incompatible reference pages compile and their
demonstrated behaviors work in the browser; clone fields and event order match
the library; stepping advances one game frame while preserving input/drawing.

### 1.4 Packaged applications

- [ ] Smoke-test starting the packaged Studio and running a first project on
  each supported operating system, complementing existing export tests.
- [x] Verify offline creation/running with the bundled runtime and both library
  flavors, and verify recovery after an interrupted save.

## Milestone 2 — Transfer a student's project between environments

Depends on the compatibility contract and publishing checks.

- [x] Specify a portable ZIP with ordinary Java files, assets, and optional
  metadata: schema version, start class, required library version/flavor,
  required browser features, and lesson/checkpoint identity.
- [x] Reuse or extend Studio's optional metadata where practical; support older
  projects through adapters rather than requiring a new proprietary source format.
- [x] Add embedded IDE workspace-to-project ZIP export and project ZIP import.
- [x] Add Studio workspace JSON/project import and browser-compatible export.
- [x] Preserve imports, filenames, folders, assets, supported settings, and entry
  points. Decode binary assets into real files and record external dependencies.
- [x] Handle unsupported APIs, library flavors, assets, and malformed archives
  with useful messages and recoverable behavior.
- [x] Update curriculum checkpoints to use the supported adapters.

Acceptance: edit a Spielwerkstatt project in the browser, continue it in Studio,
then reopen it in the browser with its code and assets preserved. Test standard
and NRW projects, custom image/audio files, shaders, and compact source files.


## Milestone 3 — Shared release metadata and example catalog

- [x] Publish API, asset, and example catalogs with each library release.
- [x] Use Javadoc/doclet output as the source for API signatures, documentation
  identifiers, Scratch block mappings, version requirements, and availability.
- [x] Preserve translated descriptions through stable identifiers and explicit
  translation overrides.
- [x] Consume the shared catalogs in Studio, the browser port, and documentation.
- [x] Make Studio help/palette entries reflect the project's chosen library.
- [x] Replace manual source-copy assumptions with versioned artifacts and
  freshness checks. Preserve readable generated diffs and offline distribution.

Acceptance: a library API/example/asset change propagates through consumers or
fails a required check; a project pinned to an older library gets appropriate
help; consumers do not require a particular sibling checkout layout.


## Milestone 4 — Coherent onboarding and one classroom course

- [x] Introduce three entry points: try in the browser, create with Studio, or
  use an existing Java IDE. Reuse the same first project and vocabulary.
- [x] Update Setup and Differences to Scratch to describe current ecosystem
  features and the frame-based Java execution model accurately.
- [x] Explain Java 17 library compatibility versus Java 25 compact source/Studio
  requirements and explain standard versus NRW selection.
- [x] Add German getting-started material linked directly to Hyperbook Informatik.
- [x] Keep published documentation, downloads, and compatibility information
  aligned with tested releases.
- [x] Assemble a course pack from the seven introductory tutorials and selected
  Spielwerkstatt activities: starters, checkpoints, assets, teacher notes,
  assessment criteria, and tested version requirements.
- [x] Support importing the course pack into Studio and working offline.
- [ ] Pilot installation, first run, saving, transfer, and recovery with students
  and teachers; record observed friction and prioritize fixes from those results.

Acceptance: a teacher can prepare the course on a school computer and students
can complete the first task, save/recover work, and continue on another supported
environment. Existing reflection activities remain part of the course.


## Milestone 5 — Portable teaching tests and guided Scratch migration

- [x] Specify reusable behavioral checks with browser and desktop JUnit adapters.
- [x] Use existing headless costume support, seeded randomness, and frame
  control for deterministic checks of movement, collisions, score, and clones.
- [x] Make curriculum test projects usable in Studio/desktop workflows instead
  of excluding browser-format test classes from desktop validation.
- [x] Present Scratch import warnings as clickable migration tasks connected to
  the original block and generated Java.
- [x] Explain changed timing and unsupported blocks with lessons on `run()`,
  timers, shared state, and sequencing. Preserve original information.

Acceptance: students can transfer a project with its behavioral checks and run
them in both environments; importing a project with a waiting/concurrent Scratch
script produces an understandable migration task rather than an unnoticed
behavior change.


## Implementation tracking

Update this section and the checkboxes after each validated implementation batch.
Record exact commands, relevant test counts, remaining failures, and any scope
changes. A skipped or incomplete check is not a passing check.

### Batch 1 — Publishing gates and lesson discovery

Status: implemented and locally validated. Milestone 1 remains in progress.

Implemented in `online-ide`:

- Shared Java/assembly fixture loading now rejects missing or malformed inputs.
  Compilation diagnostics, missing expected errors, runtime exceptions, Java
  assertions, unreached assertions, and output mismatches fail explicitly.
  Empty expected output is checked and blank output lines are preserved.
- The interpreter has a bounded execution loop and rejects asynchronous waits
  in synchronous fixtures. The original stall was caused by a shader constructor
  loading files asynchronously in a fixture that still called it desktop-only.
  Its signatures remain compiled; two dedicated asynchronous constructor tests
  cover successful loading and failure reporting. Real WebGL shader execution
  remains part of later browser validation.
- Removed mutation of `context.task.fails`: in Vitest this marks an expected
  failure and can invert the result. Seven harness regressions exercise failure
  paths, including an endless Java loop.
- `npm run test:ci` runs the whole suite without watch mode, with bounded workers.
  Pull-request CI checks the suite and embedded build. Embedded release and
  playground deployment jobs now run the same suite before their builds.

Implemented in `hyperbook-informatik`:

- Pinned Hyperbook 0.111.3 and Playwright Core 1.62.1 with an npm lockfile,
  documented local/CI setup, and local dependency lookup before the legacy
  `/tmp/pw` fallback. The orchestration script uses the pinned npm commands.
- Pages publishing now sets up Node, Python, and JDK 25, installs via `npm ci`,
  and requires `npm run check:java` before building.
- Java browser discovery walks all generated lesson HTML, including glossary,
  AI, and projects. Internal Hyperbook asset templates are excluded. Missing or
  empty builds fail explicitly. Two discovery tests cover new project folders,
  Spielwerkstatt, class matching, internal assets, and missing builds.
- Browser checks wait for actual compiler-result rows instead of fixed delays,
  read error categories independently of interface language, and reject HTTP
  failures, pages without Java blocks, and blocks without compiler results.

Validation performed:

| Repository | Command | Result |
| --- | --- | --- |
| `online-ide` | `npm run test:ci` | 193 tests in 18 files passed, including 102 Java fixtures, one assembly fixture, seven harness regressions, and two async shader regressions |
| `online-ide` | `node node_modules/typescript/bin/tsc --noEmit` | passed |
| `online-ide` | `npm run build-embedded` | passed; existing bundle-size warning remains |
| `hyperbook-informatik` | `npm ci --cache /tmp/scratch4j-ecosystem-npm-cache` | pinned dependencies installed successfully |
| `hyperbook-informatik` | `PYTHONDONTWRITEBYTECODE=1 npm run check:java` | 158 Java lesson pages, 18 desktop archives, and two discovery tests passed |
| `hyperbook-informatik` | `npm run build` | passed; existing warning in a Python/Turtle exercise remains |
| `hyperbook-informatik` | `node tools/java-lernpfad/pruefe_seiten.js --liste` | discovered 168 pages, including all four Spielwerkstatt pages |
| `hyperbook-informatik` | `HYPERBOOK_URL=http://127.0.0.1:4183 npm run check:java:browser -- projekte/spielwerkstatt/00-werkstatt.html oberstufe/oop/01-grundlagen/01-erste-schritte/01-das-erste-programm.html glossary/boolean.html` | three pages/five blocks passed in Chromium, including a page with an intentionally invalid example |
| Both changed repositories | `git diff --check` | passed |

Limitations at the end of Batch 1 (superseded where addressed in Batch 2):

- Full interactive compilation/execution across all 168 discovered pages has
  not yet been run. The publishing gate covers Java static checks and desktop
  archives; the full browser lesson check is not yet a required CI gate.
- The wider static run (`python3 tools/pruefe-alles.py --schnell --ausfuehrlich`)
  passed nine checks and failed `tools/check_permaids.py` on two existing long
  spreadsheet lesson permaids. Preserve published links while resolving this:
  `mittelstufe-calc-zwischencheck-mittelwerte` (42 characters) and
  `mittelstufe-calc-zwischencheck-gruppenrabatt` (44 characters). Browser/build
  checks intentionally skipped by `--schnell` are not passing checks.
- GitHub-hosted workflows have been edited and their local commands validated;
  remote workflow runs have not been triggered.
- API availability/version contracts, the 5.7.0 browser API gaps, portable
  project transfer, packaged application checks, and classroom pilots remain
  unchecked in the milestones above.

Planned next batch at the end of Batch 1: complete the browser lesson publishing coverage, then implement
Milestone 1.2's compatibility contract and generated reference compilation.

### Batch 2 — Full curriculum gate and compatibility contract

Status: implemented and locally validated. Milestones 1.1 and 1.2 are complete;
behavioral parity work in Milestone 1.3 remains.

Implemented in `hyperbook-informatik`:

- Builds remove stale `.hyperbook/out` output before invoking the pinned CLI.
  The earlier 168-page discovery included deleted HTML. A fresh build contains
  **141 Java pages and 331 embedded blocks**, including Spielwerkstatt.
- The complete browser run exposed four invalid blocks. Messenger's Brot and
  Flaecheninhalt starters now declare the types/constructor used by their main
  programs while retaining exercise TODOs. OneMax builds its repeated target
  with a character array supported in both Java runtimes. The protected static
  field probe uses a subclass and documents the browser package-access limit.
- The two existing long spreadsheet permaids remain valid published links via
  an exact allowlist; new permaids retain the 40-character limit and all other
  checks still apply. `--statisch` selects the static suite with a normal success
  exit code; `--schnell` continues to report intentionally skipped checks.
- Pages publishing requires all ten static checks, a fresh build and compilation
  of every embedded Java page. CI installs OpenSCAD, a pinned BOSL2 revision and
  the pinned Chromium. `--serve` supplies and closes a temporary local server.
- Local development and publishing commands are documented in both READMEs.

Implemented in `scratch-for-java` and `online-ide`:

- The Javadoc doclet emits structured owners and overloads. The generated
  versioned contract covers **467 documented API groups**: 398 implemented by
  declaration coverage, 48 desktop-only, and 21 awaiting browser support.
  It records full desktop parameter/return types, missing browser signatures,
  reviewed semantic exceptions, standard/NRW library selection and limitations.
- `Window.getLibraryVersion()` reports **5.7.0-browser.1**, separately from the
  desktop library's 5.7.0. The contract is included in browser build artifacts
  and documentation assets. A generated compatibility table links each API.
- All **408 reference examples** are compiled by the browser test suite. The 13
  known failures have explicit diagnostic IDs/ranges and reviewed availability
  reasons. New diagnostics and resolved exceptions both fail until reviewed.
  Both flavors' declaration inventories are checked against current code.
- Documentation renders 18 unavailable examples as readable source, including
  compiling clone examples whose lifecycle is incomplete and intentional
  desktop operations. The other 390 retain their interactive runners. A page
  with missing overloads can still run an example using supported overloads.
- Library CI and documentation publishing require a browser checkout, artifact
  freshness, strict parity-fixture checks and reference compilation. Scripts
  accept an explicit checkout path. Standalone documentation generation uses
  the committed browser snapshot; ordinary browser tests need no sibling repo.

Validation performed:

| Repository | Command/check | Result |
| --- | --- | --- |
| `hyperbook-informatik` | `PYTHONDONTWRITEBYTECODE=1 npm run check:static` | ten static checks and two discovery tests passed; local OpenSCAD compilation included |
| `hyperbook-informatik` | `npm run build` | fresh build passed |
| `hyperbook-informatik` | `npm run check:java:browser -- --serve` | all 141 pages / 331 blocks passed in Chromium |
| `hyperbook-informatik` | temporary-server check of a nonexistent page | failed with exit 1 and closed the server/browser |
| `online-ide` | `npm run test:ci` | 604 tests in 19 files passed, including 408 reference compilations |
| `online-ide` | `node node_modules/typescript/bin/tsc --noEmit` | passed |
| `online-ide` | `npm run build-embedded` | passed; published contract matches the canonical artifact |
| `scratch-for-java` | `mvn -q -DskipTests prepare-package` | doclet metadata generation passed |
| `scratch-for-java` | `mvn -q test` | 225 tests passed, zero skipped |
| `scratch-for-java` | `python3 scripts/browser-compatibility.py --check --online-ide ../online-ide` | all generated artifacts current |
| `scratch-for-java` | `python3 scripts/parity-fixture.py --check --strict --online-ide ../online-ide` | current |
| `scratch-for-java` | documentation build using installed Hyperbook 0.111.3 | passed; inspected unavailable pages have source and no Java runner, supported pages retain runners |
| Compatibility/parity scripts | missing checkouts and a deliberately stale temporary fixture | fail as required, including automatic strictness under `CI=true`; read-only check preserves the stale file |
| All three changed repositories | `git diff --check` | passed |

Limitations and next work:

- Availability is a documented source/declaration inventory with explicit
  semantic exceptions, not proof of complete behavioral or binary parity.
  Reference compilation currently targets the standard flavor; existing NRW
  runtime fixtures and its separate declaration inventory cover NRW.
- Clone lifecycle, variable monitors and pause/game-time speed remain unported.
  Additional constructor/overload gaps are now visible in the contract.
- Curriculum Java checks exercise the IDE bundled with pinned Hyperbook;
  reference tests exercise this `online-ide` checkout. Updating browser behavior
  will also require propagating its tested artifact into Hyperbook.
- SQL/Web browser checks remain available in `npm run check` but are not yet
  Pages publishing gates. Existing bundle-size and Python/Turtle build warnings
  remain. GitHub-hosted workflow execution has not been triggered.
- The shared library CI action checks `openpatch/online-ide`'s default branch.
  These changes require coordinated merges: the synchronized browser artifacts
  must reach that branch before the library freshness gate can pass remotely.

Next batch: Milestone 1.3, starting with matched clone lifecycle behavior and
tests, then variable monitors and consistent pause/game-time speed semantics.

No commits, releases, or deployments are implied by this plan.

### Batch 3 — Browser lifecycle, monitors and game time

Status: implemented and locally validated; Milestone 1.3 complete.

- Browser revision **5.7.0-browser.2** preserves subclass fields in clones,
  independently copies mutable runtime helpers, registers events and calls the
  clone hook synchronously. Deletion detaches before the removal hook and
  permits re-adding an original sprite. Broadcast handlers run in caller order.
- Variable suppliers update drawn monitors, including boxed numbers, arrays and
  failing suppliers. Removed sprites detach monitors; clones start without them.
- A shared game clock controls pause/resume, single stepping and speed. Timers,
  animations, gliding and timed speech use game time. Invalid speeds advance
  zero frames in both runtimes. Extra constructor/overload gaps were ported.
- The canonical Java lifecycle probe runs in desktop Java and actual Chromium
  WebGL for both flavors, with identical printed behavior and checked geometry.
  Browser checks cover paused drawing, supplier failure, seven frame steps,
  timer deadlines and speech removal. CI now requires these checks.
- Committed the browser npm lockfile by removing its ignore rule, enabling
  reproducible npm ci in a fresh checkout.

Validation: browser test suite **613 tests / 21 files** passed; desktop library
**226 tests** passed with zero skipped; TypeScript and embedded build passed;
matched desktop lifecycle and both Chromium flavor checks passed. All 408
reference examples compile with **zero diagnostic exceptions**. The contract
now records 419 implemented and 48 desktop-only groups, with
zero awaiting browser support. Compilation is not certification of every
reference example's full interactive behavior. Cross-platform packaged Studio
checks and the classroom pilot remain separate work.

The subsequent batches completed project transfer, shared catalogs, the offline
course, portable teaching tests and guided Scratch migration.


### Batch 4 — Portable project transfer

Status: implemented and validated; Milestone 2 complete.

- Specified schema-1 ZIP/metadata in `scratch-for-java/compatibility/portable-project.md`.
  Java sources and image/audio/font/shader assets are ordinary files. Library
  flavor/version, entry point, lesson, checkpoint and external dependencies travel
  in optional metadata; unknown Studio settings are retained.
- The embedded IDE imports/exports ZIPs through its actual UI and stores an atomic
  workspace snapshot for reload recovery. Invalid archives fail before replacement.
  Path, CRC, file/count/size and metadata checks have unit coverage.
- Studio imports legacy workspace JSON and portable ZIPs through staging folders,
  decodes binary assets, adapts implicit browser imports and compact mains, and
  exports browser-compatible ZIPs without bundled JARs/caches. Course-provided NRW
  sources are retained without edits and do not conflict with browser NRW classes.
- All 14 Spielwerkstatt checkpoint JSONs include source, assets and metadata;
  generated ZIP downloads use the same files. Repeated imports dispose old Monaco
  models. Sources, custom image/audio, shaders, fonts and nested/empty folders have
  round-trip coverage. Legacy spritesheets and unsupported dependencies produce
  explicit messages instead of disappearing.
- A real edited score checkpoint travels browser > Studio > browser: viewport
  changes survive, every ordinary source body/existing import is preserved, NRW
  source and asset bytes are identical, and four transferred tests pass in Studio.
  This scenario is now required in Studio Linux CI.

Commands/results:

- `online-ide`: `npm run test:browser` — Chromium lifecycle plus actual embedded
  import/edit/cache/reload/export/rejected-archive checks pass. With
  `SCRATCH_CHECKPOINT_DIR=../hyperbook-informatik/book/projekte/spielwerkstatt/checkpoints`,
  all 14 actual checkpoints also compile through the embedded UI.
- `online-ide`: `node scripts/test-curriculum-transfer.mjs
  ../hyperbook-informatik/book/projekte/spielwerkstatt/checkpoints/spielwerkstatt-q-07-testen.json
  ../scratch-for-java-ide` — full edited checkpoint round trip and transferred
  checks pass (use `SCRATCH_TRANSFER_LIBRARIES` for a different bundled JAR folder).
- `scratch-for-java-ide`: `mvn -q -pl export -am test
  -Dtest=ProjectFormatsTest,PortableProjectTest
  -Dsurefire.failIfNoSpecifiedTests=false` — malformed archives, metadata/assets,
  JSON adapters and unmodified NRW source regression pass.

### Batch 5 — Shared catalogs and project-aware help

Status: implemented and validated; Milestone 3 complete.

- Javadoc/doclet metadata drives **564 public overloads**, parameter/return docs,
  Scratch mappings, stable documentation IDs, since tags and availability.
  **441 explicit German overrides** use stable overload identities. Browser
  comments use these overrides with existing translations as fallback.
- Asset catalog: **922 unique atlas images and 266 sounds**. Duplicate resolution
  matches the library registry, including qualified/bare names and geometry.
  Browser atlas geometry and Studio asset/help panels consume the shared files.
  Chromium checks load all 922 atlas frames and verify dimensions after facing normalization.
- Example catalog: **34 projects** with ordinary files and SHA-256 checksums.
  Release ZIPs contain catalogs and examples; library JAR/docs/browser/Studio
  contain offline catalog copies. Consumers need no particular sibling layout.
- Studio uses project-local release metadata first, then an older sources-JAR
  adapter or actual binary overload filtering. Removed newer overloads disappear
  for older projects. Tests cover an older binary and a project-local catalog.
  Binary fallback can only retain documented methods it can resolve; a legacy
  sources JAR/catalog provides fuller historical descriptions.
  Atlas additions use the library registry lookup order automatically. The new
  cat atlas/demo were added during validation: 81 frames are synchronized to the
  browser; Studio filters assets against its pinned runtime and refuses a demo
  whose required atlas is absent, with an actionable library-update message.
- `sync-templates.py --artifact --check` validates the versioned example artifact
  and all **29 bundled templates**. Shader whitespace is normalized. Catalog,
  template and parity freshness are required CI checks; explicit missing checkouts
  fail. Release publishing attaches the catalog/example archive.

Commands/results:

- `scratch-for-java`: `mvn -q -DskipTests prepare-package` — doclet metadata generated.
- `python3 scripts/browser-compatibility.py --check --online-ide ../online-ide` — current.
- `python3 scripts/parity-fixture.py --check --strict --online-ide ../online-ide` — current.
- `python3 scripts/release-catalogs.py --check --studio ../scratch-for-java-ide
  --online-ide ../online-ide` — all copies current.
- `python3 scripts/release-catalogs.py --archive target/scratch-5.7.0-catalogs.zip`
  and Studio `python3 scripts/sync-templates.py --artifact
  ../scratch-for-java/target/scratch-5.7.0-catalogs.zip --check` — all 29 templates current.

### Batch 6 — Onboarding, offline course and packaged Linux validation

Status: implementation complete; Windows/macOS execution and classroom observations pending.

- Setup/first tutorial share browser, Studio and existing-Java-IDE entry points.
  Documentation explains named Java 17 projects, Java 25 Studio/compact files,
  standard/NRW selection, frames, transfer and tested browser revision requirements.
  German onboarding links directly to Hyperbook Informatik. New classroom and
  migration guides are built with the documentation.
- Deterministic course ZIP: **18 projects / 620 files** (seven tutorial starters,
  seven checkpoints and four selected Spielwerkstatt activities), real assets,
  original reflection material, tasks, teacher notes, assessment and a pilot form.
  A standalone library checkout can build the 14-project tutorial subset.
- Studio validates/imports the entire pack atomically, installs the chosen bundled
  library into each folder and permits offline work. All 18 projects compile after
  actual import; the score checkpoint's four JUnit tests pass. CI requires this
  check; release automation assembles the complete course artifact.
- Packaging now chooses the exact pinned standard JAR and requires the NRW JAR.
  The native Linux launcher opens Studio, uses its bundled compiler/runtime/JUnit,
  renders first projects with both flavors, runs costume/movement teaching checks,
  and verifies save/reopen with a simulated orphaned partial temporary write.
  This tests recovery before an atomic rename; it is not an actual killed save process.
- Native smoke checks are wired into Windows, Intel/ARM macOS and Linux packaging.
  Linux passes both the host JDK 25 jlink fallback (`SCRATCH4J_BUNDLE_JBR=0`)
  and the default JetBrains Runtime 25.0.4.1 package. Windows/macOS have not run here.

Commands/results:

- `scratch-for-java`: `python3 scripts/build-course-pack.py --curriculum
  ../hyperbook-informatik --output target/scratch-to-java-course.zip` — complete pack generated.
- Studio: `python3 scripts/check-course-pack.py --library ../scratch-for-java
  --curriculum ../hyperbook-informatik --libraries
  'target/package/Scratch for Java Studio/lib/app/library'` — all 18 imported
  projects compile offline; four score checks pass.
- Studio: `SCRATCH4J_BUNDLE_JBR=0 bash scripts/package-ide.sh app-image`, then
  `xvfb-run -a python3 scripts/smoke-packaged.py 'target/package/Scratch for Java Studio'`
  — native packaged Linux smoke passes with both flavors. Repeating packaging
  with the default settings also passes using bundled JetBrains Runtime 25.0.4.1.
- Library: `PATH=/path/to/hyperbook-informatik/node_modules/.bin:$PATH bash build.sh`
  — reference, onboarding, German, classroom and migration documentation plus the
  tutorial course download build successfully with pinned Hyperbook 0.111.3.

### Batch 7 — Portable behavior checks and Scratch migration tasks

Status: implemented and validated; Milestone 5 complete.

- Schema-1 check manifest and ordinary Java bodies are documented in
  `compatibility/behavioral-checks.md`. The same movement/collision/score/clone/
  seeded-randomness body runs in desktop headless JUnit and real browser WebGL
  with both flavors. Existing clock/lifecycle probes validate actual frame control.
- Studio adapts browser class annotations in memory, bundles JUnit console 1.11.4,
  compiles all project sources and runs checks in a bounded separate JVM. Run >
  Run behavior checks shows results and supports stopping. Source files remain
  unchanged; failures and zero tests fail the run.
- Curriculum desktop validation includes its browser-format test class instead
  of excluding it. Pinned JUnit is SHA-256 verified/cached for offline use. All
  **18 archives** compile, including the test class; **four score tests** execute.
  Unchanged source snapshots execute in the browser interpreter as well, with an
  incorrect-score negative control. Publishing checks snapshot freshness and execution.
- Scratch imports preserve the complete original .sb3 and original block data.
  Clickable migration tasks identify unsupported statements/reporters/events,
  timed sequences and concurrent scripts, linking original block IDs to Java
  markers/lines and lessons on run(), timers, shared state and sequencing.
  Marker locations are recomputed when reopening edited files. Runtime caches
  are excluded from transfers; migration/check metadata and original archives survive.

Final validation summary (local):

| Repository | Command | Result |
| --- | --- | --- |
| Studio | `SCRATCH4J_ALL_JAR=/path/to/pinned-all.jar mvn -q verify` | 300 normal unit tests pass, zero failures/errors/skips; opt-in integration tests are reported separately |
| Library | `mvn -q test` | 228 tests pass, zero failures/errors/skips |
| Browser | `npm run test:ci` | 631 tests / 24 files pass, including 408 reference examples and portable score checks |
| Browser | `node node_modules/typescript/bin/tsc --noEmit` | passes |
| Browser | `npm run build-embedded` | passes; existing bundle-size warning remains |
| Browser | `npm run test:browser` with checkpoint directory | both WebGL flavors and all 14 checkpoint imports pass |
| Curriculum | `PYTHONDONTWRITEBYTECODE=1 npm run check:static` | ten checks and two discovery tests pass |
| Curriculum | `npm run build` | fresh build passes; existing duplicate Python directive warning remains |
| Curriculum | `npm run check:java:browser -- --serve` | 141 pages / 331 blocks compile in Chromium |
| All four repositories | `git diff --check` | passes |
| All changed workflows | YAML parse | passes |

The final Studio verification uses the existing pinned all-JAR explicitly to
avoid a Java URL download that failed to resolve github.com. The local cached
artifact is the same 5.7.0 library, and no tests are skipped.

Validation logs are in `/tmp/scratch-ecosystem-review/`; the complete local course
artifact is `scratch-for-java/target/scratch-to-java-course.zip`. Browser checks
compile/reference-check the current checkout, while curriculum page checks use
pinned Hyperbook's embedded build. Propagating the updated browser into a published
Hyperbook artifact requires coordinated releases. No remote workflows, commits,
releases or deployments were performed.

## Remaining acceptance evidence

| Activity | Prepared implementation | Evidence still needed |
| --- | --- | --- |
| Native Windows/macOS packaged execution | Native launcher/offline standard+NRW smoke in the packaging matrix | Successful real runs on the supported OS/architecture combinations |
| Student/teacher classroom pilot | Complete offline pack, tasks, teacher notes, assessment, observation form | Actual installation/first run/save/transfer/recovery observations, then fixes based on those results |

These remain unchecked because automated Linux repository validation cannot
provide observations from other operating systems or a classroom. All remaining plan work is acceptance evidence and any fixes it reveals.


### Final follow-up — Asset additions during validation

The library gained the cat atlas and CatPlatformer demo while this work was
running. The catalog generator now discovers atlas order from the library
registry and synchronizes PNG bytes, including all 81 cat frames. The browser
loads atlas URLs from Vite's file discovery; geometry, direction and aliases
come from the shared catalog. Freshness checks cover the binary assets too.
Catalog artifacts include PNG/XML/license files as well as examples.

The example catalog now contains 34 projects and the Studio artifact adapter
produces 29 templates. Required atlas metadata stops Studio creating an example
with a bundled library that lacks its artwork. The pinned released 5.7.0 library
does not yet contain the new cat atlas, so Studio's asset index filters it out
and the new demo reports that the library needs updating. The demo's sources
and designer regions compile; runtime preview coverage applies to templates
available in the pinned library. Releasing the new library and updating the
Studio pin enables the new artwork there. Browser WebGL checks cover all 922
catalog textures with both flavors. No unrelated cat artwork/source was edited.

Final rechecks pass with 300 Studio tests, 228 library tests and 631 browser
tests, with no failures/errors/skips. The explicit cached-JAR override avoids a
network-only download in export tests. JetBrains Runtime 25.0.4.1 and the jlink
fallback both passed native Linux smoke. Windows/macOS execution and classroom
observations remain the two external acceptance activities above.

The final teaching-run UI also cancels work started before Stop or a new run,
so a delayed compilation cannot launch an old test process or reset a newer run.
The final Studio package compilation passes after this guard.


## Coordinated release (2026-10-07)

Release work is authorized and in progress. The existing library 5.7.1 release
was merged into the implementation branch. Shared metadata now targets library
5.8.0 and browser 5.8.0-browser.1. Studio is prepared as 0.1.0-alpha.7 and
Hyperbook will embed browser build 26 in 0.112.0. The curriculum will pin that
Hyperbook release.

Release verification found and fixed local .class/.ctxt files being included in
example catalogs; clean CI checkouts now use the same source-only file list.
Studio's legacy API index and registry counts are updated for the new library.
Known boolean costume choices resolve in the designer; loop-computed costume
arguments remain unavailable to static previews. Packaged standard/NRW teaching
checks also load a real cat costume.

Local release validation: 301 Studio tests, 228 library tests, 631 browser tests,
all 18 offline course projects and four course behavior checks pass. The native
Linux package smoke passes with library 5.8.0. The initial browser CI run passes;
final release workflows and cross-platform smoke evidence are still pending.

The plan is also tracked in Studio at docs/ecosystem-plan.md.
