#!/usr/bin/env bash
set -euo pipefail

props_file="gradle/wrapper/gradle-wrapper.properties"
current_url=$(grep '^distributionUrl=' "$props_file" | cut -d= -f2- | sed 's/\\//g')
current_checksum=$(grep '^distributionSha256Sum=' "$props_file" | cut -d= -f2- || true)

./gradlew wrapper --gradle-version latest --distribution-type all
updated_url=$(grep '^distributionUrl=' "$props_file" | cut -d= -f2- | sed 's/\\//g')

if [ "$current_url" = "$updated_url" ]; then
    exit 0
fi

updated_checksum=$(curl -fsSL "${updated_url}.sha256")
if [ "$current_checksum" = "$updated_checksum" ]; then
    exit 0
fi

if grep -q '^distributionSha256Sum=' "$props_file"; then
    sed -i '' "s/distributionSha256Sum=.*/distributionSha256Sum=${updated_checksum}/" "$props_file"
else
    printf '\ndistributionSha256Sum=%s\n' "$updated_checksum" >> "$props_file"
fi

echo "Updated wrapper checksum: ${updated_checksum}"
