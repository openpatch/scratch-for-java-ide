package org.openpatch.scratch4j.core.lint;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.openpatch.scratch4j.core.compile.CompilerService;

/** Generates printable English/German pages from the measured corpus. */
public final class CommonErrorsHandout {
  private CommonErrorsHandout() {}
  public static void generate(Path destination) throws Exception {
    Path tmp = Files.createTempDirectory("scratch4j-handout-");
    try {
      Files.createDirectories(destination);
      for (var language : DiagnosticsExplanations.Language.values()) {
        boolean de = language == DiagnosticsExplanations.Language.DE;
        StringBuilder html = new StringBuilder("<!doctype html><html lang=\"" + (de ? "de" : "en")
            + "\"><meta charset=\"utf-8\"><title>" + (de ? "Häufige Java-Fehler" : "Common Java errors")
            + "</title><style>body{font:14px sans-serif;max-width:1000px;margin:2em auto}"
            + "main{columns:2;column-gap:2em}article{break-inside:avoid;margin:0 0 1.2em}"
            + "h2{font-size:1em}pre{white-space:pre-wrap;overflow-wrap:anywhere;font-size:11px}"
            + "@media print{body{font-size:10pt;margin:0}button{display:none}pre{font-size:8pt}}"
            + "</style><h1>" + (de ? "Häufige Java-Fehler" : "Common Java errors")
            + "</h1><button onclick=\"print()\">" + (de ? "Drucken" : "Print") + "</button><main>");
        for (var snippet : SnippetCorpus.load()) {
          Path file = tmp.resolve(snippet.className() + ".java");
          Files.writeString(file, snippet.broken());
          var error = new CompilerService().compile(List.of(file), List.of(), tmp.resolve("out"), Locale.ROOT).errors().getFirst();
          var friendly = new FriendlyErrors(language).describe(error, new FriendlyErrors.Context(
              snippet.broken().lines().toList(), snippet.className(), n -> null, List.of()));
          html.append("<article><h2>").append(escape(friendly.title())).append("</h2><pre>")
              .append(escape(snippet.broken().lines().toList().get(snippet.brokenLine() - 1)))
              .append("</pre><p>").append(escape(friendly.explanation())).append("</p><b>")
              .append(de ? "Korrektur" : "Fix").append("</b><pre>").append(escape(snippet.fixed()))
              .append("</pre></article>");
        }
        Files.writeString(destination.resolve("common-errors-" + (de ? "de" : "en") + ".html"), html + "</main></html>\n");
      }
    } finally {
      try (var files = Files.walk(tmp)) {
        for (var file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
      }
    }
  }
  private static String escape(String s) {
    return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
  }
  public static void main(String[] args) throws Exception { generate(Path.of(args.length == 0 ? "docs" : args[0])); }
}
