package org.openpatch.scratch4j.core.region;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The shaders a class adds ({@code getShaders().add("name", "x.frag", "x.vert")})
 * and the uniforms its code sets to plain numbers right after
 * ({@code shader.set("pixels", 20.0, 10.0)}, also through
 * {@code getShaders().get("name").set(...)}): what a preview of the shader needs.
 */
public final class ShaderUses {

  /** One {@code add}: its 0-based line, the shader files (vert may be null) and uniforms. */
  public record Use(int line, String name, String frag, String vert,
      Map<String, double[]> uniforms) {}

  private static final String S = "\"([^\"]*)\"";
  private static final Pattern ADD = Pattern.compile(
      "(?:\\b(\\w+)\\s*=\\s*)?(?:this\\.)?getShaders\\(\\)\\.add\\(\\s*" + S + "\\s*,\\s*" + S
          + "\\s*(?:,\\s*(?:" + S + "|null)\\s*)?\\)");
  private static final String NUMBER = "-?\\d+(?:\\.\\d+)?[fFdD]?";
  private static final Pattern SET = Pattern.compile(
      "(?:\\b(\\w+)|getShaders\\(\\)\\.get\\(\\s*" + S + "\\s*\\))\\.set\\(\\s*" + S
          + "\\s*,\\s*((?:\\((?:float|double|int)\\)\\s*)?" + NUMBER
          + "(?:\\s*,\\s*(?:\\((?:float|double|int)\\)\\s*)?" + NUMBER + ")*)\\s*\\)");

  private ShaderUses() {}

  /** The shaders the source adds, in order. */
  public static List<Use> in(String source) {
    List<Use> uses = new ArrayList<>();
    String[] lines = source.split("\n", -1);
    // the variable each use was last assigned to (shader = ...add(...))
    Map<String, Integer> byVariable = new LinkedHashMap<>();
    Map<String, Integer> byName = new LinkedHashMap<>();
    List<Map<String, double[]>> uniforms = new ArrayList<>();
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i];
      if (line.strip().startsWith("//")) continue;
      Matcher add = ADD.matcher(line);
      if (add.find()) {
        Map<String, double[]> values = new LinkedHashMap<>();
        uniforms.add(values);
        uses.add(new Use(i, add.group(2), add.group(3), add.group(4),
            Collections.unmodifiableMap(values)));
        if (add.group(1) != null) byVariable.put(add.group(1), uses.size() - 1);
        byName.put(add.group(2), uses.size() - 1);
        continue;
      }
      Matcher set = SET.matcher(line);
      while (set.find()) {
        Integer index = set.group(1) != null ? byVariable.get(set.group(1))
            : byName.get(set.group(2));
        if (index == null || uniforms.get(index).containsKey(set.group(3))) continue;
        String[] parts = set.group(4).split(",");
        double[] values = new double[parts.length];
        for (int k = 0; k < parts.length; k++) {
          values[k] = Double.parseDouble(
              parts[k].replaceAll("\\((?:float|double|int)\\)", "").strip()
                  .replaceAll("[fFdD]$", ""));
        }
        uniforms.get(index).put(set.group(3), values);
      }
    }
    return uses;
  }

  /** The uses of the shader file {@code frag} (as written in the code) among the sources. */
  public static List<Use> of(String source, String frag) {
    return in(source).stream().filter(u -> frag.equals(u.frag()) || frag.equals(u.vert()))
        .toList();
  }
}
