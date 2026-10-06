package org.openpatch.scratch4j.core.api;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Generates the API index from the library's sources jar: every public method
 * of {@code org.openpatch.scratch.*} with its signature, Javadoc summary, full
 * description, parameter and return docs and {@code @scratchblock} tag, as JSON.
 *
 * <p>The generated file is committed as {@code api-index.json} (readable diffs,
 * ships offline-first). A test regenerates it and compares, so a library
 * version bump that changes the API fails the build until the index is
 * regenerated with {@code ApiIndexGenerator.main}.
 */
public final class ApiIndexGenerator {

  // The doc body may not cross the end of the comment, so one javadoc can
  // never swallow the next method's javadoc/signature.
  private static final Pattern JAVADOC_METHOD = Pattern.compile(
      "/\\*\\*(?<doc>(?:(?!\\*/).)*?)\\*/\\s*(?:@[\\w.]+(?:\\([^)]*\\))?\\s*)*"
          + "(?:public|protected)\\s+(?<rest>[\\w<>\\[\\],\\s]+?)\\s+"
          + "(?<name>\\w+)\\s*\\((?<params>[^)]*)\\)",
      Pattern.DOTALL);

  private static final Pattern JAVADOC_CONSTRUCTOR = Pattern.compile(
      "/\\*\\*(?<doc>(?:(?!\\*/).)*?)\\*/\\s*(?:@[\\w.]+(?:\\([^)]*\\))?\\s*)*"
          + "public\\s+(?<ctor>\\w+)\\s*\\((?<params>[^)]*)\\)",
      Pattern.DOTALL);

  private static final Pattern JAVADOC_TAG = Pattern.compile("^\\s*@(\\w+)(.*)$", Pattern.MULTILINE);

  private static final String DOCS_BASE = "https://scratch4j.openpatch.org/reference/";

  private ApiIndexGenerator() {}

  /** Generates the index from a sources jar and writes it as pretty JSON. */
  public static List<ApiMethod> generate(Path sourcesJar) throws IOException {
    List<ApiMethod> methods = new ArrayList<>();
    try (ZipFile zip = new ZipFile(sourcesJar.toFile(), ZipFile.OPEN_READ)) {
      Enumeration<? extends ZipEntry> entries = zip.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        String name = entry.getName();
        if (!name.startsWith("org/openpatch/scratch/")
            || name.startsWith("org/openpatch/scratch/internal/")
            || name.contains("/extensions/")
            || !name.endsWith(".java")) {
          continue;
        }
        String source;
        try (InputStream in = zip.getInputStream(entry)) {
          source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String className = name.substring(name.lastIndexOf('/') + 1, name.length() - 5);
        methods.addAll(parseFile(className, source));
      }
    }
    methods.sort(java.util.Comparator
        .comparing(ApiMethod::className)
        .thenComparing(ApiMethod::methodName));
    return methods;
  }

  private static List<ApiMethod> parseFile(String className, String source) {
    List<ApiMethod> methods = new ArrayList<>();
    Matcher ctor = JAVADOC_CONSTRUCTOR.matcher(source);
    while (ctor.find()) {
      if (!className.equals(ctor.group("ctor"))) {
        continue;
      }
      methods.add(entry(className, "constructor", "new " + className,
          splitParams(ctor.group("params")), ctor.group("doc")));
    }
    Matcher m = JAVADOC_METHOD.matcher(source);
    while (m.find()) {
      String rest = m.group("rest").trim();
      // Note: a constructor cannot match here (the pattern needs a return
      // type token before the method name), so `rest` may legitimately be the
      // class name (e.g. `public Sprite clone()`).
      if (rest.contains(" class ") || rest.contains(" interface ")) {
        continue;
      }
      String name = m.group("name");
      String doc = m.group("doc");
      methods.add(entry(className, name, rest, splitParams(m.group("params")), doc));
    }
    return methods;
  }

  private static ApiMethod entry(String className, String methodName, String returnType,
      List<String> params, String doc) {
    String summary = docSummary(doc);
    String scratchblock = docTag(doc, "scratchblock");
    String url = DOCS_BASE + className + "/" + methodName;
    String description = plainText(docDescription(doc));
    List<String> paramDocs = new ArrayList<>();
    String returns = null;
    for (String[] tag : blockTags(doc)) {
      if (tag[0].equals("param")) {
        String[] parts = tag[1].split("\\s+", 2);
        paramDocs.add(parts[0] + ": " + plainText(parts.length > 1 ? parts[1] : ""));
      } else if (tag[0].equals("return")) {
        returns = plainText(tag[1]);
      }
    }
    ApiMethod method = new ApiMethod(className, methodName, returnType, params,
        summary, scratchblock, null, url, description, paramDocs, returns);
    return new ApiMethod(className, methodName, returnType, params, summary, scratchblock,
        Categories.of(method), url, description, paramDocs, returns);
  }

  private static String cleanedDoc(String doc) {
    return doc.lines()
        .map(line -> line.replaceFirst("^\\s*\\* ?", ""))
        .collect(java.util.stream.Collectors.joining("\n"))
        .trim();
  }

  /** The doc text before the first block tag. */
  private static String docDescription(String doc) {
    String cleaned = cleanedDoc(doc);
    Matcher tag = Pattern.compile("(?m)^\\s*@\\w+").matcher(cleaned);
    return (tag.find() ? cleaned.substring(0, tag.start()) : cleaned).trim();
  }

  /** Block tags as [name, text] (multi-line texts joined). */
  private static List<String[]> blockTags(String doc) {
    List<String[]> tags = new ArrayList<>();
    Matcher m = Pattern.compile("(?ms)^\\s*@(\\w[\\w.]*)\\s*(.*?)(?=^\\s*@\\w|\\z)")
        .matcher(cleanedDoc(doc));
    while (m.find()) {
      tags.add(new String[] {m.group(1), m.group(2).trim().replaceAll("\\s+", " ")});
    }
    return tags;
  }

  /**
   * Javadoc HTML and inline tags as readable plain text: {@code x} and
   * {@link A#b} become their text, paragraphs become blank lines, list items
   * become "- " lines, other tags and entities are dropped or decoded.
   */
  static String plainText(String html) {
    // code samples keep their lines: park them, clean the prose, put them back
    List<String> samples = new ArrayList<>();
    Matcher pre = Pattern.compile("(?is)<pre>(.*?)</pre>").matcher(html);
    StringBuilder parked = new StringBuilder();
    while (pre.find()) {
      String code = pre.group(1)
          .replaceAll("\\{@(?:code|literal)\\s+([^{}]*(?:\\{[^{}]*\\}[^{}]*)*)\\}", "$1")
          .replaceAll("<[^>]+>", "")
          .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
          .replace("&#64;", "@").replace("&quot;", "\"");
      samples.add(code.lines().dropWhile(String::isBlank).map(String::stripTrailing)
          .collect(java.util.stream.Collectors.joining("\n")).stripTrailing().stripIndent());
      pre.appendReplacement(parked, "\n\n@@SAMPLE" + (samples.size() - 1) + "@@\n\n");
    }
    pre.appendTail(parked);
    String text = parked.toString()
        .replaceAll("\\{@(?:code|literal)\\s+([^{}]*(?:\\{[^{}]*\\}[^{}]*)*)\\}", "$1")
        .replaceAll("\\{@(?:link|linkplain)\\s+(?:[\\w.]*#)?([^\\s}]+)(?:\\s+([^}]*))?\\}",
            "$1")
        .replaceAll("\\{@\\w+\\s*([^}]*)\\}", "$1")
        .replaceAll("(?i)<p>|</p>|<br\\s*/?>", "\n\n")
        .replaceAll("(?i)<li>", "\n- ")
        .replaceAll("<[^>]+>", "")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
        .replace("&quot;", "\"").replace("&#64;", "@").replace("&nbsp;", " ");
    // collapse spacing inside lines, keep paragraph breaks
    String[] paragraphs = text.split("\\n\\s*\\n");
    List<String> out = new ArrayList<>();
    for (String paragraph : paragraphs) {
      String joined = paragraph.lines().map(String::strip)
          .filter(line -> !line.isEmpty())
          .collect(java.util.stream.Collectors.joining(paragraph.contains("\n- ") ? "\n" : " "));
      if (!joined.isEmpty()) {
        out.add(joined);
      }
    }
    String result = String.join("\n\n", out);
    for (int i = 0; i < samples.size(); i++) {
      result = result.replace("@@SAMPLE" + i + "@@", samples.get(i));
    }
    return result;
  }

  private static List<String> splitParams(String params) {
    List<String> out = new ArrayList<>();
    if (params == null || params.isBlank()) {
      return out;
    }
    for (String param : params.split(",")) {
      String trimmed = param.trim().replaceAll("\\s+", " ");
      if (!trimmed.isEmpty() && !trimmed.startsWith("@")) {
        out.add(trimmed);
      }
    }
    return out;
  }

  /** The description of the javadoc: the text before the first tag, first sentence. */
  private static String docSummary(String doc) {
    String cleaned = doc.lines()
        .map(line -> line.replaceFirst("^\\s*\\* ?", ""))
        .collect(java.util.stream.Collectors.joining("\n"))
        .trim();
    int tagStart = JAVADOC_TAG.matcher(cleaned).find() ? cleaned.indexOf('@') : cleaned.length();
    String description = cleaned.substring(0, Math.min(cleaned.length(), tagStart)).trim();
    int sentence = description.indexOf(".");
    if (sentence > 0 && sentence < 200) {
      return description.substring(0, sentence + 1);
    }
    return description;
  }

  /** The value of a javadoc tag ({@code @scratchblock ...}), trimmed, or null. */
  private static String docTag(String doc, String tag) {
    // A trailing @scratchblock (the common case) ends at the end of the doc.
    Matcher m = Pattern.compile("@scratchblock\\s+(.+?)(?=\\n\\s*\\*?\\s*@|\\z)",
        Pattern.DOTALL).matcher(doc);
    if (m.find()) {
      String value = m.group(1).lines()
          .map(line -> line.replaceFirst("^\\s*\\* ?", "").trim())
          .filter(s -> !s.isEmpty())
          .collect(java.util.stream.Collectors.joining(" "));
      return value.isEmpty() ? null : value;
    }
    return null;
  }

  public static void main(String[] args) throws IOException {
    if (args.length != 2) {
      System.err.println("Usage: ApiIndexGenerator <scratch-sources.jar> <output.json>");
      System.exit(2);
    }
    List<ApiMethod> methods = generate(Path.of(args[0]));
    Path out = Path.of(args[1]);
    if (out.getParent() != null) {
      Files.createDirectories(out.getParent());
    }
    Files.writeString(out, toJson(methods), StandardCharsets.UTF_8);
    System.out.println("Wrote " + methods.size() + " methods to " + out);
  }

  /** Stable, pretty JSON so the committed resource has readable diffs. */
  public static String toJson(List<ApiMethod> methods) {
    StringBuilder sb = new StringBuilder("[\n");
    for (int i = 0; i < methods.size(); i++) {
      ApiMethod m = methods.get(i);
      sb.append("  {\n");
      sb.append("    \"className\": ").append(json(m.className())).append(",\n");
      sb.append("    \"methodName\": ").append(json(m.methodName())).append(",\n");
      sb.append("    \"returnType\": ").append(json(m.returnType())).append(",\n");
      sb.append("    \"params\": [");
      for (int p = 0; p < m.params().size(); p++) {
        sb.append(p == 0 ? "" : ", ").append(json(m.params().get(p)));
      }
      sb.append("],\n");
      sb.append("    \"summary\": ").append(json(m.summary())).append(",\n");
      sb.append("    \"scratchblock\": ").append(json(m.scratchblock())).append(",\n");
      sb.append("    \"category\": ").append(json(m.category())).append(",\n");
      sb.append("    \"docsUrl\": ").append(json(m.docsUrl())).append(",\n");
      sb.append("    \"description\": ").append(json(m.description())).append(",\n");
      sb.append("    \"paramDocs\": [");
      for (int p = 0; p < m.paramDocs().size(); p++) {
        sb.append(p == 0 ? "" : ", ").append(json(m.paramDocs().get(p)));
      }
      sb.append("],\n");
      sb.append("    \"returns\": ").append(json(m.returns())).append("\n");
      sb.append(i == methods.size() - 1 ? "  }\n" : "  },\n");
    }
    sb.append("]\n");
    return sb.toString();
  }

  private static String json(String value) {
    if (value == null) {
      return "null";
    }
    StringBuilder sb = new StringBuilder("\"");
    for (char c : value.toCharArray()) {
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) {
            sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    return sb.append('"').toString();
  }
}
