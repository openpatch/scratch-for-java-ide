# Plain Java projects

**Status: plan.** Nothing here is implemented yet.

Studio should also open, edit, run and debug Java projects that do not use
Scratch for Java. Scratch for Java projects keep working exactly as they do
today.

## Principles

- **Beginners first.** Plain projects are for students who move on from
  Scratch for Java or start Java without it. There is nothing to set up: no
  build files, no classpath settings, no run configurations.
- **The same layout as Scratch for Java projects.** The classes sit in the
  project folder (no packages), jars go into `+libs/`. BlueJ and VS Code open
  these projects the same way they open Scratch for Java projects.
- **One default class.** Run always starts the same class. When several
  classes have a `main`, the student picks the default class once.
- **Friendly messages** in English and German, as for Scratch for Java
  projects.

## Scope of the first version

In:

- A flat project folder, classes in the default package, jars in `+libs/`.
- Console programs that read from `System.in`.
- Swing and AWT programs (they run in their own JVM like every program).

Later, in its own pull request: opening projects that VS Code created, with
`src/`, packages and `lib/` (see [Later](#later-projects-from-vs-code)).
Studio itself never creates such projects.

Not planned:

- Maven and Gradle projects (`pom.xml`, `build.gradle`).
- `module-info.java` and JavaFX programs.
- The app folder, BlueJ, VS Code and browser exports of plain projects.
- Console input for Scratch for Java programs.

## The project kind

A new `ProjectKind` (`SCRATCH`, `JAVA`) in `core/project`, stored as `kind`
(`"scratch"`, `"java"`) in `.scratch4j/project.json` and read with
`ScratchProject.kind()`. Every change below checks it.

When `project.json` has no kind, the IDE detects it on open:

1. A `scratch-*.jar` in `+libs` → Scratch for Java.
2. A source that imports `org.openpatch.scratch` (also `import
   org.openpatch.scratch.*`) → Scratch for Java.
3. No Java sources at all → Scratch for Java. This keeps today's behaviour:
   the IDE offers to add the library to an empty folder.
4. Otherwise → plain Java.

The detected kind is saved right away (a read-only folder just skips the
save), so it does not change later, for example when a student deletes the
last stage. A menu item **Project type** (Scratch for Java / plain Java)
corrects a wrong guess, like the Standard/NRW switch does for the flavour.

Templates, lessons and `.sb3` imports create Scratch for Java projects. The
new Java template writes `"kind": "java"`. Older Studio versions keep unknown
fields (`ProjectSettings.extra`), so the field survives opening a project in
an older version.

## The default class

Run, Debug and the JAR export start the project's default class. It is kept
in the existing `startStage` field of `project.json`, which Scratch for Java
projects already use for their start class, so the file format does not
change.

- **One class with `main`:** that class. Nothing to choose.
- **Several classes with `main`, none chosen yet** (or the chosen one lost
  its `main`): the first Run asks once, "Which class should Run start?", with
  the classes as a list and `Main` preselected if there is one. The answer is
  saved.
- **Changing it:** in the Files tab a star marks the default class. Right-click
  a class with `main` → **Set as default class**.
- **Always visible:** the Run button's tooltip and the status bar name the
  class ("Run Main").
- **No class with `main`:** a message explains what `main` is, with a quick
  fix that adds `public static void main(String[] args) { }` to the open class.

`ScratchProject.startStage()` already prefers the saved class and otherwise
takes the first class with `main` (`Main` first). For plain projects it
returns nothing instead of guessing when there are several, and the UI asks.

## Work

### 1. Project model (`core`)

- `ProjectKind` and its detection, as above.
- `ScratchProject.mainClasses()` becomes public: the default-class question
  and the Files tab star need the list.
- `ScratchProject.startStage()` for plain projects, as described under
  [The default class](#the-default-class).
- `libs()`, `javaSources()` and `sourceOf()` need no change: plain projects
  use the same layout as Scratch for Java projects.

### 2. Running (`runner`)

- `LauncherSource` gets a second launcher for plain projects, without any
  Scratch for Java import. It keeps the class name `Scratch4JLauncher`, so the
  run command and the JAR's `Main-Class` stay as they are. It:
  - calls the default class's `main` like today's launcher does (static or
    not, with or without `String[] args`);
  - rethrows the program's own exception instead of the
    `InvocationTargetException` from the reflective call, without the
    launcher's own lines, so the console shows what `java Main` would show;
  - when started by the IDE (`-Dscratch4j.ide.input=true`), wraps
    `System.in` and reports on the control line (stderr, as today) when the
    program waits for input. An exported JAR does not report anything.
- `ProjectRunner.run` picks the launcher by project kind and leaves out
  `-Dscratch4j.capture*` and the control channel for plain projects: their
  stdin belongs to the program. The debug agent and hot reload stay.
- `RunHandle` gets `input(String line)` and `closeInput()`; `send()` already
  writes to the program's stdin. A new `RunListener.onInputWanted(boolean)`
  carries the "waiting for input" report.

### 3. Console input (`ui`)

- `ConsoleView` gets an input line under the output while a plain program
  runs, with the placeholder "Type here and press Enter". Enter sends the
  line and shows it in the console in its own style.
- When the program waits for input, the input line is highlighted and
  focused, and the status bar says "Your program is waiting for input". This
  answers the most common "my program hangs" question.
- Ctrl+D closes `System.in`, for loops like `while (scanner.hasNextLine())`.
- Scratch for Java runs keep the input line hidden: their stdin is the IDE's
  control channel (`IdeControl` in `LauncherSource`).

### 4. Opening and running in the UI (`ui`, mostly `StudioApp`)

- `open()`: no `checkLibrary()` for plain projects. Today it asks to add the
  Scratch for Java jar on every open, because declining only remembers a jar
  that has a version.
- `openStart()`: opens the default class, otherwise the first class.
- `runProgram()` and `debugProgram()`: start the default class and ask for it
  when needed. `run.nostage` ("no Stage class yet") becomes the `main`
  explanation for plain projects.
- `runStage()` for plain runs: no `watchdog()`, which would report every
  console program that runs longer than 10 seconds as frozen ("no window").
  The progress bar ends when the process has started. An exit code of 0
  counts as a working run for `keepWorkingVersion()`. The Variables tab
  (sprite monitors over the control channel) stays hidden; the debugger's
  variables work as before.

### 5. Creating projects and classes (`core`, `ui`)

- `ProjectTemplate.JAVA` and `NewProject` write the same files as a Scratch
  for Java project, without the library, `assets/` and `MyWindow`:

  ```
  Main.java
  README.md
  +libs/
  .scratch4j/project.json     { "kind": "java", "startStage": "Main", ... }
  ```

  `Main.java` is a short program that asks for the student's name with
  `Scanner` and greets them, so the console input shows on the first run.
  `NewProjectDialog` gets a card for it.
- `NewClass.Kind.CLASS`: a plain `public class Name { }` in the project
  folder, with a checkbox **with main method**. For plain projects
  `NewClassDialog` offers only this kind.
- `AddToProjectDialog` offers Class, Folder, Image and Sound for plain
  projects; not the asset library, shaders or maps.
- Dropping a `.jar` on the Files pane copies it into `+libs/`, as images and
  sounds are copied today.

### 6. Hiding what only Scratch for Java needs (`ui`, `export`)

- The Stages sidebar tab is hidden and the Files tab selected.
- The Sprite editor button in the sidebar.
- The Standard/NRW choice, the library update check and the Abiturklassen
  import.
- Export offers only the runnable JAR (`ProjectFormats.exportRunnableJar`).
  It already merges the jars from `+libs`; it has to generate the plain
  launcher, and its "no Stage class to start" error becomes the `main`
  explanation. The other exports stay hidden for now.

### 7. Beginner messages (`core`)

- `RuntimeErrors` explains `InputMismatchException`: "You typed text where
  the program expected a number", with the hint to check with
  `hasNextInt()` first.
- `RuntimeErrors` has two hints written for Scratch for Java that need a
  plain Java version: `OutOfMemoryError` ("new sprites added in run()") and
  `IllegalMonitorStateException` (`getTimer().everyMillis`).
- New texts go into `messages_en.properties` and `messages_de.properties`
  (UI) and `RuntimeErrors` (crashes), in both languages.

## Already works

- The code editor, completions, go to definition, rename, diagnostics and
  the friendly compiler messages: they all use javac with the project's
  classpath.
- The block palette and the designers hide for classes that are not a stage
  or sprite (`VisualMode`).
- The Scratch-to-Java lints only look at stage and sprite classes
  (`TransitionLinter`); the asset lints only at the library's asset calls
  (`AssetLinter`).
- The debugger, hot reload, the UML diagram, local history, search and the
  formatter.

## Tests

- `ScratchProjectTest`: kind detection (jar, import, empty folder, plain
  sources), a saved kind wins. Default class: one `main` is taken, several
  without a saved choice give none, a saved choice wins, a saved class without
  `main` gives none.
- `NewProjectTest`: the Java template compiles and runs without the library.
- `NewClassTest`: a plain class, with and without `main`.
- `RuntimeErrorsTest`: `InputMismatchException`, and the plain hints for
  `OutOfMemoryError` and `IllegalMonitorStateException`.
- A runner test: a plain project reads two lines with `Scanner`, reports that
  it waits for input, gets the lines through `RunHandle.input` and prints
  them; exit code 0. A crash shows the program's exception, not
  `InvocationTargetException`.
- `mvn verify` passes before each pull request.

## Pull requests

1. **Open and run** (sections 1–4 and the default class). After it, a flat
   Java folder opens without the library prompt and runs, with console input
   and the debugger.
2. **Create** (section 5).
3. **Polish** (sections 6 and 7).
4. **Projects from VS Code**, if still wanted (next section).

## Later: projects from VS Code

Teachers may hand out projects made with VS Code's "No build tools" layout:
classes in `src/`, often in packages, jars in `lib/`. To open and run those:

- `ScratchProject.sourceRoots()`: the `java.project.sourcePaths` of
  `.vscode/settings.json`, otherwise `src/` if it exists, otherwise the
  project folder. They go on the run classpath so resources next to the
  classes are found.
- `ScratchProject.libs()`: also the jars in `lib/` and in
  `java.project.referencedLibraries`, which is either a list of globs or
  `{ "include": [...], "exclude": [...] }`. Sorted, without duplicates.
- Qualified class names from the `package` line: in `mainClasses()`,
  `startStage()` and `sourceOf()`, and in `Debugger.byClass`, whose
  `classesByName` lookup needs them for breakpoints to stop.
- `StudioApp.openStart()` opens the class through `sourceOf()` instead of
  `root/<Name>.java`.
- `ProjectCheck.check` matches compiler diagnostics to files by their full
  path instead of their file name, so an error in `a/Main.java` does not land
  in `b/Main.java`.
- The runnable JAR puts resources of a source root relative to that root
  (`src/data.txt` becomes `data.txt`), as `getResource` expects.

Studio still creates new classes in the project folder or the first source
root; moving classes between packages stays out.

## Open questions

- Console input for Scratch for Java programs needs the control channel
  moved off stdin, for example to a local socket.
- Whether the window title stays "Scratch for Java Studio" for plain
  projects.
