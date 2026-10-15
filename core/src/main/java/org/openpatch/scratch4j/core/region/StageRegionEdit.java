package org.openpatch.scratch4j.core.region;

import com.sun.source.tree.*;
import java.util.*;

/** Changes only the statements whose visual values changed, on the latest source. */
final class StageRegionEdit {
  private record Edit(int start, int end, String text) {}

  static String rewrite(String source, String regionId, StageModel before, StageModel after,
      StageModel latest) {
    Region region = RegionParser.find(source, regionId);
    boolean fields = regionId.equals("fields");
    var oldValues = values(before, fields);
    var newValues = values(after, fields);
    var currentValues = values(latest, fields);
    var statements = JavaFragment.statements(region.body(), fields);
    List<Edit> edits = new ArrayList<>();
    Map<Integer, StringBuilder> insertions = new TreeMap<>();
    Set<String> keys = new LinkedHashSet<>(oldValues.keySet());
    keys.addAll(newValues.keySet());
    for (String key : keys) {
      String oldValue = oldValues.get(key);
      String newValue = newValues.get(key);
      if (Objects.equals(oldValue, newValue)) continue;
      String currentValue = currentValues.get(key);
      if (!Objects.equals(currentValue, oldValue) && !Objects.equals(currentValue, newValue)) {
        throw new IllegalArgumentException("The code changed " + key.split("/")[0]
            + " while it was being edited visually. The code has been kept; try the visual edit again.");
      }
      List<JavaFragment.Statement> matches = statements.stream()
          .filter(s -> key.equals(key(s.tree(), fields))).toList();
      if (!matches.isEmpty()) {
        for (int i = 0; i < matches.size(); i++) {
          var match = matches.get(i);
          String replacement = i == matches.size() - 1 && newValue != null ? newValue : "";
          edits.add(removalOrReplacement(region.body(), match, replacement));
        }
      } else if (newValue != null) {
        String owner = key.substring(0, key.indexOf('/'));
        int at = region.body().length();
        if (!fields && !owner.equals("stage")) {
          // Properties of an existing sprite go before its add, keeping constructor order.
          at = statements.stream().filter(s -> (owner + "/add").equals(key(s.tree(), false)))
              .mapToInt(JavaFragment.Statement::start).findFirst().orElse(at);
          int lineStart = region.body().lastIndexOf('\n', Math.max(0, at - 1)) + 1;
          if (region.body().substring(lineStart, at).isBlank()) at = lineStart;
        }
        insertions.computeIfAbsent(at, ignored -> new StringBuilder())
            .append(region.indent()).append(newValue).append(source.contains("\r\n") ? "\r\n" : "\n");
      }
    }
    // Reordering sprites changes add order. The adds move together behind the last
    // one, which comes after every sprite is created; constructor calls stay put.
    if (!fields && !names(before).equals(names(after))) {
      List<String> survivingBefore = names(before).stream().filter(names(after)::contains).toList();
      List<String> survivingAfter = names(after).stream().filter(names(before)::contains).toList();
      if (!survivingBefore.equals(survivingAfter)) {
        var adds = statements.stream().filter(s -> {
          String key = key(s.tree(), false);
          return key != null && key.endsWith("/add") && survivingBefore.contains(key.split("/")[0]);
        }).toList();
        if (adds.size() != survivingAfter.size()) {
          throw new IllegalArgumentException(
              "The Java code decides the layer order here; change it in the code.");
        }
        var last = adds.get(adds.size() - 1);
        StringBuilder reordered = new StringBuilder();
        for (String name : survivingAfter) {
          reordered.append(reordered.isEmpty() ? ""
                  : (source.contains("\r\n") ? "\r\n" : "\n") + region.indent())
              .append("this.add(").append(name).append(");");
        }
        for (var add : adds) {
          edits.removeIf(e -> e.start == add.start());
          edits.add(add == last ? new Edit(add.start(), add.end(), reordered.toString())
              : removalOrReplacement(region.body(), add, ""));
        }
      }
    }
    insertions.forEach((at, text) -> edits.add(new Edit(at, at, text.toString())));
    edits.sort(Comparator.comparingInt(Edit::start).thenComparingInt(Edit::end).reversed());
    StringBuilder body = new StringBuilder(region.body());
    for (Edit edit : edits) body.replace(edit.start, edit.end, edit.text);
    return RegionParser.rewrite(source, regionId, body.toString());
  }

  private static List<String> names(StageModel model) {
    return model.sprites().stream().map(SpriteRef::name).toList();
  }

  private static Map<String, String> values(StageModel model, boolean fields) {
    String body = fields ? RegionStatements.generateFields(model, "")
        : RegionStatements.generateSetup(model, "");
    Map<String, String> out = new LinkedHashMap<>();
    for (var statement : JavaFragment.statements(body, fields)) {
      out.put(key(statement.tree(), fields), statement.text());
    }
    return out;
  }

  private static Edit removalOrReplacement(String body, JavaFragment.Statement statement,
      String replacement) {
    int start = statement.start();
    int end = statement.end();
    if (replacement.isEmpty()) {
      int lineStart = body.lastIndexOf('\n', Math.max(0, start - 1)) + 1;
      int lineEnd = body.indexOf('\n', end);
      if (lineEnd < 0) lineEnd = body.length();
      // a removed statement takes its line along, with the comment behind it
      String rest = body.substring(end, lineEnd).strip();
      if (body.substring(lineStart, start).isBlank() && (rest.isEmpty() || rest.startsWith("//"))) {
        start = lineStart;
        end = Math.min(body.length(), lineEnd + 1);
      }
    }
    return new Edit(start, end, replacement);
  }

  static String key(Tree tree, boolean fields) {
    if (fields) return tree instanceof VariableTree variable ? variable.getName() + "/field" : null;
    if (!(tree instanceof ExpressionStatementTree statement)) return null;
    ExpressionTree expression = statement.getExpression();
    if (expression instanceof AssignmentTree assignment && assignment.getExpression() instanceof NewClassTree) {
      return simple(assignment.getVariable()) + "/new";
    }
    if (!(expression instanceof MethodInvocationTree call)
        || !(call.getMethodSelect() instanceof MemberSelectTree member)) return null;
    String method = member.getIdentifier().toString();
    String owner = simple(member.getExpression());
    if (owner.equals("this")) {
      if (method.equals("add") && call.getArguments().size() == 1) {
        return simple(call.getArguments().get(0)) + "/add";
      }
      if (method.equals("setColor")) {
        return "stage/setColor";
      }
      if ((method.equals("addBackdrop") || method.equals("addSound"))
          && !call.getArguments().isEmpty() && call.getArguments().get(0) instanceof LiteralTree literal) {
        return "stage/" + method + "/" + literal.getValue();
      }
      return null;
    }
    return owner + "/" + (method.equals("show") || method.equals("hide") ? "visibility" : method);
  }

  private static String simple(Tree tree) {
    return tree.toString().replaceFirst("^this\\.", "");
  }
}
