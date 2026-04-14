#!/usr/bin/env just --justfile

set quiet

reset-all:
    git fetch origin && git reset --hard origin/main && git clean -f -d

push:
    git add -A && (git diff --quiet HEAD || git commit -am "WIP") && git push origin main

pull:
    git pull

build:
    ./gradlew build

rebuild:
    ./gradlew --refresh-dependencies --rerun-tasks clean build

update-dependencies:
    ./gradlew versionCatalogUpdate

@update-gradle:
    ./scripts/update-gradle.sh

update-all:
    just update-dependencies && just update-gradle

publish-libraries:
    ./gradlew build publishToMavenLocal
