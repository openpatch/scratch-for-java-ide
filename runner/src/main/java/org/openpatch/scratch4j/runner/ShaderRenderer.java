package org.openpatch.scratch4j.runner;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Renders an image through a shader the way the library draws it, without a
 * window: a small program ({@code ShaderPreview.java}) runs in source-file
 * mode with the library's all-jar (JOGL, Processing's default shaders) and
 * draws into an off-screen buffer. Results are cached by content, so the
 * same shader, image and uniforms render once.
 */
public final class ShaderRenderer {

  /** What to render; {@code vert} and {@code image} may be null (Processing's / a test image). */
  public record Request(Path frag, Path vert, Path image, int width, int height, int frames,
      double seconds, Map<String, double[]> uniforms) {}

  /** A compile error (or warning): {@code kind} is frag, vert or link; 1-based line (0: unknown). */
  public record Error(String kind, int line, String message, boolean warning) {
    public Error(String kind, int line, String message) {
      this(kind, line, message, false);
    }
  }

  /**
   * The frames (several when the shader uses {@code time}), or the errors; and
   * warnings, and the shader's uniforms with their GLSL type ({@code float},
   * {@code vec2}, ...; an array's type ends in {@code []}).
   */
  public record Result(List<Path> frames, List<Error> errors, List<String> uniforms,
      String failure, List<Error> warnings, Map<String, String> uniformTypes) {
    public Result(List<Path> frames, List<Error> errors, List<String> uniforms,
        String failure) {
      this(frames, errors, uniforms, failure, List.of(), Map.of());
    }

    public boolean ok() {
      return !frames.isEmpty();
    }
  }

  private final Path javaExecutable;
  private final List<Path> classpath;
  private final Path cacheDir;

  /** {@code classpath}: the library's all-jar (it brings JOGL). */
  public ShaderRenderer(Path java, List<Path> classpath, Path cacheDir) {
    this.javaExecutable = java;
    this.classpath = List.copyOf(classpath);
    this.cacheDir = cacheDir;
  }

  public Result render(Request request) throws IOException, InterruptedException {
    Path out = cacheDir.resolve(key(request));
    Path log = out.resolve("result.txt");
    if (!Files.isRegularFile(log)) {
      Files.createDirectories(out);
      List<String> answer = server().request(String.join("\t", arguments(request, out)));
      if (answer == null) {
        return new Result(List.of(), List.of(), List.of(), "timeout");
      }
      Path partial = out.resolve("result.part");
      Files.write(partial, answer, StandardCharsets.UTF_8);
      Files.move(partial, log, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
    return parse(Files.readAllLines(log, StandardCharsets.UTF_8));
  }

  /**
   * One frame at {@code time} seconds into {@code file} (not cached): a
   * running preview asks for the next one with the time that has passed, so
   * time runs on and does not loop. The helper keeps the compiled shader
   * while files, image and size stay the same.
   */
  public Result live(Request request, double time, Path file) throws IOException {
    List<String> args = new ArrayList<>(List.of("LIVE", String.valueOf(time),
        file.toString()));
    args.addAll(arguments(request, file.getParent()));
    List<String> answer = server().request(String.join("\t", args));
    if (answer == null) {
      return new Result(List.of(), List.of(), List.of(), "timeout");
    }
    return parse(answer);
  }

  private static List<String> arguments(Request request, Path out) {
    List<String> args = new ArrayList<>(List.of(request.frag().toString(),
        request.vert() == null ? "-" : request.vert().toString(),
        request.image() == null ? "-" : request.image().toString(), out.toString(),
        String.valueOf(request.width()), String.valueOf(request.height()),
        String.valueOf(request.frames()), String.valueOf(request.seconds())));
    for (var u : request.uniforms().entrySet()) {
      StringBuilder values = new StringBuilder();
      for (double v : u.getValue()) {
        values.append(values.isEmpty() ? "" : ",").append(v);
      }
      args.add(u.getKey() + "=" + values);
    }
    return args;
  }

  /** The helper process: one per JVM and classpath, kept running between previews. */
  private static final Map<String, Server> SERVERS = new java.util.concurrent.ConcurrentHashMap<>();

  private Server server() throws IOException {
    Path program = program();
    String key = javaExecutable + "|" + classpath + "|" + program;
    return SERVERS.computeIfAbsent(key, k -> new Server(List.of(javaExecutable.toString(),
        "--enable-native-access=ALL-UNNAMED", "-Djava.awt.headless=true", "-cp",
        String.join(System.getProperty("path.separator"),
            classpath.stream().map(Path::toString).toList()),
        program.toString(), "--serve")));
  }

  private static final class Server {
    private final List<String> command;
    private Process process;
    private java.io.BufferedReader output;
    private java.io.Writer input;

    Server(List<String> command) {
      this.command = command;
    }

    /** The answer's lines (null: the helper hung and was stopped). */
    synchronized List<String> request(String line) throws IOException {
      if (process == null || !process.isAlive()) {
        process = new ProcessBuilder(command).redirectErrorStream(true).start();
        output = new java.io.BufferedReader(new java.io.InputStreamReader(
            process.getInputStream(), StandardCharsets.UTF_8));
        input = new java.io.OutputStreamWriter(process.getOutputStream(),
            StandardCharsets.UTF_8);
      }
      Process running = process;
      // a shader that never finishes: stop the helper, the next request starts a new one
      var watchdog = java.util.concurrent.CompletableFuture.delayedExecutor(60, TimeUnit.SECONDS)
          ;
      var timer = java.util.concurrent.CompletableFuture.runAsync(running::destroyForcibly,
          watchdog);
      try {
        input.write(line + "\n");
        input.flush();
        List<String> lines = new ArrayList<>();
        String l;
        while ((l = output.readLine()) != null && !l.equals("END")) {
          lines.add(l);
        }
        if (l == null) {
          process = null;
          return running.isAlive() || timer.isDone() ? null : lines;
        }
        return lines;
      } catch (IOException e) {
        process = null;
        throw e;
      } finally {
        timer.cancel(false);
      }
    }
  }

  static Result parse(List<String> lines) {
    List<Path> frames = new ArrayList<>();
    List<Error> errors = new ArrayList<>();
    List<Error> warnings = new ArrayList<>();
    List<String> uniforms = new ArrayList<>();
    Map<String, String> types = new java.util.LinkedHashMap<>();
    String failure = null;
    boolean ok = false;
    for (String line : lines) {
      if (line.startsWith("FRAME ")) {
        frames.add(Path.of(line.substring(6)));
      } else if (line.startsWith("ERROR ") || line.startsWith("WARNING ")) {
        boolean warning = line.startsWith("WARNING ");
        String[] parts = line.split(" ", 4);
        int number = 0;
        try {
          number = Integer.parseInt(parts[2]);
        } catch (RuntimeException ignored) {
          // no line
        }
        (warning ? warnings : errors).add(new Error(parts[1], number,
            parts.length > 3 ? parts[3] : "", warning));
      } else if (line.startsWith("UNIFORMS")) {
        for (String u : line.substring(8).strip().split(" ")) {
          if (u.isBlank()) continue;
          String[] parts = u.split(":");
          uniforms.add(parts[0]);
          String type = parts.length > 1 ? parts[1] : "other";
          if (parts.length > 2 && !"1".equals(parts[2])) type += "[]";
          types.put(parts[0], type);
        }
      } else if (line.equals("OK")) {
        ok = true;
      } else if ((line.startsWith("FAILED ") || line.contains("Exception")) && failure == null) {
        failure = line.strip();
      }
    }
    if (!ok) {
      frames.clear();
      if (failure == null && errors.isEmpty()) failure = "no output";
    }
    return new Result(frames, errors, uniforms, failure, warnings, types);
  }

  private Path program() throws IOException {
    Path file = cacheDir.resolve("program").resolve("ShaderPreview.java");
    try (InputStream in = ShaderRenderer.class.getResourceAsStream("ShaderPreview.java.txt")) {
      byte[] source = in.readAllBytes();
      if (!Files.isRegularFile(file) || !java.util.Arrays.equals(Files.readAllBytes(file),
          source)) {
        Files.createDirectories(file.getParent());
        Files.write(file, source);
      }
    }
    return file;
  }

  private static String key(Request r) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (Path p : new Path[] {r.frag(), r.vert(), r.image()}) {
        digest.update((p == null ? "-" : p.toString()).getBytes(StandardCharsets.UTF_8));
        if (p != null && Files.isRegularFile(p)) digest.update(Files.readAllBytes(p));
      }
      digest.update((r.width() + "x" + r.height() + "/" + r.frames() + "/" + r.seconds())
          .getBytes(StandardCharsets.UTF_8));
      for (var u : new java.util.TreeMap<>(r.uniforms()).entrySet()) {
        digest.update((u.getKey() + "=" + java.util.Arrays.toString(u.getValue()))
            .getBytes(StandardCharsets.UTF_8));
      }
      try (InputStream in = ShaderRenderer.class.getResourceAsStream("ShaderPreview.java.txt")) {
        digest.update(in.readAllBytes());
      }
      return HexFormat.of().formatHex(digest.digest(), 0, 12);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
