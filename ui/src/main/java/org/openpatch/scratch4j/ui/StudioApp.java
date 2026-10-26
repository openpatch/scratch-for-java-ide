package org.openpatch.scratch4j.ui;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import org.openpatch.scratch4j.core.api.ApiIndex;
import org.openpatch.scratch4j.core.api.ApiMethod;
import org.openpatch.scratch4j.core.io.LocalHistory;
import org.openpatch.scratch4j.core.compile.CompileResult;
import org.openpatch.scratch4j.core.lint.DiagnosticsExplanations;
import org.openpatch.scratch4j.core.lint.QuickFixes;
import org.openpatch.scratch4j.core.lint.ProjectCheck;
import org.openpatch.scratch4j.core.project.NewClass;
import org.openpatch.scratch4j.core.project.BundledTemplates;
import org.openpatch.scratch4j.core.project.LibraryCheck;
import org.openpatch.scratch4j.core.project.LibraryFlavour;
import org.openpatch.scratch4j.core.project.NewProject;
import org.openpatch.scratch4j.core.project.ProjectAssetManagement;
import org.openpatch.scratch4j.core.project.FileUsages;
import org.openpatch.scratch4j.core.project.ProjectFileManagement;
import org.openpatch.scratch4j.core.project.ProjectFolderManagement;
import org.openpatch.scratch4j.core.project.ScratchProject;
import org.openpatch.scratch4j.core.project.StageManagement;
import org.openpatch.scratch4j.runner.LibraryJarSource;
import org.openpatch.scratch4j.runner.ProjectRunner;
import org.openpatch.scratch4j.runner.RunConfig;
import org.openpatch.scratch4j.runner.RunHandle;
import org.openpatch.scratch4j.runner.RunListener;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The IDE window. A header with the menus and the big green-flag Run / red
 * Stop buttons; on the left the stages (with thumbnails) and the project
 * files; in the middle the editor tabs; below the problems and the console.
 * A class's tab reads left to right as in Scratch: the blocks, the code, the
 * visual editor (stage designer, sprite editor), and the view switch at the
 * end of the tab strip shows the code, both, or the visual editor. Code
 * autosaves and is checked continuously; the designer and thumbnails follow
 * the code.
 */
public class StudioApp extends javafx.application.Application {

  private final ApiIndex apiIndex = ApiIndex.load();
  private final ProjectRunner runner = new ProjectRunner();
  private final AtomicReference<RunHandle> currentRun = new AtomicReference<>();
  /**
   * Counts the starts and stops of runs. A start that is no longer the newest
   * once it has compiled (the flag pressed again, Stop meanwhile) ends at once:
   * only one program runs.
   */
  private final java.util.concurrent.atomic.AtomicLong runGeneration = new java.util.concurrent.atomic.AtomicLong();
  /**
   * Counts the starts only: a program that a newer start replaced may still
   * report (its exit, its output); that no longer changes what the IDE shows.
   */
  private final java.util.concurrent.atomic.AtomicLong runStart = new java.util.concurrent.atomic.AtomicLong();
  /** Programs that were started and kept (tests). */
  private final java.util.concurrent.atomic.AtomicInteger keptRuns =
      new java.util.concurrent.atomic.AtomicInteger();
  private final AtomicReference<ScratchProject> project = new AtomicReference<>();
  private final BooleanProperty running = new SimpleBooleanProperty(false);
  private final ToggleButton debugToggle = new ToggleButton(null, Icons.of("fth-target"));
  private final ToggleButton recordToggle = new ToggleButton(null, Icons.of("fth-video"));
  // the game loop: pause, one frame at a time, slow motion
  private final ToggleButton pauseToggle = new ToggleButton(null, Icons.of("fth-pause", 16));
  private final Button stepButton = new Button(null, Icons.of("fth-skip-forward", 16));
  private final javafx.scene.control.MenuButton speedButton =
      new javafx.scene.control.MenuButton("1\u00d7");
  private final javafx.scene.control.ToggleGroup speeds = new javafx.scene.control.ToggleGroup();
  /** The program's game loop is paused (by the pause button, not a breakpoint). */
  private volatile boolean programPaused;
  private VariablesView variablesView;
  private Tab variablesTab;
  /** Frozen-program watchdog: the last heartbeat's frame count and when it last changed. */
  private long watchFrames = -2;
  private long watchChanged;
  private long watchStarted;
  private boolean watchWarned;
  private final BooleanProperty hasProject = new SimpleBooleanProperty(false);
  /** The project ran once (now or in an earlier session): export makes sense. */
  private final BooleanProperty hasRun = new SimpleBooleanProperty(false);
  /** Errors for a minute without a fix: the problems pane offers the last version that ran. */
  private final javafx.animation.PauseTransition stuckDelay =
      new javafx.animation.PauseTransition(javafx.util.Duration.seconds(60));
  private final AtomicBoolean checkRunning = new AtomicBoolean(false);
  /** The open project's check state: only what changed is compiled and linted again. */
  private volatile ProjectCheck.Cache checkCache;
  private final AtomicBoolean checkAgain = new AtomicBoolean(false);
  private final javafx.animation.PauseTransition checkDelay =
      new javafx.animation.PauseTransition(javafx.util.Duration.millis(250));

  private Stage stage;
  private Scene scene;
  private WindowChrome windowChrome;

  // rebuilt on a language switch
  private BorderPane root;
  private FileTreeView fileTree;
  private EditorTabs editor;
  private BlockPalette palette;
  private ConsoleView console;
  private ProblemsPane problems;
  private StageSelectorView selector;
  private TabPane sidebar;
  private TabPane bottomTabs;
  private Tab problemsTab;
  private Tab consoleTab;
  private ScratchMigrationPane migrationPane;
  private Tab migrationTab;
  /** The project's lesson in the sidebar (only when the project has one). */
  private Tab lessonTab;
  private LessonPanel lessonPanel;
  private final LearningSupport learning = new LearningSupport();
  /** Lesson checks read files and write progress: off the FX thread, one at a time. */
  private final java.util.concurrent.ExecutorService lessonWorker =
      java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "scratch4j-lesson");
        t.setDaemon(true);
        return t;
      });
  /** This run's first frame was counted for the lesson. */
  private boolean lessonRunCounted;
  private Tab debuggerTab;
  private Tab searchTab;
  private SearchResultsView searchResults;
  private DebuggerView debuggerView;
  private volatile org.openpatch.scratch4j.runner.Debugger debugger;
  /** The running program's connection (every run): hot reload goes through it. */
  private volatile org.openpatch.scratch4j.runner.Debugger liveSession;
  private volatile org.openpatch.scratch4j.runner.HotSwap liveSwap;
  private final java.util.concurrent.ExecutorService breakpointUpdates =
      java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "debugger-breakpoints");
        t.setDaemon(true);
        return t;
      });
  /** While the debugger holds the program, no frames come: not frozen. */
  private volatile boolean debugPaused;
  private Path debugFile;
  private SplitPane workArea;
  /** The work area with the loading card over it while a project opens. */
  private StackPane workHost;
  private final LoadingOverlay loading = new LoadingOverlay();
  /** Beside the status text while the program starts (compile, Java, its assets). */
  private javafx.scene.control.ProgressBar runProgress;
  /** The stage being started, for the status once its first frame is drawn. */
  private String startingStage;
  private SplitPane centerSplit;
  private StackPane centerStack;
  /** The code tab the block pane is docked in (one pane, moved to the tab shown). */
  private CodeEditor paletteHost;
  private ViewSwitch viewSwitch;
  private Label status;
  private Label problemBadge;
  private Label projectTitle;

  /**
   * Called by the launcher before JavaFX starts: with compatibility graphics
   * the IDE draws with JavaFX's software pipeline (old or broken graphics
   * drivers), and the programs it starts use Mesa's software renderer.
   */
  public static void prepareGraphics() {
    // JavaFX 25's native HeaderBar and extended stage style are preview APIs.
    System.setProperty("javafx.enablePreview", "true");
    if (Prefs.compatibilityGraphics()) {
      System.setProperty("prism.order", "sw");
    }
    org.openpatch.scratch4j.runner.GraphicsCompatibility.setSoftware(
        Prefs.compatibilityGraphics());
  }

  /** A run whose OpenGL failed was already restarted with compatibility graphics. */
  private final AtomicBoolean graphicsFallback = new AtomicBoolean(false);

  @Override
  public void start(Stage stage) {
    this.stage = stage;
    windowChrome = new WindowChrome(stage);
    org.openpatch.scratch4j.runner.GraphicsCompatibility.setSoftware(
        Prefs.compatibilityGraphics());
    I18n.set(Prefs.language());
    // once, not per language switch: the UI they touch is looked up when they fire
    running.addListener((o, was, isRunning) -> {
      // stopped, crashed or not compiled: the starting bar goes with it
      if (!isRunning && runProgress != null) showRunProgress(false);
      if (isRunning) {
        showTab(variablesTab, false); // the Variables tab while a program runs
      } else if (bottomTabs.getSelectionModel().getSelectedItem() != variablesTab) {
        hideTab(variablesTab);
      }
    });
    hasRun.addListener((o, was, ran) -> updateProblemsPlaceholder());
    stuckDelay.setOnFinished(e -> offerLastWorking());
    buildUi();
    // Sizes are in logical pixels: with display scaling (150% on a 1080p screen
    // leaves about 1280x690) a fixed size would push the window off the screen.
    javafx.geometry.Rectangle2D screen = javafx.stage.Screen.getPrimary().getVisualBounds();
    double frame = 40; // leave space around the window on smaller displays
    double width = Math.min(1360, screen.getWidth() - frame);
    double height = Math.min(840, screen.getHeight() - frame);
    scene = new Scene(root, width, height);
    windowChrome.installResize(scene);
    Theme.apply(scene);
    installEditorShortcuts();
    checkDelay.setOnFinished(e -> checkProject());
    stage.setTitle("Scratch for Java Studio");
    stage.getIcons().add(Branding.icon());
    stage.setScene(scene);
    stage.setMinWidth(Math.min(900, screen.getWidth()));
    stage.setMinHeight(Math.min(600, screen.getHeight()));
    stage.setOnCloseRequest(e -> {
      if (editor != null && !editor.confirmUnsavedHitboxes()) {
        e.consume();
        return;
      }
      saveAll();
      stopProgram();
      learning.close();
    });
    stage.show();
    if (width < 1360 || height < 840) windowChrome.maximize();
    // null when started without Application.launch (layout tests)
    List<String> startupArgs = getParameters() == null ? List.of() : getParameters().getRaw();
    if (startupArgs.size() == 2 && startupArgs.get(0).equals("--smoke")) {
      PackagedSmoke.start(this, stage, Path.of(startupArgs.get(1)).toAbsolutePath());
    } else openFromArguments();
  }

  // --- layout --------------------------------------------------------------------

  private void buildUi() {
    paletteHost = null;
    fileTree = new FileTreeView();
    fileTree.setOnOpen(this::openByType);
    fileTree.setOnNewClass(() -> createClass(null));
    fileTree.setOnReveal(folder -> browse(folder.toUri().toString()));
    fileTree.setOnRenameAsset(this::renameFile);
    fileTree.setOnMoveTo(this::moveTo);
    // a drop always shows the preview: a slip of the mouse moves nothing
    fileTree.setOnMove((path, folder) -> movePath(path, folder, true));
    fileTree.setOnImport(this::importAssetFiles);
    fileTree.setOnDuplicate(this::duplicateFile);
    fileTree.setOnRenameClass(this::renameClass);
    fileTree.setOnDeleteFile(this::deleteFile);
    fileTree.setOnEditSprite(this::openSpriteAssets);
    fileTree.setOnNewFolder(this::createFolder);
    fileTree.setOnRenameFolder(this::renameFolder);
    fileTree.setOnDeleteFolder(this::deleteFolder);
    editor = new EditorTabs(apiIndex, () -> {
      ScratchProject p = project.get();
      return p == null ? null : p.root();
    });
    editor.setOnSideChanged(this::updatePaletteContext);
    editor.setOnOpenFile(this::openByType);
    editor.setOnBreakpointsChanged((file, lines) -> {
      if (!lines.isEmpty()) {
        showTab(debuggerTab, false); // a first breakpoint: the debugger has a reason to show
      }
      var session = debugger;
      if (session != null && file.getFileName().toString().endsWith(".java")) {
        String className = file.getFileName().toString().replaceFirst("\\.java$", "");
        // JDI round trips stay off the FX thread, in click order
        breakpointUpdates.execute(() -> session.setBreakpoints(className, lines));
      }
    });
    editor.setOnGoToDefinition(this::goToDefinition);
    editor.setOnRename(this::renameSymbol);
    editor.setOnFindUsages(this::findUsages);
    editor.setOnFindInProject(this::findInProject);
    editor.setOnTypedEdit(learning::typed);
    editor.setOnBlockDropped((target, snippet) -> {
      addImports(target, snippet);
      apiIndex.methods().stream().filter(method -> BlockPalette.snippet(method).equals(snippet)).findFirst()
          .ifPresent(method -> learning.event("block.insert", Map.of("block",
              org.openpatch.scratch4j.core.io.EventLog.id(method.className() + "." + method.signature()))));
    });
    editor.setOnBrowse(this::browse);
    editor.setOnSaved(this::onCodeSaved);
    editor.setOnSaveFailed(error -> {
      String message = I18n.t("error.save", error.getMessage());
      setStatus(message);
      console.err(message);
    });
    // typing makes the problems list out of date until the check after the autosave
    editor.setOnEdited(() -> {
      if (problems != null) problems.setOutdated(true);
    });
    editor.getSelectionModel().selectedItemProperty().addListener((o, old, tab) -> {
      // leaving a code tab saves it, entering a designer catches up with the code
      editor.autosaveAll();
      Path shown = EditorTabs.fileOf(tab);
      if (shown != null) fileTree.selectPath(shown); // the tree follows the tab
      if (tab != null) {
        Node tool = tab.getContent() instanceof CodeEditor code ? code.sidePanel() : tab.getContent();
        if (tool != null) refreshTool(tool);
      }
      updatePaletteContext();
    });
    palette = new BlockPalette(apiIndex, this::insertFromPalette);
    palette.setOnOpenChanged(open -> {
      if (paletteHost != null) Prefs.blocksOpen(paletteHost.view() == ViewSwitch.View.SPLIT, open);
    });
    console = new ConsoleView(this::openSourceAt);
    problems = new ProblemsPane(p -> editor.openAt(p.file(), p.line()));
    problems.setOnFix(this::fixProblem);
    problems.setOnConsole(p -> {
      bottomTabs.getSelectionModel().select(consoleTab);
      console.showTrace(p.trace());
    });
    editor.setOnHint((file, line) -> supportProblems().stream()
        .filter(p -> file.equals(p.file()) && p.line() == line && p.error())
        .findFirst().ifPresent(p -> {
          bottomTabs.getSelectionModel().select(problemsTab);
          problems.hint(p);
        }));
    editor.setOnFix((file, line) -> checkedProblems.stream()
        .filter(p -> file.equals(p.file()) && p.line() == line && p.fix() != null)
        .findFirst().ifPresent(this::fixProblem));

    root = new BorderPane();
    root.getStyleClass().add("studio");
    if (!windowChrome.isExtended()) root.getStyleClass().add("custom-chrome");
    root.setTop(header());

    sidebar = new TabPane();
    sidebar.getStyleClass().addAll("sidebar-tabs");
    sidebar.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
    Tab stagesTab = new Tab(I18n.t("stages.title"));
    stagesTab.setGraphic(Icons.of("fth-monitor"));
    // one Add button instead of a row of new-something buttons
    Button add = Icons.labeled("fth-plus", I18n.t("menu.file.add.short"), this::addToProject);
    Button spriteEditor = Icons.labeled("fth-user", I18n.t("spriteassets.button"),
        this::chooseSpriteAssets);
    FlowPane assetButtons = new FlowPane(4, 4, add, spriteEditor);
    assetButtons.getStyleClass().add("asset-create-bar");
    for (Button button : List.of(add, spriteEditor)) {
      button.disableProperty().bind(hasProject.not());
    }
    VBox fileTabContent = new VBox(assetButtons, fileTree);
    VBox.setVgrow(fileTree, Priority.ALWAYS);
    Tab filesTab = new Tab(I18n.t("files.title"), fileTabContent);
    filesTab.setGraphic(Icons.of("fth-folder"));
    sidebar.getTabs().addAll(stagesTab, filesTab);
    lessonPanel = new LessonPanel();
    learning.connect(problems, lessonPanel, this::updateSupportGutters);
    lessonPanel.setOnRestart(this::restartLesson);
    lessonTab = new Tab(I18n.t("lesson.tab"), lessonPanel);
    lessonTab.setGraphic(Icons.of("fth-compass"));
    sidebar.setMinWidth(200);

    Label emptyHint = new Label(I18n.t("editor.empty"), Icons.of("fth-mouse-pointer", 22));
    emptyHint.getStyleClass().add("empty-hint");
    emptyHint.setWrapText(true);
    emptyHint.visibleProperty().bind(javafx.beans.binding.Bindings.isEmpty(editor.getTabs()));
    emptyHint.setMouseTransparent(true);
    // the view switch at the right end of the tab strip, for the tab shown
    viewSwitch = new ViewSwitch(this::showView, file -> openVisualForFile(file));
    viewSwitch.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
    editor.reserveHeaderEnd(viewSwitch.widthProperty());
    centerStack = new StackPane(editor, emptyHint, viewSwitch);
    StackPane.setAlignment(viewSwitch, Pos.TOP_RIGHT);
    StackPane.setMargin(viewSwitch, new javafx.geometry.Insets(4, 8, 0, 0));
    centerStack.getStyleClass().add("center-stack");

    problemBadge = new Label("0");
    problemBadge.getStyleClass().add("badge");
    problemsTab = new Tab(I18n.t("problems.title"), problems);
    problemsTab.setGraphic(new HBox(6, Icons.of("fth-alert-circle"), problemBadge));
    ((HBox) problemsTab.getGraphic()).setAlignment(Pos.CENTER_LEFT);
    consoleTab = new Tab(I18n.t("console.title"), console);
    debuggerView = new DebuggerView(this::stopProgram);
    debuggerTab = new Tab(I18n.t("debug.title"), debuggerView);
    debuggerTab.setClosable(false);
    consoleTab.setGraphic(Icons.of("fth-terminal"));
    searchResults = new SearchResultsView(
        match -> editor.openRange(match.file(), match.start(), match.end()));
    searchTab = new Tab(I18n.t("search.tab"), searchResults);
    searchTab.setGraphic(Icons.of("fth-search"));
    variablesView = new VariablesView((owner, field, label, on) ->
        sendToProgram(on ? "pin " + owner + " " + field + " " + label
            : "unpin " + owner + " " + field));
    variablesTab = new Tab(I18n.t("variables.title"), variablesView);
    variablesTab.setGraphic(Icons.of("fth-eye"));
    migrationPane = new ScratchMigrationPane((file, line) -> editor.openAt(file, line), this::browse);
    migrationTab = new Tab(I18n.t("migration.title"), migrationPane);
    // Problems and Console from the start; the other tabs appear when the project needs them
    bottomTabs = new TabPane(problemsTab, consoleTab);
    bottomTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
    problems.setPlaceholder(I18n.t("problems.empty.noproject"));
    bottomTabs.getStyleClass().add("bottom-tabs");
    bottomTabs.setMinHeight(90);

    // the panels are cards on the window's background, the gaps between them the dividers
    Node bottomCard = surface(bottomTabs);
    centerSplit = new SplitPane(surface(centerStack), bottomCard);
    centerSplit.getStyleClass().add("work-split");
    centerSplit.setOrientation(Orientation.VERTICAL);
    centerSplit.setDividerPositions(0.76);
    SplitPane.setResizableWithParent(bottomCard, false);

    Node sidebarCard = surface(sidebar);
    workArea = new SplitPane(sidebarCard, centerSplit);
    workArea.getStyleClass().addAll("work-area", "work-split");
    workArea.setDividerPositions(0.18);
    SplitPane.setResizableWithParent(sidebarCard, false);
    workHost = new StackPane(workArea, loading);

    status = new Label(I18n.t("status.no.project"));
    status.getStyleClass().add("status-text");
    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    Label hint = new Label(I18n.t("status.shortcuts"));
    hint.getStyleClass().add("status-hint");
    runProgress = new javafx.scene.control.ProgressBar(
        javafx.scene.control.ProgressBar.INDETERMINATE_PROGRESS);
    runProgress.getStyleClass().add("run-progress");
    showRunProgress(false);
    HBox statusBar = new HBox(12, runProgress, status, spacer, learning.indicator(), hint);
    statusBar.setAlignment(Pos.CENTER_LEFT);
    statusBar.getStyleClass().add("status-bar");
    root.setBottom(statusBar);
    showWelcome();
  }

  private Node header() {
    ImageView cat = new ImageView(Branding.icon());
    cat.setFitWidth(26);
    cat.setFitHeight(26);
    cat.setPreserveRatio(true);
    cat.setSmooth(false);
    StackPane logo = new StackPane(cat);
    logo.getStyleClass().add("app-logo");
    // the cat leads back to the start page
    logo.setCursor(javafx.scene.Cursor.HAND);
    Tooltip.install(logo, new Tooltip(I18n.t("home.tooltip")));
    logo.setOnMouseClicked(e -> closeProject());
    MenuBar menus = menuBar();
    menus.getStyleClass().add("header-menus");
    projectTitle = new Label("Scratch for Java Studio");
    projectTitle.getStyleClass().add("project-title");

    Button run = new Button(I18n.t("run.button"), Icons.of("fth-flag", 16));
    run.getStyleClass().add("run-button");
    run.setTooltip(new Tooltip(I18n.t("run.tooltip")));
    run.setOnAction(e -> runProgram());
    Button stop = new Button(null, Icons.of("fth-octagon", 16));
    stop.getStyleClass().add("stop-button");
    stop.setTooltip(new Tooltip(I18n.t("stop.tooltip")));
    stop.setOnAction(e -> stopProgram());
    stop.disableProperty().bind(running.not());
    pauseToggle.getStyleClass().addAll("button-icon", "flat", "header-icon");
    pauseToggle.setTooltip(new Tooltip(I18n.t("run.pause.tooltip")));
    pauseToggle.setOnAction(e -> setProgramPaused(pauseToggle.isSelected()));
    pauseToggle.disableProperty().bind(running.not());
    pauseToggle.selectedProperty().addListener((o, was, paused) -> {
      pauseToggle.setGraphic(Icons.of(paused ? "fth-play" : "fth-pause", 16));
      pauseToggle.setTooltip(new Tooltip(I18n.t(paused ? "run.resume.tooltip"
          : "run.pause.tooltip")));
    });
    stepButton.getStyleClass().addAll("button-icon", "flat", "header-icon");
    stepButton.setTooltip(new Tooltip(I18n.t("run.step.tooltip")));
    stepButton.setOnAction(e -> stepFrame());
    stepButton.disableProperty().bind(running.not().or(pauseToggle.selectedProperty().not()));
    speedButton.getStyleClass().addAll("flat", "header-icon", "speed-button");
    speedButton.setTooltip(new Tooltip(I18n.t("run.speed.tooltip")));
    // frames per second; the library's normal speed is 60
    for (int divisor : new int[] {1, 2, 4, 10}) {
      javafx.scene.control.RadioMenuItem speed =
          new javafx.scene.control.RadioMenuItem(I18n.t("run.speed." + divisor));
      speed.setToggleGroup(speeds);
      speed.setUserData(divisor);
      speed.setSelected(divisor == 1);
      speed.setOnAction(e -> setSpeed(divisor));
      speedButton.getItems().add(speed);
    }
    run.disableProperty().bind(hasProject.not());
    // the running program: debug overlay (like F12), screenshot, GIF recording
    debugToggle.getStyleClass().addAll("button-icon", "flat", "header-icon");
    debugToggle.setTooltip(new Tooltip(I18n.t("run.debug.tooltip")));
    debugToggle.setOnAction(e -> sendToProgram(debugToggle.isSelected() ? "debug on" : "debug off"));
    Button screenshot = new Button(null, Icons.of("fth-camera"));
    screenshot.getStyleClass().addAll("button-icon", "flat", "header-icon");
    screenshot.setTooltip(new Tooltip(I18n.t("run.screenshot.tooltip")));
    screenshot.setOnAction(e -> captureScreenshot());
    screenshot.disableProperty().bind(running.not());
    recordToggle.getStyleClass().addAll("button-icon", "flat", "header-icon");
    recordToggle.setTooltip(new Tooltip(I18n.t("run.record.tooltip")));
    recordToggle.setOnAction(e -> toggleRecording(recordToggle.isSelected()));
    recordToggle.disableProperty().bind(running.not());

    Button theme = new Button(null, Icons.of(Theme.isDark() ? "fth-sun" : "fth-moon"));
    theme.getStyleClass().addAll("button-icon", "flat", "header-icon");
    theme.setTooltip(new Tooltip(I18n.t("theme.toggle")));
    theme.setOnAction(e -> {
      Theme.toggle(scene);
      theme.setGraphic(Icons.of(Theme.isDark() ? "fth-sun" : "fth-moon"));
    });

    Region left = new Region();
    Region right = new Region();
    HBox.setHgrow(left, Priority.ALWAYS);
    HBox.setHgrow(right, Priority.ALWAYS);
    // a narrow window folds the menus into one button instead of cutting their names
    javafx.scene.control.MenuButton compactMenu = new javafx.scene.control.MenuButton(null,
        Icons.of("fth-menu", 18));
    compactMenu.getStyleClass().addAll("flat", "header-icon", "compact-menu");
    compactMenu.setTooltip(new Tooltip(I18n.t("menu.compact")));
    compactMenu.setVisible(false);
    compactMenu.setManaged(false);
    run.setMinWidth(Region.USE_PREF_SIZE);
    menus.setMinWidth(Region.USE_PREF_SIZE);
    projectTitle.setMinWidth(0);
    // the game-loop and capture controls only while a program runs
    HBox runningBar = new HBox(6, pauseToggle, stepButton, speedButton,
        new javafx.scene.control.Separator(Orientation.VERTICAL), screenshot, recordToggle);
    runningBar.setAlignment(Pos.CENTER_LEFT);
    runningBar.getStyleClass().add("running-bar");
    runningBar.visibleProperty().bind(running);
    runningBar.managedProperty().bind(running);
    HBox bar = new HBox(6, logo, menus, compactMenu, left, projectTitle, right, run, stop,
        debugToggle, runningBar,
        new javafx.scene.control.Separator(Orientation.VERTICAL), theme,
        new javafx.scene.control.Separator(Orientation.VERTICAL), windowChrome.controls(this::exitApp));
    bar.setAlignment(Pos.CENTER_LEFT);
    bar.getStyleClass().add("header");
    bar.setMinWidth(0);
    double[] menuWidth = {0};
    Runnable updateMenus = () -> {
      boolean compact = compactMenu.isVisible();
      if (!compact) menuWidth[0] = menus.prefWidth(-1);
      // Keep the expanded menu width while its items live in the compact button.
      double needed = menuWidth[0] + bar.getSpacing();
      for (Node child : bar.getChildren()) {
        if (child.isManaged() && child != menus && child != compactMenu
            && child != left && child != right && child != projectTitle) {
          needed += child.prefWidth(-1) + bar.getSpacing();
        }
      }
      needed += bar.getInsets().getLeft() + bar.getInsets().getRight() + 40;
      if (!compact && bar.getWidth() < needed) {
        compactMenu.getItems().setAll(menus.getMenus());
        menus.getMenus().clear();
        menus.setVisible(false);
        menus.setManaged(false);
        compactMenu.setVisible(true);
        compactMenu.setManaged(true);
      } else if (compact && bar.getWidth() >= needed) {
        List<Menu> all = new ArrayList<>();
        for (var item : compactMenu.getItems()) all.add((Menu) item);
        compactMenu.getItems().clear();
        menus.getMenus().setAll(all);
        menus.setVisible(true);
        menus.setManaged(true);
        compactMenu.setVisible(false);
        compactMenu.setManaged(false);
      }
    };
    bar.widthProperty().addListener((o, old, width) -> updateMenus.run());
    runningBar.managedProperty().addListener((o, was, managed) -> Platform.runLater(updateMenus));
    return windowChrome.header(bar, left, projectTitle, right);
  }

  /** One panel of the work area (stages and files, editor, problems): a card. */
  private static StackPane surface(Node content) {
    StackPane card = new StackPane(content);
    card.getStyleClass().add("surface");
    return card;
  }

  /** Shows a bottom tab the project needs now (only Problems and Console are always there). */
  private void showTab(Tab tab, boolean select) {
    if (!bottomTabs.getTabs().contains(tab)) {
      bottomTabs.getTabs().add(tab);
    }
    if (select) {
      bottomTabs.getSelectionModel().select(tab);
    }
  }

  private void hideTab(Tab tab) {
    bottomTabs.getTabs().remove(tab);
  }

  /**
   * The view switch and the block pane follow the tab shown: a sprite's or a
   * stage's blocks at the left of its code, open or folded to the categories
   * as last chosen for the code alone and for the code beside the visual
   * editor; no blocks without code to drop them into.
   */
  private void updatePaletteContext() {
    Tab tab = editor.getSelectionModel().getSelectedItem();
    CodeEditor code = tab != null && tab.getContent() instanceof CodeEditor c ? c : null;
    VisualMode.PaletteContext context = code == null ? VisualMode.PaletteContext.NONE
        : VisualMode.paletteContext(code.file());
    palette.setContext(context);
    if (code == null) {
      viewSwitch.showFor(null, VisualMode.Kind.NONE, ViewSwitch.View.CODE);
    } else {
      viewSwitch.showFor(code.file(), VisualMode.kind(code.file()), code.view());
    }
    boolean dock = project.get() != null && code != null
        && context != VisualMode.PaletteContext.NONE && code.view() != ViewSwitch.View.VISUAL;
    if (paletteHost != null && (paletteHost != code || !dock)) {
      paletteHost.undockPalette();
      paletteHost = null;
    }
    if (dock) {
      palette.setOpen(Prefs.blocksOpen(code.view() == ViewSwitch.View.SPLIT));
      code.dockPalette(palette);
      paletteHost = code;
    }
  }

  /**
   * The view switch: a class's code alone, beside its visual editor, or the
   * visual editor alone. Back to the code puts the caret on the line that
   * makes the object selected in the designer.
   */
  void showView(Path file, ViewSwitch.View view) {
    CodeEditor before = editor.editorFor(file);
    if (before == null || before.view() != view)
      learning.event("view", Map.of("view", view.name(), "kind", LearningSupport.kind(file)));
    CodeEditor code = editor.editorFor(file);
    if (view == ViewSwitch.View.CODE) {
      if (code != null && code.sidePanel() != null) {
        String selected = code.sidePanel() instanceof StageDesignerView designer
            ? designer.selectedName() : null;
        if (editor.closeSide(file) && selected != null) openCodeAtObject(file, selected);
      }
    } else {
      Prefs.visualOnly(view == ViewSwitch.View.VISUAL);
      if (code == null || code.sidePanel() == null) {
        openVisual(file);
        code = editor.editorFor(file);
      }
      if (code != null) code.setVisualOnly(view == ViewSwitch.View.VISUAL);
    }
    updatePaletteContext();
  }

  /** Saves and closes the open project, once the student agrees, and shows the start page. */
  private void closeProject() {
    ScratchProject p = project.get();
    if (p == null) {
      return; // the start page already shows
    }
    Alert ask = new Alert(Alert.AlertType.CONFIRMATION,
        I18n.t("home.confirm", p.name()), I18n.ok(), I18n.cancel());
    ask.setHeaderText(null);
    Theme.style(ask);
    if (ask.showAndWait().orElse(null) != I18n.ok() || !editor.confirmUnsavedHitboxes()) {
      return;
    }
    saveAll();
    stopProgram();
    checkDelay.stop();
    hotSwapDelay.stop();
    stuckDelay.stop();
    editor.clear();
    learning.closeProject();
    project.set(null);
    hasProject.set(false);
    stage.setTitle("Scratch for Java Studio");
    projectTitle.setText("Scratch for Java Studio");
    setStatus(I18n.t("status.no.project"));
    showWelcome();
  }

  private void showWelcome() {
    root.setCenter(new WelcomeView(this::createProject, this::openProject,
        this::openSharedZip, this::openProjectAt, this::browse));
  }

  /** The projects the start page lists, filled each time the File menu opens. */
  private Menu recentProjectsMenu(Menu file) {
    Menu recent = new Menu(I18n.t("welcome.recent"), Icons.of("fth-clock"));
    file.setOnShowing(e -> {
      Path current = project.get() == null ? null : project.get().root().toAbsolutePath().normalize();
      recent.getItems().clear();
      for (Path dir : Prefs.recentProjects()) {
        if (dir.equals(current)) continue;
        MenuItem entry = new MenuItem(dir.getFileName() + (dir.getParent() == null ? ""
            : "  \u2013  " + dir.getParent()), Icons.of("fth-folder"));
        entry.setMnemonicParsing(false);
        entry.setOnAction(a -> openProjectAt(dir));
        recent.getItems().add(entry);
      }
      recent.setDisable(recent.getItems().isEmpty());
    });
    return recent;
  }

  private MenuBar menuBar() {
    Menu file = new Menu(I18n.t("menu.file"));
    MenuItem export = item("menu.file.export", "fth-package", null, this::showExport);
    export.visibleProperty().bind(hasRun);
    file.getItems().addAll(
        item("menu.file.new", "fth-plus-square", shortcut(KeyCode.N), this::createProject),
        item("menu.file.open", "fth-folder", shortcut(KeyCode.O), this::openProject),
        recentProjectsMenu(file),
        item("menu.file.sb3", "fth-download", null, this::importScratchProject),
        new SeparatorMenuItem(),
        item("menu.file.add", "fth-plus",
            new KeyCodeCombination(KeyCode.N, KeyCombination.SHORTCUT_DOWN,
                KeyCombination.SHIFT_DOWN), this::addToProject),
        item("menu.file.library", "fth-image", shortcut(KeyCode.L), this::openAssetLibrary),
        new SeparatorMenuItem(),
        item("menu.file.save", "fth-save", shortcut(KeyCode.S), this::saveAndCheck),
        item("menu.file.close", "fth-x", shortcut(KeyCode.W), () -> editor.closeSelected()),
        new SeparatorMenuItem(),
        export,
        learning.menu(stage),
        new SeparatorMenuItem(),
        item("menu.file.exit", "fth-log-out", null, this::exitApp));

    Menu edit = new Menu(I18n.t("menu.edit"));
    edit.getItems().addAll(
        item("menu.edit.find", "fth-search", null, () -> withEditor(e -> e.showFind(false))),
        item("menu.edit.replace", "fth-repeat", null, () -> withEditor(e -> e.showFind(true))),
        item("menu.edit.findproject", "fth-search", new KeyCodeCombination(KeyCode.H,
            KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN),
            () -> findInProject(editor.activeEditor() == null ? ""
                : editor.activeEditor().searchStart())),
        item("menu.edit.gotoline", "fth-hash", shortcut(KeyCode.G),
            () -> withEditor(CodeEditor::askGotoLine)),
        new SeparatorMenuItem(),
        item("menu.edit.duplicateline", "fth-copy", shortcut(KeyCode.D),
            () -> withEditor(CodeEditor::duplicateLines)),
        item("menu.edit.deleteline", "fth-delete", new KeyCodeCombination(KeyCode.K,
            KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN),
            () -> withEditor(CodeEditor::deleteLines)),
        item("menu.edit.lineup", "fth-arrow-up", new KeyCodeCombination(KeyCode.UP,
            KeyCombination.ALT_DOWN), () -> withEditor(e -> e.moveLines(-1))),
        item("menu.edit.linedown", "fth-arrow-down", new KeyCodeCombination(KeyCode.DOWN,
            KeyCombination.ALT_DOWN), () -> withEditor(e -> e.moveLines(1))),
        new SeparatorMenuItem(),
        item("menu.edit.format", "fth-align-left", null,
            () -> withEditor(CodeEditor::formatIndentation)),
        new SeparatorMenuItem(),
        item("menu.edit.history", "fth-clock", null, this::showHistory));

    Menu runMenu = new Menu(I18n.t("menu.run"));
    runMenu.getItems().addAll(
        item("menu.run.run", "fth-flag", new KeyCodeCombination(KeyCode.F5), this::runProgram),
        item("menu.run.debug", "fth-crosshair", new KeyCodeCombination(KeyCode.F6),
            this::debugProgram),
        item("menu.run.stop", "fth-octagon",
            new KeyCodeCombination(KeyCode.F5, KeyCombination.SHIFT_DOWN), this::stopProgram),
        item("menu.run.pause", "fth-pause", new KeyCodeCombination(KeyCode.F7), () -> {
          if (running.get()) setProgramPaused(!pauseToggle.isSelected());
        }),
        item("menu.run.step", "fth-skip-forward", new KeyCodeCombination(KeyCode.F8),
            this::stepFrame),
        new SeparatorMenuItem(),
        item("menu.run.check", "fth-check-circle", null, this::checkProject),
        item("menu.run.tests", "fth-check-square", null, this::runTeachingTests));

    Menu view = new Menu(I18n.t("menu.view"));
    view.getItems().addAll(
        item("view.menu.code", "fth-code", null, () -> showActiveView(ViewSwitch.View.CODE)),
        item("view.menu.split", "fth-columns", null,
            () -> showActiveView(ViewSwitch.View.SPLIT)),
        item("view.menu.visual", "fth-monitor", null,
            () -> showActiveView(ViewSwitch.View.VISUAL)),
        new SeparatorMenuItem(),
        item("diagram.menu", "fth-layout", null, this::openDiagrams),
        new SeparatorMenuItem(),
        item("menu.view.zoomin", "fth-zoom-in", null,
            () -> CodeEditor.FONT_SIZE.set(Math.min(36, CodeEditor.FONT_SIZE.get() + 1))),
        item("menu.view.zoomout", "fth-zoom-out", null,
            () -> CodeEditor.FONT_SIZE.set(Math.max(9, CodeEditor.FONT_SIZE.get() - 1))),
        item("menu.view.zoomreset", "fth-type", null, () -> CodeEditor.FONT_SIZE.set(14)),
        new SeparatorMenuItem(),
        item("theme.toggle", "fth-moon", null, () -> {
          Theme.toggle(scene);
          root.setTop(header());
        }),
        textSizeMenu(),
        highContrastItem(),
        compatibilityGraphicsItem());
    Menu language = new Menu(I18n.t("menu.language"), Icons.of("fth-globe"));
    ToggleGroup group = new ToggleGroup();
    for (I18n.Language l : I18n.Language.values()) {
      RadioMenuItem langItem = new RadioMenuItem(l == I18n.Language.DE ? "Deutsch" : "English");
      langItem.setToggleGroup(group);
      langItem.setSelected(I18n.current() == l);
      langItem.setOnAction(e -> switchLanguage(l));
      language.getItems().add(langItem);
    }
    view.getItems().add(language);

    Menu help = new Menu(I18n.t("menu.help"));
    help.getItems().addAll(
        item("menu.help.guide", "fth-book-open", null, this::showGuide),
        item("apihelp.title", "fth-help-circle", new KeyCodeCombination(KeyCode.F1),
            this::showApiHelp),
        item("welcome.tutorials", "fth-external-link", null,
            () -> browse("https://scratch4j.openpatch.org")),
        new SeparatorMenuItem(),
        teachersMenu(),
        new SeparatorMenuItem(),
        item("menu.help.about", "fth-info", null, this::showAbout));

    return new MenuBar(file, edit, projectMenu(), runMenu, view, help);
  }

  /**
   * Go to definition, find usages and rename live in the editor's right-click
   * menu; their keys keep working everywhere through the scene (a context
   * menu's accelerators are only live while it shows).
   */
  private void installEditorShortcuts() {
    scene.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
      CodeEditor active = editor == null ? null : editor.activeEditor();
      if (active == null || !active.area().isFocused()) {
        return;
      }
      int caret = active.area().getCaretPosition();
      if (e.getCode() == KeyCode.F12 && !e.isShiftDown()) {
        goToDefinition(active, caret);
      } else if (e.getCode() == KeyCode.F12) {
        findUsages(active, caret);
      } else if (e.getCode() == KeyCode.F2 && !e.isShiftDown()) {
        renameSymbol(active, caret);
      } else if (e.getCode() == KeyCode.F2) {
        renameClass(active.file());
      } else {
        return;
      }
      e.consume();
    });
  }

  private static KeyCombination shortcut(KeyCode code) {
    return new KeyCodeCombination(code, KeyCombination.SHORTCUT_DOWN);
  }

  private static MenuItem item(String key, String icon, KeyCombination accelerator,
      Runnable action) {
    MenuItem item = new MenuItem(I18n.t(key), Icons.of(icon));
    if (accelerator != null) {
      item.setAccelerator(accelerator);
    }
    item.setOnAction(e -> action.run());
    return item;
  }

  private void showHistory() {
    ScratchProject current = project.get();
    Tab tab = editor.getSelectionModel().getSelectedItem();
    if (current == null || tab == null) {
      alert(I18n.t("history.open.first"));
      return;
    }
    Node content = tab.getContent();
    Path file = content instanceof CodeEditor code ? code.file()
        : content instanceof StageDesignerView designer ? designer.file()
        : content instanceof ImageEditorView image ? image.file()
        : content instanceof SoundEditorView sound ? sound.file() : null;
    if (file == null) {
      alert(I18n.t("history.open.first"));
      return;
    }
    saveAll();
    if (content instanceof CodeEditor code && code.hasUnsavedChanges()) {
      return;
    }
    try {
      List<Path> revisions = LocalHistory.revisions(current.root(), file);
      if (revisions.isEmpty()) {
        alert(I18n.t("history.empty"));
        return;
      }
      javafx.scene.control.ListView<Path> list = new javafx.scene.control.ListView<>();
      list.getItems().setAll(revisions);
      list.setCellFactory(view -> new javafx.scene.control.ListCell<>() {
        @Override protected void updateItem(Path revision, boolean empty) {
          super.updateItem(revision, empty);
          if (empty || revision == null) {
            setText(null);
          } else {
            String when = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(java.time.ZoneId.systemDefault())
                .format(LocalHistory.timestamp(revision));
            setText(when + "  (" + revision.toFile().length() + " bytes)");
          }
        }
      });
      javafx.scene.control.TextArea preview = new javafx.scene.control.TextArea();
      preview.setEditable(false);
      preview.setWrapText(false);
      list.getSelectionModel().selectedItemProperty().addListener((o, old, selected) -> {
        if (selected == null) {
          preview.clear();
          return;
        }
        if (!SyntaxHighlighter.isText(file)) {
          preview.setText(I18n.t("history.binary"));
          return;
        }
        try {
          String text = Files.readString(selected);
          preview.setText(text.length() > 200_000 ? text.substring(0, 200_000) : text);
        } catch (IOException e) {
          preview.setText(e.getMessage());
        }
      });
      list.getSelectionModel().selectFirst();
      javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(8, list, preview);
      if (content instanceof ImageEditorView || content instanceof SoundEditorView) {
        box.getChildren().add(new Label(I18n.t("history.media.warning")));
      }
      list.setPrefHeight(180);
      preview.setPrefHeight(320);
      javafx.scene.control.ButtonType restore = new javafx.scene.control.ButtonType(
          I18n.t("history.restore"), javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
      javafx.scene.control.Dialog<Path> dialog = new javafx.scene.control.Dialog<>();
      dialog.setTitle(I18n.t("history.title", file.getFileName()));
      dialog.setHeaderText(null);
      dialog.getDialogPane().setContent(box);
      dialog.getDialogPane().setPrefWidth(680);
      dialog.getDialogPane().getButtonTypes().addAll(restore, I18n.cancel());
      dialog.setResultConverter(button -> button == restore
          ? list.getSelectionModel().getSelectedItem() : null);
      dialog.showAndWait().ifPresent(revision -> {
        try {
          LocalHistory.restore(current.root(), file, revision);
          if (content instanceof CodeEditor) {
            editor.reloadIfOpen(file);
          } else if (content instanceof StageDesignerView designer) {
            designer.reload();
          } else {
            editor.closeSelected();
            openByType(file);
          }
          onCodeSaved(file);
          setStatus(I18n.t("history.restored"));
        } catch (IOException e) {
          alert(I18n.t("error.save", e.getMessage()), e);
        }
      });
    } catch (IOException e) {
      alert(I18n.t("error.openfile", e.getMessage()), e);
    }
  }

  private void withEditor(java.util.function.Consumer<CodeEditor> action) {
    CodeEditor active = editor.activeEditor();
    if (active != null) {
      editor.revealCode(active); // the code was hidden behind its visual editor
      action.accept(active);
    }
  }

  /** Rebuilds the whole window in the new language and restores what was open. */
  private void switchLanguage(I18n.Language language) {
    saveAll();
    List<Path> openFiles = editor.openFiles();
    I18n.set(language);
    Prefs.language(language);
    ScratchProject p = project.get();
    buildUi();
    scene.setRoot(root);
    Theme.apply(scene);
    if (p != null) {
      open(p);
      openFiles.forEach(editor::open);
    }
  }

  // --- projects ------------------------------------------------------------------

  /**
   * {@code Main <projectDir>} opens that project right away.
   * {@code Main <project.zip|workspace.json> [<parentDir>]} imports it first, like
   * Project > Import project (ZIP / workspace JSON), into parentDir or the projects folder.
   */
  private void openFromArguments() {
    // null when started without Application.launch (layout tests)
    List<String> args = getParameters() == null ? List.of() : getParameters().getRaw();
    if (args.isEmpty()) return;
    Path first = Path.of(args.get(0)).toAbsolutePath();
    if (Files.isDirectory(first)) {
      openProjectAt(first);
    } else if (SharedProjectFile.accepts(first)) {
      importShared(first, args.size() > 1 ? Path.of(args.get(1)).toAbsolutePath() : ProjectsFolder.current());
    }
  }

  private void createProject() {
    NewProjectDialog.show(stage).ifPresent(result -> {
      try {
        // the default projects folder is made when the first project goes there
        Files.createDirectories(result.parentDir());
        Path allJar = LibraryJarSource.allJar(bundledLibraryDir());
        if (result.lesson() != null) {
          var lesson = org.openpatch.scratch4j.core.lesson.Lesson.bundled(result.lesson());
          Path created = BundledTemplates.create(lesson.template(), result.parentDir(),
              result.name(), allJar);
          org.openpatch.scratch4j.core.lesson.Lesson.install(created, lesson.id());
        } else if (result.example() != null) {
          BundledTemplates.create(result.example(), result.parentDir(), result.name(), allJar);
        } else {
          NewProject.create(result.template(), result.parentDir(), result.name(), allJar);
        }
        openProjectAt(result.parentDir().resolve(result.name()));
      } catch (IOException e) {
        NewProjectDialog.showError(e.getMessage(), e);
      }
    });
  }

  /**
   * Scratch 3 project to Scratch for Java: sprites become classes, the stage
   * keeps everything's place in its designer regions. Scripts are rebuilt by
   * hand; the notes say where.
   */
  private void importScratchProject() {
    javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
    chooser.setTitle(I18n.t("menu.file.sb3"));
    chooser.getExtensionFilters().add(
        new javafx.stage.FileChooser.ExtensionFilter("Scratch 3 (*.sb3)", "*.sb3"));
    File sb3 = chooser.showOpenDialog(stage);
    if (sb3 == null) {
      return;
    }
    javafx.stage.DirectoryChooser parent = new javafx.stage.DirectoryChooser();
    parent.setTitle(I18n.t("sb3.target"));
    parent.setInitialDirectory(ProjectsFolder.existing(ProjectsFolder.current()));
    File folder = parent.showDialog(stage);
    if (folder == null) {
      return;
    }
    String name = sb3.getName().replaceFirst("(?i)\\.sb3$", "").replaceAll("[^\\w\\- ]", "")
        .trim();
    if (name.isEmpty()) {
      name = "ScratchProject";
    }
    String projectName = name;
    for (int i = 2; Files.exists(folder.toPath().resolve(projectName)); i++) {
      projectName = name + i;
    }
    String target = projectName;
    importing(sb3.getName(), folder.toPath(), progress -> org.openpatch.scratch4j.core.project.Sb3Importer
        .importProject(sb3.toPath(), folder.toPath(), target,
            LibraryJarSource.allJar(bundledLibraryDir()), mp3 -> {
              try {
                Path wav = mp3.resolveSibling(mp3.getFileName().toString()
                    .replaceFirst("(?i)\\.mp3$", ".wav"));
                org.openpatch.scratch4j.sound.SoundIO.writeWav(
                    org.openpatch.scratch4j.sound.SoundIO.decode(mp3), wav);
                return wav;
              } catch (IOException | RuntimeException e) {
                return null;
              }
            }, progress),
        result -> {
          openProjectAt(result.root());
          console.info("\u2714 " + I18n.t("sb3.done", result.spriteClasses().size()));
          for (String note : result.notes()) {
            console.info("\u2022 " + note);
          }
          bottomTabs.getSelectionModel().select(consoleTab);
        },
        message -> I18n.t("sb3.failed", message));
  }

  /** An import for {@link #importing}: runs on a worker thread and reports its steps. */
  private interface ImportJob<T> {
    T run(org.openpatch.scratch4j.core.project.ImportProgress progress) throws IOException;
  }

  /**
   * Runs an import of {@code fileName} into {@code parentDir} on a worker
   * thread behind a progress window, so a large project does not look like a
   * hung IDE; then {@code done} with the result on the FX thread, or an alert
   * with the error message as {@code failed} words it. A cancelled import
   * leaves nothing behind: what it added to {@code parentDir} goes again.
   */
  private <T> void importing(String fileName, Path parentDir, ImportJob<T> job,
      java.util.function.Consumer<T> done,
      java.util.function.UnaryOperator<String> failed) {
    String what = I18n.t("import.running", fileName);
    setStatus(what);
    java.util.Set<Path> before = children(parentDir);
    ProgressWindow progress = new ProgressWindow(stage, what);
    progress.show();
    Thread worker = new Thread(() -> {
      try {
        T result = job.run(progress::step);
        if (progress.cancelled()) {
          throw new ProgressWindow.Cancelled(); // cancelled after its last step
        }
        Platform.runLater(() -> {
          progress.close();
          setStatus(""); // opening the project says so
          done.accept(result);
        });
      } catch (IOException | RuntimeException | Error e) {
        // an Error too (out of memory drawing costumes): the window must not stay
        if (!progress.cancelled()) {
          String message = e.getMessage() == null ? e.toString() : e.getMessage();
          Platform.runLater(() -> {
            progress.close();
            setStatus(I18n.t("import.failed"));
            alert(failed.apply(message), e);
          });
          return;
        }
        // cancelled (an importer may have wrapped the exception): nothing stays behind
        java.util.Set<Path> after = before == null ? null : children(parentDir);
        for (Path added : after == null ? java.util.Set.<Path>of() : after) {
          if (!before.contains(added)) {
            try {
              org.openpatch.scratch4j.core.project.PortableProject.deleteTree(added);
            } catch (IOException | RuntimeException ignored) {
              // what is left is an unfinished folder the student can delete
            }
          }
        }
        Platform.runLater(() -> {
          progress.close();
          setStatus(I18n.t("import.cancelled"));
        });
      }
    }, "project-import");
    worker.setDaemon(true);
    worker.start();
  }

  /** The entries of {@code dir}: none when it is missing, null when it can't be read. */
  private static java.util.Set<Path> children(Path dir) {
    if (Files.notExists(dir)) return java.util.Set.of();
    try (var entries = Files.list(dir)) {
      return entries.collect(java.util.stream.Collectors.toSet());
    } catch (IOException | RuntimeException e) {
      return null;
    }
  }

  private void openProject() {
    DirectoryChooser chooser = new DirectoryChooser();
    chooser.setTitle(I18n.t("menu.file.open"));
    chooser.setInitialDirectory(ProjectsFolder.existing(ProjectsFolder.current()));
    File dir = chooser.showDialog(stage);
    if (dir != null) {
      openProjectAt(dir.toPath());
    }
  }

  void openProjectAt(Path dir) {
    try {
      open(ScratchProject.open(dir));
    } catch (IOException e) {
      alert(I18n.t("error.open", dir, e.getMessage()), e);
    }
  }

  private void open(ScratchProject p) {
    saveAll();
    stopProgram();
    project.set(p);
    learning.open(p);
    checkedProblems = List.of();
    crashProblems.clear();
    checkProblems.clear();
    ProjectCheck.Cache oldCache = checkCache;
    checkCache = new ProjectCheck.Cache(p.root());
    if (oldCache != null && !checkRunning.get()) {
      closeQuietly(oldCache); // a running check closes its own when it ends
    }
    try { apiIndex.useProject(p); palette.refresh(); }
    catch (IOException e) { console.err(e.getMessage()); }
    Prefs.addRecentProject(p.root());
    ProjectsFolder.remember(p.root().getParent());
    stage.setTitle(p.name() + " — Scratch for Java Studio");
    projectTitle.setText(p.name());
    hasProject.set(true);
    fileTree.setRoot(p.root());
    editor.clear();
    selector = new StageSelectorView(p, this::openDesigner,
        cls -> editor.open(p.root().resolve(cls + ".java")),
        () -> {
          fileTree.reload();
          // settings and the start star may rewrite the window class
          for (Path open : editor.openFiles()) editor.reloadIfOpen(open);
        });
    selector.setOnRun(this::runStage);
    selector.setOnInsertSwitch(snippet -> {
      CodeEditor target = editor.activeEditor();
      if (target == null) {
        List<Path> files = editor.openFiles();
        if (!files.isEmpty()) {
          editor.open(files.get(files.size() - 1));
          target = editor.activeEditor();
        }
      }
      if (target == null) {
        setStatus(I18n.t("stages.switch.open.code"));
      } else {
        insertWithImports(target, snippet);
      }
    });
    selector.setOnDuplicate((oldName, newName) -> {
      editor.saveAll();
      StageManagement.duplicate(p, oldName, newName);
      fileTree.reload();
      checkProject();
    });
    selector.setOnRename((oldName, newName) -> {
      editor.saveAll();
      StageManagement.rename(p, oldName, newName);
      editor.closeForFile(p.root().resolve(oldName + ".java"));
      fileTree.reload();
      checkProject();
    });
    selector.setOnDelete((name, ignored) -> {
      editor.saveAll();
      StageManagement.delete(p, name);
      editor.closeForFile(p.root().resolve(name + ".java"));
      fileTree.reload();
      checkProject();
    });
    // the same questions as the Files tree: is the stage still used?
    selector.setOnDeleteRequest(name -> deleteFile(p.root().resolve(name + ".java")));
    sidebar.getTabs().get(0).setContent(selector);
    sidebar.getSelectionModel().select(0);
    root.setCenter(workHost);
    loading.show(I18n.t("loading.project", p.name()), I18n.t("loading.files"), 0.2);
    updatePaletteContext();
    console.clear();
    problems.setProblems(List.of());
    List<org.openpatch.scratch4j.core.project.ScratchMigration.Task> migration;
    try {
      migration = org.openpatch.scratch4j.core.project.ScratchMigration.load(p.root());
    } catch (IOException e) {
      migration = List.of();
      console.err(e.getMessage());
    }
    migrationPane.setTasks(p.root(), migration);
    // the Scratch migration tab only for a project that came from an .sb3
    if (migration.isEmpty()) {
      hideTab(migrationTab);
    } else {
      showTab(migrationTab, false);
    }
    hideTab(searchTab);
    hideTab(debuggerTab);
    hideTab(variablesTab);
    boolean ranBefore;
    try {
      ranBefore = org.openpatch.scratch4j.core.io.ProjectSnapshots.lastWorking(p.root()) != null;
    } catch (IOException e) {
      ranBefore = false;
    }
    hasRun.set(ranBefore);
    openLesson(p);
    stuckDelay.stop();
    problems.hideStuck();
    updateProblemsPlaceholder();
    setStatus(I18n.t("status.opened", p.name()));
    // the start stage's designer takes the longest (most of the opening): after a
    // frame, so the card is drawn first and says what is loading
    loading.step(I18n.t("loading.stage"), 0.4);
    LoadingOverlay.next(() -> {
      if (project.get() != p) return; // another project was opened meanwhile
      try {
        openStart(p);
      } finally {
        loading.step(I18n.t("loading.ready"), 1);
        LoadingOverlay.next(loading::hide);
      }
      checkProject();
      checkLibrary(p, false);
    });
  }

  /** Shows the start stage in its designer, or the code of a Window class or main class. */
  private void openStart(ScratchProject p) {
    try {
      String start = p.firstStage();
      if (p.stageClasses().contains(start)) {
        openDesigner(start);
        selector.select(start);
      } else if (!start.isEmpty() && Files.isRegularFile(p.root().resolve(start + ".java"))) {
        // a Window subclass or plain main class starts the program: show its code
        editor.open(p.root().resolve(start + ".java"));
      }
    } catch (IOException ignored) {
      // no stage yet: the welcome hint in the centre explains what to do
    }
  }

  /** The loading card over the work area (tests). */
  boolean loadingShowing() {
    return loading.showing();
  }

  /** Whether the bar beside the status text shows the program starting (tests). */
  boolean runProgressShowing() {
    return runProgress.isVisible();
  }

  private void showRunProgress(boolean show) {
    runProgress.setVisible(show);
    runProgress.setManaged(show);
  }

  /** Opens a file in the right editor by its type (code, image, sound). */
  private void openByType(Path file) {
    String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
    if (name.matches(".*\\.(png|jpg|jpeg|gif)$")) {
      editor.openTool("image:" + file, file.getFileName().toString(), "fth-image", () -> {
        try {
          ImageEditorView paint = new ImageEditorView(project.get(), file);
          paint.setOnSpriteWritten(this::afterSpriteClassWritten);
          paint.setOnSaved(saved -> afterImageSaved());
          return paint;
        } catch (IOException e) {
          alert(I18n.t("error.openfile", e.getMessage()), e);
          return null;
        }
      });
    } else if (name.matches(".*\\.(wav|ogg|mp3|aif|aiff|au)$")) {
      editor.openTool("sound:" + file, file.getFileName().toString(), "fth-music", () -> {
        try {
          return new SoundEditorView(project.get().root(), file);
        } catch (IOException e) {
          alert(I18n.t("error.openfile", e.getMessage()), e);
          return null;
        }
      });
    } else if (name.endsWith(".svg")) {
      // the library loads bitmaps only: draw the SVG into a PNG next to it, then paint that
      Path png = file.resolveSibling(file.getFileName().toString().replaceFirst("(?i)\\.svg$", "")
          + ".png");
      try {
        if (!Files.exists(png)) {
          org.openpatch.scratch4j.core.assets.SvgRasterizer.toPng(Files.readAllBytes(file), 1, png);
          fileTree.reload();
          setStatus(I18n.t("svg.converted", png.getFileName()));
        }
        openByType(png);
      } catch (IOException e) {
        alert(I18n.t("error.openfile", e.getMessage()), e);
      }
    } else if (name.matches(".*\\.(ttf|otf)$")) {
      editor.openTool("font:" + file, file.getFileName().toString(), "fth-type", () -> {
        try {
          return new FontPreviewView(project.get().root(), file, snippet -> {
            CodeEditor target = editor.activeEditor();
            if (target != null) target.insertBlock(snippet);
            else setStatus(I18n.t("font.open.code"));
          });
        } catch (IOException e) {
          alert(I18n.t("error.openfile", e.getMessage()), e);
          return null;
        }
      });
    } else if (name.endsWith(".tmx")) {
      editor.openTool("map:" + file, file.getFileName().toString(), "fth-map", () -> {
        try {
          return new MapEditorView(project.get(), file, saved -> {
            editor.reloadIfOpen(saved);
            fileTree.reload();
            reloadDesigners();
            scheduleCheck();
          }, () -> editor.open(file));
        } catch (IOException e) {
          alert(I18n.t("error.openfile", e.getMessage()), e);
          // an unreadable map still opens as XML
          editor.open(file);
          return null;
        }
      });
    } else if (SyntaxHighlighter.isText(file)) {
      try {
        editor.open(file);
      } catch (java.io.UncheckedIOException e) {
        alert(I18n.t("error.openfile", e.getMessage()), e);
      }
    }
  }

  /** View menu: the active class tab's code, both, or its visual editor. */
  private void showActiveView(ViewSwitch.View view) {
    CodeEditor active = editor.activeEditor();
    VisualMode.Kind kind = active == null ? VisualMode.Kind.NONE : VisualMode.kind(active.file());
    if (kind == VisualMode.Kind.STAGE || kind == VisualMode.Kind.SPRITE) {
      showView(active.file(), view);
    } else if (kind == VisualMode.Kind.WINDOW && view != ViewSwitch.View.CODE) {
      openVisualForFile(active.file());
    } else if (view != ViewSwitch.View.CODE) {
      setStatus(I18n.t("mode.unavailable"));
    }
  }

  /**
   * Opens a class's visual editor, or closes it when it is open: the
   * designer for a stage, the sprite editor for a sprite, the settings
   * dialog for the window class.
   */
  void openVisualForFile(Path file) {
    CodeEditor open = editor.editorFor(file);
    if (open != null && open.sidePanel() != null) {
      showView(file, ViewSwitch.View.CODE);
      return;
    }
    openVisual(file);
    CodeEditor opened = editor.editorFor(file);
    if (opened != null) learning.event("view", Map.of("view", opened.view().name(), "kind", LearningSupport.kind(file)));
  }

  /** The visual editor of a class, in the view last chosen for it (beside the code or alone). */
  private void openVisual(Path file) {
    if (VisualMode.isStageSource(file)) {
      String stageClass = file.getFileName().toString().replaceFirst("\\.java$", "");
      CodeEditor code = editor.editorFor(file);
      String caretLine = code == null ? null : code.caretLineText();
      openDesigner(stageClass);
      if (editor.tool("designer:" + stageClass) instanceof StageDesignerView view) {
        view.selectFromCodeLine(caretLine);
      }
    } else if (VisualMode.isSpriteSource(file)) {
      openSpriteAssets(file);
    } else if (VisualMode.isWindowSource(file) && selector != null) {
      saveAll();
      selector.showWindowSettings();
    } else {
      setStatus(I18n.t("mode.unavailable"));
    }
  }

  /**
   * Visual-to-code switch: open the stage's code at the line that creates the
   * selected object ({@code name = new ...}), or at the top when none is selected.
   */
  /**
   * Designer and code side by side follow each other: a selected object's
   * lines light up in the code, the caret on such a line selects the object.
   */
  private void linkDesigner(Path codeFile, StageDesignerView view) {
    CodeEditor code = editor.editorFor(codeFile);
    if (code == null) return;
    view.setSourceSupplier(code::content);
    // one history: the designer's undo steps back through the code's
    view.setUndoDelegates(() -> {
      code.undo();
      saveAll();
      view.reload();
    }, () -> {
      code.redo();
      saveAll();
      view.reload();
    });
    boolean[] fromCode = {false};
    view.setOnSelectionChanged(name -> code.showLinkedLines(
        name == null ? List.of() : objectLines(code.content(), name), !fromCode[0]));
    code.setOnCaretLine(line -> {
      if (code.sidePanel() != view) return;
      fromCode[0] = true;
      try {
        view.selectFromCode(line, code.caretLine());
      } finally {
        fromCode[0] = false;
      }
    });
  }

  /** Opens a project class's code (a library class: its help). */
  void openClass(String type) {
    ScratchProject p = project.get();
    if (p == null || type == null) return;
    Path file = p.root().resolve(type + ".java");
    if (Files.isRegularFile(file)) {
      editor.open(file);
    } else {
      learning.help("api", null);
      ApiHelpDialog.showForWord(apiIndex, type, this::browse);
    }
  }

  /** The same for the sprite editor: an entry and the line that adds it. */
  private void linkSpriteEditor(Path codeFile, SpriteAssetsView view) {
    CodeEditor code = editor.editorFor(codeFile);
    if (code == null) return;
    view.setSourceSupplier(code::content);
    boolean[] fromCode = {false};
    view.setOnEntrySelected(entry -> {
      List<Integer> lines = new ArrayList<>();
      for (var call : org.openpatch.scratch4j.core.region.AssetCalls.of(code.content())) {
        if (entry.name().equals(call.string(0))) lines.add((int) call.line());
      }
      code.showLinkedLines(lines, !fromCode[0]);
    });
    code.setOnCaretLine(line -> {
      if (code.sidePanel() != view) return;
      fromCode[0] = true;
      try {
        view.selectFromCodeLine(line);
      } finally {
        fromCode[0] = false;
      }
    });
  }

  /**
   * A problem's fix (the button under it, the light bulb in the gutter): the
   * stage designer taking over a sprite, or one of the {@link QuickFixes} for common errors.
   */
  private void fixProblem(Problem problem) {
    if (problem.file() == null || (project.get() != null && project.get().settings().hintsOnly)) return;
    if (ProjectCheck.FIX_PROMOTE.equals(problem.fix())) {
      promoteSprite(problem.file(), (int) problem.line());
    } else if (problem.fix() != null) {
      applyQuickFix(problem);
    }
  }

  /** One-click edit of the saved file; the local history keeps the version before. */
  void applyQuickFix(Problem problem) {
    if (project.get() == null || project.get().settings().hintsOnly) return;
    try {
      saveAll();
      String source = Files.readString(problem.file());
      String fixed = QuickFixes.apply(source, problem.fix(), problem.line(), problem.column(),
          problem.fixData());
      if (fixed == null) {
        // the code changed since the check: the next check says what is left
        setStatus(I18n.t("problems.fix.stale"));
        scheduleCheck();
        return;
      }
      org.openpatch.scratch4j.core.io.LocalHistory.writeString(project.get().root(),
          problem.file(), fixed);
      editor.reloadIfOpen(problem.file());
      learning.help("fix.applied", problem);
      if (QuickFixes.RENAME.equals(problem.fix())) learning.help("rename", problem);
      scheduleCheck();
      setStatus(I18n.t("problems.fixed.quick"));
    } catch (IOException | RuntimeException e) {
      alert(e.getMessage(), e);
    }
  }

  /** "Make editable in the designer": the sprite's lines become the designer's. */
  void promoteSprite(Path file, int line) {
    try {
      saveAll();
      String promoted = org.openpatch.scratch4j.core.region.DesignerPromotion.apply(
          Files.readString(file), line);
      org.openpatch.scratch4j.core.io.LocalHistory.writeString(project.get().root(), file,
          promoted);
      editor.reloadIfOpen(file);
      reloadDesigners();
      scheduleCheck();
      learning.help("fix.applied", null);
      setStatus(I18n.t("problems.fixed.promote"));
    } catch (IOException | RuntimeException e) {
      alert(e.getMessage(), e);
    }
  }

  /** Lines (1-based) of the designer's regions that mention {@code name}. */
  static List<Integer> objectLines(String source, String name) {
    List<Integer> out = new ArrayList<>();
    java.util.regex.Pattern word = java.util.regex.Pattern.compile(
        "(?<![\\w.])" + java.util.regex.Pattern.quote(name) + "(?![\\w(])");
    try {
      for (var region : org.openpatch.scratch4j.core.region.RegionParser.parse(source)) {
        if (!region.id().equals("fields") && !region.id().equals("setup")) continue;
        int line = (int) source.substring(0, region.beginOffset()).chars()
            .filter(c -> c == '\n').count() + 1;
        for (String text : source.substring(region.beginOffset(), region.endOffset())
            .split("\n", -1)) {
          if (word.matcher(text).find()) out.add(line);
          line++;
        }
      }
    } catch (RuntimeException e) {
      // unreadable regions: nothing to show
    }
    return out;
  }

  private void openCodeAtObject(Path stageFile, String objectName) {
    int line = 0;
    if (objectName != null) {
      try {
        List<String> lines = Files.readAllLines(stageFile);
        java.util.regex.Pattern creation = java.util.regex.Pattern.compile(
            "(?<![\\w.])" + java.util.regex.Pattern.quote(objectName) + "\\s*=\\s*new\\b");
        for (int i = 0; i < lines.size(); i++) {
          if (creation.matcher(lines.get(i)).find()) {
            line = i + 1;
            break;
          }
        }
      } catch (IOException ignored) {
        // open at the top
      }
    }
    editor.openAt(stageFile, line);
  }

  /** The animation editor for one file-pattern animation of an AnimatedSprite class. */
  private void openAnimationEditor(Path spriteFile, String name, String pattern) {
    ScratchProject p = project.get();
    if (p == null) return;
    String className = spriteFile.getFileName().toString().replaceFirst("\\.java$", "");
    editor.openTool("animation:" + spriteFile + ":" + name, className + " \u00b7 " + name,
        "fth-film", () -> new AnimationEditorView(p, spriteFile, name, pattern,
            this::openByType, this::afterSpriteClassWritten));
  }

  /** Breakpoints read on the FX thread before a debug run starts. */
  private java.util.Map<Path, java.util.Set<Integer>> breakpointsNow() {
    java.util.concurrent.atomic.AtomicReference<java.util.Map<Path, java.util.Set<Integer>>> out =
        new java.util.concurrent.atomic.AtomicReference<>();
    if (Platform.isFxApplicationThread()) {
      return editor.breakpoints();
    }
    java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
    Platform.runLater(() -> {
      out.set(editor.breakpoints());
      done.countDown();
    });
    try {
      done.await(5, java.util.concurrent.TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return out.get() == null ? java.util.Map.of() : out.get();
  }

  private void sendToProgram(String command) {
    RunHandle handle = currentRun.get();
    if (handle != null && running.get()) {
      handle.send(command);
    }
  }

  /**
   * Pauses or continues the game loop. The buttons follow the program's
   * answer ({@code onPaused}, {@code onResumed}), not the click.
   */
  private void setProgramPaused(boolean pause) {
    if (!running.get()) {
      pauseToggle.setSelected(false);
      return;
    }
    pauseToggle.setSelected(pause);
    sendToProgram(pause ? "pause" : "resume");
  }

  /** One frame: every run() once, then paused again. Pauses first if needed. */
  private void stepFrame() {
    if (!running.get()) return;
    if (!programPaused) {
      setProgramPaused(true);
    } else {
      sendToProgram("step");
    }
  }

  private void setSpeed(int divisor) {
    speedButton.setText(divisor == 1 ? "1×" : "1/" + divisor + "×");
    sendToProgram("speed " + 60f / divisor);
  }

  /** A new or ended run: running, normal speed. */
  private void resetGameLoop() {
    programPaused = false;
    pauseToggle.setSelected(false);
    speedButton.setText("1×");
    speeds.getToggles().stream().filter(t -> Integer.valueOf(1).equals(t.getUserData()))
        .findFirst().ifPresent(t -> t.setSelected(true));
  }

  /** Saves the running program's current frame to screenshots/. */
  private void captureScreenshot() {
    Path file = mediaFile("png");
    if (file != null) {
      sendToProgram("screenshot " + file.toAbsolutePath());
    }
  }

  private void toggleRecording(boolean on) {
    if (on) {
      Path file = mediaFile("gif");
      if (file == null) {
        recordToggle.setSelected(false);
        return;
      }
      sendToProgram("gif start " + file.toAbsolutePath());
      console.info("\u25CF " + I18n.t("run.recording"));
    } else {
      sendToProgram("gif stop");
    }
  }

  private Path mediaFile(String extension) {
    ScratchProject p = project.get();
    if (p == null) {
      return null;
    }
    try {
      Path folder = p.root().resolve("screenshots");
      Files.createDirectories(folder);
      String stamp = java.time.LocalDateTime.now().format(
          java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
      return folder.resolve(p.name().replaceAll("[^A-Za-z0-9_-]", "") + "-" + stamp + "."
          + extension);
    } catch (IOException e) {
      alert(e.getMessage(), e);
      return null;
    }
  }

  /**
   * A program whose frame count stops for 3 seconds (or that never shows a
   * window) is frozen - almost always a loop that never ends. The console
   * explains it once; the Problems tab shows where the transition lint found it.
   * On the loading screen it gets 20 seconds: a slow PC draws nothing for a
   * while when the stage takes over.
   */
  void watchdog(long frames, boolean loading) {
    long now = System.currentTimeMillis();
    if (runProgress.isVisible() && running.get()) {
      if (frames > 0 && !loading) {
        // the first frame is drawn: the program runs
        showRunProgress(false);
        setStatus(I18n.t("status.running", startingStage));
      } else if (loading) {
        setStatus(I18n.t("run.phase.loading", startingStage));
      }
    }
    if (frames != watchFrames) {
      if (watchWarned && frames > watchFrames && watchFrames >= 0) {
        console.info("\u2714 " + I18n.t("run.unfrozen"));
        watchWarned = false;
      }
      watchFrames = frames;
      watchChanged = now;
      if (frames > 0 && !lessonRunCounted && running.get()) {
        lessonRunCounted = true;
        lessonRan();
      }
      // drawn for a few seconds without a crash: this code works
      if (frames > 0 && now - Math.max(watchStarted, lastSwap) >= 4000 && !runCrashed
          && !runKept && running.get()) {
        runKept = true;
        hasRun.set(true);
        keepWorkingVersion();
      }
      return;
    }
    if (debugPaused || programPaused) {
      watchChanged = now;
      return;
    }
    boolean stalled = frames >= 0 && now - watchChanged >= (loading ? 20000 : 3000);
    boolean noWindow = frames < 0 && now - watchStarted >= 10000;
    if ((stalled || noWindow) && !watchWarned && running.get()) {
      watchWarned = true;
      console.err("\u26A0 " + I18n.t(stalled ? "run.frozen" : "run.nowindow"));
      console.info(I18n.t("run.frozen.explain"));
      if (noWindow && !org.openpatch.scratch4j.runner.GraphicsCompatibility.isSoftware()) {
        console.hint("\u2139 " + I18n.t("graphics.nowindow.hint"));
      }
      setStatus(I18n.t(stalled ? "run.frozen" : "run.nowindow"));
      checkProject();
    }
  }

  /** A tool wrote into a sprite class: show the new code everywhere it is open. */
  private void afterSpriteClassWritten(Path classFile) {
    editor.reloadIfOpen(classFile);
    reloadDesigners();
    scheduleCheck();
  }

  /** Console stack-trace click: find the source file by name and jump to the line. */
  private void openSourceAt(String fileName, int line) {
    ScratchProject p = project.get();
    if (p == null) {
      return;
    }
    try {
      p.javaSources().stream()
          .filter(s -> s.getFileName().toString().equals(fileName))
          .findFirst()
          .ifPresent(file -> editor.openAt(file, line));
    } catch (IOException ignored) {
      // project folder vanished
    }
  }

  /** The built-in asset library (841 images, 266 sounds). */
  private void openAssetLibrary() {
    ScratchProject p = project.get();
    if (p == null) {
      alert(I18n.t("status.no.project"));
      return;
    }
    editor.openTool("library", I18n.t("library.title"), "fth-image",
        () -> new AssetLibraryView(p, this::insertAsset, copied -> {
          fileTree.reload();
          if (copied != null) {
            openByType(copied);
          }
        }));
  }

  /** Library insert: costume/sound call at the caret of the last code editor. */
  private void insertAsset(String kind, String reference) {
    CodeEditor target = editor.activeEditor();
    if (target == null) {
      ScratchProject p = project.get();
      List<Path> files = editor.openFiles();
      if (!files.isEmpty()) {
        editor.open(files.get(files.size() - 1));
        target = editor.activeEditor();
      } else if (p != null) {
        setStatus(I18n.t("library.open.editor.first"));
        return;
      }
    }
    if (target != null) {
      target.insertBlock(("image".equals(kind) ? "this.addCostume(\"" : "this.addSound(\"")
          + reference + "\");\n");
    }
  }

  /** Opens (or focuses) the designer tab for a stage class. */
  private void openDesigner(String stageClass) {
    ScratchProject p = project.get();
    if (p == null) {
      return;
    }
    Path stageFile;
    try {
      stageFile = p.sourceOf(stageClass);
    } catch (IOException e) {
      stageFile = null;
    }
    if (stageFile == null) stageFile = p.root().resolve(stageClass + ".java");
    Path codeFile = stageFile;
    // beside the stage's code, in its tab
    editor.openSide(stageFile, "designer:" + stageClass, () -> {
      try {
        StageDesignerView view = new StageDesignerView(p, stageClass, file -> {
          editor.reloadIfOpen(file);
          if (selector != null) {
            selector.refresh();
          }
          scheduleCheck();
        });
        // "Open code": the panel closes, the caret goes to the selected object's line
        view.setOnOpenCode(() -> showView(codeFile, ViewSwitch.View.CODE));
        view.setOnNewSpriteClass(() -> createClass(NewClass.Kind.SPRITE));
        view.setOnEditSpriteAssets(this::openSpriteAssets);
        view.setOnOpenClass(this::openClass);
        linkDesigner(codeFile, view);
        view.setOnMapObject((map, id) -> {
          openByType(map);
          if (editor.tool("map:" + map) instanceof MapEditorView mapEditor) {
            mapEditor.selectObjectById(id);
          }
        });
        return view;
      } catch (IOException e) {
        alert(I18n.t("error.designer", e.getMessage()), e);
        return null;
      }
    });
  }

  private void openSpriteAssets(String className) {
    ScratchProject p = project.get();
    if (p != null) openSpriteAssets(p.root().resolve(className + ".java"));
  }

  private void chooseSpriteAssets() {
    ScratchProject p = project.get();
    if (p == null) return;
    try {
      List<String> classes = p.spriteClasses();
      if (classes.isEmpty()) {
        alert(I18n.t("designer.no.sprites"));
        return;
      }
      javafx.scene.control.ChoiceDialog<String> dialog =
          new javafx.scene.control.ChoiceDialog<>(classes.get(0), classes);
      dialog.setTitle(I18n.t("spriteassets.button"));
      dialog.setHeaderText(null);
      dialog.setContentText(I18n.t("spriteassets.choose"));
      Theme.style(dialog);
      dialog.showAndWait().ifPresent(this::openSpriteAssets);
    } catch (IOException e) {
      alert(e.getMessage(), e);
    }
  }

  /** Opens a sprite class's costumes, sounds and hitbox (also used by tests). */
  void openSpriteAssets(Path sourceFile) {
    ScratchProject p = project.get();
    if (p == null || sourceFile == null) return;
    if (!VisualMode.isSpriteSource(sourceFile)) {
      alert(I18n.t("spriteassets.not.sprite"));
      return;
    }
    saveAll();
    editor.openSide(sourceFile, "sprite-assets:" + sourceFile, () -> {
          try {
            SpriteAssetsView view = new SpriteAssetsView(p, sourceFile, () -> {
              editor.reloadIfOpen(sourceFile);
              reloadDesigners();
              scheduleCheck();
            });
            // a hitbox saves itself while it is being shaped: the code beside follows at
            // once, a stage designer in another tab when it is shown (it reloads then)
            view.setOnHitboxSaved(() -> {
              editor.reloadIfOpen(sourceFile);
              reloadShownDesigners();
              scheduleCheck();
            });
            linkSpriteEditor(sourceFile, view);
            view.setOnOpenAnimation(this::openAnimationEditor);
            view.setOnEditFile(this::openByType);
            return view;
          } catch (IOException e) {
            alert(I18n.t("error.openfile", e.getMessage()), e);
            return null;
          }
        });
  }

  private void createClass(NewClass.Kind preset) {
    ScratchProject p = project.get();
    if (p == null) {
      alert(I18n.t("status.no.project"));
      return;
    }
    NewClassDialog.show(preset).ifPresent(result -> {
      try {
        Path file = NewClass.create(result.kind(), p.root(), result.name());
        fileTree.reload();
        if (selector != null) {
          selector.refresh();
        }
        reloadDesigners();
        if (result.kind() == NewClass.Kind.STAGE) {
          openDesigner(result.name());
        } else {
          editor.open(file);
        }
        scheduleCheck();
      } catch (IOException | RuntimeException e) {
        alert(e.getMessage(), e);
      }
    });
  }

  /** File > Add to project: one dialog for everything a project can get. */
  private void addToProject() {
    if (project.get() == null) {
      alert(I18n.t("status.no.project"));
      return;
    }
    AddToProjectDialog.show(stage).ifPresent(choice -> {
      switch (choice) {
        case CLASS -> createClass(null);
        case IMAGE -> createAsset(ProjectAssetCreator.Kind.IMAGE);
        case SOUND -> createAsset(ProjectAssetCreator.Kind.SOUND);
        case LIBRARY -> openAssetLibrary();
        case FOLDER -> createFolder(fileTree.folderForCreation());
        case SHADER -> createShader();
        case MAP -> createMap();
      }
    });
  }

  /** File > Export: the targets as cards, then the export itself. */
  private void showExport() {
    if (project.get() == null) {
      alert(I18n.t("status.no.project"));
      return;
    }
    ExportDialog.show(stage).ifPresent(format -> {
      if (ExportDialog.APP_INFO.equals(format)) {
        editAppInfo();
      } else {
        export(format);
      }
    });
  }

  private void createShader() {
    if (project.get() == null) {
      alert(I18n.t("status.no.project"));
      return;
    }
    // Scratch for Java 7 shaders are fragment shaders only
    createAsset(ProjectAssetCreator.Kind.FRAGMENT_SHADER);
  }

  private void createAsset(ProjectAssetCreator.Kind kind) {
    ScratchProject p = project.get();
    if (p == null) {
      alert(I18n.t("status.no.project"));
      return;
    }
    javafx.scene.control.TextField name = new javafx.scene.control.TextField();
    name.setPromptText(I18n.t("asset.new.name"));
    javafx.scene.layout.VBox fields = new javafx.scene.layout.VBox(8,
        new Label(I18n.t("asset.new.name")), name);
    javafx.scene.control.TextField width = new javafx.scene.control.TextField("64");
    javafx.scene.control.TextField height = new javafx.scene.control.TextField("64");
    if (kind == ProjectAssetCreator.Kind.IMAGE) {
      HBox dimensions = new HBox(8, new Label(I18n.t("asset.new.width")), width,
          new Label(I18n.t("asset.new.height")), height);
      dimensions.setAlignment(Pos.CENTER_LEFT);
      width.setPrefColumnCount(4);
      height.setPrefColumnCount(4);
      fields.getChildren().add(dimensions);
    }
    javafx.scene.control.Dialog<javafx.scene.control.ButtonType> dialog =
        new javafx.scene.control.Dialog<>();
    dialog.setTitle(I18n.t(switch (kind) {
      case IMAGE -> "asset.new.image";
      case SOUND -> "asset.new.sound";
      case FRAGMENT_SHADER -> "asset.new.shader";
    }));
    dialog.setHeaderText(null);
    Theme.style(dialog);
    dialog.getDialogPane().setContent(fields);
    dialog.getDialogPane().getButtonTypes().addAll(I18n.ok(), I18n.cancel());
    Platform.runLater(name::requestFocus);
    if (dialog.showAndWait().orElse(null) != I18n.ok()) {
      return;
    }
    try {
      int w = kind == ProjectAssetCreator.Kind.IMAGE ? Integer.parseInt(width.getText()) : 1;
      int h = kind == ProjectAssetCreator.Kind.IMAGE ? Integer.parseInt(height.getText()) : 1;
      Path file = ProjectAssetCreator.create(p.root(), kind, name.getText().trim(), w, h);
      fileTree.reload();
      openByType(file);
      setStatus(I18n.t("asset.new.created", file.getFileName()));
    } catch (IOException | NumberFormatException e) {
      alert(I18n.t("asset.new.error", e.getMessage()), e);
    }
  }

  /** Copies dropped images and sounds off the FX thread, then shows the new files. */
  private void importAssetFiles(List<Path> files) {
    ScratchProject p = project.get();
    if (p == null || files.isEmpty()) return;
    setStatus(I18n.t("asset.import.running"));
    Thread.ofVirtual().name("scratch4j-import-assets").start(() -> {
      List<Path> imported = new ArrayList<>();
      List<String> failures = new ArrayList<>();
      for (Path source : files) {
        try {
          imported.add(org.openpatch.scratch4j.core.assets.AssetCopier.copyExternalFile(
              source, p.root()));
        } catch (IOException | RuntimeException e) {
          failures.add(source.getFileName() + ": " + e.getMessage());
        }
      }
      Platform.runLater(() -> {
        if (project.get() != p) return;
        fileTree.reload();
        if (!imported.isEmpty()) {
          fileTree.selectPath(imported.getFirst());
          reloadDesigners();
          scheduleCheck();
        }
        setStatus(I18n.t("asset.import.done", imported.size()));
        if (!failures.isEmpty()) {
          alert(I18n.t("asset.import.error", String.join("\n", failures)));
        }
      });
    });
  }

  private boolean saveBeforeAssetChange() {
    saveAll();
    boolean dirty = editor.openFiles().stream()
        .map(editor::editorFor)
        .anyMatch(code -> code != null && code.hasUnsavedChanges());
    if (dirty) alert(I18n.t("asset.save.first"));
    return !dirty;
  }

  private void renameAsset(Path file) {
    ScratchProject p = project.get();
    if (p == null || file == null || !saveBeforeAssetChange()) return;
    String oldName = file.getFileName().toString();
    int dot = oldName.lastIndexOf('.');
    String base = dot < 0 ? oldName : oldName.substring(0, dot);
    javafx.scene.control.TextInputDialog dialog = new javafx.scene.control.TextInputDialog(base);
    dialog.setTitle(I18n.t("asset.rename"));
    dialog.setHeaderText(null);
    dialog.setContentText(I18n.t("asset.new.name"));
    Theme.style(dialog);
    dialog.showAndWait().map(String::trim).filter(name -> !name.equals(base)).ifPresent(name -> {
      List<FileUsages.Usage> usages = usagesOf(p, file);
      Set<FileUsages.Usage> skipped = usages == null ? null
          : askMove(p, file, file.resolveSibling(name + oldName.substring(base.length())),
              usages, true, false);
      if (skipped == null) return;
      try {
        Path renamed = ProjectAssetManagement.rename(p, file, name, FileUsages.Mode.UPDATE,
            skipped);
        editor.closeForFile(file);
        for (Path source : editor.openFiles()) editor.reloadIfOpen(source);
        fileTree.reload();
        reloadDesigners();
        scheduleCheck();
        setStatus(I18n.t("asset.renamed", renamed.getFileName()));
        showLeftUsages(p, file, usages, skipped);
      } catch (IOException e) {
        alert(e.getMessage(), e);
      }
    });
  }

  /**
   * Rename the class of a Java file (file plus every compiler-resolved
   * reference). The project must compile so every reference is found.
   */
  private void renameClass(Path file) {
    ScratchProject p = project.get();
    if (p == null || file == null || !file.getFileName().toString().endsWith(".java")) return;
    String oldName = file.getFileName().toString().replaceFirst("\\.java$", "");
    javafx.scene.control.TextInputDialog dialog =
        new javafx.scene.control.TextInputDialog(oldName);
    dialog.setTitle(I18n.t("class.rename"));
    dialog.setHeaderText(I18n.t("class.rename.hint", oldName));
    dialog.setContentText(I18n.t("class.new.name"));
    Theme.style(dialog);
    dialog.showAndWait().map(String::trim).filter(name -> !name.equals(oldName))
        .ifPresent(name -> {
          try {
            editor.saveAll();
            Path renamed = StageManagement.renameClass(p, oldName, name);
            editor.closeForFile(file);
            for (Path source : editor.openFiles()) editor.reloadIfOpen(source);
            fileTree.reload();
            if (selector != null) selector.refresh();
            reloadDesigners();
            editor.open(renamed);
            scheduleCheck();
            setStatus(I18n.t("class.renamed", oldName, name));
          } catch (IOException e) {
            alert(e.getMessage(), e);
          }
        });
  }

  /**
   * F12 / Ctrl+click: jump to the project declaration of the name at the
   * offset; a library name shows its API help instead. Resolved off the FX
   * thread with javac (the unsaved editor text included).
   */
  private void goToDefinition(CodeEditor source, int offset) {
    ScratchProject p = project.get();
    if (p == null) return;
    String text = source.content();
    String word = source.wordAtCaret();
    Thread worker = new Thread(() -> {
      try {
        var target = org.openpatch.scratch4j.core.compile.Definitions.find(source.file(), text,
            offset, p.javaSources(), p.libs());
        Platform.runLater(() -> {
          if (target.isEmpty()) {
            setStatus(I18n.t("definition.none", word));
          } else if (target.get().location().isPresent()) {
            var location = target.get().location().get();
            editor.openAt(location.file(), location.line());
          } else if (target.get().libraryType() != null
              && target.get().libraryType().startsWith("org.openpatch.scratch")) {
            String name = target.get().libraryMember() != null
                ? target.get().libraryMember()
                : target.get().libraryType().substring(
                    target.get().libraryType().lastIndexOf('.') + 1);
            learning.help("api", null);
            ApiHelpDialog.showForWord(apiIndex, name, this::browse);
          } else {
            setStatus(I18n.t("definition.library", target.get().libraryType()));
          }
        });
      } catch (IOException | RuntimeException e) {
        Platform.runLater(() -> setStatus(I18n.t("definition.none", word)));
      }
    }, "go-to-definition");
    worker.setDaemon(true);
    worker.start();
  }

  /** Rename for a file that is not a class: an asset keeps its extension. */
  private void renameFile(Path file) {
    ScratchProject p = project.get();
    if (p == null || file == null) return;
    if (file.toAbsolutePath().normalize().startsWith(
        p.root().resolve("assets").toAbsolutePath().normalize())) {
      renameAsset(file);
      return;
    }
    if (!saveBeforeAssetChange()) return;
    javafx.scene.control.TextInputDialog dialog =
        new javafx.scene.control.TextInputDialog(file.getFileName().toString());
    dialog.setTitle(I18n.t("tree.rename"));
    dialog.setHeaderText(null);
    dialog.setContentText(I18n.t("file.new.name"));
    Theme.style(dialog);
    dialog.showAndWait().map(String::trim)
        .filter(name -> !name.equals(file.getFileName().toString()))
        .ifPresent(name -> {
          List<FileUsages.Usage> usages = usagesOf(p, file);
          Set<FileUsages.Usage> skipped = usages == null ? null
              : askMove(p, file, file.resolveSibling(name), usages, true, false);
          if (skipped == null) return;
          try {
            Path renamed = ProjectFileManagement.rename(p, file, name, FileUsages.Mode.UPDATE,
                skipped);
            afterMove(file, renamed, usages, skipped);
            setStatus(I18n.t("file.renamed", renamed.getFileName()));
            showLeftUsages(p, file, usages, skipped);
          } catch (IOException e) {
            alert(e.getMessage(), e);
          }
        });
  }

  /** "Move to...": pick one of the project's folders. */
  private void moveTo(Path path) {
    ScratchProject p = project.get();
    if (p == null) return;
    if (path == null) {
      alert(I18n.t("file.delete.select"));
      return;
    }
    List<Path> folders = new ArrayList<>();
    try (var walk = Files.walk(p.root())) {
      Path normalized = path.toAbsolutePath().normalize();
      walk.filter(Files::isDirectory)
          .filter(dir -> !dir.toAbsolutePath().normalize().startsWith(normalized))
          .filter(dir -> !dir.equals(path.getParent()))
          .filter(dir -> {
            Path relative = p.root().relativize(dir);
            return relative.toString().isEmpty() || !java.util.Set.of(".scratch4j", ".git",
                "target", "build", "+libs", "export", ".vscode")
                .contains(relative.getName(0).toString());
          })
          .sorted()
          .forEach(folders::add);
    } catch (IOException e) {
      alert(e.getMessage(), e);
      return;
    }
    if (folders.isEmpty()) {
      alert(I18n.t("move.nofolder"));
      return;
    }
    java.util.Map<String, Path> byName = new java.util.LinkedHashMap<>();
    for (Path folder : folders) {
      String relative = p.root().relativize(folder).toString().replace('\\', '/');
      byName.put(relative.isEmpty() ? I18n.t("move.projectfolder") : relative + "/", folder);
    }
    javafx.scene.control.ChoiceDialog<String> dialog = new javafx.scene.control.ChoiceDialog<>(
        byName.keySet().iterator().next(), byName.keySet());
    dialog.setTitle(I18n.t("tree.moveto"));
    dialog.setHeaderText(I18n.t("move.header", path.getFileName()));
    dialog.setContentText(I18n.t("move.folder"));
    Theme.style(dialog, stage);
    dialog.showAndWait().map(byName::get).ifPresent(folder ->
        Platform.runLater(() -> movePath(path, folder, false)));
  }

  /** Moves a file or folder (drag and drop or "Move to...") and updates what names it. */
  private void movePath(Path path, Path folder, boolean ask) {
    ScratchProject p = project.get();
    if (p == null || path == null || folder == null || !saveBeforeAssetChange()) return;
    // a Java class moves back into the project folder unchanged (nothing names its path)
    boolean java = path.getFileName().toString().endsWith(".java");
    if (java) {
      movePath(p, path, folder, List.of(), ask);
      return;
    }
    // the search reads and parses the project's sources: off the FX thread, so the
    // window stays responsive on slow machines
    setStatus(I18n.t("usages.searching", path.getFileName()));
    Thread worker = new Thread(() -> {
      try {
        List<FileUsages.Usage> usages = FileUsages.find(p, path);
        Platform.runLater(() -> {
          if (project.get() == p) movePath(p, path, folder, usages, ask);
        });
      } catch (IOException | RuntimeException e) {
        Platform.runLater(() -> alert(I18n.t("usages.error", e.getMessage()), e));
      }
    }, "move-usages");
    worker.setDaemon(true);
    worker.start();
  }

  private void movePath(ScratchProject p, Path path, Path folder, List<FileUsages.Usage> usages,
      boolean ask) {
    Set<FileUsages.Usage> skipped = askMove(p, path, folder.resolve(path.getFileName()), usages,
        false, ask);
    if (skipped == null) {
      setStatus("");
      return;
    }
    try {
      Path moved = Files.isDirectory(path)
          ? ProjectFolderManagement.move(p, path, folder, FileUsages.Mode.UPDATE, skipped)
          : ProjectFileManagement.move(p, path, folder, FileUsages.Mode.UPDATE, skipped);
      afterMove(path, moved, usages, skipped);
      showLeftUsages(p, path, usages, skipped);
      String where = p.root().toRealPath().relativize(moved.getParent()).toString();
      setStatus(I18n.t("file.moved", moved.getFileName(),
          where.isEmpty() ? I18n.t("move.projectfolder") : where.replace('\\', '/')));
    } catch (IOException e) {
      alert(e.getMessage(), e);
    }
  }

  /**
   * After a file or folder moved: tabs on the old path reopen on the new one.
   * Only what the move touched is loaded again (the code it rewrote; the
   * designers only when code names the file), which keeps a drop quick.
   */
  private void afterMove(Path from, Path to, List<FileUsages.Usage> usages,
      Set<FileUsages.Usage> skipped) {
    Set<Path> rewritten = new java.util.HashSet<>();
    for (FileUsages.Usage u : usages) {
      if (u.updatable() && !skipped.contains(u)) {
        rewritten.add(u.file().toAbsolutePath().normalize());
      }
    }
    boolean java = from.getFileName().toString().endsWith(".java");
    Path oldPath = from.toAbsolutePath().normalize();
    List<Path> reopen = new ArrayList<>();
    for (Path open : editor.openFiles()) {
      Path normalized = open.toAbsolutePath().normalize();
      if (normalized.startsWith(oldPath)) {
        reopen.add(to.resolve(oldPath.relativize(normalized)));
      }
    }
    if (Files.isDirectory(to)) {
      editor.closeUnderFolder(from);
    } else {
      editor.closeForFile(from);
    }
    for (Path source : editor.openFiles()) {
      if (rewritten.contains(source.toAbsolutePath().normalize())) editor.reloadIfOpen(source);
    }
    fileTree.reload();
    fileTree.selectPath(to);
    if (java || !usages.isEmpty()) {
      reloadDesigners();
      if (selector != null) selector.refresh();
    }
    scheduleCheck();
    for (Path file : reopen) {
      if (Files.isRegularFile(file)) {
        openByType(file);
      }
    }
  }

  /** Duplicate: a class gets a new name, any other file a "-2" copy next to it. */
  private void duplicateFile(Path file) {
    ScratchProject p = project.get();
    if (p == null) return;
    if (file == null || !Files.isRegularFile(file)) {
      alert(I18n.t("file.delete.select"));
      return;
    }
    saveAll();
    boolean java = file.getFileName().toString().endsWith(".java");
    String oldName = file.getFileName().toString().replaceFirst("\\.java$", "");
    String suggestion = java ? oldName + "2"
        : ProjectFileManagement.copyName(file);
    javafx.scene.control.TextInputDialog dialog =
        new javafx.scene.control.TextInputDialog(suggestion);
    dialog.setTitle(I18n.t("tree.duplicate"));
    dialog.setHeaderText(I18n.t(java ? "duplicate.class.hint" : "duplicate.file.hint",
        file.getFileName()));
    dialog.setContentText(I18n.t(java ? "class.new.name" : "file.new.name"));
    Theme.style(dialog);
    dialog.showAndWait().map(String::trim).filter(name -> !name.isEmpty()).ifPresent(name -> {
      try {
        Path copy = java ? StageManagement.duplicateClass(p, oldName, name)
            : ProjectFileManagement.duplicate(p, file, name);
        fileTree.reload();
        fileTree.selectPath(copy);
        if (selector != null) selector.refresh();
        scheduleCheck();
        openByType(copy);
        setStatus(I18n.t("file.duplicated", copy.getFileName()));
      } catch (IOException e) {
        alert(e.getMessage(), e);
      }
    });
  }

  /** The current text of every project Java file (open editors are saved first). */
  private java.util.Map<Path, String> javaTexts(ScratchProject p) throws IOException {
    java.util.Map<Path, String> texts = new java.util.HashMap<>();
    for (Path source : p.javaSources()) {
      texts.put(source, Files.readString(source));
    }
    return texts;
  }

  /** The key under which {@link #javaTexts} holds {@code file} (the same file, spelled alike). */
  private static Path sourceKey(java.util.Map<Path, String> texts, Path file) {
    Path normalized = file.toAbsolutePath().normalize();
    return texts.keySet().stream()
        .filter(key -> key.toAbsolutePath().normalize().equals(normalized))
        .findFirst().orElse(file);
  }

  /**
   * F2: renames the field, method, variable or class at the offset everywhere
   * in the project. A class renames its file too ({@link #renameClass}).
   */
  private void renameSymbol(CodeEditor source, int offset) {
    ScratchProject p = project.get();
    if (p == null || source == null) return;
    if (!source.file().getFileName().toString().endsWith(".java")) {
      setStatus(I18n.t("rename.nojava"));
      return;
    }
    saveAll();
    if (source.hasUnsavedChanges()) {
      alert(I18n.t("asset.save.first"));
      return;
    }
    setStatus(I18n.t("rename.resolving"));
    Thread worker = new Thread(() -> {
      try {
        var texts = javaTexts(p);
        var found = org.openpatch.scratch4j.core.compile.Symbols.at(
            sourceKey(texts, source.file()), offset, texts, p.libs());
        Platform.runLater(() -> {
          setStatus("");
          if (found.isEmpty()) {
            alert(I18n.t("rename.noname"));
            return;
          }
          var symbol = found.get();
          if (!symbol.inProject()) {
            alert(I18n.t("rename.library", symbol.name()));
            return;
          }
          if (symbol.libraryMethod() != null) {
            alert(I18n.t("rename.override", symbol.name(), symbol.libraryMethod()));
            return;
          }
          if (symbol.topLevelClass()) {
            symbol.occurrences().stream().filter(o -> o.declaration()
                    && o.file().getFileName().toString().equals(symbol.name() + ".java"))
                .findFirst()
                .ifPresentOrElse(o -> renameClass(o.file()),
                    () -> askSymbolName(p, source, offset, symbol));
            return;
          }
          askSymbolName(p, source, offset, symbol);
        });
      } catch (IOException | RuntimeException e) {
        Platform.runLater(() -> alert(e.getMessage(), e));
      }
    }, "rename");
    worker.setDaemon(true);
    worker.start();
  }

  private void askSymbolName(ScratchProject p, CodeEditor source, int offset,
      org.openpatch.scratch4j.core.compile.Symbols.Symbol symbol) {
    javafx.scene.control.TextInputDialog dialog =
        new javafx.scene.control.TextInputDialog(symbol.name());
    dialog.setTitle(I18n.t("menu.refactor.rename"));
    dialog.setHeaderText(I18n.t("rename.hint", symbol.name(), symbol.occurrences().size(),
        symbol.byFile().size()));
    dialog.setContentText(I18n.t("rename.newname"));
    Theme.style(dialog);
    dialog.showAndWait().map(String::trim).filter(name -> !name.equals(symbol.name()))
        .ifPresent(name -> {
          int caret = source.area().getCaretPosition();
          Thread worker = new Thread(() -> {
            try {
              var texts = javaTexts(p);
              var changed = org.openpatch.scratch4j.core.compile.Symbols.rename(
                  sourceKey(texts, source.file()), offset, name, texts, p.libs());
              Platform.runLater(() -> applyRename(p, source, caret, symbol, name, texts,
                  changed));
            } catch (IOException | RuntimeException e) {
              Platform.runLater(() -> alert(e.getMessage(), e));
            }
          }, "rename");
          worker.setDaemon(true);
          worker.start();
        });
  }

  private void applyRename(ScratchProject p, CodeEditor source, int caret,
      org.openpatch.scratch4j.core.compile.Symbols.Symbol symbol, String name,
      java.util.Map<Path, String> before, java.util.Map<Path, String> changed) {
    // nothing may have been typed since the rename was computed
    for (Path file : changed.keySet()) {
      CodeEditor open = editor.editorFor(file);
      if (open != null && !open.content().equals(before.get(file))) {
        alert(I18n.t("rename.changed"));
        return;
      }
    }
    List<Path> written = new ArrayList<>();
    try {
      for (var entry : changed.entrySet()) {
        LocalHistory.writeString(p.root(), entry.getKey(), entry.getValue());
        written.add(entry.getKey());
      }
    } catch (IOException e) {
      for (Path file : written) {
        try {
          org.openpatch.scratch4j.core.io.AtomicFiles.writeString(file, before.get(file));
        } catch (IOException rollback) {
          e.addSuppressed(rollback);
        }
      }
      alert(e.getMessage(), e);
      return;
    }
    for (Path file : changed.keySet()) editor.reloadIfOpen(file);
    // the caret stays on the renamed name: earlier occurrences in its file shifted it
    int shift = 0;
    Path sourceFile = source.file().toAbsolutePath().normalize();
    for (var o : symbol.occurrences()) {
      if (o.file().toAbsolutePath().normalize().equals(sourceFile) && o.end() <= caret) {
        shift += name.length() - symbol.name().length();
      }
    }
    source.area().moveTo(Math.max(0, Math.min(caret + shift, source.area().getLength())));
    reloadDesigners();
    if (selector != null) selector.refresh();
    scheduleCheck();
    setStatus(I18n.t("rename.done", symbol.name(), name, symbol.occurrences().size()));
  }

  /** Shift+F12: every use of the name at the offset, in the Search tab. */
  private void findUsages(CodeEditor source, int offset) {
    ScratchProject p = project.get();
    if (p == null || source == null) return;
    if (!source.file().getFileName().toString().endsWith(".java")) {
      setStatus(I18n.t("rename.nojava"));
      return;
    }
    saveAll();
    String word = source.wordAtCaret();
    java.util.Map<Path, String> texts;
    try {
      texts = javaTexts(p);
    } catch (IOException e) {
      alert(e.getMessage(), e);
      return;
    }
    Path key = sourceKey(texts, source.file());
    texts.put(key, source.content()); // unsaved text counts too
    Thread worker = new Thread(() -> {
      try {
        var found = org.openpatch.scratch4j.core.compile.Symbols.at(key, offset,
            texts, p.libs());
        Platform.runLater(() -> {
          if (found.isEmpty()) {
            setStatus(I18n.t("definition.none", word));
            return;
          }
          var symbol = found.get();
          List<SearchResultsView.Match> matches = new ArrayList<>();
          for (var o : symbol.occurrences()) {
            String text = texts.get(o.file());
            int lineStart = text.lastIndexOf('\n', o.start() - 1) + 1;
            int lineEnd = text.indexOf('\n', o.start());
            String raw = text.substring(lineStart, lineEnd < 0 ? text.length() : lineEnd);
            int from = o.start() - lineStart - (raw.length() - raw.stripLeading().length());
            matches.add(new SearchResultsView.Match(o.file(), o.start(), o.end(), o.line(),
                o.lineText(), from, from + symbol.name().length(), o.declaration()));
          }
          searchResults.show(p.root(), I18n.t("usages.title", symbol.name(), matches.size(),
              symbol.byFile().size()), matches);
          showTab(searchTab, true);
          setStatus(I18n.t("usages.status", symbol.name(), matches.size()));
        });
      } catch (IOException | RuntimeException e) {
        Platform.runLater(() -> setStatus(I18n.t("definition.none", word)));
      }
    }, "find-usages");
    worker.setDaemon(true);
    worker.start();
  }

  /** Ctrl+Shift+H: every line of the project's text files that contains a text. */
  private void findInProject(String start) {
    ScratchProject p = project.get();
    if (p == null) return;
    javafx.scene.control.TextInputDialog dialog =
        new javafx.scene.control.TextInputDialog(start == null ? "" : start);
    dialog.setTitle(I18n.t("menu.edit.findproject"));
    dialog.setHeaderText(null);
    dialog.setContentText(I18n.t("search.prompt"));
    javafx.scene.control.CheckBox matchCase = new javafx.scene.control.CheckBox(
        I18n.t("search.matchcase"));
    dialog.getDialogPane().setExpandableContent(null);
    var content = dialog.getDialogPane().getContent();
    dialog.getDialogPane().setContent(new VBox(8, content, matchCase));
    Theme.style(dialog);
    dialog.showAndWait().filter(query -> !query.isEmpty()).ifPresent(query -> {
      saveAll();
      boolean exact = matchCase.isSelected();
      Thread worker = new Thread(() -> {
        try {
          var hits = org.openpatch.scratch4j.core.project.ProjectSearch.search(p, query, exact);
          List<SearchResultsView.Match> matches = hits.stream()
              .map(h -> new SearchResultsView.Match(h.file(), h.start(), h.end(), h.line(),
                  h.lineText(), h.from(), h.to(), false))
              .toList();
          long files = hits.stream().map(h -> h.file()).distinct().count();
          Platform.runLater(() -> {
            String heading = hits.size() >= org.openpatch.scratch4j.core.project.ProjectSearch.LIMIT
                ? I18n.t("search.title.limit", query, hits.size())
                : I18n.t("search.title.found", query, hits.size(), files);
            searchResults.show(p.root(), heading, matches);
            showTab(searchTab, true);
          });
        } catch (IOException e) {
          Platform.runLater(() -> alert(e.getMessage(), e));
        }
      }, "find-in-project");
      worker.setDaemon(true);
      worker.start();
    });
  }

  /** The usages of a file or folder, or null when they could not be found (the user was told). */
  private List<FileUsages.Usage> usagesOf(ScratchProject p, Path path) {
    try {
      return FileUsages.find(p, path);
    } catch (IOException | RuntimeException e) {
      alert(I18n.t("usages.error", e.getMessage()), e);
      return null;
    }
  }

  /**
   * Before a move or rename to {@code target}: the preview of what changes
   * (see {@link UsagesDialog#forMove}). The usages the user turned off, or
   * null when cancelled.
   */
  private Set<FileUsages.Usage> askMove(ScratchProject p, Path path, Path target,
      List<FileUsages.Usage> usages, boolean rename, boolean ask) {
    String from;
    String to;
    try {
      Path root = p.root().toRealPath();
      from = root.relativize(path.toRealPath()).toString().replace('\\', '/');
      to = root.relativize(target.getParent().toRealPath().resolve(target.getFileName()))
          .toString().replace('\\', '/');
    } catch (IOException e) {
      alert(e.getMessage(), e);
      return null;
    }
    return UsagesDialog.forMove(stage, p.root(), path.getFileName().toString(), from, to, usages,
        rename, ask);
  }

  /**
   * Before a delete: preview the path and warn about every reference that will break.
   */
  private boolean confirmDelete(ScratchProject p, Path path, List<FileUsages.Usage> usages) {
    switch (UsagesDialog.forDelete(stage, p.root(), path, usages)) {
      case DELETE:
        return true;
      case SHOW:
        showUsages(p, I18n.t("usages.search.title", path.getFileName(), usages.size()),
            usages, false);
        return false;
      default:
        return false;
    }
  }

  /**
   * After a move or rename: the usages the IDE did not update (all of them
   * when the user chose not to) go to the Search tab, to be fixed by hand.
   */
  private void showLeftUsages(ScratchProject p, Path path, List<FileUsages.Usage> usages,
      Set<FileUsages.Usage> skipped) {
    List<FileUsages.Usage> left = usages.stream()
        .filter(u -> skipped.contains(u) || !u.updatable())
        .toList();
    if (!left.isEmpty()) {
      showUsages(p, I18n.t("usages.left.title", path.getFileName(), left.size()), left, true);
    }
  }

  /**
   * Shows usages in the Search tab. {@code edited}: the files may have changed
   * since the usages were found (paths were rewritten on earlier columns), so
   * each usage is found again on its line.
   */
  private void showUsages(ScratchProject p, String heading, List<FileUsages.Usage> usages,
      boolean edited) {
    List<SearchResultsView.Match> matches = new ArrayList<>();
    for (FileUsages.Usage u : usages) {
      if (u.line() <= 0) continue; // a project setting, not a line of a file
      int start = u.start();
      int end = u.end();
      if (edited) {
        try {
          String text = Files.readString(u.file());
          int lineStart = 0;
          for (long line = 1; line < u.line() && lineStart >= 0; line++) {
            lineStart = text.indexOf('\n', lineStart) + 1;
            if (lineStart == 0) lineStart = -1;
          }
          if (lineStart < 0) continue;
          int lineEnd = text.indexOf('\n', lineStart);
          String line = text.substring(lineStart, lineEnd < 0 ? text.length() : lineEnd);
          String needle = u.lineText().substring(u.from(), u.to());
          int at = line.indexOf(needle);
          start = lineStart + Math.max(0, at);
          end = start + (at < 0 ? 0 : needle.length());
        } catch (IOException | RuntimeException gone) {
          continue;
        }
      }
      matches.add(new SearchResultsView.Match(u.file(), start, end, u.line(), u.lineText(),
          u.from(), u.to(), false));
    }
    searchResults.show(p.root(), heading, matches);
    showTab(searchTab, true);
  }

  private void deleteFile(Path file) {
    if (project.get() == null) return;
    if (file == null) {
      alert(I18n.t("file.delete.select"));
      return;
    }
    deletePath(file, false);
  }

  private void createFolder(Path parent) {
    ScratchProject p = project.get();
    if (p == null || parent == null) return;
    javafx.scene.control.TextInputDialog dialog = new javafx.scene.control.TextInputDialog();
    dialog.setTitle(I18n.t("folder.new"));
    dialog.setHeaderText(I18n.t("folder.parent", p.root().relativize(parent)));
    dialog.setContentText(I18n.t("folder.name"));
    Theme.style(dialog);
    dialog.showAndWait().ifPresent(name -> {
      try {
        Path created = ProjectFolderManagement.create(p, parent, name.trim());
        fileTree.keepVisible(created);
        fileTree.reload();
        fileTree.selectPath(created);
        setStatus(I18n.t("folder.created", created.getFileName()));
      } catch (IOException e) {
        alert(e.getMessage(), e);
      }
    });
  }

  private void renameFolder(Path folder) {
    ScratchProject p = project.get();
    if (p == null || folder == null || !saveBeforeAssetChange()) return;
    javafx.scene.control.TextInputDialog dialog = new javafx.scene.control.TextInputDialog(
        folder.getFileName().toString());
    dialog.setTitle(I18n.t("folder.rename"));
    dialog.setHeaderText(null);
    dialog.setContentText(I18n.t("folder.name"));
    Theme.style(dialog);
    dialog.showAndWait().map(String::trim)
        .filter(name -> !name.equals(folder.getFileName().toString())).ifPresent(name -> {
      List<FileUsages.Usage> usages = usagesOf(p, folder);
      Set<FileUsages.Usage> skipped = usages == null ? null
          : askMove(p, folder, folder.resolveSibling(name), usages, true, false);
      if (skipped == null) return;
      try {
        Path renamed = ProjectFolderManagement.rename(p, folder, name, FileUsages.Mode.UPDATE,
            skipped);
        afterMove(folder, renamed, usages, skipped);
        setStatus(I18n.t("folder.renamed", renamed.getFileName()));
        showLeftUsages(p, folder, usages, skipped);
      } catch (IOException e) {
        alert(e.getMessage(), e);
      }
    });
  }

  private void deleteFolder(Path folder) {
    if (folder != null) deletePath(folder, true);
  }

  /** Look for references off the FX thread, then ask before moving anything to trash. */
  private void deletePath(Path path, boolean folder) {
    ScratchProject p = project.get();
    if (p == null || !saveBeforeAssetChange()) return;
    setStatus(I18n.t("usages.searching", path.getFileName()));
    Thread.ofVirtual().name("delete-usages").start(() -> {
      try {
        List<FileUsages.Usage> usages = FileUsages.find(p, path);
        Platform.runLater(() -> {
          if (project.get() != p) return;
          setStatus("");
          if (!confirmDelete(p, path, usages)) return;
          try {
            if (folder) {
              ProjectFolderManagement.delete(p, path, true);
              editor.closeUnderFolder(path);
            } else {
              ProjectFileManagement.delete(p, path, true);
              editor.closeForFile(path);
            }
            fileTree.reload();
            reloadDesigners();
            if (selector != null) selector.refresh();
            scheduleCheck();
            setStatus(I18n.t(folder ? "folder.deleted" : "file.deleted", path.getFileName()));
          } catch (IOException e) {
            alert(e.getMessage(), e);
          }
        });
      } catch (IOException | RuntimeException e) {
        Platform.runLater(() -> {
          if (project.get() != p) return;
          setStatus("");
          alert(I18n.t("usages.error", e.getMessage()), e);
        });
      }
    });
  }

  // --- saving and checking -----------------------------------------------------------

  private void saveAll() {
    if (editor == null) {
      return;
    }
    try {
      editor.saveAll();
    } catch (IOException e) {
      alert(I18n.t("error.save", e.getMessage()), e);
    }
  }

  /** Ctrl+S: everything autosaves anyway; this saves now and checks now. */
  private void saveAndCheck() {
    saveAll();
    Tab shown = editor == null ? null : editor.getSelectionModel().getSelectedItem();
    if (shown != null && shown.getContent() instanceof SavableTool tool) {
      tool.save();
    }
    reloadDesigners();
    checkProject();
    setStatus(I18n.t("status.saved"));
  }

  private void onCodeSaved(Path file) {
    scheduleHotSwap();
    reloadDesigners();
    if (selector != null) {
      selector.refresh();
    }
    scheduleCheck();
  }

  /** A repainted picture: animation, sprite and stage previews and code lines re-read it. */
  private void afterImageSaved() {
    reloadDesigners();
    for (Tab tab : editor.getTabs()) {
      if (tab.getContent() instanceof CodeEditor code) {
        code.refreshPreviews();
      }
    }
  }

  private void reloadDesigners() {
    for (Node tool : editor.tools()) {
      // Hidden tabs catch up when selected. A save must not rebuild every
      // diagram, sprite list and animation just because their tabs are open.
      if (showing(tool)) refreshTool(tool);
    }
  }

  private void refreshTool(Node tool) {
    if (tool instanceof DiagramView diagrams) diagrams.refresh();
    else if (tool instanceof StageDesignerView designer) designer.reload();
    else if (tool instanceof SpriteAssetsView assets) assets.reload();
    else if (tool instanceof AnimationEditorView animation) animation.reload();
  }

  /** The stage designers on screen now (the others catch up when their tab is shown). */
  private void reloadShownDesigners() {
    for (Node tool : editor.tools()) {
      if (tool instanceof StageDesignerView designer && showing(designer)) {
        designer.reload();
      }
    }
  }

  private static boolean showing(Node node) {
    for (Node n = node; n != null; n = n.getParent()) {
      if (!n.isVisible()) return false;
    }
    return node.getScene() != null;
  }

  private void scheduleCheck() {
    if (problems != null) problems.setOutdated(true);
    checkDelay.playFromStart();
  }

  /** Full check off the FX thread (compile + asset lints); one at a time. */
  /** Starts the project's program like the green flag (tests). */
  void runForTests() {
    runProgram();
  }

  /** As after the green flag for {@code stage}, without a program (tests). */
  void startingForTests(String stage, boolean isRunning) {
    startingStage = stage;
    showRunProgress(isRunning);
    running.set(isRunning);
  }

  /** The status line's text (tests). */
  String statusForTests() {
    return status.getText();
  }

  /** How many started programs were kept, not ended at once as replaced (tests). */
  int keptRunsForTests() {
    return keptRuns.get();
  }

  /** Whether a program runs or starts (tests). */
  boolean runningForTests() {
    return running.get();
  }

  /** Stops the program (tests). */
  void stopForTests() {
    stopProgram();
  }

  /** Runs the project check now (tests). */
  void recheckForTests() {
    checkProject();
  }

  private void checkProject() {
    ScratchProject p = project.get();
    if (p == null) {
      return;
    }
    if (!checkRunning.compareAndSet(false, true)) {
      checkAgain.set(true);
      return;
    }
    DiagnosticsExplanations.Language language = explanationLanguage();
    ProjectCheck.Cache cache = checkCache;
    Thread worker = new Thread(() -> {
      List<Problem> found;
      try {
        found = ProjectCheck.check(p, language, cache).stream()
            .map(pr -> new Problem(pr.file(), pr.line(), pr.column(), pr.message(),
                pr.explanation(), pr.suggestions(), pr.error(), pr.fix(), pr.original(),
                pr.followUp(), pr.fixData(), pr.trace(), pr.caseKey(), pr.diagnosticCode()))
            .toList();
      } catch (RuntimeException e) {
        found = List.of(Problem.crash(e.toString(), e));
      } finally {
        if (cache != checkCache) {
          closeQuietly(cache); // the project changed while this check ran
        }
      }
      List<Problem> result = found;
      Platform.runLater(() -> {
        checkRunning.set(false);
        boolean outdated = checkAgain.getAndSet(false);
        // the code changed while this check ran: its problems may be ones the user
        // just fixed, so the newer check shows its own instead
        if (project.get() == p && !outdated) {
          showProblems(result);
        }
        if (outdated) {
          checkProject();
        }
      });
    }, "scratch4j-check");
    worker.setDaemon(true);
    worker.start();
  }

  /** The last project check and the crashes of the current run, shown together. */
  private List<Problem> checkedProblems = List.of();
  private boolean runCrashed;
  private boolean runKept;
  /** When code was last swapped into the running program (it must run a while too). */
  private long lastSwap;
  private final List<Problem> crashProblems = new ArrayList<>();
  private final List<Problem> checkProblems = new ArrayList<>();

  private void showProblems(List<Problem> checked) {
    checkedProblems = checked;
    problems.setOutdated(false);
    List<Problem> found = new ArrayList<>(crashProblems);
    found.addAll(checked);
    found.addAll(checkProblems);
    problems.setProblems(found);
    learning.checked(found);
    Map<Path, List<CodeEditor.Diagnostic>> byFile = new HashMap<>();
    for (Problem problem : found) {
      if (problem.file() == null) continue;
      // hints are no squiggles: their light bulb is enough
      if (!problem.isHint()) {
        byFile.computeIfAbsent(problem.file(), f -> new ArrayList<>())
            .add(problem.toDiagnostic());
      }
    }
    editor.setDiagnostics(byFile);
    updateSupportGutters();
    long errors = problems.errorCount();
    long count = found.stream().filter(p -> !p.isHint()).count();
    found = found.stream().filter(p -> !p.isHint()).toList();
    problemBadge.setText(String.valueOf(count));
    problemBadge.getStyleClass().removeAll("badge-error", "badge-warning", "badge-ok");
    problemBadge.getStyleClass().add(errors > 0 ? "badge-error"
        : found.stream().allMatch(p -> "check.passed".equals(p.caseKey())) ? "badge-ok" : "badge-warning");
    if (!running.get()) {
      setStatus(found.isEmpty() ? I18n.t("problems.none")
          : I18n.t("status.problems", found.size()));
    }
    updateLesson(checked.stream().noneMatch(Problem::error));
    if (errors > 0) {
      // a minute without getting back to green: offer the version that ran
      if (!problems.stuckShowing() && stuckDelay.getStatus()
          != javafx.animation.Animation.Status.RUNNING) {
        stuckDelay.playFromStart();
      }
    } else {
      stuckDelay.stop();
      problems.hideStuck();
    }
  }

  private List<Problem> supportProblems() {
    List<Problem> all = new ArrayList<>(checkedProblems);
    all.addAll(crashProblems);
    all.addAll(checkProblems);
    return all;
  }

  private void updateSupportGutters() {
    Map<Path, Map<Integer, Boolean>> hints = new HashMap<>();
    for (Problem p : supportProblems()) {
      if (p.error() && p.file() != null && p.line() > 0) hints.computeIfAbsent(p.file(), f -> new HashMap<>())
          .merge((int) p.line(), learning.isNudged(p), (a, b) -> a || b);
    }
    editor.setHints(hints);
    Map<Path, Map<Integer, String>> fixes = new HashMap<>();
    if (project.get() != null && !project.get().settings().hintsOnly)
      for (Problem p : checkedProblems) if (p.fix() != null && p.file() != null)
        fixes.computeIfAbsent(p.file(), f -> new HashMap<>()).put((int) p.line(), p.fixLabel());
    editor.setFixes(fixes);
  }

  private static void closeQuietly(ProjectCheck.Cache cache) {
    try {
      cache.close();
    } catch (IOException ignored) {
      // only the compiler's open jars
    }
  }

  // --- lesson ------------------------------------------------------------------------

  /** The project's lesson, if it has one: a sidebar tab, shown first. */
  private void openLesson(ScratchProject p) {
    org.openpatch.scratch4j.core.lesson.LessonProgress progress = null;
    try {
      var lesson = org.openpatch.scratch4j.core.lesson.Lesson.load(p.root());
      if (lesson != null) {
        progress = org.openpatch.scratch4j.core.lesson.LessonProgress.load(lesson, p.root());
      }
    } catch (IOException e) {
      console.err(e.getMessage());
    }
    lessonPanel.show(progress);
    learning.attach(progress);
    if (progress == null) {
      sidebar.getTabs().remove(lessonTab);
    } else {
      if (!sidebar.getTabs().contains(lessonTab)) {
        sidebar.getTabs().add(0, lessonTab);
      }
      sidebar.getSelectionModel().select(lessonTab);
    }
    // three tabs and the step texts need more room than the stages alone
    sidebar.setMinWidth(progress == null ? 200 : LESSON_SIDEBAR_WIDTH);
  }

  private static final double LESSON_SIDEBAR_WIDTH = 320;

  /** After a project check: the lesson looks whether the current step is done. */
  private void updateLesson(boolean compiles) {
    var progress = lessonPanel.progress();
    ScratchProject p = project.get();
    if (progress == null || p == null) return;
    lessonWorker.execute(() -> lessonStep(p, progress, false, compiles));
  }

  /** A run drew its first frame: "run it" steps are done. */
  private void lessonRan() {
    var progress = lessonPanel.progress();
    ScratchProject p = project.get();
    if (progress == null || p == null) return;
    boolean compiles = checkedProblems.stream().noneMatch(Problem::error);
    lessonWorker.execute(() -> lessonStep(p, progress, true, compiles));
  }

  private void lessonStep(ScratchProject p,
      org.openpatch.scratch4j.core.lesson.LessonProgress progress, boolean ran,
      boolean compiles) {
    List<org.openpatch.scratch4j.core.lesson.Lesson.Step> completed;
    try {
      Map<String, String> files = new HashMap<>();
      for (Path source : p.javaSources()) {
        files.put(source.getFileName().toString(), Files.readString(source));
      }
      completed = ran ? progress.ran(files, compiles) : progress.update(files, compiles);
    } catch (IOException e) {
      return;
    }
    Platform.runLater(() -> {
      if (lessonPanel.progress() != progress) return; // another project by now
      lessonPanel.refresh();
      if (completed.isEmpty()) return;
      java.util.Locale locale = I18n.current() == I18n.Language.DE
          ? java.util.Locale.GERMAN : java.util.Locale.ENGLISH;
      var last = completed.get(completed.size() - 1);
      setStatus("\u2714 " + I18n.t("lesson.stepdone", last.title(locale)));
      if (progress.finished()) {
        notice(I18n.t("lesson.finished", progress.lesson().title(locale)));
      }
      if (sidebar.getTabs().contains(lessonTab)) {
        sidebar.getSelectionModel().select(lessonTab);
      }
    });
  }

  /** Help > For teachers > Lesson for this project: create, edit or remove it. */
  private void editLesson() {
    ScratchProject p = project.get();
    if (p == null) {
      alert(I18n.t("status.no.project"));
      return;
    }
    saveAll();
    org.openpatch.scratch4j.core.lesson.Lesson lesson;
    List<String> javaFiles = new ArrayList<>();
    try {
      lesson = org.openpatch.scratch4j.core.lesson.Lesson.load(p.root());
      for (Path source : p.javaSources()) {
        javaFiles.add(source.getFileName().toString());
      }
    } catch (IOException e) {
      alert(e.getMessage(), e);
      return;
    }
    String newId = "lesson-" + p.root().getFileName().toString().toLowerCase(java.util.Locale.ROOT)
        .replaceAll("[^a-z0-9]+", "-");
    var outcome = LessonEditorDialog.show(stage, lesson, newId, javaFiles, () -> {
      Map<String, String> files = new HashMap<>();
      try {
        for (Path source : p.javaSources()) {
          files.put(source.getFileName().toString(), Files.readString(source));
        }
      } catch (IOException ignored) {
        // the test shows "not yet"
      }
      return new org.openpatch.scratch4j.core.lesson.Lesson.Facts(files,
          checkedProblems.stream().noneMatch(Problem::error), false);
    });
    if (outcome.isEmpty()) return;
    try {
      if (outcome.get() instanceof LessonEditorDialog.Saved saved) {
        saved.lesson().write(p.root());
        setStatus(I18n.t("lessoneditor.saved"));
      } else {
        Alert ask = new Alert(Alert.AlertType.CONFIRMATION, I18n.t("lessoneditor.remove.confirm"),
            I18n.ok(), I18n.cancel());
        ask.setHeaderText(null);
        Theme.style(ask);
        if (ask.showAndWait().orElse(null) != I18n.ok()) return;
        Files.deleteIfExists(p.root().resolve(org.openpatch.scratch4j.core.lesson.Lesson.FILE));
        Files.deleteIfExists(p.root().resolve(
            org.openpatch.scratch4j.core.lesson.LessonProgress.FILE));
        setStatus(I18n.t("lessoneditor.removed"));
      }
    } catch (IOException e) {
      alert(e.getMessage(), e);
      return;
    }
    openLesson(p);
    updateLesson(checkedProblems.stream().noneMatch(Problem::error));
  }

  private void restartLesson() {
    var progress = lessonPanel.progress();
    if (progress == null) return;
    lessonWorker.execute(() -> {
      try {
        progress.restart();
      } catch (IOException ignored) {
        // progress is a convenience
      }
      Platform.runLater(lessonPanel::refresh);
    });
  }

  /** The empty problems list says what to do next. */
  private void updateProblemsPlaceholder() {
    problems.setPlaceholder(I18n.t(project.get() == null ? "problems.empty.noproject"
        : hasRun.get() ? "problems.none" : "problems.empty.notrun"));
  }

  /** Errors for a minute and a version that ran: the problems pane offers it. */
  private void offerLastWorking() {
    ScratchProject p = project.get();
    if (p == null || problems.errorCount() == 0) {
      return;
    }
    try {
      var version = org.openpatch.scratch4j.core.io.ProjectSnapshots.lastWorking(p.root());
      if (version == null
          || org.openpatch.scratch4j.core.io.ProjectSnapshots.changes(p.root(), version)
              .isEmpty()) {
        return;
      }
      String when = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
          .withZone(java.time.ZoneId.systemDefault()).format(version.time());
      problems.showStuck(when, this::restoreLastWorking);
    } catch (IOException ignored) {
      // versions are a convenience
    }
  }

  private DiagnosticsExplanations.Language explanationLanguage() {
    return I18n.current() == I18n.Language.DE
        ? DiagnosticsExplanations.Language.DE
        : DiagnosticsExplanations.Language.EN;
  }

  // --- run -------------------------------------------------------------------------

  private void runTeachingTests() {
    ScratchProject p = project.get();
    if (p == null) return;
    saveAll();
    stopProgram();
    long generation = runGeneration.incrementAndGet();
    running.set(true);
    checkProblems.clear();
    showProblems(checkedProblems);
    var eventLog = learning.log();
    int attempt = learning.attempt();
    bottomTabs.getSelectionModel().select(consoleTab);
    Thread.ofVirtual().start(() -> {
      RunHandle handle = null;
      try {
        handle = new org.openpatch.scratch4j.runner.TeachingTestRunner().run(p, new RunListener() {
          public void onCheck(org.openpatch.scratch4j.runner.CheckResult result) {
            if (eventLog != null && !result.status().equals("SKIPPED"))
              eventLog.record("check", Map.of("name", result.name().replaceAll("[\\r\\n\\t]", " ").substring(0, Math.min(300, result.name().length())), "passed", result.passed()));
            Problem feedback = CheckFeedback.describe(p, result, I18n.current() == I18n.Language.DE);
            Platform.runLater(() -> {
              if (project.get() != p || runGeneration.get() != generation) return;
              checkProblems.add(feedback);
              showProblems(checkedProblems);
              bottomTabs.getSelectionModel().select(problemsTab);
            });
          }
          public void onCompileFailed(CompileResult result) { learning.ran(p, eventLog, attempt, result); }
          public void onCompileSucceeded(CompileResult result) { learning.ran(p, eventLog, attempt, result); }
          public void onStdout(String line) { Platform.runLater(() -> console.info(line)); }
          public void onStderr(String line) { Platform.runLater(() -> console.err(line)); }
        });
        if (runGeneration.get() != generation || !currentRun.compareAndSet(null, handle)) {
          handle.stop();
          return;
        }
        if (runGeneration.get() != generation) {
          currentRun.compareAndSet(handle, null);
          handle.stop();
          return;
        }
        int code = handle.exitFuture().get(30, java.util.concurrent.TimeUnit.SECONDS);
        Platform.runLater(() -> {
          if (runGeneration.get() == generation) setStatus(I18n.t("tests.summary",
              checkProblems.stream().filter(pr -> "check.passed".equals(pr.caseKey())).count(), checkProblems.size()));
        });
      } catch (Exception error) {
        if (handle != null) handle.stop();
        Platform.runLater(() -> console.err(error.getMessage()));
      } finally {
        currentRun.compareAndSet(handle, null);
        Platform.runLater(() -> {
          if (runGeneration.get() == generation) running.set(false);
        });
      }
    });
  }

  private void runProgram() {
    ScratchProject p = project.get();
    if (p == null) {
      return;
    }
    try {
      runStage(p.startStage());
    } catch (IOException e) {
      alert(I18n.t("error.open", p.root(), e.getMessage()), e);
    }
  }

  /** Run > Debug: the program under the debugger, stopping at the gutter's breakpoints. */
  private void debugProgram() {
    ScratchProject p = project.get();
    if (p == null) {
      return;
    }
    try {
      runStage(p.startStage(), true);
    } catch (IOException e) {
      alert(I18n.t("error.open", p.root(), e.getMessage()), e);
    }
  }

  private void runStage(String startStage) {
    runStage(startStage, false);
  }

  private final org.openpatch.scratch4j.runner.Debugger.Listener debugListener =
      new org.openpatch.scratch4j.runner.Debugger.Listener() {
        @Override public void onPaused(org.openpatch.scratch4j.runner.Debugger.Pause pause) {
          debugPaused = true;
          Platform.runLater(() -> {
            debuggerView.paused(pause);
            showTab(debuggerTab, true);
            showDebugLine(-1);
            ScratchProject p = project.get();
            if (p == null) return;
            try {
              Path file = p.sourceOf(pause.sourceFile().replaceFirst("\\.java$", ""));
              if (file != null) {
                editor.openAt(file, pause.line());
                debugFile = file;
                showDebugLine(pause.line());
              }
            } catch (IOException ignored) {
              // the console still shows where it stopped
            }
            setStatus(I18n.t("debug.paused", pause.sourceFile(), pause.line(), pause.method()));
          });
        }

        @Override public void onResumed() {
          debugPaused = false;
          watchChanged = System.currentTimeMillis();
          Platform.runLater(() -> {
            showDebugLine(-1);
            debuggerView.running(I18n.t("debug.running"));
          });
        }

        @Override public void onEnded() {
          debugPaused = false;
          Platform.runLater(() -> {
            showDebugLine(-1);
            debuggerView.running(null);
          });
        }
      };

  private void showDebugLine(int line) {
    if (debugFile == null) return;
    CodeEditor code = editor.editorFor(debugFile);
    if (code != null) code.showDebugLine(line);
  }

  /** Compiles and starts {@code startStage} in a fresh JVM (optionally under the debugger). */
  private void runStage(String startStage, boolean debug) {
    runStage(startStage, debug, true);
  }

  private void runStage(String startStage, boolean debug, boolean explicit) {
    ScratchProject p = project.get();
    if (p == null) {
      return;
    }
    saveAll();
    if (startStage.isEmpty()) {
      alert(I18n.t("run.nostage"));
      return;
    }
    stopProgram();
    long generation = runGeneration.incrementAndGet();
    long start = runStart.incrementAndGet();
    // this start is still the newest (not replaced by another one)
    java.util.function.BooleanSupplier current = () -> runStart.get() == start;
    var eventLog = learning.log();
    int attempt = explicit ? learning.attempt() : 0;
    console.clear();
    crashProblems.clear();
    checkProblems.clear();
    showProblems(checkedProblems);
    runCrashed = false;
    runKept = false;
    lessonRunCounted = false;
    bottomTabs.getSelectionModel().select(consoleTab);
    console.info("▶ " + I18n.t("status.compiling", startStage));
    setStatus(I18n.t("status.compiling", startStage));
    running.set(true);
    startingStage = startStage;
    showRunProgress(true);
    Thread worker = new Thread(() -> {
      try {
        Platform.runLater(() -> {
          watchFrames = -2;
          watchStarted = System.currentTimeMillis();
          watchChanged = watchStarted;
          watchWarned = false;
          recordToggle.setSelected(false);
          resetGameLoop();
          variablesView.reset();
        });
        // a JetBrains Runtime (bundled or downloaded) also hot-reloads new attributes/methods
        RunConfig config = RunConfig.of(startStage).withControl()
            .withJava(org.openpatch.scratch4j.runner.ProgramRuntime.java());
        // every run can take saved code changes (hot reload). A debug run connects
        // at once and stops at the breakpoints; a plain run starts without waiting
        // and is connected only when hot reload or the object diagram needs it.
        var session = debug
            ? new org.openpatch.scratch4j.runner.Debugger(
                org.openpatch.scratch4j.runner.Debugger.byClass(breakpointsNow()), debugListener)
            : org.openpatch.scratch4j.runner.Debugger.onDemand(pause -> { });
        liveSession = session;
        if (debug) {
          debugger = session;
        }
        config = config.withDebugger(session);
        if (debug) {
          Thread attach = new Thread(() -> {
            try {
              session.attach();
            } catch (IOException e) {
              console.err(e.getMessage());
            }
          }, "debugger-attach");
          attach.setDaemon(true);
          attach.start();
        }
        if (debug) {
          Platform.runLater(() -> {
            debuggerView.attach(session);
            showTab(debuggerTab, true);
          });
        }
        CrashWatcher crashes = new CrashWatcher(p, explanationLanguage(), e -> {
          Platform.runLater(() -> { if (project.get() == p && current.getAsBoolean()) showCrash(e); });
        });
        // the code the program starts with: hot reload compares setup code against it
        liveSwap = new org.openpatch.scratch4j.runner.HotSwap(p, session);
        RunHandle handle = runner.run(p, config,
            new RunListener() {
          @Override public void onFrames(long frames, boolean loading) {
            Platform.runLater(() -> {
              if (current.getAsBoolean()) watchdog(frames, loading);
            });
          }

          @Override public void onPaused(long frame) {
            Platform.runLater(() -> {
              if (!current.getAsBoolean()) return;
              programPaused = true;
              pauseToggle.setSelected(true);
              setStatus(I18n.t("run.paused", String.valueOf(frame)));
              // a pause is for looking: show the variables (unless debugging)
              if (bottomTabs.getSelectionModel().getSelectedItem() != debuggerTab) {
                showTab(variablesTab, true);
              }
            });
          }

          @Override public void onResumed() {
            Platform.runLater(() -> {
              if (!current.getAsBoolean()) return;
              programPaused = false;
              pauseToggle.setSelected(false);
              setStatus(I18n.t("status.running", startStage));
            });
          }

          @Override public void onState(org.openpatch.scratch4j.runner.ProgramState state) {
            Platform.runLater(() -> {
              if (running.get() && current.getAsBoolean()) variablesView.show(state);
            });
          }

          @Override public void onSaved(String path) {
            Platform.runLater(() -> {
              console.info("\u2714 " + I18n.t("run.saved", path));
              setStatus(I18n.t("run.saved", path));
              fileTree.reload();
            });
          }

          @Override public void onStdout(String line) {
            if (session.offer(line)) {
              return; // the debug agent's port, not the student's output
            }
            if (current.getAsBoolean()) console.out(line);
          }

          @Override public void onStderr(String line) {
            if (!current.getAsBoolean()) return; // a replaced program's last words
            console.err(line);
            crashes.feed(line);
            // the graphics driver cannot run OpenGL: once, start again in software
            if (!org.openpatch.scratch4j.runner.GraphicsCompatibility.isSoftware()
                && org.openpatch.scratch4j.runner.GraphicsCompatibility.isGraphicsFailure(line)
                && graphicsFallback.compareAndSet(false, true)) {
              Platform.runLater(() -> rerunWithCompatibilityGraphics(startStage, debug));
            }
          }

          @Override public void onCompileSucceeded(CompileResult r) {
            if (explicit) learning.ran(p, eventLog, attempt, r);
          }

          @Override public void onCompileFailed(CompileResult r) {
            if (explicit) learning.ran(p, eventLog, attempt, r);
            Platform.runLater(() -> {
              if (!current.getAsBoolean()) return;
              // the full check gives these errors explanations and did-you-mean
              checkProject();
              bottomTabs.getSelectionModel().select(problemsTab);
              setStatus(I18n.t("status.compiled.errors", r.errors().size()));
              console.info("✖ " + I18n.t("status.compiled.errors", r.errors().size()));
            });
          }

          @Override public void onExit(int code) {
            crashes.flush();
            Platform.runLater(() -> {
              closeLiveSession(session);
              // a program replaced by a newer start: the new one runs on
              if (!current.getAsBoolean()) return;
              resetGameLoop();
              variablesView.ended();
              running.set(false);
              setStatus(I18n.t("status.stopped", code));
              console.info("■ " + I18n.t("status.stopped", code));
            });
          }

          @Override public void onError(String message) {
            console.err(message);
          }
        });
        // pressed again or stopped while this one compiled: it does not run as well
        if (runGeneration.get() != generation || !currentRun.compareAndSet(null, handle)) {
          handle.stop();
          return;
        }
        keptRuns.incrementAndGet();
        if (debugToggle.isSelected()) {
          handle.send("debug on");
        }
        // compiled and launched: Java starts, then the library loads the assets
        Platform.runLater(() -> {
          if (runProgress.isVisible()) setStatus(I18n.t("run.phase.starting", startStage));
        });
      } catch (ProjectRunner.CompilationFailedException e) {
        Platform.runLater(() -> {
          if (!current.getAsBoolean()) return;
          running.set(false);
          endDebugSession(); // no program will ever connect to it
        });
      } catch (IOException | RuntimeException e) {
        if (current.getAsBoolean()) console.err(String.valueOf(e.getMessage()));
        Platform.runLater(() -> {
          if (!current.getAsBoolean()) return;
          running.set(false);
          endDebugSession();
          setStatus(String.valueOf(e.getMessage()));
        });
      }
    }, "scratch4j-run");
    worker.setDaemon(true);
    worker.start();
  }

  /**
   * Run > Hot reload for new attributes and methods: shows whether the runtime
   * that can do it is there, or downloads it (JetBrains Runtime, ~100 MB).
   */
  private void enhancedHotReload() {
    Thread probe = new Thread(() -> {
      Path java = org.openpatch.scratch4j.runner.ProgramRuntime.enhanced();
      Platform.runLater(() -> {
        if (java != null) {
          Alert info = new Alert(Alert.AlertType.INFORMATION,
              I18n.t("hotswap.enhanced.active", java.getParent().getParent()), I18n.ok());
          info.setHeaderText(null);
          Theme.style(info);
          info.showAndWait();
          return;
        }
        Alert ask = new Alert(Alert.AlertType.CONFIRMATION, I18n.t("hotswap.enhanced.ask"),
            I18n.ok(), I18n.cancel());
        ask.setHeaderText(null);
        Theme.style(ask);
        if (ask.showAndWait().orElse(null) != I18n.ok()) return;
        Thread download = new Thread(() -> {
          try {
            long[] shown = {-1};
            Path got = org.openpatch.scratch4j.runner.ProgramRuntime.download(progress -> {
              long percent = Math.round(progress * 100);
              if (percent != shown[0]) {
                shown[0] = percent;
                Platform.runLater(() -> setStatus(I18n.t("hotswap.enhanced.loading", percent)));
              }
            });
            Platform.runLater(() -> {
              setStatus(I18n.t("hotswap.enhanced.ready"));
              console.info("\u26A1 " + I18n.t("hotswap.enhanced.ready") + " (" + got + ")");
            });
          } catch (IOException e) {
            Platform.runLater(() -> alert(I18n.t("hotswap.enhanced.failed", e.getMessage()), e));
          }
        }, "runtime-download");
        download.setDaemon(true);
        download.start();
      });
    }, "runtime-probe");
    probe.setDaemon(true);
    probe.start();
  }

  /** View > Diagrams: the class diagram, and the objects of the running program. */
  private void openDiagrams() {
    ScratchProject p = project.get();
    if (p == null) return;
    saveAll();
    editor.openTool("diagrams", I18n.t("diagram.title"), "fth-layout",
        () -> new DiagramView(p, () -> liveSession, file -> editor.open(file)));
  }

  /** Project > Versions: what changed since a version, restore all or one file. */
  private void showVersions() {
    ScratchProject p = project.get();
    if (p == null) return;
    try {
      editor.saveForVersion();
      VersionsDialog.show(p.root(), this::afterRestore, editor::saveForVersion);
    } catch (IOException e) {
      alert(e.getMessage(), e);
    }
  }

  private void saveVersion() {
    ScratchProject p = project.get();
    if (p != null && VersionsDialog.save(p.root(), editor::saveForVersion)) {
      setStatus(I18n.t("versions.saved"));
    }
  }

  /** Project > Back to the last version that worked (one click, undoable). */
  void restoreLastWorking() {
    ScratchProject p = project.get();
    if (p == null) return;
    try {
      editor.saveForVersion();
      var version = org.openpatch.scratch4j.core.io.ProjectSnapshots.lastWorking(p.root());
      if (version == null) {
        alert(I18n.t("versions.lastworking.none"));
        return;
      }
      if (org.openpatch.scratch4j.core.io.ProjectSnapshots.changes(p.root(), version)
          .isEmpty()) {
        setStatus(I18n.t("versions.same"));
        return;
      }
      String when = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
          .withZone(java.time.ZoneId.systemDefault()).format(version.time());
      Alert ask = new Alert(Alert.AlertType.CONFIRMATION,
          I18n.t("versions.lastworking.confirm", when), I18n.ok(), I18n.cancel());
      ask.setHeaderText(null);
      Theme.style(ask);
      if (ask.showAndWait().orElse(null) != I18n.ok()) return;
      org.openpatch.scratch4j.core.io.ProjectSnapshots.restore(p.root(), version);
      afterRestore();
      setStatus(I18n.t("versions.restored", when));
    } catch (IOException e) {
      alert(e.getMessage(), e);
    }
  }

  /**
   * Files changed on disk: every view catches up. The crashes of the last run
   * were the replaced code's, so they go too, and a running program gets the
   * restored code like a save.
   */
  private void afterRestore() {
    ScratchProject p = project.get();
    if (p != null) p.reloadMetadata();
    for (Tab tab : List.copyOf(editor.getTabs())) {
      Path open = EditorTabs.fileOf(tab);
      if (open == null) continue;
      if (java.nio.file.Files.exists(open)) {
        if (tab.getContent() instanceof ImageEditorView || tab.getContent() instanceof SoundEditorView
            || tab.getContent() instanceof MapEditorView || tab.getContent() instanceof FontPreviewView) {
          editor.closeForFile(open);
          openByType(open);
        } else {
          editor.reloadIfOpen(open);
        }
      } else {
        editor.closeForFile(open);
      }
    }
    fileTree.reload();
    reloadDesigners();
    if (selector != null) selector.refresh();
    crashProblems.clear();
    showProblems(checkedProblems);
    scheduleHotSwap();
    scheduleCheck();
  }

  /** A run that drew for a few seconds without crashing: keep the code as a version. */
  private void keepWorkingVersion() {
    ScratchProject p = project.get();
    if (p == null) return;
    Thread keep = new Thread(() -> {
      try {
        org.openpatch.scratch4j.core.io.ProjectSnapshots.keep(p.root(),
            org.openpatch.scratch4j.core.io.ProjectSnapshots.Kind.RAN);
      } catch (IOException ignored) {
        // versions are a convenience
      }
    }, "keep-version");
    keep.setDaemon(true);
    keep.start();
  }

  /** A crash of the running program: in the problems, at its line, in the console. */
  void showCrash(org.openpatch.scratch4j.core.lint.RuntimeErrors.Explanation e) {
    ScratchProject crashedProject = project.get();
    Platform.runLater(() -> {
      if (project.get() != crashedProject) return;
      String where = e.file() == null ? "" : " (" + e.file().getFileName() + ":" + e.line() + ")";
      Problem problem = new Problem(e.file(), Math.max(0, e.line()), 0,
          "\u2716 " + I18n.t("crash.title", e.title()),
          e.explanation() + "\n" + e.hint(), List.of(), true, null, null, false, null,
          e.trace(), "runtime." + e.caseKey(), null);
      learning.crash(e, problem);
      crashProblems.add(problem);
      runCrashed = true;
      showProblems(checkedProblems);
      console.info("\u2716 " + I18n.t("crash.title", e.title()) + where);
      console.info("   " + e.explanation());
      console.info("   " + e.hint());
      console.info("   " + I18n.t("versions.crash.tip"));
      bottomTabs.getSelectionModel().select(problemsTab);
      if (e.file() != null && e.line() > 0) {
        editor.openAt(e.file(), e.line());
      }
      setStatus(I18n.t("crash.title", e.title()) + where);
    });
  }

  private void stopProgram() {
    runGeneration.incrementAndGet();
    var debug = debugger;
    var live = liveSession;
    debugger = null;
    liveSession = null;
    liveSwap = null;
    debugPaused = false;
    if (debug != null) {
      debuggerView.running(null);
      showDebugLine(-1);
    }
    RunHandle handle = currentRun.getAndSet(null);
    if (handle != null || debug != null || live != null) {
      // Finish terminating child processes even if closing the last IDE window
      // shuts down JavaFX while this worker is still waiting for their exit.
      Thread.ofPlatform().daemon(false).name("stop-program").start(() -> {
        try {
          if (handle != null) handle.stop();
        } finally {
          if (debug != null) debug.close();
          if (live != null && live != debug) live.close();
        }
      });
    }
  }

  private void closeLiveSession(org.openpatch.scratch4j.runner.Debugger session) {
    if (session != null && liveSession == session) {
      liveSession = null;
      Thread.ofVirtual().name("close-live-session").start(session::close);
    }
  }

  /** Saved code while the program runs: swap the changed classes in (debounced). */
  private void scheduleHotSwap() {
    var session = liveSession;
    var swap = liveSwap;
    ScratchProject p = project.get();
    if (session == null || swap == null || p == null || !running.get()) return;
    hotSwapDelay.setOnFinished(e -> {
      if (!session.isConnectable() || liveSession != session) return;
      Thread worker = new Thread(() -> {
        org.openpatch.scratch4j.runner.HotSwap.Result result;
        // a plain run is connected the first time a change goes in
        if (!session.connect()) return;
        try {
          synchronized (hotSwapLock) {
            result = swap.apply();
          }
        } catch (IOException | RuntimeException ex) {
          return;
        }
        Platform.runLater(() -> showHotSwap(result));
      }, "hot-swap");
      worker.setDaemon(true);
      worker.start();
    });
    hotSwapDelay.playFromStart();
  }

  private final Object hotSwapLock = new Object();
  private final javafx.animation.PauseTransition hotSwapDelay =
      new javafx.animation.PauseTransition(javafx.util.Duration.millis(250));

  private void showHotSwap(org.openpatch.scratch4j.runner.HotSwap.Result result) {
    switch (result.status()) {
      case SWAPPED -> {
        String classes = String.join(", ", result.classes().stream()
            .map(c -> c.contains("$") ? c.substring(0, c.indexOf('$')) : c).distinct().toList());
        console.info("\u26A1 " + I18n.t("hotswap.done", classes));
        lastSwap = System.currentTimeMillis();
        runKept = runCrashed; // the new code is kept too once it ran a while
        for (var update : result.values()) {
          console.info("\u26A1 " + I18n.t("hotswap.value", update.className().replace('$', '.')
              + "." + update.field(), update.value(), update.objects()));
        }
        if (!result.setupChanged().isEmpty()) {
          // objects were made with the old constructor: tell the student why nothing moves
          String names = String.join(", ", result.setupChanged().stream()
              .map(c -> c.replace('$', '.')).toList());
          String warning = I18n.t("hotswap.setup", names);
          console.hint("\u26A0 " + warning);
          setStatus("\u26A0 " + warning);
          notice(warning);
        }
        setStatus("\u26A1 " + I18n.t("hotswap.done", classes));
      }
      case COMPILE_ERRORS -> setStatus(I18n.t("hotswap.waiting"));
      case RESTART_NEEDED -> {
        console.info("\u21bb " + I18n.t("hotswap.restart"));
        setStatus("\u21bb " + I18n.t("hotswap.restart"));
        if (org.openpatch.scratch4j.runner.ProgramRuntime.enhanced() == null) {
          console.hint("\u26A1 " + I18n.t("hotswap.enhanced.tip"));
        }
      }
      default -> { }
    }
  }

  /** A short note in the window's bottom right corner that fades away by itself. */
  private void notice(String text) {
    if (stage == null || stage.getScene() == null) return;
    Label label = new Label("\u26A0 " + text, null);
    label.setWrapText(true);
    label.setMaxWidth(420);
    label.getStyleClass().add("notice");
    javafx.stage.Popup popup = new javafx.stage.Popup();
    popup.getContent().add(label);
    popup.setAutoHide(true);
    label.setOnMouseClicked(e -> popup.hide());
    popup.show(stage);
    label.applyCss();
    label.layout();
    double w = label.prefWidth(-1);
    double h = label.prefHeight(Math.min(420, w));
    popup.setX(stage.getX() + stage.getWidth() - Math.min(440, w + 20) - 24);
    popup.setY(stage.getY() + stage.getHeight() - h - 70);
    javafx.animation.PauseTransition hide =
        new javafx.animation.PauseTransition(javafx.util.Duration.seconds(8));
    hide.setOnFinished(e -> popup.hide());
    hide.play();
  }

  /** Closes the debugger session (stops listening, releases the attach thread). */
  private void endDebugSession() {
    var session = debugger;
    debugger = null;
    debugPaused = false;
    if (session != null) {
      Thread.ofVirtual().name("close-debug-session").start(session::close);
      debuggerView.running(null);
      showDebugLine(-1);
    }
  }

  // --- export ------------------------------------------------------------------------

  /** The four export formats; writes generated output below the project export folder. */
  private void export(String format) {
    ScratchProject p = project.get();
    if (p == null) {
      alert(I18n.t("status.no.project"));
      return;
    }
    saveAll();
    Path out = p.root().resolve("export");
    setStatus(I18n.t("export.running"));
    Thread worker = new Thread(() -> {
      try {
        Path result = switch (format) {
          case "app" -> new org.openpatch.scratch4j.export.StudentExport()
              .export(p, currentOs(), null, out).appDir();
          case "windows-x64", "windows-aarch64", "mac-aarch64", "mac-x64", "linux-x64",
              "linux-aarch64" -> {
            String[] target = format.split("-");
            Platform.runLater(() -> setStatus(I18n.t("export.runtime", format)));
            yield new org.openpatch.scratch4j.export.StudentExport().export(p,
                new org.openpatch.scratch4j.export.JmodsFetcher.Target(target[0], target[1]),
                out, runtimeCache()).archive();
          }
          case "jar" -> org.openpatch.scratch4j.export.ProjectFormats
              .exportRunnableJar(p, out.resolve(p.name() + ".jar"));
          case "bluej" -> org.openpatch.scratch4j.export.ProjectFormats
              .exportBlueJZip(p, out.resolve(p.name() + "-bluej.zip"));
          case "vscode" -> org.openpatch.scratch4j.export.ProjectFormats
              .exportVsCodeZip(p, out.resolve(p.name() + "-vscode.zip"));
          default -> throw new IOException("unknown export format " + format);
        };
        Platform.runLater(() -> {
          fileTree.reload();
          setStatus(I18n.t("export.done", result));
          Alert done = new Alert(Alert.AlertType.INFORMATION, I18n.t("export.done", result));
          done.setHeaderText(null);
          ButtonType show = new ButtonType(I18n.t("tree.reveal"));
          done.getButtonTypes().setAll(show, I18n.ok());
          done.showAndWait().filter(b -> b == show).ifPresent(b ->
              browse((Files.isDirectory(result) ? result : result.getParent()).toUri()
                  .toString()));
        });
      } catch (IOException | RuntimeException e) {
        Platform.runLater(() -> alert(I18n.t("export.failed", e.getMessage()), e));
      }
    }, "scratch4j-export");
    worker.setDaemon(true);
    worker.start();
  }

  private static org.openpatch.scratch4j.export.StudentExport.Os currentOs() {
    String os = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT);
    if (os.contains("win")) {
      return org.openpatch.scratch4j.export.StudentExport.Os.WINDOWS;
    }
    if (os.contains("mac") || os.contains("darwin")) {
      return org.openpatch.scratch4j.export.StudentExport.Os.MACOS;
    }
    return org.openpatch.scratch4j.export.StudentExport.Os.LINUX;
  }

  // --- project menu: library, sharing, snippets ----------------------------------

  private final ToggleGroup flavourGroup = new ToggleGroup();
  private RadioMenuItem standardFlavour;
  private RadioMenuItem nrwFlavour;

  private Menu projectMenu() {
    Menu menu = new Menu(I18n.t("menu.project"));
    menu.getItems().addAll(
        item("versions.save", "fth-save", null, this::saveVersion),
        item("versions.menu", "fth-clock", null, this::showVersions),
        item("versions.lastworking", "fth-rotate-ccw", null, this::restoreLastWorking),
        new SeparatorMenuItem(),
        item("menu.project.sharezip", "fth-share-2", null, this::shareZip),
        item("menu.project.openzip", "fth-archive", null, this::openSharedZip));
    return menu;
  }

  /**
   * Help > For teachers: the library flavour and updates, course packs, the
   * browser and Online IDE exchange formats, and the hot-reload runtime. A
   * student never needs these, so they stay out of the main menus.
   */
  private Menu teachersMenu() {
    Menu menu = new Menu(I18n.t("menu.help.teachers"), Icons.of("fth-users"));
    Menu library = new Menu(I18n.t("menu.project.library"), Icons.of("fth-package"));
    standardFlavour = new RadioMenuItem(I18n.t("library.standard"));
    nrwFlavour = new RadioMenuItem(I18n.t("library.nrw"));
    standardFlavour.setToggleGroup(flavourGroup);
    nrwFlavour.setToggleGroup(flavourGroup);
    standardFlavour.setOnAction(e -> switchFlavour(LibraryFlavour.STANDARD));
    nrwFlavour.setOnAction(e -> switchFlavour(LibraryFlavour.NRW));
    library.getItems().addAll(standardFlavour, nrwFlavour, new SeparatorMenuItem(),
        item("library.abiturklassen", "fth-folder-plus", null, this::importAbiturklassen),
        item("library.update", "fth-refresh-cw", null, this::checkLibraryUpdate));
    library.setOnShowing(e -> syncFlavourItems());
    menu.getItems().addAll(
        library,
        learning.flag(true), learning.flag(false), learning.reportItem(stage),
        item("menu.project.course", "fth-book-open", null, this::importCoursePack),
        item("lessoneditor.menu", "fth-compass", null, this::editLesson),
        new SeparatorMenuItem(),
        item("menu.project.browserzip", "fth-share-2", null, this::shareBrowserZip),
        item("menu.project.snippet.import", "fth-clipboard", null, this::importSnippet),
        item("menu.project.snippet.export", "fth-copy", null, this::exportCompact),
        new SeparatorMenuItem(),
        item("hotswap.enhanced.menu", "fth-zap", null, this::enhancedHotReload));
    return menu;
  }

  private void syncFlavourItems() {
    ScratchProject p = project.get();
    boolean nrw = p != null && LibraryFlavour.fromId(p.settings().flavour) == LibraryFlavour.NRW;
    standardFlavour.setSelected(!nrw);
    nrwFlavour.setSelected(nrw);
    standardFlavour.setDisable(p == null);
    nrwFlavour.setDisable(p == null);
  }

  /** Standard <-> NRW: the flavour is saved, the matching jar goes into +libs with consent. */
  private void switchFlavour(LibraryFlavour flavour) {
    ScratchProject p = project.get();
    if (p == null) return;
    try {
      p.settings().flavour = flavour.id();
      p.settings().libraryPin = "";
      p.settings().save(p.root());
      // the NRW library's API returns the Abiturklassen List: offer it right away
      if (LibraryCheck.nrwListMissing(p)) {
        importAbiturklassen();
      }
    } catch (IOException e) {
      alert(e.getMessage(), e);
      return;
    }
    checkLibrary(p, true);
  }

  /**
   * Compares +libs with the IDE's library in the project's flavour and offers
   * the swap (only with consent; "keep" pins the project's jar).
   */
  private void checkLibrary(ScratchProject p, boolean explicit) {
    Thread worker = new Thread(() -> {
      try {
        var status = LibraryCheck.status(p, LibraryJarSource.SCRATCH_VERSION);
        LibraryFlavour flavour = LibraryFlavour.fromId(p.settings().flavour);
        boolean nrwListMissing = LibraryCheck.nrwListMissing(p);
        Platform.runLater(() -> {
          if (status.offerSwap(p.settings()) || (explicit && status.state()
              != LibraryCheck.State.CURRENT && status.state() != LibraryCheck.State.NEWER)) {
            offerLibrarySwap(p, status, flavour);
          } else if (explicit) {
            setStatus(I18n.t("library.ok", status.version() == null ? "-" : status.version()));
          }
          if (nrwListMissing) {
            console.err("\u26A0 " + I18n.t("library.nrw.list"));
          }
        });
      } catch (IOException e) {
        Platform.runLater(() -> setStatus(e.getMessage()));
      }
    }, "library-check");
    worker.setDaemon(true);
    worker.start();
  }

  private void offerLibrarySwap(ScratchProject p, LibraryCheck.Status status,
      LibraryFlavour flavour) {
    String wanted = LibraryJarSource.SCRATCH_VERSION
        + (flavour == LibraryFlavour.NRW ? " (NRW)" : "");
    String message = switch (status.state()) {
      case MISSING -> I18n.t("library.missing", wanted);
      case OTHER_FLAVOUR -> I18n.t("library.flavour", status.jar().getFileName(), wanted);
      default -> I18n.t("library.outdated", status.jar() == null ? "?" : status.jar().getFileName(),
          wanted);
    };
    // nothing to replace or keep when +libs is empty
    boolean missing = status.state() == LibraryCheck.State.MISSING;
    ButtonType replace = new ButtonType(I18n.t(missing ? "library.add" : "library.replace"),
        javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
    ButtonType keep = new ButtonType(I18n.t(missing ? "library.skip" : "library.keep"),
        javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
    Alert ask = new Alert(Alert.AlertType.CONFIRMATION, message, replace, keep);
    ask.setHeaderText(I18n.t("library.title"));
    Theme.style(ask);
    var answer = ask.showAndWait().orElse(keep);
    if (answer != replace) {
      try {
        if (status.version() != null) LibraryCheck.pin(p, status);
      } catch (IOException e) {
        alert(e.getMessage(), e);
      }
      return;
    }
    setStatus(I18n.t("library.installing", wanted));
    Thread worker = new Thread(() -> {
      try {
        Path jar = LibraryJarSource.jar(flavour, libraryDirFor(flavour));
        LibraryCheck.install(p, jar);
        Platform.runLater(() -> {
          fileTree.reload();
          setStatus(I18n.t("library.installed", jar.getFileName()));
          try { apiIndex.useProject(p); palette.refresh(); } catch (IOException e) { console.err(e.getMessage()); }
          scheduleCheck();
        });
      } catch (IOException e) {
        Platform.runLater(() -> alert(e.getMessage(), e));
      }
    }, "library-install");
    worker.setDaemon(true);
    worker.start();
  }

  /**
   * NRW: the Abiturklassen (QUA-LiS) the student picks, downloaded once into
   * ~/.scratch4j/abiturklassen and copied into the project with what they need.
   */
  private void importAbiturklassen() {
    ScratchProject p = project.get();
    if (p == null) return;
    java.util.Set<String> present;
    try {
      present = org.openpatch.scratch4j.core.project.Abiturklassen.present(p);
    } catch (IOException e) {
      alert(e.getMessage(), e);
      return;
    }
    var choice = AbiturklassenDialog.show(present).orElse(null);
    if (choice == null) return;
    setStatus(I18n.t(choice.source() == null ? "abitur.downloading" : "abitur.copying"));
    Thread worker = new Thread(() -> {
      try {
        Path source = choice.source() != null ? choice.source()
            : org.openpatch.scratch4j.core.project.Abiturklassen.download(Path.of(
                System.getProperty("user.home"), ".scratch4j", "abiturklassen"));
        var written = org.openpatch.scratch4j.core.project.Abiturklassen.install(p,
            org.openpatch.scratch4j.core.project.Abiturklassen.read(source), choice.classes());
        // the database classes run only with the SQLite JDBC driver in +libs
        Path driver = null;
        String driverError = null;
        if (org.openpatch.scratch4j.core.project.Abiturklassen.withRequirements(choice.classes())
            .contains("DatabaseConnector")) {
          Platform.runLater(() -> setStatus(I18n.t("abitur.driver.downloading")));
          try {
            driver = org.openpatch.scratch4j.core.project.Abiturklassen.installSqliteDriver(p,
                Path.of(System.getProperty("user.home"), ".scratch4j", "abiturklassen"));
          } catch (IOException e) {
            driverError = e.getMessage();
          }
        }
        Path addedDriver = driver;
        String failedDriver = driverError;
        Platform.runLater(() -> {
          fileTree.reload();
          scheduleCheck();
          setStatus(I18n.t("abitur.done", written.size()));
          for (Path file : written) {
            console.info("\u2714 " + I18n.t("abitur.added", file.getFileName()));
          }
          if (addedDriver != null) {
            console.info("\u2714 " + I18n.t("abitur.driver.added", addedDriver.getFileName()));
          }
          if (failedDriver != null) {
            console.err("\u26A0 " + I18n.t("abitur.driver.failed", failedDriver));
          }
        });
      } catch (IOException e) {
        Platform.runLater(() -> {
          setStatus(I18n.t("abitur.failed.short"));
          alert(I18n.t("abitur.failed", e.getMessage(),
              org.openpatch.scratch4j.core.project.Abiturklassen.URL), e);
        });
      }
    }, "scratch4j-abiturklassen");
    worker.setDaemon(true);
    worker.start();
  }

  /** Is there a newer library on GitHub? Offers it for this project (pinned there). */
  private void checkLibraryUpdate() {
    ScratchProject p = project.get();
    setStatus(I18n.t("library.update.checking"));
    Thread worker = new Thread(() -> {
      try {
        String latest = LibraryJarSource.latestVersion();
        String current = p == null ? LibraryJarSource.SCRATCH_VERSION
            : java.util.Optional.ofNullable(LibraryCheck.status(p,
                LibraryJarSource.SCRATCH_VERSION).version())
                .orElse(LibraryJarSource.SCRATCH_VERSION);
        boolean newer = LibraryCheck.isNewer(latest, current);
        Platform.runLater(() -> {
          if (!newer || p == null) {
            setStatus(I18n.t("library.update.none", current));
            return;
          }
          ButtonType get = new ButtonType(I18n.t("library.update.get"),
              javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
          Alert ask = new Alert(Alert.AlertType.CONFIRMATION,
              I18n.t("library.update.found", latest, current), get, I18n.cancel());
          ask.setHeaderText(null);
          Theme.style(ask);
          if (ask.showAndWait().orElse(null) == get) {
            Thread download = new Thread(() -> {
              try {
                boolean nrw = LibraryFlavour.fromId(p.settings().flavour) == LibraryFlavour.NRW;
                // never into the installed app's library folder (read-only in Program Files, /opt)
                Path jar = LibraryJarSource.download(latest, nrw, userLibraryCache());
                LibraryCheck.install(p, jar);
                LibraryCheck.pin(p, LibraryCheck.status(p, LibraryJarSource.SCRATCH_VERSION));
                Platform.runLater(() -> {
                  fileTree.reload();
                  setStatus(I18n.t("library.installed", jar.getFileName()));
                  try { apiIndex.useProject(p); palette.refresh(); } catch (IOException e) { console.err(e.getMessage()); }
                  scheduleCheck();
                });
              } catch (IOException e) {
                Platform.runLater(() -> alert(e.getMessage(), e));
              }
            }, "library-download");
            download.setDaemon(true);
            download.start();
          }
        });
      } catch (IOException e) {
        Platform.runLater(() -> setStatus(I18n.t("library.update.offline")));
      }
    }, "library-update");
    worker.setDaemon(true);
    worker.start();
  }

  private void shareZip() {
    ScratchProject p = project.get();
    if (p == null) return;
    saveAll();
    try {
      Path zip = org.openpatch.scratch4j.export.ProjectFormats.exportShareZip(p,
          p.root().resolve("export").resolve(p.name() + ".zip"));
      fileTree.reload();
      setStatus(I18n.t("export.done", zip));
    } catch (IOException e) {
      alert(I18n.t("export.failed", e.getMessage()), e);
    }
  }

  private void importCoursePack() {
    javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
    chooser.setTitle(I18n.t("menu.project.course"));
    chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("Course pack ZIP", "*.zip"));
    File archive = chooser.showOpenDialog(stage);
    if (archive == null) return;
    DirectoryChooser parent = new DirectoryChooser();
    parent.setTitle(I18n.t("sb3.target"));
    parent.setInitialDirectory(ProjectsFolder.existing(ProjectsFolder.current()));
    File folder = parent.showDialog(stage);
    if (folder == null) return;
    importing(archive.getName(), folder.toPath(), progress -> {
      var libraries = new java.util.HashMap<String, Path>();
      for (LibraryFlavour flavour : LibraryFlavour.values()) {
        libraries.put(flavour.id(), LibraryJarSource.jar(flavour, libraryDirFor(flavour)));
      }
      return org.openpatch.scratch4j.export.CoursePack.importZip(archive.toPath(), folder.toPath(),
          libraries, progress);
    }, imported -> {
      var names = imported.projects().stream().map(path -> imported.root().relativize(path).toString()).toList();
      javafx.scene.control.ChoiceDialog<String> choose = new javafx.scene.control.ChoiceDialog<>(names.get(0), names);
      choose.setTitle(I18n.t("menu.project.course"));
      choose.setHeaderText(I18n.t("course.choose"));
      Theme.style(choose);
      choose.showAndWait().ifPresent(name -> openProjectAt(imported.root().resolve(name)));
    }, message -> message);
  }

  private void openSharedZip() {
    javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
    chooser.setTitle(I18n.t("menu.project.openzip"));
    chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter(I18n.t("welcome.import.hint"), "*.zip", "*.json"));
    File zip = chooser.showOpenDialog(stage);
    if (zip == null) return;
    javafx.stage.DirectoryChooser parent = new javafx.stage.DirectoryChooser();
    parent.setTitle(I18n.t("sb3.target"));
    parent.setInitialDirectory(ProjectsFolder.existing(ProjectsFolder.current()));
    File folder = parent.showDialog(stage);
    if (folder == null) return;
    importShared(zip.toPath(), folder.toPath());
  }

  /** Imports a project ZIP or workspace JSON into a new folder in {@code parentDir} and opens it. */
  private void importShared(Path file, Path parentDir) {
    importing(file.getFileName().toString(), parentDir, progress -> {
      Files.createDirectories(parentDir);
      return SharedProjectFile.isJson(file)
          ? org.openpatch.scratch4j.core.project.PortableProject.importWorkspace(file, parentDir)
          : org.openpatch.scratch4j.export.ProjectFormats.importZip(file, parentDir, progress);
    }, imported -> {
      openProjectAt(imported);
      ScratchProject opened = project.get();
      try {
        if (opened != null && "nrw".equals(opened.settings().flavour)
            && !org.openpatch.scratch4j.core.project.Abiturklassen.present(opened).contains("List")) {
          alert(I18n.t("project.transfer.nrw"));
        }
      } catch (IOException e) {
        alert(e.getMessage(), e);
      }
    }, message -> message);
  }

  private void shareBrowserZip() {
    ScratchProject p = project.get();
    if (p == null) return;
    saveAll();
    try {
      Path zip = org.openpatch.scratch4j.export.ProjectFormats.exportBrowserZip(p,
          p.root().resolve("export").resolve(p.name() + "-browser.zip"));
      fileTree.reload();
      setStatus(I18n.t("export.done", zip));
    } catch (IOException e) { alert(I18n.t("export.failed", e.getMessage()), e); }
  }

  /** Online IDE / docs example: paste it, get normal classes. */
  private void importSnippet() {
    ScratchProject p = project.get();
    if (p == null) {
      alert(I18n.t("status.no.project"));
      return;
    }
    javafx.scene.control.TextArea text = new javafx.scene.control.TextArea();
    text.setPromptText("void main() {\n  new MyStage();\n}\n\nclass MyStage extends Stage { ... }");
    text.setPrefSize(640, 360);
    text.getStyleClass().add("code-input");
    javafx.scene.control.Dialog<ButtonType> dialog = new javafx.scene.control.Dialog<>();
    dialog.setTitle(I18n.t("menu.project.snippet.import"));
    dialog.setHeaderText(I18n.t("snippet.hint"));
    dialog.getDialogPane().setContent(text);
    dialog.getDialogPane().getButtonTypes().addAll(I18n.ok(), I18n.cancel());
    Theme.style(dialog);
    if (dialog.showAndWait().orElse(null) != I18n.ok() || text.getText().isBlank()) return;
    try {
      var imported = org.openpatch.scratch4j.core.project.CompactSource.importSnippet(
          text.getText(), p.root());
      fileTree.reload();
      if (selector != null) selector.refresh();
      for (Path file : imported.files()) editor.open(file);
      scheduleCheck();
      setStatus(I18n.t("snippet.done", imported.files().size(), imported.startClass()));
    } catch (IOException | RuntimeException e) {
      alert(e.getMessage(), e);
    }
  }

  /** The whole project as one compact source file for the Online IDE. */
  private void exportCompact() {
    ScratchProject p = project.get();
    if (p == null) return;
    saveAll();
    try {
      String compact = org.openpatch.scratch4j.core.project.CompactSource.export(p,
          p.startStage());
      Path file = p.root().resolve("export").resolve(p.name().replaceAll("\\W", "") + ".java");
      Files.createDirectories(file.getParent());
      Files.writeString(file, compact);
      javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
      content.putString(compact);
      javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
      fileTree.reload();
      setStatus(I18n.t("snippet.exported", file.getFileName()));
    } catch (IOException e) {
      alert(e.getMessage(), e);
    }
  }

  /** File > New map: assets/maps/<name>.tmx, optionally with a first tileset. */
  private void createMap() {
    ScratchProject p = project.get();
    if (p == null) {
      alert(I18n.t("status.no.project"));
      return;
    }
    TextField name = new TextField("level1");
    TextField width = new TextField("30");
    TextField height = new TextField("20");
    TextField tile = new TextField("32");
    for (TextField f : List.of(width, height, tile)) f.setPrefColumnCount(4);
    javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
    grid.setHgap(8);
    grid.setVgap(8);
    grid.addRow(0, new Label(I18n.t("debug.name")), name);
    grid.addRow(1, new Label(I18n.t("map.resize.size")),
        new HBox(4, width, new Label("\u00d7"), height, new Label(I18n.t("map.new.tiles"))));
    grid.addRow(2, new Label(I18n.t("map.new.tilesize")), new HBox(4, tile, new Label("px")));
    javafx.scene.control.Dialog<ButtonType> dialog = new javafx.scene.control.Dialog<>();
    dialog.setTitle(I18n.t("map.new"));
    dialog.setHeaderText(I18n.t("map.new.hint"));
    dialog.getDialogPane().setContent(grid);
    dialog.getDialogPane().getButtonTypes().addAll(I18n.ok(), I18n.cancel());
    Theme.style(dialog);
    if (dialog.showAndWait().orElse(null) != I18n.ok()) return;
    try {
      String fileName = name.getText().trim().replaceAll("[^A-Za-z0-9_-]", "");
      if (fileName.isEmpty()) fileName = "map";
      Path file = p.root().resolve("assets/maps/" + fileName + ".tmx");
      if (Files.exists(file)) {
        alert(I18n.t("wizard.error.exists"));
        return;
      }
      int size = Integer.parseInt(tile.getText().trim());
      org.openpatch.scratch4j.core.tiled.TmxDocument.create(file,
          Integer.parseInt(width.getText().trim()), Integer.parseInt(height.getText().trim()),
          size, size).save();
      fileTree.reload();
      openByType(file);
    } catch (NumberFormatException e) {
      alert(I18n.t("map.new.numbers"));
    } catch (IOException e) {
      alert(e.getMessage(), e);
    }
  }

  /** Accessibility: the whole UI's text size (the code editor zooms on its own). */
  private Menu textSizeMenu() {
    Menu menu = new Menu(I18n.t("menu.view.textsize"), Icons.of("fth-type"));
    ToggleGroup sizes = new ToggleGroup();
    for (double size : new double[] {1, 1.25, 1.5}) {
      RadioMenuItem item = new RadioMenuItem(Math.round(size * 100) + " %");
      item.setToggleGroup(sizes);
      item.setSelected(Math.abs(Theme.scale() - size) < 0.01);
      item.setOnAction(e -> Theme.setScale(scene, size));
      menu.getItems().add(item);
    }
    return menu;
  }

  /** View > Compatibility graphics: for graphics drivers that cannot run OpenGL. */
  private javafx.scene.control.CheckMenuItem compatibilityGraphicsItem() {
    javafx.scene.control.CheckMenuItem item = new javafx.scene.control.CheckMenuItem(
        I18n.t("menu.view.compatgraphics"), Icons.of("fth-cpu"));
    item.setSelected(Prefs.compatibilityGraphics());
    item.setOnAction(e -> setCompatibilityGraphics(item.isSelected()));
    return item;
  }

  private void setCompatibilityGraphics(boolean on) {
    Prefs.compatibilityGraphics(on);
    org.openpatch.scratch4j.runner.GraphicsCompatibility.setSoftware(on);
    String message = I18n.t(on ? "graphics.on" : "graphics.off");
    if (on && org.openpatch.scratch4j.runner.GraphicsCompatibility.onWindows()) {
      message += " " + I18n.t("graphics.windows");
    }
    setStatus(message);
    console.info("\u2139 " + message);
  }

  /**
   * A program's OpenGL could not start: switch compatibility graphics on (it
   * stays on, the student hears where to switch it off) and run again.
   */
  private void rerunWithCompatibilityGraphics(String startStage, boolean debug) {
    Prefs.compatibilityGraphics(true);
    org.openpatch.scratch4j.runner.GraphicsCompatibility.setSoftware(true);
    runStage(startStage, debug, false);
    String message = I18n.t("graphics.fallback");
    if (org.openpatch.scratch4j.runner.GraphicsCompatibility.onWindows()) {
      message += " " + I18n.t("graphics.windows");
    }
    console.hint("\u26A0 " + message);
    notice(message);
  }

  private javafx.scene.control.CheckMenuItem highContrastItem() {
    javafx.scene.control.CheckMenuItem item =
        new javafx.scene.control.CheckMenuItem(I18n.t("menu.view.contrast"), Icons.of("fth-sun"));
    item.setSelected(Theme.isHighContrast());
    item.setOnAction(e -> Theme.setHighContrast(scene, item.isSelected()));
    return item;
  }

  /** The exported app's version and icon (stored in .scratch4j/project.json). */
  private void editAppInfo() {
    ScratchProject p = project.get();
    if (p == null) return;
    javafx.scene.control.TextField version = new javafx.scene.control.TextField(
        p.settings().appVersion);
    javafx.scene.control.TextField icon = new javafx.scene.control.TextField(p.settings().appIcon);
    icon.setPromptText(I18n.t("appinfo.icon.default"));
    Button pick = new Button(I18n.t("stages.window.splash.pick"));
    pick.setOnAction(e -> AssetPickerDialog.pickImage(p.root(), I18n.t("appinfo.icon"))
        .filter(ref -> Files.isRegularFile(p.root().resolve(ref)))
        .ifPresent(icon::setText));
    javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
    grid.setHgap(8);
    grid.setVgap(8);
    grid.addRow(0, new Label(I18n.t("appinfo.version")), version);
    grid.addRow(1, new Label(I18n.t("appinfo.icon")), new HBox(6, icon, pick));
    javafx.scene.control.Dialog<ButtonType> dialog = new javafx.scene.control.Dialog<>();
    dialog.setTitle(I18n.t("menu.export.appinfo"));
    dialog.setHeaderText(I18n.t("appinfo.hint"));
    dialog.getDialogPane().setContent(grid);
    dialog.getDialogPane().getButtonTypes().addAll(I18n.ok(), I18n.cancel());
    Theme.style(dialog);
    if (dialog.showAndWait().orElse(null) != I18n.ok()) return;
    p.settings().appVersion = version.getText().isBlank() ? "1.0" : version.getText().trim();
    p.settings().appIcon = icon.getText().trim();
    try {
      p.settings().save(p.root());
    } catch (IOException e) {
      alert(e.getMessage(), e);
    }
  }

  /** Apps for the other computers in a class: Windows, macOS (both chips), Linux. */
  /**
   * Where downloaded target runtimes are kept (school admins can pre-seed it
   * with the Temurin JRE archives): {@code SCRATCH4J_RUNTIME_CACHE} or
   * {@code ~/.scratch4j/runtimes}.
   */
  private static Path runtimeCache() {
    String override = System.getenv("SCRATCH4J_RUNTIME_CACHE");
    return override != null && !override.isBlank() ? Path.of(override)
        : Path.of(System.getProperty("user.home"), ".scratch4j", "runtimes");
  }

  // --- help ----------------------------------------------------------------------

  /** F1: help for the word at the caret of the active editor. */
  private void showApiHelp() {
    learning.help("api", null);
    CodeEditor active = editor.activeEditor();
    String word = active == null ? "" : active.wordAtCaret();
    if (word.isEmpty()) {
      // no word at the caret (or no editor): the whole reference
      browse("https://scratch4j.openpatch.org/reference/");
    } else {
      ApiHelpDialog.showForWord(apiIndex, word, this::browse);
    }
  }

  private void showGuide() {
    Label text = new Label(I18n.t("guide.text"));
    text.setWrapText(true);
    text.getStyleClass().add("guide-text");
    VBox box = new VBox(text);
    box.setPrefWidth(620);
    javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(box);
    scroll.setFitToWidth(true);
    scroll.setPrefHeight(480);
    javafx.scene.control.Dialog<Void> dialog = new javafx.scene.control.Dialog<>();
    dialog.setTitle(I18n.t("guide.title"));
    Theme.style(dialog);
    dialog.getDialogPane().getButtonTypes().add(I18n.close());
    dialog.getDialogPane().setContent(scroll);
    dialog.showAndWait();
  }

  private void showAbout() {
    String currentVersion = StudioUpdates.currentVersion();
    Label description = new Label(I18n.t("about.text", currentVersion));
    description.setWrapText(true);
    Label updateStatus = new Label();
    updateStatus.setWrapText(true);
    Hyperlink releaseLink = new Hyperlink(I18n.t("about.update.open"));
    releaseLink.setVisible(false);
    releaseLink.setManaged(false);
    Button check = new Button(I18n.t("about.update.check"));
    check.setOnAction(event -> {
      check.setDisable(true);
      releaseLink.setVisible(false);
      releaseLink.setManaged(false);
      updateStatus.setText(I18n.t("about.update.checking"));
      Thread worker = new Thread(() -> {
        try {
          StudioUpdates.Release latest = StudioUpdates.latestRelease(currentVersion);
          Platform.runLater(() -> {
            if (currentVersion.equals("development")
                || StudioUpdates.isNewer(latest.version(), currentVersion)) {
              updateStatus.setText(I18n.t("about.update.available", latest.version()));
              releaseLink.setOnAction(click -> browse(latest.page().toString()));
              releaseLink.setVisible(true);
              releaseLink.setManaged(true);
            } else {
              updateStatus.setText(I18n.t("about.update.current"));
            }
            check.setDisable(false);
          });
        } catch (IOException e) {
          Platform.runLater(() -> {
            updateStatus.setText(I18n.t("about.update.error"));
            check.setDisable(false);
          });
        }
      }, "studio-update-check");
      worker.setDaemon(true);
      worker.start();
    });
    VBox content = new VBox(10, description, check, updateStatus, releaseLink);
    Alert alert = new Alert(Alert.AlertType.INFORMATION, "", I18n.close());
    alert.setHeaderText(null);
    alert.getDialogPane().setContent(content);
    Theme.style(alert);
    alert.showAndWait();
  }

  /** Block palette: insert the Java code at the caret of the active editor. */
  private void insertFromPalette(ApiMethod method) {
    CodeEditor active = editor.activeEditor();
    if (active != null) {
      if (insertWithImports(active, BlockPalette.snippet(method)))
        learning.event("block.insert", Map.of("block", org.openpatch.scratch4j.core.io.EventLog.id(method.className() + "." + method.signature())));
    } else {
      setStatus(I18n.t("palette.open.editor.first"));
    }
  }

  /** The project's library classes by simple name, for imports (read once per project). */
  private org.openpatch.scratch4j.core.project.JavaImports.Library importLibrary;
  private List<Path> importLibraryJars;

  /**
   * Inserts code and the imports its library classes need ({@code Window},
   * {@code KeyCode}, ...), so it compiles without fully qualified names.
   */
  private boolean insertWithImports(CodeEditor target, String snippet) {
    if (!target.insertBlock(snippet)) return false;
    addImports(target, snippet);
    return true;
  }

  /** The imports the library classes in inserted code need (a dropped block's as well). */
  private void addImports(CodeEditor target, String snippet) {
    ScratchProject p = project.get();
    if (p == null) return;
    try {
      List<Path> jars = p.libs();
      if (importLibrary == null || !jars.equals(importLibraryJars)) {
        importLibrary = new org.openpatch.scratch4j.core.project.JavaImports.Library(jars);
        importLibraryJars = jars;
      }
      List<String> own = p.javaSources().stream()
          .map(f -> f.getFileName().toString().replaceFirst("\\.java$", "")).toList();
      target.addImports(org.openpatch.scratch4j.core.project.JavaImports.neededBy(
          snippet, importLibrary, own));
    } catch (IOException e) {
      // no imports: javac's friendly message names the missing one
    }
  }

  private void browse(String url) {
    getHostServices().showDocument(url);
  }

  private void setStatus(String text) {
    if (Platform.isFxApplicationThread()) {
      status.setText(text);
    } else {
      Platform.runLater(() -> status.setText(text));
    }
  }

  private void exitApp() {
    stage.fireEvent(new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST));
  }

  private void alert(String message) {
    Alert alert = new Alert(Alert.AlertType.ERROR, message, I18n.ok());
    alert.setHeaderText(null);
    alert.showAndWait();
  }

  /** An error with {@code e}'s stack trace folded under "Show details". */
  private void alert(String message, Throwable e) {
    Alert alert = ErrorDetails.attach(new Alert(Alert.AlertType.ERROR, message, I18n.ok()), e);
    alert.setHeaderText(null);
    alert.showAndWait();
  }

  /**
   * The library jars the IDE ships: in an installed IDE the launcher sets
   * {@code scratch4j.libraryDir} to the app's {@code library} folder (offline);
   * in development they are downloaded once into the user cache.
   */
  /** Where the IDE may download library jars: always writable, per user. */
  static Path userLibraryCache() {
    return Path.of(System.getProperty("user.home"), ".cache", "scratch4j-ide");
  }

  /**
   * The folder holding (or receiving) the flavour's jar: the shipped library
   * folder when the jar is there, else the user cache (a download must not
   * go into the installed app's folder).
   */
  static Path libraryDirFor(LibraryFlavour flavour) {
    Path bundled = bundledLibraryDir();
    if (flavour != LibraryFlavour.NRW || Files.isRegularFile(bundled.resolve(
        "scratch-" + LibraryJarSource.SCRATCH_VERSION + "-nrw-all.jar"))) {
      return bundled;
    }
    return userLibraryCache();
  }

  static Path bundledLibraryDir() {
    String shipped = System.getProperty("scratch4j.libraryDir");
    if (shipped != null && !shipped.isBlank()
        && Files.isRegularFile(Path.of(shipped, "scratch-" + LibraryJarSource.SCRATCH_VERSION
            + "-all.jar"))) {
      return Path.of(shipped);
    }
    return userLibraryCache();
  }
}
