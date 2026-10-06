package org.openpatch.scratch4j.runner;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * What a running program reports a few times a second ({@code monitor} on the
 * control channel): its stage and sprites with their properties and variables,
 * and the shared ({@code static}) variables of the student's classes. Values
 * are display strings; another sprite is {@code @ref:<id>:<Class>}.
 *
 * @param total sprites on the stage (only the first 100 are reported)
 */
public record ProgramState(long frame, boolean paused, Entry stage, List<Entry> sprites,
    int total, List<Entry> statics) {

  /** A name and its value: a variable, or a property such as {@code @x}. */
  public record Value(String name, String value) {}

  /**
   * One object (the stage, a sprite, or a class with shared variables).
   * {@code props} are the library's ({@code @x}, {@code @y}, ...),
   * {@code fields} the student's own variables.
   */
  public record Entry(String id, String className, List<Value> props, List<Value> fields) {}

  /** The reference marker the program writes for other sprites. */
  public static final String REF = "@ref:";

  public static ProgramState parse(String json) {
    JsonNode root = JsonMapper.shared().readTree(json);
    List<Entry> sprites = new ArrayList<>();
    root.path("sprites").forEach(n -> sprites.add(entry(n)));
    List<Entry> statics = new ArrayList<>();
    root.path("statics").forEach(n -> statics.add(entry(n)));
    JsonNode stage = root.path("stage");
    return new ProgramState(root.path("frame").asLong(-1), root.path("paused").asBoolean(false),
        stage.isObject() ? entry(stage) : null, List.copyOf(sprites),
        root.path("total").asInt(sprites.size()), List.copyOf(statics));
  }

  private static Entry entry(JsonNode node) {
    return new Entry(node.path("id").asText(""), node.path("class").asText(""),
        values(node.path("props")), values(node.path("fields")));
  }

  private static List<Value> values(JsonNode pairs) {
    List<Value> values = new ArrayList<>();
    for (JsonNode pair : pairs) {
      values.add(new Value(pair.path(0).asText(""), pair.path(1).asText("")));
    }
    return List.copyOf(values);
  }
}
