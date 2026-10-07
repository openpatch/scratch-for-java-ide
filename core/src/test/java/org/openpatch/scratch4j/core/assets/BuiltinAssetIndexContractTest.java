package org.openpatch.scratch4j.core.assets;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test: the IDE reads the library's internal asset registries
 * (pinned scratch 5.8.0). If the library moves or renames them, this fails and
 * the pin must be revisited.
 */
class BuiltinAssetIndexContractTest {

  private final BuiltinAssetIndex index = BuiltinAssetIndex.get();

  @Test void sharedCatalogCoordinatesAndAliasesMatchThePinnedRuntimeRegistry() {
    for (var entry : org.openpatch.scratch.internal.BuiltinAssets.getEntries()) {
      assertThat(index.image(entry.sheet + "/" + entry.name)).hasValueSatisfying(image -> {
        assertThat(image.x()).isEqualTo(entry.x);
        assertThat(image.y()).isEqualTo(entry.y);
        assertThat(image.width()).isEqualTo(entry.width);
        assertThat(image.height()).isEqualTo(entry.height);
        assertThat(image.direction()).isEqualTo(entry.direction);
        assertThat(image.referenceName()).isEqualTo(org.openpatch.scratch.internal.BuiltinAssets.getReferenceName(entry));
      });
    }
    assertThat(index.sounds()).containsExactlyElementsOf(org.openpatch.scratch.internal.BuiltinSounds.getNames());
  }

  @Test
  void shipsTheKenneyAndCatSheets() {
    assertThat(index.images().stream().map(BuiltinImage::sheet).distinct().toList())
        .containsExactlyInAnyOrder("platformer", "jumper", "space_shooter", "tappy_plane", "cat");
  }

  @Test
  void hasTheDocumentedBuiltinImageAndSoundCounts() {
    // 922 sprite entries on 5 sheets and 266 sounds, verified against the jar.
    assertThat(index.images()).hasSize(922);
    assertThat(index.imageNames()).hasSize(922);
    assertThat(index.sounds().stream().distinct().count()).isEqualTo(266);
  }

  @Test
  void referenceNameIsBareUnlessAmbiguous() {
    // bunny1_stand is unambiguous -> bare; platformer is the first sheet, so
    // it owns the bare "cactus"; the same sprite on jumper must be qualified
    assertThat(index.image("bunny1_stand")).hasValueSatisfying(
        i -> assertThat(i.referenceName()).isEqualTo("bunny1_stand"));
    assertThat(index.image("cactus")).hasValueSatisfying(
        i -> {
          assertThat(i.sheet()).isEqualTo("platformer");
          assertThat(i.referenceName()).isEqualTo("cactus");
        });
    assertThat(index.image("jumper/cactus")).hasValueSatisfying(
        i -> assertThat(i.referenceName()).isEqualTo("jumper/cactus"));
  }

  @Test
  void everyImageFacesRightOrCarriesItsDrawnDirection() {
    assertThat(index.images())
        .allSatisfy(i -> assertThat(i.direction()).isBetween(-180.0, 180.0));
  }

  @Test
  void knownNamesResolveCaseInsensitively() {
    assertThat(index.image("Bunny1_Stand")).isPresent();
    assertThat(index.image("jumper/cactus")).isPresent();
    assertThat(index.image("does-not-exist")).isEmpty();
  }

  @Test
  void suggestFindsCloseNames() {
    List<String> suggestions = index.suggestImages("buny1_stand", 3);
    assertThat(suggestions).anySatisfy(s -> assertThat(s).containsIgnoringCase("bunny1_stand"));
  }

  @Test
  void soundNamesAreSuggestable() {
    assertThat(index.hasSound("impactGlass_medium_001")).isTrue();
    assertThat(index.hasSound("jingles")).isFalse(); // category folder, not a sound
    assertThat(index.suggestSounds("impactGlass", 5)).isNotEmpty();
  }
}
