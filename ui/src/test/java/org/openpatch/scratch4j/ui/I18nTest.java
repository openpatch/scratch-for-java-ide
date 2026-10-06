package org.openpatch.scratch4j.ui;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.ResourceBundle;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class I18nTest {

  @Test
  void everyKeyExistsInBothLanguages() {
    ResourceBundle en = I18n.bundleFor(I18n.Language.EN);
    ResourceBundle de = I18n.bundleFor(I18n.Language.DE);
    Set<String> enKeys = en.keySet();
    Set<String> deKeys = de.keySet();
    assertThat(enKeys).isEqualTo(deKeys);
  }

  @Test
  void noBlankTranslations() {
    for (I18n.Language language : I18n.Language.values()) {
      ResourceBundle bundle = I18n.bundleFor(language);
      for (String key : new HashSet<>(bundle.keySet())) {
        assertThat(bundle.getString(key)).as("%s.%s", language, key).isNotBlank();
      }
    }
  }

  @Test
  void switchChangesTranslations() {
    I18n.set(I18n.Language.EN);
    String en = I18n.t("menu.file.new");
    I18n.set(I18n.Language.DE);
    assertThat(I18n.t("menu.file.new")).isNotEqualTo(en);
    assertThat(I18n.t("menu.file.new")).isEqualTo("Neues Projekt...");
  }

  @Test
  void formatsArguments() {
    I18n.set(I18n.Language.EN);
    assertThat(I18n.t("status.stopped", 3)).contains("3");
  }
}
