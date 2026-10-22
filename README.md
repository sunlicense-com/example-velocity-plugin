# Velocity plugin with SunLicense

A working, copyable [Velocity](https://papermc.io/software/velocity) proxy plugin with
[SunLicense](https://sunlicense.com) built in: each proxy needs a valid license key, leaked keys can be
revoked or blocked, and your customers' networks keep running if SunLicense is ever unreachable.

- Gradle + Shadow, the official [Java SDK](https://github.com/sunlicense-com/sunlicense-java) relocated into your package
- License key from **config.properties**, or built in by **BuiltByBit / Voxel Shop** placeholders
- Clear console messages for every result (expired, revoked, too many proxies, blacklisted, …), keys masked
- Re-checked every few hours, so a revoke reaches running proxies (features switch off)
- **Offline fallback** with a signed license file (Ed25519, verified without network)
- Tests against a stub server, CI on GitHub Actions, and [AGENTS.md](AGENTS.md) for AI coding assistants

Runs on Velocity 3.5 (Java 21) and Velocity 4.x (Java 25); built against the 3.5 API with Java 21.
The same license code works in a BungeeCord or Waterfall plugin: only `MyPlugin.java` changes.

## 1. Set up SunLicense

In the [dashboard](https://app.sunlicense.com):

1. **Products → New product** (your plugin), then a **policy**, e.g. "Network", *1 machine*: a machine is a proxy here.
2. **API keys → Create API key**. It can only validate licenses, so it's safe inside a jar.
3. Optional, for the offline fallback: open any license and copy **Public key**.

## 2. Make it yours

1. Use this template (or copy the folder), then rename `com.example.myplugin`, `MyPlugin`, the `@Plugin`
   id and `my-plugin` (`settings.gradle.kts`, the Java package).
2. Put the API key in your **user** Gradle properties (`~/.gradle/gradle.properties`), never in git:

   ```properties
   sunlicenseApiKey=slk_...
   sunlicensePublicKey=MCowBQYDK2VwAyEA...
   ```

   For a local SunLicense stack add `sunlicenseUrl=http://localhost`.
3. Build: `./gradlew build` → `build/libs/my-plugin-1.0.0.jar` (Java 21 needed; Gradle downloads itself).
4. Try it: `./gradlew runVelocity` starts a local Velocity proxy with the plugin (`end` stops it). Put a
   test license key in `run/plugins/myplugin/config.properties` and run `myplugin recheck` in its console.

Your features go in `MyPlugin.startFeatures()`, which runs once the license is valid, and are switched
off again in `stopFeatures()` if the license is revoked later (Velocity can't unload a plugin).

## 3. Sell it

| Where | How the key gets to the proxy |
|---|---|
| [BuiltByBit](https://docs.sunlicense.com/integrations/builtbybit), [Voxel Shop](https://docs.sunlicense.com/integrations/voxel-shop) | Create a `%%__SUNLICENSE__%%` placeholder in the store. It replaces `BUILT_IN_KEY` in `MyPlugin` with each buyer's key on download, so there's nothing to configure |
| [SpigotMC](https://docs.sunlicense.com/integrations/spigotmc), [Tebex](https://docs.sunlicense.com/integrations/tebex), [Gumroad](https://docs.sunlicense.com/integrations/gumroad) and the other stores | The buyer gets a key by email or on the redeem page and puts it in `config.properties` |

## What happens on a proxy

| SunLicense answers | The plugin |
|---|---|
| Valid | Starts your features; logs the masked key |
| Expired, revoked, suspended, unknown key, too many proxies, blacklisted | Logs why (see `license/Messages.java`) and keeps (or switches) your features off |
| Nothing (outage, network, rate limit) | Uses `plugins/myplugin/license.lic` if it's a valid offline license for this key; otherwise keeps running and retries every 10 minutes (`ALLOW_WHEN_UNREACHABLE = false` to stop instead) |

Each proxy keeps a random id in `plugins/myplugin/.server-id`, which is what counts against the policy's
machine limit. `/myplugin` shows the license status; `/myplugin recheck` re-reads the key and checks
again (permission `myplugin.admin`, and the console).

Developing against your own license? Add an **exclusion** for your machine or IP in SunLicense
([blacklists and exclusions](https://docs.sunlicense.com/guide/access-rules)) so your test proxies
don't use up slots.

## Files

```
build.gradle.kts                          Velocity API, SDK, Shadow relocation, settings baked into the jar
src/main/java/.../MyPlugin.java           start-up, re-checks, the command, your features
src/main/java/.../license/LicenseGuard.java   online check → offline file → status (no Velocity, tested)
src/main/java/.../license/OfflineLicense.java Ed25519 offline license file check
src/main/java/.../license/Messages.java   what the proxy owner reads
src/main/resources/config.properties      license-key, recheck-hours
src/test/java/.../LicenseGuardTest.java   every result against a stub SunLicense
.github/workflows/build.yml               CI
```

## Licence

[MIT No Attribution](LICENSE): copy any of it into your plugin, commercial or not, without credit.
