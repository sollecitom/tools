# Project Package Migrator

A tool to migrate packages for JVM projects, including moving files and deleting empty directories.

## When do I need this?

IntelliJ IDEA is shockingly poor at migrating packages in JVM projects. Until they get their act together, you might need this when:

1. You want to clone a service template, and then change the name of the service as it appears in the packages to the name of your new service.
2. You want to fix a typo within a package, after spotting it while working on a project.
3. You want to refactor a hierarchy of libaries.

## Usage

1. Ensure the project you want to migrate is up-to-date with your remote branch e.g. "main", without any local changes.
2. Edit `migrations` in `./app/src/main/kotlin/sollecitom/tools/package_migrator/app/PackageMigrator.kt` with the package mappings you want e.g. `packageMigrations("sollecitom.example.command_endpoint" to "sollecitom.example.another_endpoint")`. You can batch multiple package migrations in the same invocation; run the most specific ones first.
3. Commit locally, without pushing upstream.
4. Run `PackageMigrator.kt` with `--project <dir>`, the root folder of the project you want to migrate (without it, the working directory is migrated).
5. Check the state of the project to ensure it's now what you intended it to be.
6. If it's all good, commit and push.
7. If anything is wrong, restore your workspace to the original upstream branch e.g., by running `git fetch origin && git reset --hard origin/main && git clean -f -d` if you were on the main branch.

Packages are matched on whole segments only, both in file contents and in paths: migrating `a.b.c` leaves `a.b.common` and `xa.b.c` alone, while `a.b.c.d` (a subpackage) is migrated. Directories named `build`, `gradle`, `.git`, `.gradle`, `.kotlin` and `.idea` are never rewritten or moved.
