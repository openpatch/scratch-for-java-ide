package org.openpatch.scratch4j.core.api;

import java.util.List;

/** One public method of the Scratch for Java API, indexed for the IDE. */
public record ApiMethod(
    String className,
    String methodName,
    String returnType,
    List<String> params,
    String summary,
    /** The Scratch block this replaces, in scratchblocks syntax; null if none. */
    String scratchblock,
    /** Palette category heuristic (Motion, Looks, Sound, Events, Control, Sensing, Operators, Pen) or the class name. */
    String category,
    String docsUrl,
    /** The whole Javadoc description as plain text (paragraphs separated by blank lines). */
    String description,
    /** {@code @param} texts as "name: text", in parameter order. */
    List<String> paramDocs,
    /** The {@code @return} text; null if none. */
    String returns) {

  /** Index entries written before descriptions existed read as empty, not null. */
  public ApiMethod {
    paramDocs = paramDocs == null ? List.of() : List.copyOf(paramDocs);
  }

  public record Param(String type, String name) {}

  /** The call skeleton shown in the palette, e.g. {@code this.move(10);}. */
  public String callSkeleton() {
    StringBuilder sb = new StringBuilder();
    if (!"constructor".equals(methodName)) {
      sb.append("this.").append(methodName).append("(");
    } else {
      sb.append("new ").append(className).append("(");
    }
    for (int i = 0; i < params.size(); i++) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append(params.get(i));
    }
    sb.append(");");
    return sb.toString();
  }

  public String signature() {
    StringBuilder sb = new StringBuilder();
    sb.append("constructor".equals(methodName)
        ? "new " + className + "("
        : returnType + " " + methodName + "(");
    for (int i = 0; i < params.size(); i++) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append(params.get(i));
    }
    return sb.append(")").toString();
  }
}
