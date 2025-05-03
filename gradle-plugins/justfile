#!/usr/bin/env just --justfile

push:
    git add . && git commit -m "WIP" && git push --recurse-submodules=on-demand origin main

pull:
    git submodule update --recursive --remote

build:
    ./gradlew build

rebuild:
    ./gradlew clean build --refresh-dependencies --rerun-tasks

updateDependencies:
    ./gradlew versionCatalogUpdate

updateGradle:
    ./gradlew wrapper --gradle-version latest --distribution-type all

updateAll:
    just updateDependencies && just updateGradle