# Environment for the batch lab. Must be *sourced*:  source scripts/env.sh

# --- AWS Emulator (same values as the official docs) ---
EMU_PORT="${EMU_PORT:-9297}"
EMU_DIR="$HOME/.aws/emulator/$EMU_PORT"
export AWS_ENDPOINT_URL="https://127.0.0.1:$EMU_PORT"
export AWS_SHARED_CREDENTIALS_FILE="$EMU_DIR/emulator_credentials"
export AWS_CONFIG_FILE="$EMU_DIR/config"
export AWS_CA_BUNDLE="$EMU_DIR/ca.pem"
export AWS_EC2_METADATA_DISABLED=true
export AWS_DISABLE_HOST_PREFIX_INJECTION=true
export AWS_REGION=us-east-1
export AWS_DEFAULT_REGION=us-east-1
unset AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN AWS_PROFILE
export NO_PROXY="127.0.0.1,localhost${NO_PROXY:+,$NO_PROXY}"
export no_proxy="$NO_PROXY"

# --- Job settings ---
export INBOUND_BUCKET=inbound-files
export DB_URL="jdbc:postgresql://127.0.0.1:5432/eligibility?connectTimeout=5&socketTimeout=60"
export DB_USER=batch
export DB_PASSWORD=batch
export MAX_REJECT_RATIO=0.10

if [ -f "$AWS_CA_BUNDLE" ]; then
  echo "OK: S3 -> $AWS_ENDPOINT_URL | DB -> 127.0.0.1:5432/eligibility"
else
  echo "WARNING: $AWS_CA_BUNDLE not found. Is aws-emulator running?"
fi
