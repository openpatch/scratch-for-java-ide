package org.openpatch.scratch4j.ui;

import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.ResourceBundle;

/**
 * UI strings, EN + DE, switchable at runtime. Every user-visible string comes
 * from {@code messages_<lang>.properties}; a test keeps both bundles in sync.
 */
public final class I18n {

  public enum Language {
    EN(Locale.ENGLISH), DE(Locale.GERMAN);

    final Locale locale;

    Language(Locale locale) {
      this.locale = locale;
    }
  }

  private static volatile ResourceBundle bundle = bundleFor(defaultLanguage());
  private static ButtonType ok;
  private static ButtonType cancel;
  private static ButtonType close;

  private I18n() {}

  /** The language matching the system locale, EN otherwise. */
  public static Language defaultLanguage() {
    return Locale.getDefault().getLanguage().equals("de") ? Language.DE : Language.EN;
  }

  static ResourceBundle bundleFor(Language language) {
    return ResourceBundle.getBundle("org.openpatch.scratch4j.ui.messages", language.locale);
  }

  /**
   * Switches the language; all future {@link #t} calls use it. The JVM
   * default locale follows, so JavaFX's own texts (colour names, text-field
   * menus) match too.
   */
  public static void set(Language language) {
    bundle = bundleFor(language);
    Locale.setDefault(language.locale);
    ok = null;
    cancel = null;
    close = null;
  }

  /*
   * Dialog buttons in the UI language. JavaFX's ButtonType.OK/CANCEL/CLOSE
   * take their text from the locale at class-load time and never change; these
   * are rebuilt after a language switch and cached so == comparisons hold.
   */

  static ButtonType ok() {
    if (ok == null) {
      ok = new ButtonType(t("button.ok"), ButtonBar.ButtonData.OK_DONE);
    }
    return ok;
  }

  static ButtonType cancel() {
    if (cancel == null) {
      cancel = new ButtonType(t("button.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
    }
    return cancel;
  }

  static ButtonType close() {
    if (close == null) {
      close = new ButtonType(t("button.close"), ButtonBar.ButtonData.CANCEL_CLOSE);
    }
    return close;
  }

  public static Language current() {
    return bundle.getLocale().getLanguage().equals("de") ? Language.DE : Language.EN;
  }

  /** Translates a key. */
  public static String t(String key) {
    return bundle.getString(key);
  }

  /** Translates a key with arguments ({@code {0}}, ...). */
  public static String t(String key, Object... args) {
    return MessageFormat.format(bundle.getString(key), args);
  }
}
