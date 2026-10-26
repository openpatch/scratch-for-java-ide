package org.openpatch.scratch4j.runner;

import org.openpatch.scratch4j.core.project.ProjectSettings;

/**
 * The launcher the IDE generates into the project's build folder and compiles
 * together with the student's classes. It calls the start class's own static
 * {@code main} when it has one, otherwise instantiates it (a stage goes
 * into a new 480x360 {@code Window}), and
 * implements the smoke-test switch {@code -Dscratch4j.ide.exitAfter=N}, which
 * closes the window after N seconds so CI can run programs headlessly. With
 * the control channel enabled, that countdown starts after the first frame.
 *
 * <p>Scratch for Java 7: the IDE's control channel is an extension of the library
 * ({@code IdeControl}, registered with {@code Window.use} before the program
 * starts) on its public API only - no reflection into the library. The program
 * is started through {@code org.openpatch.scratch.Launcher} (see ProjectRunner),
 * which keeps the first thread for the window on macOS.
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
      beforeStage.append("    Window.usePixelArt();\n");
    }
    if (settings.splashLogo != null && !settings.splashLogo.isBlank()) {
      beforeStage.append("    Window.useSplashLogo(\"")
          .append(javaString(settings.splashLogo)).append("\");\n");
    }
    String afterStage = settings.debugOnStart
        ? "    Window.getInstance().setDebug(true);\n" : "";
    return """
        import org.openpatch.scratch.Window;
        import org.openpatch.scratch.extension.Extension;
        import org.openpatch.scratch.extension.ExtensionContext;

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
              Window.use(IdeControl.INSTANCE);
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
              if (org.openpatch.scratch.Stage.class.isAssignableFrom(start)) {
                // a stage without main: the window it is shown in comes first
                Window window = new Window(480, 360);
                window.setStage((org.openpatch.scratch.Stage) constructor.newInstance());
              } else {
                constructor.newInstance();
              }
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
           * The IDE's line to a running program (only with -Dscratch4j.ide.control=true), an
           * extension of the library: commands arrive on stdin, a heartbeat with the frame count
           * goes to stderr once a second (the IDE hides these marked lines and spots a frozen
           * program). It pauses the game, steps single ticks, changes the speed, saves frames and
           * GIFs, shows pinned values with the library's monitors, and reports the sprites'
           * variables a few times a second ("monitor {json}"). Learner code and the variables are
           * only touched on the game thread.
           */
          public static final class IdeControl implements Extension {
            static final String MARK = "@@scratch4j-ide@@ ";
            static final IdeControl INSTANCE = new IdeControl();
            static volatile long frames = -1;
            static volatile boolean paused;
            static volatile boolean monitoring = true;
            static volatile ExtensionContext ctx;
            /** A screenshot to save from the next frame, or null. */
            static volatile String screenshot;
            /** A pause waits for the first frame after it took effect, to report its tick. */
            static volatile boolean pauseReport;
            /** The tick count before a step, -1 when no step waits for its report. */
            static volatile long stepFrom = -1;
            static org.openpatch.scratch.recorder.GifRecorder gif;
            static String gifPath;
            /** Monitors pinned onto the stage: {owner id, field or @property, label}. */
            static final java.util.List<String[]> PINS =
                new java.util.concurrent.CopyOnWriteArrayList<>();
            static volatile boolean pinsChanged;
            /** The objects the last report showed, by id: pins look them up. */
            static final java.util.Map<String, java.lang.ref.WeakReference<Object>> SEEN =
                new java.util.concurrent.ConcurrentHashMap<>();

            @Override
            public String id() {
              return "org.openpatch.scratch4j.ide";
            }

            @Override
            public String name() {
              return "Scratch for Java Studio";
            }

            @Override
            public String version() {
              return "1";
            }

            @Override
            public void install(ExtensionContext context) {
              ctx = context;
              frames = 0;
              // after each frame, on the window thread: counting, screenshots, step reports
              context.afterFrame((stage, frame) -> {
                frames++;
                String shot = screenshot;
                if (shot != null) {
                  screenshot = null;
                  try {
                    frame.savePng(java.nio.file.Path.of(shot));
                    System.err.println(MARK + "saved " + shot);
                  } catch (java.io.IOException e) {
                    System.err.println(MARK + "error " + e);
                  }
                }
                if (pauseReport) {
                  // the batch that took the pause has finished: the tick count is final
                  pauseReport = false;
                  context.runOnGameThread(() -> {
                    System.err.println(MARK + "paused " + ticks());
                    report();
                  });
                }
                long from = stepFrom;
                if (from >= 0 && context.getTickCount() > from) {
                  stepFrom = -1;
                  context.runOnGameThread(() -> {
                    System.err.println(MARK + "paused " + ticks());
                    report();
                  });
                }
                if (pinsChanged || stage != Pins.stage) {
                  pinsChanged = false;
                  context.runOnGameThread(Pins::sync);
                }
                if (monitoring && !paused && frames %% 15 == 0) {
                  context.runOnGameThread(IdeControl::report);
                }
              });
            }

            /** Ticks the game ran (they stop while paused, a step adds one). */
            static long ticks() {
              ExtensionContext c = ctx;
              return c == null ? 0 : c.getTickCount();
            }

            static void start() {
              Thread beat = new Thread(() -> {
                while (true) {
                  ExtensionContext c = ctx;
                  // "loading" while the library's loading screen shows
                  System.err.println(MARK + "frames " + frames
                      + (c != null && c.isLoading() ? " loading" : ""));
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

            /** On the game thread, once the window and its game exist. */
            static void later(Runnable task) {
              while (ctx == null || Window.getInstance() == null) {
                try {
                  Thread.sleep(25);
                } catch (InterruptedException e) {
                  return;
                }
              }
              ctx.runOnGameThread(() -> {
                try {
                  task.run();
                } catch (Throwable t) {
                  System.err.println(MARK + "error " + t);
                }
              });
            }

            static void handle(String line) {
              if (line.equals("debug on") || line.equals("debug off")) {
                boolean on = line.endsWith("on");
                later(() -> Window.getInstance().setDebug(on));
              } else if (line.startsWith("screenshot ")) {
                screenshot = line.substring("screenshot ".length());
              } else if (line.startsWith("gif start ")) {
                String path = line.substring("gif start ".length());
                later(() -> {
                  if (gif == null) {
                    gif = new org.openpatch.scratch.recorder.GifRecorder(path);
                    gifPath = path;
                    gif.start();
                  }
                });
              } else if (line.equals("gif stop")) {
                later(() -> {
                  if (gif != null) {
                    gif.stop();
                    System.err.println(MARK + "saved " + gifPath);
                    gif = null;
                  }
                });
              } else if (line.equals("pause")) {
                // reported once the pause took effect (see afterFrame): the paused picture
                // and the reported tick belong together
                later(() -> {
                  Window.getInstance().pause();
                  paused = true;
                  pauseReport = true;
                });
              } else if (line.equals("resume")) {
                later(() -> {
                  if (paused) {
                    paused = false;
                    Window.getInstance().resume();
                    System.err.println(MARK + "resumed");
                  }
                });
              } else if (line.equals("step")) {
                later(() -> {
                  if (paused) {
                    // reported after the frame that shows the stepped tick
                    stepFrom = ticks();
                    Window.getInstance().step();
                  }
                });
              } else if (line.startsWith("speed ")) {
                try {
                  // frames per second in the IDE's speed control; the game runs 60 ticks a second
                  double factor = Float.parseFloat(line.substring("speed ".length())) / 60.0;
                  later(() -> Window.getInstance().setGameSpeed(factor));
                } catch (NumberFormatException e) {
                  System.err.println(MARK + "error " + e);
                }
              } else if (line.equals("monitor on") || line.equals("monitor off")) {
                monitoring = line.endsWith("on");
              } else if (line.startsWith("pin ")) {
                String[] pin = line.substring("pin ".length()).split(" ", 3);
                if (pin.length == 3) {
                  PINS.add(pin);
                  pinsChanged = true;
                }
              } else if (line.startsWith("unpin ")) {
                String[] pin = line.substring("unpin ".length()).split(" ", 2);
                if (pin.length == 2) {
                  PINS.removeIf(p -> p[0].equals(pin[0]) && p[1].equals(pin[1]));
                  pinsChanged = true;
                }
              }
            }

            /** Sends the variables of the stage and its sprites to the IDE (game thread). */
            static void report() {
              try {
                System.err.println(MARK + "monitor " + Monitors.json());
              } catch (RuntimeException e) {
                System.err.println(MARK + "error monitor " + e);
              }
            }
          }

          /** The pinned values, shown by the library's own monitors on the current stage. */
          public static final class Pins {
            static volatile Object stage;
            /** Labels of the pins shown on stage. */
            static final java.util.Set<String> shown = new java.util.HashSet<>();

            /** Game thread. */
            static void sync() {
              Window window = Window.getInstance();
              org.openpatch.scratch.Stage current = window == null ? null : window.getStage();
              if (current == null) return;
              if (current != stage) {
                stage = current;
                shown.clear();
              }
              java.util.Set<String> wanted = new java.util.HashSet<>();
              for (String[] pin : IdeControl.PINS) {
                wanted.add(pin[2]);
                if (shown.add(pin[2])) {
                  String owner = pin[0];
                  String field = pin[1];
                  current.showVariable(pin[2], () -> Monitors.pinned(owner, field));
                }
              }
              for (java.util.Iterator<String> it = shown.iterator(); it.hasNext(); ) {
                String label = it.next();
                if (!wanted.contains(label)) {
                  current.hideVariable(label);
                  it.remove();
                }
              }
            }
          }

          /**
           * The variables report (JSON without a library: the program's classpath has
           * none); pinned values are shown by the library's own monitors.
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
              sb.append(str("frame")).append(':').append(IdeControl.ticks()).append(',')
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
                  || n.startsWith("javax.") || n.startsWith("org.lwjgl.")
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
          }
        }
        """.formatted(beforeStage, afterStage);
  }

  private static String javaString(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r");
  }
}
