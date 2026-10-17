# Compiler snippet corpus, schema 1

`corpus.json` contains a `v` and a `snippets` array. Each entry has a stable `id`,
`className`, complete compilable `fixed` source, complete erroneous `broken`
source, a one-based `brokenLine` for the handout, and `caseKey` (the first
friendly case, or null for a measured raw-message gap). No student data belongs
here. The coverage test compiles both versions; the same entries supply
analogous hint examples and the printable handouts. Maven packages this
directory as `/lint/snippets/` for those two uses.
