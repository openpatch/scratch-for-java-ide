package org.openpatch.scratch4j.runner;

import org.openpatch.scratch4j.core.project.ProjectSettings;

/**
 * The launcher the IDE generates into the project's build folder and compiles
 * together with the student's classes. It calls the start class's own static
 * {@code main} when it has one, otherwise instantiates it (the first
 * {@code Stage} creates the library's singleton {@code Window}), and
 * implements the smoke-test switch {@code -Dscratch4j.ide.exitAfter=N}, which
 * closes the window after N seconds so CI can run programs headlessly. With
 * the control channel enabled, that countdown starts after the first frame.
 *
 * <p>The class name is fixed so re-running does not litter the build folder;
 * it lives in the default package like the student's classes.
 */
public final class LauncherSource {

  private LauncherSource() {}

  public static String generate() {
    return generate(new ProjectSettings());
  }

  public static String generate(ProjectSettings settings) {
    StringBuilder beforeStage = new StringBuilder();
    if (settings.fullScreen) {
      beforeStage.append("    Window.useFullScreen();\n");
    }
    if (settings.pixelArt) {
      beforeStage.append("    Window.useTextureSampling(org.openpatch.scratch.TextureSampling.POINT);\n");
    }
    if (settings.splashLogo != null && !settings.splashLogo.isBlank()) {
      beforeStage.append("    Window.useSplashLogo(\"")
          .append(javaString(settings.splashLogo)).append("\");\n");
    }
    String afterStage = settings.debugOnStart
        ? "    Window.getInstance().setDebug(true);\n" : "";
    return """
        import org.openpatch.scratch.Window;

        public class Scratch4JLauncher {

          public static void main(String[] args) throws Exception {
            long exitAfter = Long.getLong("scratch4j.ide.exitAfter", -1L);
            if (exitAfter > 0) {
              Thread hook = new Thread(() -> {
                try {
                  // Native graphics startup can take longer than the smoke duration.
                  // Give controlled runs their full duration after rendering starts.
                  if (Boolean.getBoolean("scratch4j.ide.control")) {
                    while (IdeControl.frames < 1) Thread.sleep(25);
                  }
                  Thread.sleep(exitAfter * 1000L);
                  Window.getInstance().exit();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                } catch (Throwable ignored) {
                  // the window may not exist (yet) - nothing to exit then
                }
              });
              hook.setDaemon(true);
              hook.start();
            }
            if (Boolean.getBoolean("scratch4j.ide.control")) {
              IdeControl.start();
            }
        %s    Class<?> start = Class.forName(startClassName(args));
            java.lang.reflect.Method main = null;
            try {
              main = start.getDeclaredMethod("main", String[].class);
            } catch (NoSuchMethodException e) {
              try {
                main = start.getDeclaredMethod("main");
              } catch (NoSuchMethodException noMain) {
                // no main: instantiate the stage or window
              }
            }
            if (main != null) {
              // the class's own main may prepare things first (fonts, settings)
              main.setAccessible(true);
              Object receiver = null;
              if (!java.lang.reflect.Modifier.isStatic(main.getModifiers())) {
                java.lang.reflect.Constructor<?> constructor = start.getDeclaredConstructor();
                constructor.setAccessible(true);
                receiver = constructor.newInstance();
              }
              if (main.getParameterCount() == 0) main.invoke(receiver);
              else main.invoke(receiver, (Object) new String[0]);
            } else {
              java.lang.reflect.Constructor<?> constructor = start.getDeclaredConstructor();
              constructor.setAccessible(true);
              constructor.newInstance();
            }
        %s
          }

          private static String startClassName(String[] args) throws Exception {
            if (args.length > 0) {
              return args[0];
            }
            java.net.URL classUrl = Scratch4JLauncher.class.getResource(
                "/Scratch4JLauncher.class");
            if (classUrl != null && "jar".equals(classUrl.getProtocol())) {
              java.net.JarURLConnection connection =
                  (java.net.JarURLConnection) classUrl.openConnection();
              String start = connection.getManifest().getMainAttributes()
                  .getValue("Start-Class");
              if (start != null && !start.isBlank()) {
                return start;
              }
            }
            throw new IllegalArgumentException(
                "No start class supplied and the JAR has no Start-Class manifest entry");
          }

          /**
           * The IDE's line to a running program (only with -Dscratch4j.ide.control=true):
           * commands arrive on stdin, a heartbeat with the frame count goes to stderr
           * once a second (the IDE hides these marked lines and spots a frozen program).
           * It also pauses the game loop, steps single frames, changes the speed, and
           * reports the sprites' variables a few times a second ("monitor {json}").
           */
          public static final class IdeControl {
            static final String MARK = "@@scratch4j-ide@@ ";
            static final java.util.concurrent.ConcurrentLinkedQueue<Runnable> TASKS =
                new java.util.concurrent.ConcurrentLinkedQueue<>();
            static volatile long frames = -1;
            static volatile boolean paused;
            static volatile boolean monitoring = true;
            static org.openpatch.scratch.extensions.recorder.GifRecorder gif;
            /** Monitors pinned onto the stage: {owner id, field or @property, label}. */
            static final java.util.List<String[]> PINS =
                new java.util.concurrent.CopyOnWriteArrayList<>();
            /** The objects the last report showed, by id: pins look them up. */
            static final java.util.Map<String, java.lang.ref.WeakReference<Object>> SEEN =
                new java.util.concurrent.ConcurrentHashMap<>();

            /** Counts frames and runs IDE tasks on the render thread (after each frame). */
            public static final class Hook extends org.openpatch.scratch.extensions.recorder.Recorder {
              Hook() {
                super("scratch4j-ide", ".hook");
                this.start();
                // draw() runs inside each frame, after the program's own drawing
                call(applet(), "registerMethod", new Class<?>[] {String.class, Object.class},
                    "draw", this);
              }

              @Override
              public void saveFrame() {
                frames++;
                // a task queued by a task runs with the next frame
                for (int n = TASKS.size(); n > 0; n--) {
                  Runnable task = TASKS.poll();
                  if (task == null) break;
                  try {
                    task.run();
                  } catch (Throwable t) {
                    System.err.println(MARK + "error " + t);
                  }
                }
                if (Library.monitors()) {
                  Library.syncPins();
                }
                if (monitoring && !paused && frames %% 15 == 0) {
                  report();
                }
              }

              public void draw() {
                if (!PINS.isEmpty() && !Library.monitors()) {
                  Monitors.draw();
                }
              }
            }

            static void start() {
              Thread beat = new Thread(() -> {
                boolean hooked = false;
                while (true) {
                  if (!hooked && Window.getInstance() != null && applet() != null) {
                    TASKS.clear();
                    frames = 0;
                    new Hook();
                    hooked = true;
                  }
                  // "loading" while the library's loading screen shows (slow PCs stop
                  // drawing for a while when the stage takes over)
                  System.err.println(MARK + "frames " + frames + (loading() ? " loading" : ""));
                  try {
                    Thread.sleep(1000);
                  } catch (InterruptedException e) {
                    return;
                  }
                }
              }, "scratch4j-ide-heartbeat");
              beat.setDaemon(true);
              beat.start();
              Thread commands = new Thread(() -> {
                try (java.io.BufferedReader in = new java.io.BufferedReader(
                    new java.io.InputStreamReader(System.in,
                        java.nio.charset.StandardCharsets.UTF_8))) {
                  String line;
                  while ((line = in.readLine()) != null) {
                    handle(line.trim());
                  }
                } catch (java.io.IOException ignored) {
                  // the IDE closed the pipe
                }
              }, "scratch4j-ide-commands");
              commands.setDaemon(true);
              commands.start();
            }

            static void handle(String line) {
              if (line.equals("debug on") || line.equals("debug off")) {
                boolean on = line.endsWith("on");
                TASKS.add(() -> Window.getInstance().setDebug(on));
              } else if (line.startsWith("screenshot ")) {
                String path = line.substring("screenshot ".length());
                TASKS.add(() -> {
                  try {
                    Object applet = applet();
                    applet.getClass().getMethod("save", String.class).invoke(applet, path);
                    System.err.println(MARK + "saved " + path);
                  } catch (ReflectiveOperationException e) {
                    System.err.println(MARK + "error " + e);
                  }
                });
              } else if (line.startsWith("gif start ")) {
                String path = line.substring("gif start ".length());
                if (gif == null) {
                  gif = new org.openpatch.scratch.extensions.recorder.GifRecorder(path);
                  TASKS.add(() -> gif.start());
                }
              } else if (line.equals("pause")) {
                // after the frame being drawn: the paused picture is a whole frame
                TASKS.add(() -> {
                  if (Library.gameClock()) {
                    Library.window("pause");
                  } else {
                    call(applet(), "noLoop", new Class<?>[0]);
                  }
                  paused = true;
                  System.err.println(MARK + "paused " + gameFrame());
                  report();
                });
              } else if (line.equals("resume")) {
                if (paused) {
                  paused = false;
                  if (Library.gameClock()) {
                    Library.window("resume");
                  } else {
                    freshDelta();
                    call(applet(), "loop", new Class<?>[0]);
                  }
                  System.err.println(MARK + "resumed");
                }
              } else if (line.equals("step")) {
                if (paused && Library.gameClock()) {
                  // the library steps with the next frame: report the one after it
                  TASKS.add(() -> {
                    Library.window("step");
                    TASKS.add(() -> {
                      System.err.println(MARK + "paused " + gameFrame());
                      report();
                    });
                  });
                } else if (paused) {
                  TASKS.add(() -> {
                    System.err.println(MARK + "paused " + gameFrame());
                    report();
                  });
                  freshDelta();
                  call(applet(), "redraw", new Class<?>[0]);
                }
              } else if (line.startsWith("speed ")) {
                try {
                  float fps = Float.parseFloat(line.substring("speed ".length()));
                  if (Library.gameClock()) {
                    // the whole game in slow motion: run(), timers, gliding, animations
                    double factor = fps / 60.0;
                    TASKS.add(() -> Library.window("setGameSpeed", factor));
                  } else {
                    // OpenGL changes its frame rate on the render thread only (while
                    // paused, with the next step or on resume)
                    TASKS.add(() -> call(applet(), "frameRate", new Class<?>[] {float.class}, fps));
                  }
                } catch (NumberFormatException e) {
                  System.err.println(MARK + "error " + e);
                }
              } else if (line.equals("monitor on") || line.equals("monitor off")) {
                monitoring = line.endsWith("on");
              } else if (line.startsWith("pin ")) {
                String[] pin = line.substring("pin ".length()).split(" ", 3);
                if (pin.length == 3) {
                  PINS.add(pin);
                }
              } else if (line.startsWith("unpin ")) {
                String[] pin = line.substring("unpin ".length()).split(" ", 2);
                if (pin.length == 2) {
                  PINS.removeIf(p -> p[0].equals(pin[0]) && p[1].equals(pin[1]));
                }
              } else if (line.equals("gif stop")) {
                org.openpatch.scratch.extensions.recorder.GifRecorder recording = gif;
                gif = null;
                if (recording != null) {
                  TASKS.add(() -> {
                    recording.stop();
                    System.err.println(MARK + "saved " + recording_path(recording));
                  });
                }
              }
            }

            /**
             * Frames the game ran: the library's game steps when it has a game clock
             * (its window keeps drawing while paused), else the frames drawn.
             */
            static long gameFrame() {
              Object steps = Library.clockSteps();
              return steps instanceof Long ? (Long) steps : frames;
            }

            /** Sends the variables of the stage and its sprites to the IDE. */
            static void report() {
              try {
                System.err.println(MARK + "monitor " + Monitors.json());
              } catch (RuntimeException e) {
                System.err.println(MARK + "error monitor " + e);
              }
            }

            /**
             * The library measures the time between frames: after a pause, the next
             * frame would see the whole pause and jump. Make it a normal frame.
             */
            static void freshDelta() {
              try {
                Object applet = applet();
                java.lang.reflect.Field last = applet.getClass().getDeclaredField("lastMillis");
                last.setAccessible(true);
                int now = (Integer) applet.getClass().getMethod("millis").invoke(applet);
                last.setInt(applet, now - 16);
              } catch (ReflectiveOperationException | RuntimeException ignored) {
                // another library version: the first frame after a pause is longer
              }
            }

            static Object call(Object target, String name, Class<?>[] types, Object... args) {
              try {
                return target.getClass().getMethod(name, types).invoke(target, args);
              } catch (java.lang.reflect.InvocationTargetException e) {
                System.err.println(MARK + "error " + name + ": " + e.getCause());
                return null;
              } catch (ReflectiveOperationException | RuntimeException e) {
                System.err.println(MARK + "error " + name + ": " + e);
                return null;
              }
            }

            /** The library's Processing sketch (reflective: no Processing types needed to compile). */
            private static java.lang.reflect.Field state;

            /** The library still shows its loading screen (before the stage runs). */
            static boolean loading() {
              Object applet = applet();
              if (applet == null) return false;
              try {
                if (state == null) {
                  java.lang.reflect.Field field = applet.getClass().getDeclaredField("state");
                  field.setAccessible(true);
                  state = field;
                }
                return String.valueOf(state.get(applet)).equals("LOADING");
              } catch (ReflectiveOperationException | RuntimeException e) {
                return false; // an older library: no loading screen to wait for
              }
            }

            static Object applet() {
              try {
                return Class.forName("org.openpatch.scratch.internal.Applet")
                    .getMethod("getInstance").invoke(null);
              } catch (ReflectiveOperationException e) {
                return null;
              }
            }

            static String recording_path(Object recorder) {
              try {
                java.lang.reflect.Field path =
                    org.openpatch.scratch.extensions.recorder.Recorder.class
                        .getDeclaredField("path");
                path.setAccessible(true);
                return String.valueOf(path.get(recorder));
              } catch (ReflectiveOperationException e) {
                return "";
              }
            }
          }

          /**
           * What the project's library can do itself (newer versions): pause, step and
           * game speed on its own game clock, and Scratch-style variable monitors. Older
           * versions get the IDE's own way (Processing's loop and monitors drawn here).
           */
          public static final class Library {
            static Boolean gameClock;
            static Boolean monitors;
            static Object pinStage;
            /** Labels of the pins shown on pinStage. */
            static final java.util.Set<String> shown = new java.util.HashSet<>();

            static boolean gameClock() {
              if (gameClock == null) {
                gameClock = has(Window.class, "pause") && has(Window.class, "setGameSpeed", double.class);
              }
              return gameClock;
            }

            static boolean monitors() {
              if (monitors == null) {
                monitors = has(org.openpatch.scratch.Stage.class, "showVariable",
                    java.util.function.Supplier.class);
              }
              return monitors;
            }

            static boolean has(Class<?> type, String name, Class<?>... parameters) {
              try {
                type.getMethod(name, parameters);
                return true;
              } catch (NoSuchMethodException e) {
                return false;
              }
            }

            static void window(String name, Object... args) {
              Class<?>[] types = new Class<?>[args.length];
              for (int i = 0; i < args.length; i++) {
                types[i] = args[i] instanceof Double ? double.class : args[i].getClass();
              }
              IdeControl.call(Window.getInstance(), name, types, args);
            }

            /** The library's game steps, or null without a game clock. */
            static Object clockSteps() {
              if (!gameClock()) return null;
              try {
                Object clock = IdeControl.applet().getClass().getMethod("getClock")
                    .invoke(IdeControl.applet());
                return clock.getClass().getMethod("steps").invoke(clock);
              } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
              }
            }

            /** Shows the pinned values with the library's own monitors, on the current stage. */
            static void syncPins() {
              Window window = Window.getInstance();
              Object stage = window == null ? null : window.getStage();
              if (stage == null) return;
              if (stage != pinStage) {
                pinStage = stage;
                shown.clear();
              }
              java.util.Set<String> wanted = new java.util.HashSet<>();
              for (String[] pin : IdeControl.PINS) {
                wanted.add(pin[2]);
                if (shown.add(pin[2])) {
                  String owner = pin[0];
                  String field = pin[1];
                  java.util.function.Supplier<Object> value = () -> Monitors.pinned(owner, field);
                  IdeControl.call(stage, "showVariable",
                      new Class<?>[] {String.class, java.util.function.Supplier.class}, pin[2], value);
                }
              }
              for (java.util.Iterator<String> it = shown.iterator(); it.hasNext(); ) {
                String label = it.next();
                if (!wanted.contains(label)) {
                  IdeControl.call(stage, "hideVariable", new Class<?>[] {String.class}, label);
                  it.remove();
                }
              }
            }
          }

          /**
           * The variables report (JSON without a library: the program's classpath has
           * none) and the monitors pinned onto the stage, drawn like Scratch's.
           */
          public static final class Monitors {
            static final char Q = 34;
            static final char BACKSLASH = 92;
            static final int MAX_SPRITES = 100;
            static boolean drawFailed;

            static String json() {
              Window window = Window.getInstance();
              org.openpatch.scratch.Stage stage = window == null ? null : window.getStage();
              StringBuilder sb = new StringBuilder("{");
              sb.append(str("frame")).append(':').append(IdeControl.gameFrame()).append(',')
                  .append(str("paused")).append(':').append(IdeControl.paused);
              java.util.Set<Class<?>> classes = new java.util.LinkedHashSet<>();
              if (stage != null) {
                sb.append(',').append(str("stage")).append(':');
                object(sb, stage, "stage", classes);
                java.util.List<?> all = sprites(stage);
                sb.append(',').append(str("total")).append(':').append(all.size());
                sb.append(',').append(str("sprites")).append(":[");
                for (int i = 0; i < Math.min(all.size(), MAX_SPRITES); i++) {
                  if (i > 0) sb.append(',');
                  object(sb, all.get(i), id(all.get(i)), classes);
                }
                sb.append(']');
              }
              sb.append(',').append(str("statics")).append(":[");
              boolean first = true;
              for (Class<?> type : classes) {
                StringBuilder fields = new StringBuilder();
                for (java.lang.reflect.Field f : type.getDeclaredFields()) {
                  int m = f.getModifiers();
                  if (!java.lang.reflect.Modifier.isStatic(m)
                      || java.lang.reflect.Modifier.isFinal(m) || f.isSynthetic()) continue;
                  pair(fields, f.getName(), value(read(f, null), 0));
                }
                if (fields.length() == 0) continue;
                if (!first) sb.append(',');
                first = false;
                sb.append('{').append(str("id")).append(':').append(str("static:" + type.getName()))
                    .append(',').append(str("class")).append(':').append(str(type.getSimpleName()))
                    .append(',').append(str("props")).append(":[],")
                    .append(str("fields")).append(":[").append(fields).append("]}");
              }
              return sb.append("]}").toString();
            }

            /** Copies the standard collection or the NRW API's newly created Abitur List. */
            static java.util.List<?> sprites(org.openpatch.scratch.Stage stage) {
              // The NRW return type is inferred by the caller. Keep it as Object
              // so javac does not insert a cast to java.util.Collection.
              Object list = stage.getAll();
              if (list instanceof java.util.Collection<?>) {
                return new java.util.ArrayList<>((java.util.Collection<?>) list);
              }
              java.util.List<Object> all = new java.util.ArrayList<>();
              try {
                Class<?> type = list.getClass();
                java.lang.reflect.Method first = type.getMethod("toFirst");
                java.lang.reflect.Method access = type.getMethod("hasAccess");
                java.lang.reflect.Method content = type.getMethod("getContent");
                java.lang.reflect.Method next = type.getMethod("next");
                first.invoke(list);
                while (Boolean.TRUE.equals(access.invoke(list))) {
                  all.add(content.invoke(list));
                  next.invoke(list);
                }
              } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Could not read the stage's sprite list", e);
              }
              return all;
            }

            static String id(Object o) {
              return Integer.toHexString(System.identityHashCode(o));
            }

            static void object(StringBuilder sb, Object o, String id, java.util.Set<Class<?>> classes) {
              IdeControl.SEEN.put(id, new java.lang.ref.WeakReference<>(o));
              sb.append('{').append(str("id")).append(':').append(str(id)).append(',')
                  .append(str("class")).append(':').append(str(o.getClass().getSimpleName()))
                  .append(',').append(str("props")).append(":[");
              StringBuilder props = new StringBuilder();
              if (o instanceof org.openpatch.scratch.Sprite) {
                for (String p : new String[] {"@x", "@y", "@direction", "@size", "@costume",
                    "@visible"}) {
                  pair(props, p, prop(o, p));
                }
              }
              sb.append(props).append("],").append(str("fields")).append(":[");
              StringBuilder fields = new StringBuilder();
              for (Class<?> c = o.getClass(); c != null && own(c); c = c.getSuperclass()) {
                classes.add(c);
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                  if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) {
                    continue;
                  }
                  pair(fields, f.getName(), value(read(f, o), 0));
                }
              }
              sb.append(fields).append("]}");
            }

            /** A class of the student's program (not the library's, the JDK's or ours). */
            static boolean own(Class<?> c) {
              String n = c.getName();
              return !(n.startsWith("org.openpatch.scratch.") || n.startsWith("java.")
                  || n.startsWith("javax.") || n.startsWith("processing.")
                  || n.startsWith("Scratch4JLauncher"));
            }

            static void pair(StringBuilder sb, String name, String value) {
              if (sb.length() > 0) sb.append(',');
              sb.append('[').append(str(name)).append(',').append(str(value)).append(']');
            }

            static String prop(Object o, String name) {
              org.openpatch.scratch.Sprite s = (org.openpatch.scratch.Sprite) o;
              switch (name) {
                case "@x": return number(s.getX());
                case "@y": return number(s.getY());
                case "@direction": return number(s.getDirection());
                case "@size": return number(s.getSize());
                case "@costume": return String.valueOf(s.getCurrentCostumeName());
                case "@visible": return String.valueOf(s.isVisible());
                default: return "?";
              }
            }

            static Object read(java.lang.reflect.Field f, Object o) {
              try {
                f.setAccessible(true);
                return f.get(o);
              } catch (ReflectiveOperationException | RuntimeException e) {
                return "?";
              }
            }

            /** A short, readable value: other sprites as references the IDE names. */
            static String value(Object v, int depth) {
              if (v == null) return "null";
              if (v instanceof String) {
                String s = (String) v;
                return Q + (s.length() > 40 ? s.substring(0, 40) + "..." : s) + Q;
              }
              if (v instanceof Double || v instanceof Float) {
                return number(((Number) v).doubleValue());
              }
              if (v instanceof Number || v instanceof Boolean) return String.valueOf(v);
              if (v instanceof Character) return "'" + v + "'";
              if (v instanceof Enum) return ((Enum<?>) v).name();
              if (v instanceof org.openpatch.scratch.Sprite
                  || v instanceof org.openpatch.scratch.Stage) {
                return "@ref:" + id(v) + ":" + v.getClass().getSimpleName();
              }
              if (depth > 0) return v.getClass().getSimpleName();
              java.util.List<Object> items = new java.util.ArrayList<>();
              int size;
              String kind;
              if (v.getClass().isArray()) {
                size = java.lang.reflect.Array.getLength(v);
                for (int i = 0; i < Math.min(size, 5); i++) {
                  items.add(java.lang.reflect.Array.get(v, i));
                }
                kind = v.getClass().getComponentType().getSimpleName() + "[" + size + "]";
              } else if (v instanceof java.util.Collection) {
                java.util.Collection<?> c = (java.util.Collection<?>) v;
                size = c.size();
                for (Object item : c) {
                  if (items.size() == 5) break;
                  items.add(item);
                }
                kind = v.getClass().getSimpleName() + " (" + size + ")";
              } else if (v instanceof java.util.Map) {
                return v.getClass().getSimpleName() + " (" + ((java.util.Map<?, ?>) v).size() + ")";
              } else {
                return v.getClass().getSimpleName();
              }
              StringBuilder list = new StringBuilder(kind).append(" [");
              for (int i = 0; i < items.size(); i++) {
                if (i > 0) list.append(", ");
                list.append(value(items.get(i), depth + 1));
              }
              return list.append(size > items.size() ? ", ...]" : "]").toString();
            }

            static String number(double d) {
              if (d == Math.rint(d) && Math.abs(d) < 1e15) return String.valueOf((long) d);
              return String.format(java.util.Locale.ROOT, "%%.2f", d);
            }

            static String str(String s) {
              StringBuilder b = new StringBuilder().append(Q);
              for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == Q || c == BACKSLASH) {
                  b.append(BACKSLASH).append(c);
                } else {
                  b.append(c < 32 ? ' ' : c);
                }
              }
              return b.append(Q).toString();
            }

            /** The pinned monitors, top left, like Scratch's: "Cat: score [3]". */
            static void draw() {
              if (drawFailed) return;
              Object g = IdeControl.applet();
              try {
                float y = 8;
                for (String[] pin : IdeControl.PINS) {
                  box(g, 8, y, pin[2], pinned(pin[0], pin[1]));
                  y += 30;
                }
              } catch (ReflectiveOperationException | RuntimeException e) {
                drawFailed = true; // say it once, not every frame
                System.err.println(IdeControl.MARK + "error monitors " + e);
              }
            }

            static String pinned(String owner, String field) {
              try {
                if (owner.startsWith("static:")) {
                  Class<?> type = Class.forName(owner.substring("static:".length()));
                  return plain(value(read(type.getDeclaredField(field), null), 0));
                }
                java.lang.ref.WeakReference<Object> ref = IdeControl.SEEN.get(owner);
                Object o = ref == null ? null : ref.get();
                if (o == null) return "-";
                if (field.startsWith("@")) return prop(o, field);
                for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
                  try {
                    return plain(value(read(c.getDeclaredField(field), o), 0));
                  } catch (NoSuchFieldException e) {
                    // declared further up
                  }
                }
              } catch (ReflectiveOperationException | RuntimeException e) {
                // the class or field is gone (hot reload): nothing to show
              }
              return "-";
            }

            /** A value as the stage shows it: no quotes, references by class. */
            static String plain(String value) {
              if (value.length() >= 2 && value.charAt(0) == Q
                  && value.charAt(value.length() - 1) == Q) {
                return value.substring(1, value.length() - 1);
              }
              if (value.startsWith("@ref:")) return value.substring(value.lastIndexOf(':') + 1);
              return value;
            }

            static final Class<?>[] NONE = {};
            static final Class<?>[] F1 = {float.class};
            static final Class<?>[] F3 = {float.class, float.class, float.class};
            static final Class<?>[] F4 = {float.class, float.class, float.class, float.class};
            static final Class<?>[] F5 =
                {float.class, float.class, float.class, float.class, float.class};
            static final Class<?>[] I1 = {int.class};
            static final Class<?>[] I2 = {int.class, int.class};
            static final Class<?>[] S1 = {String.class};
            static final Class<?>[] SFF = {String.class, float.class, float.class};
            static final int LEFT = 37;
            static final int CENTER = 3;
            static final int CORNER = 0;

            static Object g(Object g, String name, Class<?>[] types, Object... args)
                throws ReflectiveOperationException {
              return g.getClass().getMethod(name, types).invoke(g, args);
            }

            static void box(Object g, float x, float y, String label, String value)
                throws ReflectiveOperationException {
              g(g, "pushStyle", NONE);
              g(g, "pushMatrix", NONE);
              g(g, "resetMatrix", NONE);
              // the library draws sprites centred; pushStyle/popStyle restore it
              g(g, "rectMode", I1, CORNER);
              g(g, "textSize", F1, 12f);
              float labelWidth = (Float) g(g, "textWidth", S1, label);
              float valueWidth = Math.max(30f, (Float) g(g, "textWidth", S1, value) + 14);
              g(g, "noStroke", NONE);
              g(g, "fill", F4, 230f, 240f, 255f, 235f);
              g(g, "rect", F5, x, y, labelWidth + valueWidth + 18, 24f, 4f);
              g(g, "fill", F3, 87f, 94f, 117f);
              g(g, "textAlign", I2, LEFT, CENTER);
              g(g, "text", SFF, label, x + 6, y + 11);
              g(g, "fill", F3, 255f, 140f, 26f);
              g(g, "rect", F5, x + labelWidth + 12, y + 3, valueWidth, 18f, 9f);
              g(g, "fill", F3, 255f, 255f, 255f);
              g(g, "textAlign", I2, CENTER, CENTER);
              g(g, "text", SFF, value, x + labelWidth + 12 + valueWidth / 2, y + 11);
              g(g, "popMatrix", NONE);
              g(g, "popStyle", NONE);
            }
          }
        }
        """.formatted(beforeStage, afterStage);
  }

  private static String javaString(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r");
  }
}
