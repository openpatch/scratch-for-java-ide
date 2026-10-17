# Learner and teacher support: plan

Status: draft, October 2026.

Clearer error messages, hints that reveal step by step, behavior checks that
explain themselves, and an opt-in research log that teachers can turn into a
class report. Each feature lists what the IDE already does, so the work items
are only what is left.

## F1: Compiler-error explanations

`FriendlyErrors` + `explanations_en/de.properties` (core, `lint`).

Already in place: `=` vs `==` in a condition, missing return, premature end of
file, "did you mean" for unknown names (project and library names, in
`ProjectCheck`), follow-up errors marked after a syntax error, and an EN/DE key
parity test (`FriendlyErrorsTest`).

- **Snippet corpus** in `core/src/test/resources/lint/snippets/`. Each entry
  holds the broken code, the fixed code and the expected friendly case key. One
  format serves three uses: the coverage harness, the level-3 examples in F3,
  and the common-errors handout. Define it in milestone 1 so it doesn't need
  rewriting later.
- **Coverage harness:** `FriendlyErrorsCoverageTest` measures which mistakes
  still fall through to javac's raw message. The gap list comes from this
  measurement, not from guesses.
- **Known gap:** mismatched brackets, with the line of the opening bracket in
  the headline.
- **Follow-up errors:** collapse them under the first structural error in
  `ProblemsPane` instead of only fading them.
- **Common-errors handout** for teachers, generated from the corpus: each
  mistake with the broken line, the friendly explanation and the fix, in EN and
  DE, as a printable page for the first Java lesson or the projector.

## F2: Runtime exception explanations

`RuntimeErrors` → `CrashWatcher` → Problems pane and console.

Already in place: NullPointerException (naming the null variable), index out of
bounds (including the empty-list case), `/ by zero`,
ConcurrentModificationException, StackOverflowError (naming the method that
calls itself), NumberFormatException, ClassCastException and more. Each crash
gets a card in the Problems pane and the console, and the editor jumps to the
line.

- **Texts into the bundle:** move the hard-coded EN/DE strings out of
  `RuntimeErrors.java` into the explanations bundle (`runtime.<exception>.title`
  and so on), and extend the parity test to cover those keys.
- **Loop and array case:** "the counter went to 5, the last position is 4",
  built from "Index 5 out of bounds for length 5".
- **Card layout:** what happened in the student's words, one concrete action,
  the student's file and line, and a link from the card to the raw trace in the
  console.

## F3: Progressive hints

- **Ladder:** level 1 orients (the explanation), level 2 locates (the line and
  what to look for), level 3 shows an analogous example from the corpus or a
  preview of the fix. Applying a fix stays an explicit click, as it is today.
- **Placement:** a Hint button before Fix in `ProblemsPane` and in the gutter.
- **Lesson steps:** `Lesson.Step` gets per-step `hints` (EN/DE), edited in
  `LessonEditorDialog` and shown as the ladder for the current step. A step
  that doesn't tick off also shows what is still missing, generated from its
  check where possible: `classExists` gives "No class extends Sprite yet",
  `methodExists` gives "No method `whenClicked` yet". A regex `contains` check
  can't be explained automatically, so those steps rely on the teacher's hints.
- **Stuck nudge:** a fixed rule, not adaptive logic. If the same error survives
  3 runs, or a lesson step makes no progress across several runs, the Hint
  button is highlighted. It never opens a hint by itself.
- **Teacher flag:** `hintsOnly` in `ProjectSettings`, so it travels with the
  project the teacher hands out. Not in `Prefs`, which is per machine.
- A fixed ladder per error class; no adaptive logic yet.

## F4: Behavior-check feedback

Today Run → behavior checks runs JUnit through `TeachingTestRunner`, prints the
raw output into the console, and the status bar only says "Behavior checks:
exit code 1".

- **Structured results:** the check process prints one structured line per test
  from a small JUnit listener, instead of the IDE parsing JUnit's text output.
- **Checklist** in the Problems pane: a tick or a cross per check, named by the
  teacher's `@DisplayName`.
- **Failures in plain words** ("expected score 5, got 4"), jumping to the
  student's line by reusing the frame parsing in `RuntimeErrors`. The raw output
  stays in the console.

## F5: Research log

One event stream, opt-in, local only.

- **Storage:** `EventLog` in `core/io` writes `.scratch4j/log/events.jsonl`.
  Each line is one event with `v` (schema version), `t` (timestamp), `session`
  and `type`. Append only, with a size cap and rotation.
- **Events:**
  - `session.start` / `session.end`: lesson id, language, IDE version
  - `run`: success, error count, attempt number. A Run click is the compile
    event; the background check runs 250 ms after every change and doesn't
    count.
  - `errors.changed`: logged only when the set of errors changes; case keys,
    javac codes and line numbers
  - `crash`: exception class, friendly case key, line
  - `check`: behavior check name, passed or failed
  - `hint`: level, case key or lesson step
  - `nudge`: the stuck rule fired
  - `help`: explanation expanded, "did you mean" used, fix applied or
    dismissed, API help opened
  - `lesson.step`: started or completed, emitted from the existing
    `LessonProgress`
  - `view`: switches between CODE, SPLIT and VISUAL
  - `block.insert`: palette block id, to compare against typed edits (counted,
    never stored as text)
  - `transition.lint`: `TransitionLinter` finding keys, i.e. Scratch habits in
    Java code
- **Derived when the report is built, never stored:** whether the next run
  succeeded, time to fix, error quotient (based on runs), time per lesson step,
  help-seeking patterns, whether nudges help.
- **Pseudonymous ID:** created once per OS user account, in the settings
  directory. Never written to `project.json`, which travels with share zips,
  browser zips and course packs.
- **Data minimisation:** no source code, no friendly titles and no javac
  messages, because both contain student identifiers. No file names either;
  only the file's kind (stage, sprite, window, other, from `VisualMode.Kind`).
- **Excluded from exports:** add `.scratch4j/log` to
  `ProjectFormats.generated()`, which filters the share, browser, BlueJ and
  VS Code zips, with a test for each. `StudentExport` already skips
  `.scratch4j`.
- **Opt-in:** a `researchLog` teacher flag in `ProjectSettings` / `course.json`
  of the project the teacher hands out. A visible "logging on" indicator in the
  status bar. Students can open or delete their log.
- **Hand-in:** an explicit "Hand in learning log" action that writes a separate
  file for the student to submit. It is never bundled into a share zip by
  default.
- **Class report:** the teacher points "Copy class report" at a folder of
  handed-in logs. It produces an error frequency table, error quotient, time per
  lesson step, the behavior checks that fail most, and help patterns.
- **Privacy note** in the README: what is logged, what never is, where it is
  stored and how hand-in works. Consent is collected by the school, outside the
  IDE.

## Milestones

| # | Deliverable | Notes |
|---|---|---|
| 1 | Snippet corpus format, coverage harness, measured gap list | test only, no product change |
| 2 | F1 gaps (brackets and whatever milestone 1 finds), collapsed follow-up errors, runtime texts in the bundle, loop case, parity test for runtime keys | mostly finishing work already started |
| 3 | Common-errors handout generated from the corpus | small once the corpus exists |
| 4 | F4 behavior-check feedback | reuses the crash card and frame parsing |
| 5 | Data-path decision note in `docs/`, README privacy section drafted | no code; can be written alongside 1–4; blocks every logging change |
| 6 | `EventLog` core; `session`, `run`, `errors.changed`, `crash`, `check` events; `researchLog` flag and indicator; export exclusion with tests for all four zip formats | privacy section published with this |
| 7 | F3 hint ladder, lesson-step hints and "still missing", stuck nudge, `hintsOnly` flag; `hint` and `nudge` events | hint use measured from the first deployment |
| 8 | `help`, `lesson.step`, `view`, `block.insert`, `transition.lint` events; hand-in action; class report with derived metrics | |

Why this order: explanations and behavior-check feedback ship student value
first, and milestone 2 is small because most of F1 and F2 already exists. The
data path is decided before any logging code, because the hand-in route, where
the ID lives and who controls the flag change what the core looks like. The log
core lands before hints, so hint use is measured from the start. The report
comes last and computes its metrics from the event stream, so nothing is
rewritten in place.

## Decisions

Resolved: the research log is **opt-in**. The teacher turns it on in the
project or course pack they hand out, so students don't have to do anything.

Open, for the milestone 5 note (default in brackets):

- **Shared class accounts:** if a computer room uses one OS login for several
  students, a per-account ID mixes them together. [Hand-in asks for a pseudonym
  code the teacher assigns, used only when the account is shared.]
- **Hand-in format:** a separate log file, or an "include learning log" option
  on the share zip. [Separate file, so including the log is always deliberate.]

Deferred: AI-generated hints and a live classroom dashboard, until the logs
show where students actually get stuck.
