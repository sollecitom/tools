# Project Package Migrator

A tool to migrate packages for JVM projects, including moving files and deleting empty directories.

## When do I need this?

IntelliJ IDEA is shockingly poor at migrating packages in JVM projects. Until they get their act together, you might need this when:

1. You want to clone a service template, and then change the name of the service as it appears in the packages to the name of your new service.
2. You want to fix a typo within a package, after spotting it while working on a project.
3. You want to refactor a hierarchy of libaries.

## Usage

1. Ensure the project you want to migrate is up-to-date with your remote branch e.g. "main", without any local changes.
2. Modify your local `./app/src/main/kotlin/sollecitom/tools/project_package_migrator/app/ProjectPackageMigrator.kt`, changing the following:
    1. `targetProjectRootDirectory`: the absolute path for the root folder of the project you want to migrate e.g. `"/Users/michele/workspace/kotlin-monorepo-monolith-example/example/command-endpoint"`.
    2. `migrations`: the specific package mappings you want to migrate e.g. `packageMigrations("sollecitom.example.command_endpoint" to "sollecitom.example.another_endpoint")`. You can batch multiple package migrations in the same invocation, with the note that having the most specific migrations run first is a good idea in this case.
3. Commit locally, without pushing upstream.
4. Run/Debug `./app/src/main/kotlin/sollecitom/tools/project_package_migrator/app/ProjectPackageMigrator.kt`.
5. Check the state of the project to ensure it's now what you intended it to be.
6. If it's all good, commit and push.
7. If anything is wrong, restore your workspace to the original upstream branch e.g., by running `git fetch origin && git reset --hard origin/main && git clean -f -d` if you were on the main branch. 