#!/usr/bin/env bash
set -euo pipefail
./gradlew wrapper --gradle-version latest --distribution-type all
DIST_URL=$(grep distributionUrl gradle/wrapper/gradle-wrapper.properties | cut -d= -f2 | sed 's/\\//g')
CHECKSUM=$(curl -sL "${DIST_URL}.sha256")
sed -i '' "s/distributionSha256Sum=.*/distributionSha256Sum=${CHECKSUM}/" gradle/wrapper/gradle-wrapper.properties
echo "Updated wrapper checksum: ${CHECKSUM}"
