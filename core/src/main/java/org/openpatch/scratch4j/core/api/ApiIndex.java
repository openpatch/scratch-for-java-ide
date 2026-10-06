package org.openpatch.scratch4j.core.api;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;

/**
 * The API index the IDE ships (offline-first): every public library method
 * with signature, summary, Scratch block and docs URL. Generated from the
 * library's sources jar — see {@link ApiIndexGenerator}.
 */
public final class ApiIndex {

  private static final String RESOURCE = "/api-index.json";

  private final List<ApiMethod> methods;

  private ApiIndex(List<ApiMethod> methods) {
    this.methods = List.copyOf(methods);
  }

  /** Loads the bundled index. */
  public static ApiIndex load() {
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
