#!/usr/bin/env bash
# Install Java, Maven, PostgreSQL (+ AWS Emulator and AWS CLI if missing), then build the jar.
set -euo pipefail
cd "$(dirname "$0")/.."

ARCH="$(dpkg --print-architecture)"
case "$ARCH" in amd64) CLI_ARCH=x86_64 ;; arm64) CLI_ARCH=aarch64 ;; *) echo "Unsupported: $ARCH"; exit 1 ;; esac

echo "==> Installing Java 21, Maven and PostgreSQL"
sudo apt-get update -qq
sudo apt-get install -y -qq openjdk-21-jdk-headless maven postgresql postgresql-client unzip >/dev/null
java -version 2>&1 | head -1
psql --version

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
if ! command -v aws-emulator >/dev/null; then
  echo "==> Installing AWS Emulator"
  curl -fsSLo "$TMP/aws-emulator.deb" \
    "https://downloads.aws.com/aws-emulator/packages/aws-emulator/LTS/latest/aws-emulator_${ARCH}.deb"
  sudo apt-get install -y "$TMP/aws-emulator.deb"
fi
if ! command -v aws >/dev/null; then
  echo "==> Installing AWS CLI v2"
  curl -fsSLo "$TMP/awscliv2.zip" "https://awscli.amazonaws.com/awscli-exe-linux-${CLI_ARCH}.zip"
  unzip -q "$TMP/awscliv2.zip" -d "$TMP" && sudo "$TMP/aws/install"
fi

echo "==> Building the jar (first time downloads dependencies, ~1-2 min)"
mvn -q -B package -DskipTests
ls -lh target/eligibility-loader.jar

echo
echo "Done. Next: start aws-emulator in another terminal, then:"
echo "  source scripts/env.sh && bash scripts/setup.sh"
