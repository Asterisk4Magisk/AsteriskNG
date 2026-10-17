# AsteriskNG Development Guidelines

This document applies to this repository and all of its subdirectories. Current user instructions and any more deeply nested `AGENTS.md` take precedence.

## Top-Priority Principles

### Keep All Three Apps Consistent

AsteriskBOX, AsteriskNG, and AsteriskMETA belong to the same product family. When implementing shared capabilities, keep their code structure and UX consistent instead of creating three similar but subtly different implementations.

- Before making a change, search sibling repositories for equivalent screens, state, use cases, and runtime components. When the other apps are present in the workspace, proactively assess and apply all relevant changes to them.
- Shared code should use the same top-level layers: `app`, `data`, `engine`, `features`, `system`, `ui`, and `utils`. Equivalent files should use consistent relative paths, names, interfaces, state models, error semantics, log fields, and test locations.
- Keep information architecture, interaction order, component choices, copy semantics, loading/empty/error states, confirmation flows, notifications, quick settings, service controls, and accessibility behavior aligned across the apps.
- Confine product differences to core adapters, configuration compilation, and resource packaging boundaries. NG-specific Xray behavior belongs in dedicated areas such as `engine/xray`; do not spread it into shared UI or runtime flows.
- Do not invent unsupported capabilities in another app merely to create superficial consistency. When a difference is necessary, document its reason in the implementation, tests, or handoff notes.
- If a shared capability is changed only in this repository, explain why the other two apps are not affected or are intentionally deferred.

### Keep VPN and ROOT Strictly Separate

VPN Service is an independent non-ROOT execution path. Even on a rooted device, starting, running, and stopping VPN must produce no ROOT side effects.

- The VPN path must not request, probe, or cache ROOT access and must not execute root shell commands.
- The VPN path must not start, query, stop, or depend on `asteriskd`, and must not connect to `@asteriskd.control`.
- The VPN path must not publish ROOT configuration, manage ROOT boot scripts, or access ROOT state, logs, or binaries.
- The VPN path must not modify iptables/nftables, policy routing, sysctl, tether/dnsmasq, BPF, TC, or ROOT network interfaces.
- Missing ROOT access, a missing `asteriskd`, or conflicting ROOT resources must not affect VPN startup or shutdown.
- `engine/vpn` and VPN-only call chains must not depend on `engine/root`. Put reusable models and pure functions in side-effect-free shared layers.
- When switching from ROOT to VPN, any active ROOT cycle must be stopped by the ROOT lifecycle boundary before entering VPN. The VPN runtime itself must not perform ROOT cleanup.
- Changes to proxy orchestration, mode selection, or lifecycle handling must include a regression check that the entire VPN lifecycle performs zero ROOT operations.

## Repository Structure and Product Boundaries

- Application ID: `org.asterisk.zcc.ang`.
- Core: Xray; ROOT owner/core: `asteriskng` / `xray`.
- ROOT modes: TPROXY, TUN2SOCKS, and BPF2SOCKS.
- `app/`: Android application, Compose UI, data layer, VPN/ROOT orchestration, and core adapters.
- `asteriskd/`: the ROOT supervisor native submodule shared by all three apps.
- `bpfmatcher/`, `bpf2socks/`, and `hevtun/`: native helper modules.
- `enhance/`: optional AsteriskEnhance packaging; native Git submodule at `enhance/src/main/module`.
- `buildSrc/`: versions, package name, SDK levels, dependency versions, and build conventions.

Do not edit `.gradle/`, `build/`, module-level `build/` directories, or other generated output. Do not commit build artifacts as source files.

## asteriskd Constraints

The following rules apply only to ROOT/asteriskd changes. They do not justify expanding work into VPN or unrelated features.

- All three apps share the control endpoint `@asteriskd.control` and BPF namespace `/sys/fs/bpf/asterisk`; ROOT instances are mutually exclusive.
- Keep fixed shared resource names, including `ASTERISK_FAKE_IP_ICMP`, identical across all three apps. Do not reintroduce per-app or PID-derived namespaces.
- The control socket is the single-instance authority. Acquire its listener before any ROOT resource operation; do not replace this with state files, configuration files, or process scanning.
- Startup performs stateless reconciliation against the fixed Asterisk-owned catalog. It must not recover from a saved phase, configuration, owner, IPv6 setting, or matcher setting.
- `asteriskd.state` is telemetry only and must not drive startup cleanup or recovery.
- Original sysctl and tether/dnsmasq values exist only in the current supervisor's memory and are restored best-effort during graceful cleanup. Do not add persistent recovery paths.
- Do not add legacy-resource cleanup or migration. Ignore old per-app BPF paths and remnants such as `asteriskbox_hotspot_recovery_<number>`.
- Do not add scanning, adoption, or forced termination of child processes left after a crash. A visible port-conflict failure on the next launch is acceptable.
- With service control enabled, `stop` in a resident `monitor` supervisor ends only the active service cycle; only `shutdown` exits the supervisor. Related changes must cover this regression.
- When changing the `asteriskd` protocol, configuration schema, resource names, or native source, also check the AsteriskBOX and AsteriskMETA submodule versions, sources, and downstream Kotlin adapters so all three contracts remain aligned.

## Enhancement Module Constraints

These rules apply to AsteriskEnhance integration. See `enhance/src/main/module/README.md` for current dependencies, supported modes, installation, packaging, and boot recovery, and `enhance/src/main/module/docs/control-protocol.md` for the runtime contract. Changes to those designs must update the affected implementations, documentation, and checks together; current version numbers and compatibility matrices are not permanent restrictions.

- Keep shared module identity, application integration, supervisor adapters, and packaging contracts consistent across all three apps. Coordinate compatibility changes across the affected components; do not silently introduce app-specific variants of shared contracts.
- Preserve VPN/ROOT separation. Check feature eligibility before creating or invoking a ROOT backend, and keep module inspection, installation, and policy control out of the VPN lifecycle. Enforce the current mode and DNS prerequisites at the appropriate boundaries.
- Preserve the requesting application's full Android UID and resolver-cache isolation across networks and policy generations. Require runtime capability and resolver-readiness checks before activation; never report unsafe or failed preparation as active enhancement.
- Use read-only runtime information to determine the loaded module's availability and version. Disk files may support installation diagnostics but do not prove runtime activation. Keep application/native compatibility checks aligned and distinguish frame protocol versions from capability revisions. Update ordering uses `versionCode`, not version text or build identifiers; a newer version still needs compatibility checks.
- Restrict policy control to the authenticated supervisor owning the current `@asteriskd.control` listener. Preserve explicit withdrawal, disconnect cleanup, and cache invalidation when resolver configuration changes. Under the current connection-scoped contract, do not add periodic renewal or restore prior active policies from disk. Ordinary UI persistence must never authorize or reconstruct runtime policy.
- Require user confirmation for installation and updates, expose installation failures, and distinguish pending reboot from a running module. Never reboot automatically or treat a saved UI status as evidence that the module is active.
- Keep SELinux enforcing and permissions narrowly scoped. Preserve the documented packaging and system-ABI selection contract. Missed-injection recovery belongs to the module's documented boot lifecycle, not application startup or VPN; hook or resolver failures alone must not trigger netd recovery. Changes to DNS ingress, dependency bundling, or recovery timing require a corresponding design and compatibility update.
- Scale verification to the affected behavior: documentation changes need diff checks; UI/coordinator changes need relevant state, cancellation, and ROOT-guard checks; packaging changes need builds and payload inspection. Protocol, resolver, or native-hook changes require relevant device scenarios for routing, cache isolation, failure handling, and cleanup. Host checks alone cannot establish device hook compatibility; record any unverified scenarios.

## Implementation and Change Discipline

- Read the existing implementation and tests first, then make the smallest change that satisfies the requirement. Do not rewrite unrelated code opportunistically.
- Follow the existing Kotlin, Compose, Gradle Kotlin DSL, and C styles. Keep names explicit, functions focused, and dependency direction clear.
- Model shared behavior behind consistent interfaces instead of copying large blocks with small app-specific differences.
- Errors must remain diagnosable, but logs, state, and control responses must not expose configuration content, keys, AGE secrets, or private command-line arguments.
- Check `git status --short` before and after work. Preserve existing user changes and submodule state; do not use destructive reset or checkout operations.
- Do not commit, push, or open a PR unless the user explicitly asks.

## Build and Static Checks

Use the repository's Gradle wrapper on Windows/PowerShell. When native submodules contain local changes, exclude version-sync tasks so the build cannot automatically check them out:

```powershell
.\gradlew.bat :app:test :app:lintDebug :app:assembleDebug `
  -x :asteriskd:syncAsteriskdVersion `
  -x :bpfmatcher:syncBpfMatcherVersion `
  -x :bpf2socks:syncBpf2SocksVersion `
  -x :hevtun:syncHevSocks5TunnelVersion `
  -x :enhance:syncEnhanceVersion
```

- Enhancement packaging changes must build `:enhance:assembleDebug` and `:app:assembleDebug`; inspect APK installer entries and all four ABI asset payloads. Use `-PenhanceSourceDir=<path>` for an alternate source checkout. Exclude `:enhance:syncEnhanceVersion` when preserving local submodule changes.
- When changing another module, add that module's `test`, `lintDebug`, and `assembleDebug` tasks. ROOT/native changes must at least build `:asteriskd:assembleDebug` and `:app:assembleDebug`.
- Keep lint clean. Do not hide new findings with a baseline, broad suppressions, or disabled rules.
- Documentation-only changes require at least `git diff --check`; do not trigger a full Android build without a technical reason.
- Sign release builds only when the user requests device verification. Treat the keystore path, passwords, and alias as external secrets; never persist them in the repository, scripts, logs, or documentation.

## Test Requirements

Scale verification to the affected surface. Verify shared behavior in all three apps, and never treat host/Gradle tests alone as sufficient proof of ROOT behavior.

- VPN regression: start, run, and stop VPN without invoking any ROOT capability. Confirm there are no `asteriskd` control requests, ROOT permission requests, ROOT file publications, or ROOT network-resource changes.
- ROOT matrix: cover every ROOT mode supported by this app with IPv6 both enabled and disabled.
- bpfmatcher: test matcher-supported modes with the matcher both enabled and disabled. For unsupported modes, verify it cannot be enabled incorrectly or alter behavior.
- Service control: cover ordinary startup and the resident supervisor used when service control is enabled; verify `stop`, another start, and `shutdown`.
- Lifecycle: at minimum cover startup, status, graceful stop, duplicate-start/mutual exclusion, failure exit, and subsequent startup.
- Cross-app exclusion: when another `asteriskd` owner is active, the app must report a clear foreign-owner conflict and must not seize resources.

For device testing on an authorized adb device:

```powershell
adb root
adb shell id -u
```

After `adb root`, use `adb shell` directly; do not wrap commands in `su`. Stop services or clean device state only within the user's authorization. Touch only resources clearly owned by the current test, and do not remove unknown or legacy remnants. At the end, record the APK, device, scenarios, and results, and restore the normal service state when practical.
