# Changelog

## 1.2.2-19.70

- Support JEI 19.70.0.469 (requires MezzConfig 0.6.10+)
- Fix `VerifyError` crash in `AnvilHelper.setAnvilMenu` when opening JEI in a world: the anvil mixin no longer reads the target's arguments at `RETURN`, since JEI now reuses those local slots in its catch handler
- Fix `VerifyError` in `ElementSearch.<init>` (JEI would not start its GUI): the constructor mixin now reads the parser from the field instead of the reused argument slots
- Dev environment: MezzConfig 0.6.10

## 1.2.1-19.64

- Support JEI 19.64.0.455 (requires NeoForge 21.1.238+ and MezzConfig 0.6.3+)
- Adapt `ElementSearch` mixin to JEI's new per-`ElementSearchIndex` rebuild
- Dev environment: NeoForge 21.1.240, MezzConfig 0.6.8
