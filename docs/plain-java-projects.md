# Plain Java projects

**Status: plan.** Nothing here is implemented yet.

Studio should also open, edit, run and debug Java projects that do not use
Scratch for Java: a folder of classes, or a VS Code "No build tools" project
with `src/` and `lib/`. Scratch for Java projects keep working exactly as
they do today.

## Scope of the first version

In:

- Classes in the project folder or in a source folder: `src/`, or the
  `java.project.sourcePaths` of `.vscode/settings.json`.
- Packages.
- Jars in `lib/`, `+libs/`, or the `java.project.referencedLibraries` of
  `.vscode/settings.json`.
- Console programs that read from `System.in`.
- Swing and AWT programs (they run in their own JVM like every program).

Not yet:

- Maven and Gradle projects (`pom.xml`, `build.gradle`).
- Moving classes between packages. `ProjectFileManagement.move` keeps
  refusing to move `.java` files out of the project folder.
- `module-info.java` and JavaFX programs.
- Clickable stack-trace lines when two classes share a file name.
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

## Work

### 1. Project model (`core`)

- `ProjectKind` and its detection, as above.
- `ScratchProject.sourceRoots()`: the `java.project.sourcePaths` of
  `.vscode/settings.json`, otherwise `src/` if it exists, otherwise the
  project folder. Scratch for Java projects always get the project folder.
  `javaSources()` already scans subfolders, so packaged classes are found
  today; the roots are needed for resources and for the package of new
  classes.
- `ScratchProject.libs()`: also the jars in `lib/` (with subfolders) and in
  `java.project.referencedLibraries`, which is either a list of globs or
  `{ "include": [...], "exclude": [...] }`. Sorted, without duplicates
  (Scratch for Java's VS Code projects already list `+libs/*.jar` there).
  Every compile, check, hot reload and run takes its jars from `libs()`.
- `ScratchProject.qualifiedName(Path)`: the file's `package` line plus its
  name. `mainClasses()` and `startStage()` return qualified names, and
  `sourceOf()` accepts them. Classes in the default package keep their simple
  name, so Scratch for Java projects see no difference.

### 2. Running (`runner`)

- `ProjectRunner.run` for plain projects: no generated `Scratch4JLauncher`.
  It compiles `javaSources()` into `.scratch4j/build/classes` and starts

  ```
  java <debug agent> <hot reload flags> -cp <classes>:<source roots>:<project folder>:<jars> com.example.Main
  ```

  in the project folder, as VS Code does. No `-Dscratch4j.capture*` and no
  `-Dscratch4j.ide.*` flags. The source roots on the classpath find resources
  that sit next to the classes.
- The bundled runtime is Java 25, which runs `void main()` programs
  (JEP 512) without the launcher's help.
- `RunConfig.stageClass` holds the qualified start class (rename it to
  `startClass`).
- `Debugger.byClass` names a file's class by its qualified name, so
  breakpoints in packaged classes stop the program. The debugger looks
  classes up with `classesByName`, which needs that name.
- `HotSwap` already reads class names from the compiled `.class` files and
  needs no change.

### 3. Console input (`runner`, `ui`)

- `ConsoleView` gets an input line under the output, enabled while a plain
  program runs. Enter sends the line to the program's `System.in` and shows
  it in the console in its own style. An "end of input" button (Ctrl+D)
  closes `System.in`, for loops like `while (scanner.hasNextLine())`.
- `RunHandle` gets `input(String line)` and `closeInput()`. `send()` already
  writes to the program's stdin.
- Scratch for Java runs keep the input line hidden: their stdin is the IDE's
  control channel (`IdeControl` in `LauncherSource`).

### 4. Opening and running in the UI (`ui`, mostly `StudioApp`)

- `open()`: no `checkLibrary()` for plain projects. Today it asks to add the
  Scratch for Java jar on every open, because declining only remembers a jar
  that has a version.
- `openStart()`: opens the main class through `sourceOf()` instead of
  `root/<Name>.java`, which misses `src/` and packages.
- `runProgram()` and `debugProgram()`: the start class is the open editor's
  class if it has a `main`, otherwise the saved start class, otherwise the
  first class with `main` (`Main` first). Without one, a new message
  `run.nomain` replaces `run.nostage` ("no Stage class yet").
- `runStage()` for plain runs: no control channel and no `watchdog()`, which
  would report every console program that runs longer than 10 seconds as
  frozen ("no window"). The progress bar ends when the process has started.
  An exit code of 0 counts as a working run for `keepWorkingVersion()`.
- `ProjectCheck.check` matches compiler diagnostics to files by their full
  path instead of their file name, so an error in `a/Main.java` does not land
  in `b/Main.java`.

### 5. Creating projects and classes (`core`, `ui`)

- `ProjectTemplate.JAVA` and `NewProject` write VS Code's own "No build
  tools" layout, which opens unchanged in VS Code:

  ```
  src/Main.java
  lib/
  .vscode/settings.json
  .scratch4j/project.json     { "kind": "java", ... }
  ```

  with this `settings.json`:

  ```json
  {
    "java.project.sourcePaths": ["src"],
    "java.project.outputPath": "bin",
    "java.project.referencedLibraries": ["lib/**/*.jar"]
  }
  ```

  No `+libs`, `assets/` or `MyWindow`. `NewProjectDialog` gets a card for it.
- `NewClass.Kind.CLASS`: a plain `public class Name { }`, written into the
  folder selected in the Files tree (otherwise the first source root) with
  the `package` line for that folder. For plain projects `NewClassDialog`
  offers only this kind.
- `AddToProjectDialog` offers Class, Folder, Image and Sound for plain
  projects; not the asset library, shaders or maps.

### 6. Hiding what only Scratch for Java needs (`ui`, `export`)

- The Stages sidebar tab is hidden and the Files tab selected.
- The Sprite editor button in the sidebar.
- The Standard/NRW choice, the library update check and the Abiturklassen
  import.
- Export offers only the runnable JAR: `ProjectFormats.exportRunnableJar`
  writes `Main-Class: <qualified start class>` instead of the launcher and
  keeps merging the jars. Resources in a source root go into the JAR relative
  to that root (`src/data.txt` becomes `data.txt`), as `getResource` expects.
  The other exports are built around the launcher or `+libs` and stay hidden
  for now.
- New texts go into `messages_en.properties` and `messages_de.properties`.

## Already works

- The code editor, completions, go to definition, rename, diagnostics and
  the friendly error messages: they all use javac with the project's
  classpath.
- The block palette and the designers hide for classes that are not a stage
  or sprite (`VisualMode`).
- The Scratch-to-Java lints only look at stage and sprite classes
  (`TransitionLinter`); the asset lints only at the library's asset calls
  (`AssetLinter`).
- Hot reload, the UML diagram, local history, search and the formatter.

## Tests

- `ScratchProjectTest`: kind detection (jar, import, empty folder, plain
  sources), a saved kind wins, source roots from `settings.json` and `src/`,
  qualified main classes, jars from `lib/` and `referencedLibraries`.
- `NewProjectTest`: the Java template compiles without the library.
- `NewClassTest`: a class in a package folder gets the package line.
- `ProjectCheckTest`: two `Main.java` in different packages; the error is
  reported in the right one.
- A runner test: a packaged plain project that reads two lines with
  `Scanner` gets them through `RunHandle.input` and prints them; exit code 0.
- A `Debugger.byClass` test for a class in a package.
- `mvn verify` passes before each pull request.

## Pull requests

1. **Open and run** (sections 1–4). After it, an existing plain or VS Code
   Java folder opens without the library prompt and runs, with console input
   and the debugger.
2. **Create** (section 5).
3. **Polish** (section 6).

## Open questions

- New Java projects use the VS Code layout above. A flat folder in BlueJ's
  style would also work.
- Run starts the open editor's class. Alternative: a fixed start class with
  a "Set as start class" action in the Files tree.
- Console input for Scratch for Java programs needs the control channel
  moved off stdin, for example to a local socket.
- Whether the window title stays "Scratch for Java Studio" for plain
  projects.
