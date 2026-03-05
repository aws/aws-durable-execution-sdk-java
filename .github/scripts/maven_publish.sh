
#!/bin/bash
# publish-maven.sh
# Builds, signs, and publishes to Maven Central using central-publishing-maven-plugin
set -euo pipefail

SETTINGS_FILE="./settings.xml"

# Auto-cleanup settings.xml on exit (success or failure)
trap 'echo "Cleaning up settings.xml..."; rm -f "${SETTINGS_FILE}"' EXIT

echo "=== Step 1: Format GPG private key ==="

BEGIN_MARKER="-----BEGIN PGP PRIVATE KEY BLOCK-----"
END_MARKER="-----END PGP PRIVATE KEY BLOCK-----"
MIDDLE="${MVN_GPG_KEYS_GPGPRIVATEKEY#*$BEGIN_MARKER}"
MIDDLE="${MIDDLE%$END_MARKER*}"

MIDDLE=$(echo "$MIDDLE" | tr ' ' $'
')

export MAVEN_GPG_KEY="${BEGIN_MARKER}
${MIDDLE}
${END_MARKER}"

export MAVEN_GPG_PASSPHRASE="${MVN_GPG_KEYS_GPGPASSPHRASE}"

echo "=== Step 2: Write minimal settings.xml ==="
cat > "${SETTINGS_FILE}" <<EOF
<settings>
  <servers>
    <server>
      <id>central</id>
      <username>${MVN_ACCOUNT_KEYS_USERNAME}</username>
      <password>${MVN_ACCOUNT_KEYS_PASSWORD}</password>
    </server>
  </servers>
</settings>
EOF

echo "settings.xml written."

echo "=== Step 3: Deploy to Maven Central ==="

mvn clean deploy -s "${SETTINGS_FILE}" -pl sdk -P publishing -DskipTests --no-transfer-progress
mvn clean deploy -s "${SETTINGS_FILE}" -pl sdk-testing -P publishing -DskipTests --no-transfer-progress

echo "=== Release ${RELEASE_VERSION} published successfully! ==="
