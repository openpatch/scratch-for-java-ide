package org.openpatch.scratch4j.core.api;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApiIndexTest {

  private final ApiIndex index = ApiIndex.load();

  @Test
  void bundlesTheWholePublicApiWithAllScratchblocks() {
    assertThat(index.methods().size()).isGreaterThan(300);
    // 146 @scratchblock tags exist in the library sources - all must be indexed
    assertThat(index.methods().stream().filter(m -> m.scratchblock() != null))
        .hasSize(146);
  }

  @Test
  void everyEntryHasADocsUrlAndCategory() {
    for (ApiMethod m : index.methods()) {
      assertThat(m.docsUrl())
          .startsWith("https://scratch4j.openpatch.org/reference/");
      assertThat(m.category()).isNotBlank();
      assertThat(m.className()).isNotEmpty();
      assertThat(m.methodName()).isNotEmpty();
    }
  }

  @Test
  void findsScratchBlockMethodsByName() {
    ApiMethod move = index.byName("move").stream()
        .filter(m -> m.className().equals("Sprite"))
        .findFirst().orElseThrow();
    assertThat(move.scratchblock()).isEqualTo("move (steps) steps");
    assertThat(move.params()).containsExactly("double steps");
    assertThat(move.summary()).isNotBlank();
    assertThat(move.docsUrl()).isEqualTo(
        "https://scratch4j.openpatch.org/reference/Sprite/move");
  }

  @Test
  void constructorsAndCallSkeletonsAreIndexed() {
    ApiMethod ctor = index.methods().stream()
        .filter(m -> m.className().equals("Sprite") && m.methodName().equals("constructor"))
        .findFirst().orElseThrow();
    assertThat(ctor.callSkeleton()).startsWith("new Sprite(");
  }

  @Test
  void paletteGroupsByCategory() {
    Map<String, ? extends java.util.List<ApiMethod>> palette = index.blockPalette();
    assertThat(palette).containsKeys("Motion", "Looks", "Sound", "Events", "Sensing", "Operators");
    assertThat(palette.get("Motion")).isNotEmpty();
    assertThat(palette.get("Looks"))
        .anySatisfy(m -> assertThat(m.scratchblock()).contains("costume"));
  }

  @Test
  void completionNamesAreUniqueAndSorted() {
    assertThat(index.methodNames()).isSorted().doesNotHaveDuplicates();
    assertThat(index.methodNames()).contains("move", "addCostume", "playAnimation");
  }
}
