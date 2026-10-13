package org.openpatch.scratch4j.core.region;

import com.sun.source.tree.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Accessible sprite constructors whose arguments can be entered as Java literals. */
public final class SpriteConstructors {
  public record Parameter(String type, String name) {
    public String initialValue() {
      return switch (type.replace("java.lang.", "")) {
        case "String" -> "\"\"";
        case "boolean", "Boolean" -> "false";
        case "char", "Character" -> "'a'";
        case "long", "Long" -> "0L";
        case "float", "Float" -> "0f";
        case "double", "Double" -> "0d";
        default -> "0";
      };
    }
  }

  public record Constructor(String type, List<Parameter> parameters) {
    @Override public String toString() {
      return type + "(" + String.join(", ", parameters.stream()
          .map(p -> p.type() + " " + p.name()).toList()) + ")";
    }
    public boolean accepts(List<String> arguments) {
      if (arguments.size() != parameters.size()) return false;
      try {
        var literals = JavaFragment.literalArguments(String.join(", ", arguments));
        for (int i = 0; i < literals.size(); i++) {
          Object value = JavaFragment.value(literals.get(i));
          String type = parameters.get(i).type().replace("java.lang.", "");
          if (value == null) {
            if (Character.isLowerCase(type.charAt(0))) return false;
            continue;
          }
          boolean accepted = switch (type) {
            case "String" -> value instanceof String;
            case "char", "Character" -> value instanceof Character;
            case "boolean", "Boolean" -> value instanceof Boolean;
            case "double" -> value instanceof Number;
            case "Double" -> value instanceof Double;
            case "float" -> value instanceof Number && !(value instanceof Double);
            case "Float" -> value instanceof Float;
            case "long" -> value instanceof Integer || value instanceof Long;
            case "Long" -> value instanceof Long;
            case "int", "Integer" -> value instanceof Integer;
            case "short", "Short" -> value instanceof Integer n && n >= Short.MIN_VALUE && n <= Short.MAX_VALUE;
            case "byte", "Byte" -> value instanceof Integer n && n >= Byte.MIN_VALUE && n <= Byte.MAX_VALUE;
            default -> false;
          };
          if (!accepted) return false;
        }
        return true;
      } catch (RuntimeException e) {
        return false;
      }
    }
  }

  private static final Set<String> LITERAL_TYPES = Set.of("String", "boolean", "Boolean",
      "char", "Character", "double", "Double", "float", "Float", "long", "Long", "int",
      "Integer", "short", "Short", "byte", "Byte");

  private SpriteConstructors() {}

  private record Cached(long modified, List<Constructor> constructors) {}

  /** Parsed constructors per class file, until the file changes (the designer asks often). */
  private static final Map<Path, Cached> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

  public static List<Constructor> of(Path root, String className) {
    Path file = root.resolve(className + ".java");
    long modified;
    try {
      modified = Files.getLastModifiedTime(file).toMillis();
    } catch (java.io.IOException e) {
      return List.of();
    }
    Cached cached = CACHE.get(file);
    if (cached != null && cached.modified() == modified) return cached.constructors();
    List<Constructor> constructors = read(file, className);
    CACHE.put(file, new Cached(modified, constructors));
    return constructors;
  }

  private static List<Constructor> read(Path file, String className) {
    try {
      var fragment = JavaFragment.statements(Files.readString(file)
          .replaceAll("(?m)^\\s*(?:package|import)\\s+[^;]+;", ""), true);
      ClassTree cls = fragment.stream().map(JavaFragment.Statement::tree)
          .filter(ClassTree.class::isInstance).map(ClassTree.class::cast)
          .filter(c -> c.getSimpleName().contentEquals(className)).findFirst().orElse(null);
      if (cls == null || cls.getModifiers().getFlags().contains(javax.lang.model.element.Modifier.ABSTRACT)) {
        return List.of();
      }
      List<Constructor> out = new ArrayList<>();
      boolean declared = false;
      for (Tree member : cls.getMembers()) {
        if (!(member instanceof MethodTree method) || !method.getName().contentEquals("<init>")) continue;
        declared = true;
        if (method.getModifiers().getFlags().contains(javax.lang.model.element.Modifier.PRIVATE)) continue;
        List<Parameter> parameters = method.getParameters().stream()
            .map(p -> new Parameter(p.getType().toString(), p.getName().toString())).toList();
        if (parameters.stream().allMatch(p -> LITERAL_TYPES.contains(p.type().replace("java.lang.", "")))) {
          out.add(new Constructor(className, parameters));
        }
      }
      if (!declared) out.add(new Constructor(className, List.of()));
      return List.copyOf(out);
    } catch (java.io.IOException | RuntimeException e) {
      return List.of();
    }
  }
}
