package org.openpatch.scratch4j.core.project;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/** Workspace JSON adapter; the destination is published only after a complete import. */
public final class PortableProject {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final long MAX_FILE = 16L * 1024 * 1024;
  private static final long MAX_TOTAL = 64L * 1024 * 1024;
  private PortableProject() {}

  public static String path(String name) throws IOException {
    if (name == null || name.isEmpty() || name.startsWith("/") || name.matches(".*[\\\\:\\x00-\\x1f].*")) {
      throw new IOException("Invalid project path: " + name);
    }
    for (String part : name.split("/", -1)) {
      if (part.isEmpty() || part.equals(".") || part.equals("..") || part.endsWith(".") || part.endsWith(" ")
          || part.matches("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?")) {
        throw new IOException("Invalid project path: " + name);
      }
    }
    return name;
  }

  public static Path importWorkspace(Path file, Path parent) throws IOException {
    if (Files.size(file) > 96L * 1024 * 1024) throw new IOException("Workspace JSON exceeds 96 MiB");
    try {
      JsonNode workspace = JSON.readTree(file.toFile());
      if (workspace.isArray()) {
        if (workspace.size() != 1) throw new IOException("Choose a JSON file containing one workspace");
        workspace = workspace.get(0);
      }
      JsonNode modules = workspace.get("modules");
      JsonNode settings = workspace.get("settings");
      if (modules == null || !modules.isArray() || modules.isEmpty() || modules.size() > 2000
          || settings == null || !"Java".equals(settings.path("language").asText())) {
        throw new IOException("Expected a Java workspace with 1–2,000 files");
      }
      if (!workspace.path("spritesheetBase64").asText("").isBlank()) {
        throw new IOException("Extract the legacy spritesheet into image files before importing. The original JSON is unchanged.");
      }
      ProjectSettings metadata = settings.has("scratchProject")
          ? JSON.treeToValue(settings.get("scratchProject"), ProjectSettings.class) : new ProjectSettings();
      boolean scratch = false;
      for (JsonNode library : settings.path("libraries")) {
        String id = library.asText();
        if (id.equals("scratch")) scratch = true;
        else if (id.equals("nrw")) metadata.flavour = "nrw";
        else throw new IOException("No desktop adapter for browser library: " + id);
      }
      if (!scratch) throw new IOException("Choose a workspace using the Scratch library");
      if (metadata.version != 1 || metadata.portableVersion != 1) throw new IOException("Unsupported project metadata version");
      if (!java.util.List.of("standard", "nrw").contains(metadata.flavour)) throw new IOException("Unsupported library flavour: " + metadata.flavour);
      Map<Long, JsonNode> ids = new HashMap<>();
      for (JsonNode module : modules) {
        if (module.has("id") && ids.put(module.get("id").asLong(), module) != null) throw new IOException("Duplicate workspace file IDs");
      }
      Map<String, byte[]> contents = new LinkedHashMap<>();
      HashSet<String> names = new HashSet<>();
      long total = 0;
      for (JsonNode module : modules) {
        if (!module.path("name").isString() || !module.path("text").isString()) throw new IOException("Invalid workspace file");
        String name = module.get("name").asText();
        HashSet<JsonNode> seen = new HashSet<>();
        seen.add(module);
        JsonNode cursor = module;
        while (cursor.hasNonNull("parent_folder_id")) {
          cursor = ids.get(cursor.get("parent_folder_id").asLong());
          if (cursor == null || !cursor.path("isFolder").asBoolean() || !seen.add(cursor)) throw new IOException("Invalid folder for " + name);
          name = cursor.path("name").asText() + "/" + name;
        }
        path(name);
        if (!names.add(name.toLowerCase(java.util.Locale.ROOT))) throw new IOException("Conflicting project filename: " + name);
        if (module.path("isFolder").asBoolean()) { contents.put(name + "/", new byte[0]); continue; }
        String text = module.get("text").asText();
        byte[] bytes;
        if (text.startsWith("data:")) {
          int comma = text.indexOf(',');
          if (comma < 0 || !text.substring(0, comma).endsWith(";base64")) throw new IOException("Expected a base64 asset: " + name);
          bytes = java.util.Base64.getDecoder().decode(text.substring(comma + 1));
        } else {
          if (name.endsWith(".java")) {
            if (metadata.desktopFiles == null || !metadata.desktopFiles.contains(name)) text = implicitImports(text, metadata.flavour);
            if (metadata.startStage.isBlank() && text.matches("(?s).*\\bvoid\\s+main\\s*\\(.*")) {
              metadata.startStage = Path.of(name).getFileName().toString().replaceFirst("\\.java$", "");
              metadata.startFile = name;
            }
          }
          bytes = text.getBytes(StandardCharsets.UTF_8);
        }
        total += bytes.length;
        if (bytes.length > MAX_FILE || total > MAX_TOTAL) throw new IOException("Workspace asset limit exceeded: " + name);
        contents.put(name, bytes);
      }
      if (contents.keySet().stream().noneMatch(name -> name.endsWith(".java"))) throw new IOException("Workspace contains no Java source");
      String base = file.getFileName().toString().replaceFirst("(?i)\\.json$", "");
      Path root = parent.toAbsolutePath().resolve(path(base));
      if (Files.exists(root)) throw new IOException("Folder already exists: " + root);
      Files.createDirectories(parent);
      Path staging = Files.createTempDirectory(parent, ".scratch4j-import-");
      try {
        for (Map.Entry<String, byte[]> entry : contents.entrySet()) {
          Path target = staging.resolve(entry.getKey());
          if (entry.getKey().endsWith("/")) Files.createDirectories(target);
          else { Files.createDirectories(target.getParent()); Files.write(target, entry.getValue()); }
        }
        metadata.sourceEnvironment = "studio";
        metadata.save(staging);
        Files.move(staging, root);
      } finally { if (Files.exists(staging)) deleteTree(staging); }
      return root;
    } catch (RuntimeException e) { throw new IOException("Invalid workspace JSON or asset: " + e.getMessage(), e); }
  }

  /** Add only missing implicit imports, preserving explicit student imports and package lines. */
  public static String implicitImports(String source, String flavour) {
    for (String line : CompactSource.IMPORTS.lines().toList()) {
      String type = line.replace("import ", "").replace(";", "").strip();
      if (!type.isEmpty()) source = JavaImports.ensure(source, type);
    }
    if (!"nrw".equals(flavour)) source = JavaImports.ensure(source, "java.util.*");
    source = JavaImports.ensure(source, "java.util.function.*");
    return source;
  }

  public static void deleteTree(Path root) throws IOException {
    try (var files = Files.walk(root)) {
      for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
    }
  }
}
