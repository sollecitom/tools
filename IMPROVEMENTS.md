# tools

## Overview
Development utilities — currently contains a single "package-migrator" tool for refactoring package names across Gradle/JVM projects.

## Scorecard

| Dimension | Rating | Notes |
|-----------|--------|-------|
| Build system | A | Gradle 9.4.0, consistent conventions |
| Code quality | B+ | Clean DDD, value classes, good patterns |
| Test coverage | F | Zero tests despite highly testable domain |
| Documentation | C | README with build instructions, no usage docs |
| Dependency freshness | A | All current |
| Modularity | A | 2 modules (domain + app), clean separation |
| Maintainability | B | 186 LOC, small but untested |

## Structure
- 2 modules: `package-migrator/domain` (6 files, ~170 LOC), `package-migrator/app` (1 file, 18 LOC)
- 7 Kotlin files total

## Issues
- Zero unit tests — domain logic is highly testable (value classes, pure functions)
- No error handling on file operations (Path operations can throw)
- No dry-run mode — applies migrations immediately
- Hardcoded excluded folders (`.git`, `build`, `.kotlin`, `gradle`)
- App layer hardcodes migration rules — not configurable
- Logging via `companion object : Loggable()` couples domain to logging

## Potential Improvements
1. Add unit tests for `Package`, `Directory`, `StaticPackageMigration`
2. Implement dry-run mode with preview output
3. Add CLI argument parsing (picocli) instead of hardcoded rules
4. Make excluded folders configurable
5. Add rollback capability (snapshot state before applying)
6. Add error handling with custom exceptions
7. Support Java module names (jigsaw syntax)
