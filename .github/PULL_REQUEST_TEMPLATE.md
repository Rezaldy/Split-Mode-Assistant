## What changed and why

<!-- Describe the change and the reasoning behind it. -->

## Verification

- [ ] `./gradlew build`
- [ ] Monolithic `runIde`
- [ ] Split mode run configuration
- [ ] `./gradlew verifyPlugin` (when plugin.xml/descriptors/platform APIs changed)

## New external dependency?

Justification: (or "none")

## Docs

- [ ] `DOCUMENTATION.md` updated if the app's definition changed
- [ ] `README.md` updated if setup, requirements, or headline features changed
- [ ] Plugin version bumped if any `@Rpc` interface or DTO changed (wire-contract rule)
