# Learning log data path (schema 1)

Decision, October 2026. This note precedes the logging implementation.

The teacher opts a project into logging with `researchLog: true` in
`.scratch4j/project.json`, or at the top level of `.scratch4j/course.json`.
Course flags are copied to each imported project's settings. The default is off.
`hintsOnly` is also a project/course flag. It hides automatic fix actions.

An opted-in session writes only local JSON Lines to `.scratch4j/log/events.jsonl`.
Each line has `v`, UTC `t`, a random `session`, `type`, and allowlisted event data.
The stream rotates at 2 MiB, retaining three previous segments. Reports explicitly
state the observed run count: rotated-away history cannot be reconstructed.
There is no network service or automatic upload.

A random pseudonymous participant ID is created once in the OS user's settings
directory (`scratch4j.settingsDir` when configured, otherwise
`~/.config/scratch4j`; on Windows `%APPDATA%/Scratch4J`, on macOS
`~/Library/Application Support/Scratch4J`). It is never project metadata.
On shared OS accounts the hand-in dialog asks for a teacher-assigned pseudonym
code. It replaces the account ID in the submitted copy only. Teachers must use
one code per student; otherwise shared accounts cannot be separated. A portable
settings directory shared by students is treated as a shared account too.

Hand-in is a separate `.jsonl` file outside the project folder, chosen explicitly
by the student. It includes
all retained segments in order. It is never an option implicitly enabled by ZIP
export. Share, browser, BlueJ and VS Code exports exclude every `.scratch4j/log`
subtree, including logs inside course projects. Student app exports already skip
`.scratch4j`. A student can inspect or delete the entire retained local stream.
Deletion pauses logging until the project is opened again.

No source, typed text, file or class names, exception messages, friendly titles,
javac messages, assertion values or lesson titles are recorded.
File kinds are stage/sprite/window/other. Compiler and lint case keys, exception
classes, line numbers and fixed action IDs are safe metadata. Teacher-provided
lesson/step IDs remain readable identifiers; free-form IDs fall back to stable
SHA-256 hashes. Palette IDs are hashed; code inserted by a block is never logged.
Behavior check names are teacher-authored labels and are recorded for the report.
The account ID is pseudonymous, not anonymous; treat submitted files accordingly.

Keyboard edits are counts of key events that actually change the code, including
keyboard paste/delete/indent actions. Navigation, file reloads and palette
insertions do not count as typed edits. Counts are aggregated by file kind and
flushed on a run, inspection, hand-in or session end as `edit` events. No key,
character or changed text is passed to the log. Mouse-only edits are outside
this keyboard metric.

The class report reads explicitly handed-in logs from a selected folder. It
computes error frequencies, failed-run quotient, observed time to the next
successful run, step durations, failed checks and help patterns. Time intervals
are paired within participant/session, never across sessions. Incomplete pairs are omitted.
A nudge outcome is the next observed run; this association is not a causal claim.
Metrics and reports never rewrite the original event stream. Reports are copied
to the teacher's clipboard, not sent to a service.

Consent, retention of handed-in files and any link between pseudonym codes and
students are the school's responsibility, outside this IDE.
