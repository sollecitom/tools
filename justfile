#!/usr/bin/env just --justfile

resetAll:
    git fetch origin && git reset --hard origin/main && git clean -f -d

push:
    git add . && git commit -m "WIP" && git push origin main

pull:
    git pull

build:
    ./gradlew build

rebuild:
    ./gradlew --refresh-dependencies --rerun-tasks clean build

updateDependencies:
    ./gradlew versionCatalogUpdate

updateGradle:
    ./gradlew wrapper --gradle-version latest --distribution-type all

updateAll:
    just updateDependencies && just updateGradle

publishLibraries:
    ./gradlew build publishToMavenLocal