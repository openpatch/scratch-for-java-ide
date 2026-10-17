# Measured compiler explanation coverage

Baseline: Java 25, `FriendlyErrorsCoverageTest`, 20 curated mistakes.
19/20 received a friendly case. `unclosed-character` fell through to javac with
`compiler.err.unclosed.char.lit`. `mismatched-bracket` received `expected` but
its headline had no opening line. Both are addressed by milestone 2.

The coverage test compiles every broken and fixed program and writes remaining
raw fallbacks to `core/target/friendly-errors-gaps.txt`. This is corpus coverage,
not a claim about all javac diagnostics. Add newly observed mistakes to the same
corpus before extending explanations. Run `mvn -pl core test
-Dtest=FriendlyErrorsCoverageTest` to repeat the measurement.

The user-reported `public class Welt exends Spielwelt {` is also a regression
case. Javac reports `compiler.err.expected` (`'{' expected`), but the friendly
case must identify the spelling of `extends`. This checks explanation quality
as well as raw-message coverage. The reported `public cass Welt extends ...`
and `pulic class Welt extends ...` are also regression cases, with
`keyword.class` and `keyword.public` explanations. The corpus now has 23 entries.

Declaration spelling checks use the surrounding tokens and a unique keyword
one edit away (missing, extra, replaced or transposed letters, or incorrect
case). Tests also cover incomplete headers, comments, strings, annotations,
generic types, and legitimate identifiers such as `Class`. This does not imply
that every typo anywhere in Java can be inferred safely.
