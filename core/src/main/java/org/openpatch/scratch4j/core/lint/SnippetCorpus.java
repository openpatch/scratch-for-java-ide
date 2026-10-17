package org.openpatch.scratch4j.core.lint;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/** Shared, curated examples for coverage, hints and the teacher handout (never student code). */
public final class SnippetCorpus {
  public record Snippet(String id, String className, String broken, String fixed,
      String caseKey, int brokenLine) {}
  private record Corpus(int v, List<Snippet> snippets) {}
  private SnippetCorpus() {}

  public static List<Snippet> load() throws IOException {
    try (var in = SnippetCorpus.class.getResourceAsStream("/lint/snippets/corpus.json")) {
      if (in == null) throw new IOException("Missing snippet corpus");
      Corpus corpus = JsonMapper.builder().build().readValue(
          new String(in.readAllBytes(), StandardCharsets.UTF_8), Corpus.class);
      if (corpus.v() != 1) throw new IOException("Unsupported snippet corpus");
      return List.copyOf(corpus.snippets());
    }
  }

  public static String example(String caseKey) {
    if (caseKey == null) return null;
    try {
      return load().stream().filter(s -> caseKey.equals(s.caseKey())).findFirst()
          .map(s -> s.broken() + "\n→\n" + s.fixed()).orElse(null);
    } catch (IOException e) {
      return null;
    }
  }
}
