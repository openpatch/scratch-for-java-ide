package org.openpatch.scratch4j.runner;

import org.openpatch.scratch4j.core.project.ProjectSettings;

/**
 * The launcher the IDE generates into the project's build folder and compiles
 * together with the student's classes. It calls the start class's own static
 * {@code main} when it has one, otherwise instantiates it (the first
 * {@code Stage} creates the library's singleton {@code Window}), and
 * implements the smoke-test switch {@code -Dscratch4j.ide.exitAfter=N}, which
 * closes the window after N seconds so CI can run programs headlessly.
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
              // no main: instantiate the stage (or window) like BlueJ's new MyStage()
            }
            if (main != null && java.lang.reflect.Modifier.isStatic(main.getModifiers())) {
              // the class's own main may prepare things first (fonts, settings)
              main.setAccessible(true);
              main.invoke(null, (Object) new String[0]);
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
           */
          static final class IdeControl {
            static final String MARK = "@@scratch4j-ide@@ ";
            static final java.util.concurrent.ConcurrentLinkedQueue<Runnable> TASKS =
                new java.util.concurrent.ConcurrentLinkedQueue<>();
            static volatile long frames = -1;
            static org.openpatch.scratch.extensions.recorder.GifRecorder gif;

            /** Counts frames and runs IDE tasks on the render thread (after each frame). */
            static final class Hook extends org.openpatch.scratch.extensions.recorder.Recorder {
              Hook() {
                super("scratch4j-ide", ".hook");
                this.start();
              }

              @Override
              public void saveFrame() {
                frames++;
                Runnable task;
                while ((task = TASKS.poll()) != null) {
                  try {
                    task.run();
                  } catch (Throwable t) {
                    System.err.println(MARK + "error " + t);
                  }
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
                  System.err.println(MARK + "frames " + frames);
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

            /** The library's Processing sketch (reflective: no Processing types needed to compile). */
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
        }
        """.formatted(beforeStage, afterStage);
  }

  private static String javaString(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r");
  }
}
