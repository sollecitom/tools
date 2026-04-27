#!/usr/bin/env just --justfile

set quiet

reset-all:
    git fetch origin && git reset --hard origin/main && git clean -f -d

push:
    git add -A && (git diff --quiet HEAD || git commit -am "WIP") && git push origin main

pull:
    git pull

build:
    ./gradlew updateInternalCatalogVersions && ./gradlew build

license-audit-workspace +repos:
    bash ../scripts/run-license-audit.sh {{repos}}

license-audit:
    bash ../scripts/run-license-audit.sh tools

generate-sbom:
    bash ../scripts/run-generate-sbom.sh tools

cleanup:
    bash ../scripts/cleanup-maven-local.sh --repo-root . --keep 2 --max-age-days 14

update-internal-dependencies:
    ./gradlew updateInternalCatalogVersions

rebuild:
    ./gradlew --refresh-dependencies --rerun-tasks clean build

update-dependencies:
    ./gradlew versionCatalogUpdate

@update-gradle:
    ./scripts/update-gradle.sh

update-all:
    just update-internal-dependencies && just update-dependencies && just update-gradle

publish-libraries:
    ./gradlew build publishToMavenLocal

workflow +steps:
    bash ../scripts/run-just-workflow.sh {{steps}}
