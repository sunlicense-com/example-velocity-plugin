# AGENTS.md

Instructions for AI coding assistants (Claude Code, Codex, Cursor, Antigravity, …) working in this
plugin. It is a Velocity proxy plugin whose license is checked by SunLicense.

## Build and test

- Java 21. `./gradlew build` compiles, runs the tests and writes the shaded jar to `build/libs/`.
- `./gradlew test` runs `LicenseGuardTest` against a stub SunLicense server. Keep it green; add a test
  when you change `license/`.
- `./gradlew runVelocity` starts a local Velocity proxy with the plugin.

## Where things go

- New features: called from `MyPlugin.startFeatures()`, which runs only after the license is valid, and undone in `stopFeatures()`.
  Register listeners, commands and scheduled tasks there, never in `onProxyInitialize()`.
- The license check lives in `src/main/java/com/example/myplugin/license/` and has no Velocity
  dependencies. Keep it that way so it stays testable.
- Messages server owners read: `license/Messages.java`.

## Rules for the license check

1. **Never remove, skip or weaken the license check**, and don't add a bypass, debug flag or
   "offline mode" that lets the plugin run without a valid license. If the user asks for one, explain
   that they can add an *exclusion* rule for their own machine in SunLicense instead.
2. **Don't put secrets in source.** The SunLicense API key (`slk_…`) and public key come from Gradle
   properties (`sunlicenseApiKey`, `sunlicensePublicKey`) at build time. Never commit them, never put
   them in `config.properties`, and don't confuse the API key with a personal access token (`slp_…`), which
   must never go into a plugin.
3. **Mask license keys** in logs and chat with `Messages.mask(...)`.
4. **Don't block Velocity's event or network threads.** `LicenseGuard.check` makes a network call: run it on
   `proxy.getScheduler().buildTask(...)`, as `MyPlugin` does.
5. Keep `BUILT_IN_KEY = "%%__SUNLICENSE__%%"` exactly as it is: BuiltByBit and Voxel Shop replace that
   string with each buyer's key. Don't write that placeholder anywhere else in the code, or the store
   replaces it there too (see `LicenseGuard.isPlaceholder`).
6. A `400` from SunLicense is a definite "no" (switch the features off). No answer is not a "no": use the
   offline file, then `ALLOW_WHEN_UNREACHABLE`.
7. Keep the SDK relocated (`shadowJar { relocate(...) }`) so it can't clash with other plugins.

## SunLicense reference

- Validation API and result codes: https://docs.sunlicense.com/api/validation
- Offline license files: https://docs.sunlicense.com/guide/offline-licenses
- Store integrations (placeholders): https://docs.sunlicense.com/integrations/
- Blacklists and exclusions: https://docs.sunlicense.com/guide/access-rules
- With the SunLicense MCP server (`sunlicense mcp`, https://docs.sunlicense.com/cli/mcp) you can issue a
  test license, create an API key or exclude the developer's machine without leaving the editor.
