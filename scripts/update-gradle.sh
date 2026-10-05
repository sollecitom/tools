#!/usr/bin/env bash
set -euo pipefail

props_file="gradle/wrapper/gradle-wrapper.properties"
current_url=$(grep '^distributionUrl=' "$props_file" | cut -d= -f2- | sed 's/\\//g')
current_checksum=$(grep '^distributionSha256Sum=' "$props_file" | cut -d= -f2- || true)

latest_metadata=$(curl -fsSL https://services.gradle.org/versions/current)
latest_version=$(printf '%s\n' "$latest_metadata" | sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')
[ -n "$latest_version" ]

updated_url="https://services.gradle.org/distributions/gradle-${latest_version}-all.zip"
updated_checksum=$(curl -fsSL "${updated_url}.sha256")

./gradlew \
    wrapper \
    --gradle-version "$latest_version" \
    --distribution-type all \
    --gradle-distribution-sha256-sum "$updated_checksum" \
    --no-validate-url

if grep -q '^validateDistributionUrl=' "$props_file"; then
    sed -i '' 's/validateDistributionUrl=.*/validateDistributionUrl=true/' "$props_file"
else
    printf '\nvalidateDistributionUrl=true\n' >> "$props_file"
fi

if [ "$current_url" = "$updated_url" ]; then
    exit 0
fi

if [ "$current_checksum" = "$updated_checksum" ]; then
    exit 0
fi

echo "Updated wrapper checksum: ${updated_checksum}"
