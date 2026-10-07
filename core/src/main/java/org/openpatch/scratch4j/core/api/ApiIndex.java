package org.openpatch.scratch4j.core.api;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.zip.ZipFile;
import org.openpatch.scratch4j.core.project.ScratchProject;
import org.openpatch.scratch4j.core.project.LibraryCheck;

/**
 * The API index the IDE ships (offline-first): every public library method
 * with signature, summary, Scratch block and docs URL. Generated from the
 * library's release catalog. {@link ApiIndexGenerator} adapts older sources JARs.
 */
public final class ApiIndex {

  private static final String RESOURCE = "/api-index.json";

  private List<ApiMethod> methods;
  private String libraryVersion = "";

  private ApiIndex(List<ApiMethod> methods) {
    this.methods = List.copyOf(methods);
  }

  /** Loads the bundled index. */
  public static ApiIndex load() {
    try (InputStream in = ApiIndex.class.getResourceAsStream("/catalogs/api.json")) {
      if (in != null) return catalog(in);
    } catch (IOException | RuntimeException e) {
      throw new IllegalStateException("Cannot load bundled API catalog", e);
    }
    try (InputStream in = ApiIndex.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Missing bundled resource " + RESOURCE);
      }
      ApiMethod[] parsed = JsonMapper.builder().build().readValue(in, ApiMethod[].class);
      return new ApiIndex(List.of(parsed));
    } catch (IOException | RuntimeException e) {
      throw new IllegalStateException("Cannot load " + RESOURCE, e);
    }
  }

  private static ApiIndex catalog(InputStream in) throws IOException {
    var root = JsonMapper.builder().build().readTree(in);
    if (root.path("schemaVersion").asInt() != 1) throw new IOException("Unsupported API catalog");
    List<ApiMethod> methods = new ArrayList<>();
    for (var entry : root.path("methods")) {
      List<String> params = new ArrayList<>();
      entry.path("params").forEach(value -> params.add(value.asText()));
      List<String> paramDocs = new ArrayList<>();
      entry.path("paramDocs").forEach(value -> paramDocs.add(value.asText()));
      ApiMethod method = new ApiMethod(entry.path("className").asText(), entry.path("methodName").asText(),
          entry.path("returnType").asText(), params, entry.path("summary").asText(),
          entry.path("scratchblock").isNull() ? null : entry.path("scratchblock").asText(),
          "", entry.path("docsUrl").asText(), entry.path("description").asText(), paramDocs,
          entry.path("returns").isNull() || entry.path("returns").isMissingNode() ? null : entry.path("returns").asText());
      methods.add(new ApiMethod(method.className(), method.methodName(), method.returnType(), method.params(),
          method.summary(), method.scratchblock(), Categories.of(method), method.docsUrl(),
          method.description(), method.paramDocs(), method.returns()));
    }
    if (methods.isEmpty()) throw new IOException("Empty API catalog");
    ApiIndex result = new ApiIndex(methods);
    result.libraryVersion = root.path("libraryVersion").asText();
    return result;
  }

  /** Select local release metadata for this project, with a sources adapter for older releases. */
  public void useProject(ScratchProject project) throws IOException {
    var status = LibraryCheck.status(project, load().libraryVersion);
    ApiIndex selected = load();
    if (status.jar() != null) {
      try (ZipFile jar = new ZipFile(status.jar().toFile())) {
        var entry = jar.getEntry("catalogs/api.json");
        if (entry != null) {
          try (InputStream in = jar.getInputStream(entry)) { selected = catalog(in); }
        } else if (status.version() == null || !status.version().equals(selected.libraryVersion)) {
          Path sources = status.jar().resolveSibling(status.jar().getFileName().toString()
              .replaceFirst("(?:-all)?\\.jar$", "-sources.jar"));
          if (Files.isRegularFile(sources)) selected = new ApiIndex(ApiIndexGenerator.generate(sources));
          else selected = new ApiIndex(actualMembers(status.jar(), selected.methods()));
          selected.libraryVersion = status.version() == null ? "unknown" : status.version();
        }
      }
    }
    methods = selected.methods;
    libraryVersion = selected.libraryVersion;
  }

  /** No source metadata in a legacy jar: show only signatures that the chosen binary declares. */
  private static List<ApiMethod> actualMembers(Path jar, List<ApiMethod> descriptions) throws IOException {
    var owners = new java.util.HashMap<String, String>();
    try (ZipFile archive = new ZipFile(jar.toFile())) {
      archive.stream().map(java.util.zip.ZipEntry::getName)
          .filter(name -> name.startsWith("org/openpatch/scratch/") && name.endsWith(".class")
              && !name.contains("$") && !name.contains("/internal/"))
          .forEach(name -> owners.put(name.substring(name.lastIndexOf('/') + 1, name.length() - 6),
              name.substring(0, name.length() - 6).replace('/', '.')));
    }
    List<ApiMethod> result = new ArrayList<>();
    try (var loader = new java.net.URLClassLoader(new java.net.URL[] { jar.toUri().toURL() },
        ClassLoader.getPlatformClassLoader())) {
      for (ApiMethod method : descriptions) {
        String owner = owners.get(method.className());
        if (owner == null) continue;
        try {
          Class<?> type = Class.forName(owner, false, loader);
          java.lang.reflect.Executable[] members = "constructor".equals(method.methodName())
              ? type.getConstructors() : type.getDeclaredMethods();
          for (var member : members) {
            if (!java.lang.reflect.Modifier.isPublic(member.getModifiers())) continue;
            if (member instanceof java.lang.reflect.Method m && !m.getName().equals(method.methodName())) continue;
            List<String> params = java.util.Arrays.stream(member.getParameterTypes()).map(Class::getSimpleName).toList();
            List<String> wanted = method.params().stream().map(p -> p.replaceFirst("\\s+\\w+$", "")
                .replaceAll("<.*>", "").replaceAll("\\b(?:\\w+\\.)+(\\w+)", "$1")).toList();
            if (params.equals(wanted)) { result.add(method); break; }
          }
        } catch (ReflectiveOperationException | LinkageError ignored) {
          // Missing optional classes have no usable signature in this project.
        }
      }
    }
    return result;
  }

  public String libraryVersion() { return libraryVersion; }

  public List<ApiMethod> methods() {
    return methods;
  }

  /** Methods whose name matches (case-insensitive), best first. */
  public List<ApiMethod> byName(String name) {
    String lower = name.toLowerCase(java.util.Locale.ROOT);
    return methods.stream()
        .filter(m -> m.methodName().toLowerCase(java.util.Locale.ROOT).contains(lower))
        .sorted((a, b) -> {
          int aExact = a.methodName().equalsIgnoreCase(name) ? 0 : 1;
          int bExact = b.methodName().equalsIgnoreCase(name) ? 0 : 1;
          int c = Integer.compare(aExact, bExact);
          if (c != 0) {
            return c;
          }
          boolean aBlock = a.scratchblock() != null;
          boolean bBlock = b.scratchblock() != null;
          if (aBlock != bBlock) {
            return aBlock ? -1 : 1;
          }
          return Integer.compare(a.methodName().length(), b.methodName().length());
        })
        .toList();
  }

  /** All methods with a Scratch block, grouped by palette category. */
  public java.util.Map<String, List<ApiMethod>> blockPalette() {
    return methods.stream()
        .filter(m -> m.scratchblock() != null)
        .collect(java.util.stream.Collectors.groupingBy(ApiMethod::category));
  }

  /** Method names for completion, unique, sorted. */
  public List<String> methodNames() {
    return methods.stream().map(ApiMethod::methodName).distinct().sorted().toList();
  }

  public Optional<ApiMethod> first(String methodName) {
    return byName(methodName).stream().findFirst();
  }
}
