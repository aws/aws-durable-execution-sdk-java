
#!/bin/bash
# publish-maven.sh
# Builds, signs, and publishes to Maven Central using central-publishing-maven-plugin
#
# Required environment variables:
#   GPG_PRIVATE_KEY  - GPG private key content (from AWS Secrets Manager)
#   GPG_PASSPHRASE   - GPG key passphrase (from AWS Secrets Manager)
#   MAVEN_USERNAME   - Maven Central portal token username (from AWS Secrets Manager)
#   MAVEN_PASSWORD   - Maven Central portal token password (from AWS Secrets Manager)
#   RELEASE_VERSION  - The version being released (e.g. 0.6.0-beta)
source .env
set -euo pipefail

SETTINGS_FILE="./settings.xml"

# Auto-cleanup settings.xml on exit (success or failure)
trap 'echo "Cleaning up settings.xml..."; rm -f "${SETTINGS_FILE}"' EXIT

echo "=== Step 1: Import GPG private key ==="
printf '%s' "${GPG_PRIVATE_KEY}" | gpg --batch --import
echo "GPG key imported successfully."

echo "=== Step 2: Write minimal settings.xml ==="
cat > "${SETTINGS_FILE}" <<EOF
<settings>
  <servers>
    <server>
      <id>central</id>
      <username>${MAVEN_USERNAME}</username>
      <password>${MAVEN_PASSWORD}</password>
    </server>
  </servers>
</settings>
EOF

echo "settings.xml written."

echo "=== Step 3: Build artifacts ==="
mvn clean install --no-transfer-progress

echo "=== Step 4: Deploy to Maven Central ==="

mvn clean deploy -s "${SETTINGS_FILE}" -pl sdk -P publishing -DskipTests -Dgpg.passphrase="${GPG_PASSPHRASE}"
mvn clean deploy -s "${SETTINGS_FILE}" -pl sdk-testing -P publishing -DskipTests -Dgpg.passphrase="${GPG_PASSPHRASE}"

echo "=== Release ${RELEASE_VERSION} published successfully! ==="
