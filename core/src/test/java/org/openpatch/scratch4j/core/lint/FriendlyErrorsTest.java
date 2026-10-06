package org.openpatch.scratch4j.core.lint;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.compile.CompilerDiagnostic;
import org.openpatch.scratch4j.core.compile.CompilerService;
import org.openpatch.scratch4j.core.lint.DiagnosticsExplanations.Language;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;

import static org.assertj.core.api.Assertions.assertThat;

class FriendlyErrorsTest {

  @TempDir
  Path tmp;

  private final CompilerService compiler = new CompilerService();

  /** Compiles one class (named like the file) and describes its first error. */
  private FriendlyErrors.Friendly first(String className, String source, Language language)
      throws IOException {
    Path file = tmp.resolve(className + ".java");
    Files.writeString(file, source);
    Locale locale = language == Language.DE ? Locale.GERMAN : Locale.ROOT;
    List<CompilerDiagnostic> errors = compiler.compile(List.of(file), List.of(),
        tmp.resolve("out"), locale).errors();
    assertThat(errors).as("%s has errors", className).isNotEmpty();
    var context = new FriendlyErrors.Context(Files.readAllLines(file), className,
        name -> name.equals("Sprite") ? "import org.openpatch.scratch.Sprite;" : null,
        List.of("run", "whenClicked"));
    return new FriendlyErrors(language).describe(errors.get(0), context);
  }

  private FriendlyErrors.Friendly first(String className, String source) throws IOException {
    return first(className, source, Language.EN);
  }

  @Test
  void headlinesNameWhatIsWrongInTheStudentsCode() throws IOException {
    Map<String, String> expected = new java.util.LinkedHashMap<>();
    expected.put("class Semi { void go() { int x = 1\n } }", "A semicolon ; is missing here");
    expected.put("class Paren { void go() { if (true { } } }",
        "A closing bracket ) is missing here");
    expected.put("class Brace { void go() { int x = 1; }",
        "The file ends too early: a closing brace } is missing");
    expected.put("class Extra { void go() { } } }", "This code is outside of the class");
    expected.put("class Typo { void go() { int number = 1; System.out.println(numer); } }",
        "Java does not know a variable named numer");
    expected.put("class MTypo { void go() { this.og(); } }",
        "Java does not know a method named og()");
    expected.put("class TTypo { void go() { Strng s = null; } }",
        "Java does not know a class named Strng");
    expected.put("class NonStatic { void go() {} public static void main(String[] a) { go(); } }",
        "go() belongs to an object, but this code is static");
    expected.put("class Ctor { public Cotr() { } }",
        "Is Cotr meant to be the constructor? It must be named exactly Ctor");
    expected.put("class NoType { public go() { } }", "The method go has no return type");
    expected.put("class Ops { void go() { boolean b = \"a\" > 1; } }",
        "> does not work with String and int");
    expected.put("class VoidRet { void go() { return 1; } }",
        "This method is void, so it cannot return a value");
    expected.put("class Lossy { void go() { int x = 1.5; } }",
        "double does not fit into int without losing part of it");
    expected.put("class Cond { void go() { int x = 1; if (x = 2) {} } }",
        "= stores a value. To compare, write ==");
    expected.put("class Bool { void go() { int x = 1; if (x) {} } }",
        "A condition needs true or false, but this is int");
    expected.put("class Text { void go() { int x = \"42\"; } }", "Text cannot be used as int");
    expected.put("class Deref { void go() { int x = 1; x.toString(); } }",
        "int is a plain value and has no methods");
    expected.put("class Priv { void go() { new P2().secret(); } } class P2 { private void secret() {} }",
        "secret() is private in P2");
    expected.put("class Fin { void go() { final int x = 1; x = 2; } }",
        "x is final and cannot change");
    expected.put("class Dup { void go() { int x = 1; int x = 2; } }", "x already exists here");
    expected.put("class Uninit { int go() { int s; return s; } }", "s has no value yet");
    expected.put("class Args { void go(int s) { go(); } }", "go needs (int) but gets (nothing)");
    expected.put("class Str { void go() { String s = \"hello; } }",
        "This text is missing its closing \"");
    expected.put("class Hash { void go() { int x = 1 # 2; } }",
        "The character # does not belong in Java code");
    expected.put("class Quotes { void go() { String s = “hi”; } }",
        "Java needs straight quotes \"");
    expected.put("class Brk { void go() { break; } }", "break only works inside a loop or switch");
    expected.put("class Inner { void go() { void inner() { } } }",
        "A method cannot be inside another method");
    expected.put("class Ret { int go() { if (true) {} } }",
        "A return is missing at the end of this method");

    for (var e : expected.entrySet()) {
      String className = e.getKey().split("[\\s{]+")[1];
      var friendly = first(className, e.getKey());
      assertThat(friendly.title()).as(e.getKey()).isEqualTo(e.getValue());
      assertThat(friendly.explanation()).as("explanation for %s", e.getKey()).isNotBlank();
    }
  }

  @Test
  void aMissingImportNamesTheLine() throws IOException {
    var friendly = first("Player", "public class Player extends Sprite { }");
    assertThat(friendly.title()).isEqualTo("Sprite needs an import");
    assertThat(friendly.explanation()).contains("import org.openpatch.scratch.Sprite;");
  }

  @Test
  void aMisspelledOverrideSuggestsTheLibraryMethod() throws IOException {
    var friendly = first("Cat", """
        class Cat {
          @Override
          public void Run() { }
        }
        """);
    assertThat(friendly.title()).isEqualTo("Run() does not replace a method of the class it extends");
    assertThat(friendly.suggestions()).containsExactly("run");
  }

  @Test
  void germanHeadlinesComeFromTheEnglishMessage() throws IOException {
    assertThat(first("Semi", "class Semi { void go() { int x = 1\n } }", Language.DE).title())
        .isEqualTo("Hier fehlt ein Semikolon ;");
    assertThat(first("Typo", "class Typo { void go() { int n = 1; n = numer; } }", Language.DE)
        .title()).isEqualTo("Java kennt keine Variable namens numer");
    assertThat(first("Lossy", "class Lossy { void go() { int x = 1.5; } }", Language.DE).title())
        .isEqualTo("double passt nicht ohne Verlust in int");
  }

  @Test
  void unknownErrorsKeepJavacsMessage() throws IOException {
    Path file = tmp.resolve("Odd.java");
    Files.writeString(file, "class Odd { void go() { int x = 1; } }");
    var diagnostic = new CompilerDiagnostic(CompilerDiagnostic.Kind.ERROR, file.toString(), 1, 1,
        "compiler.err.some.future.code", "something new", "something new");
    var friendly = new FriendlyErrors(Language.EN).describe(diagnostic,
        new FriendlyErrors.Context(List.of(), "Odd", n -> null, List.of()));
    assertThat(friendly.title()).isEqualTo("something new");
    assertThat(friendly.explanation()).isNull();
  }

  @Test
  void everyFriendlyKeyExistsInBothLanguages() {
    var en = ResourceBundle.getBundle("org.openpatch.scratch4j.core.lint.explanations",
        Locale.ENGLISH);
    var de = ResourceBundle.getBundle("org.openpatch.scratch4j.core.lint.explanations",
        Locale.GERMAN);
    var enKeys = en.keySet().stream().filter(k -> k.startsWith("friendly.")).sorted().toList();
    var deKeys = de.keySet().stream().filter(k -> k.startsWith("friendly.")).sorted().toList();
    assertThat(enKeys).isNotEmpty().isEqualTo(deKeys);
  }
}
