# Gradle plugins

A set of Gradle plugins for other projects to use.

## Requirements

1. Java 23 (neither below nor above).

## How to

### Build the project (incrementally)

```bash
just build

```

### Rebuild the project (without caches)

```bash
just rebuild

```

### Upgrade the Gradle wrapper to the latest available version

```bash
just updateGradle

```

### Update all dependencies if more recent versions exist, and remove unused ones (it will update `gradle/libs.versions.toml`)

```bash
just updateDependencies

```

## Use

In another project:

In `settings.gradle`:

``
includeBuild 'gradle-plugins'
``

In `build.gradle`:

``
buildscript {
    dependencies {
        classpath 'sollecitom:gradle-plugins:0'
        }
    }
``