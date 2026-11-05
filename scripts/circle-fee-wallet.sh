#!/usr/bin/env bash
# Creates Circaso's fee wallet: a Circle developer-controlled wallet (the backend holds it, the admin manages it).
# Prints only ids and addresses; never prints the API key, the entity secret or its ciphertext.
#
# Needs: CIRCLE_API_KEY (from the backend's .env), and the 32-byte hex entity secret, either in CIRCLE_ENTITY_SECRET or
# in a file named by CIRCLE_ENTITY_SECRET_FILE. Optional: BLOCKCHAINS (default BASE-SEPOLIA), ENV_FILE to load.
#
# First run on a Circle account registers the entity secret and writes Circle's recovery file next to this script as
# circle-recovery-<date>.dat (mode 600): move it to a password manager; it's the only way to reset the secret.
set -euo pipefail

# Only the one setting this needs is taken from ENV_FILE (the file is not sourced: it may hold lines a shell can't run).
setting() { sed -n "s/^[[:space:]]*\(export[[:space:]]\{1,\}\)\{0,1\}$1[[:space:]]*=[[:space:]]*//p" "$ENV_FILE" | tail -1 | sed 's/^["\x27]//; s/["\x27][[:space:]]*$//'; }
if [ -n "${ENV_FILE:-}" ] && [ -f "$ENV_FILE" ]; then
  [ -n "${CIRCLE_API_KEY:-}" ] || CIRCLE_API_KEY="$(setting CIRCLE_API_KEY)"
  [ -n "${CIRCLE_ENTITY_SECRET:-}" ] || CIRCLE_ENTITY_SECRET="$(setting CIRCLE_ENTITY_SECRET)"
fi
: "${CIRCLE_API_KEY:?set CIRCLE_API_KEY (or ENV_FILE=path/to/.env)}"
if [ -z "${CIRCLE_ENTITY_SECRET:-}" ] && [ -n "${CIRCLE_ENTITY_SECRET_FILE:-}" ]; then
  CIRCLE_ENTITY_SECRET="$(tr -d ' \n\r' < "$CIRCLE_ENTITY_SECRET_FILE")"
fi
: "${CIRCLE_ENTITY_SECRET:?set CIRCLE_ENTITY_SECRET or CIRCLE_ENTITY_SECRET_FILE}"
BLOCKCHAINS="${BLOCKCHAINS:-BASE-SEPOLIA}"
API="https://api.circle.com/v1/w3s"
for tool in curl jq openssl xxd uuidgen; do command -v "$tool" >/dev/null || { echo "missing $tool" >&2; exit 1; }; done

work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
auth=(-H "Authorization: Bearer $CIRCLE_API_KEY" -H "Content-Type: application/json")

call() { # method path [json]
  local out code
  out="$work/out.json"
  code=$(curl -sS -o "$out" -w '%{http_code}' -X "$1" "${auth[@]}" "$API$2" ${3:+--data "$3"})
  if [ "$code" -ge 300 ]; then
    echo "Circle $1 $2 -> HTTP $code: $(jq -c '{code,message}' "$out" 2>/dev/null || head -c 300 "$out")" >&2
    return 1
  fi
  cat "$out"
}

# Circle's public key, then a fresh ciphertext of the entity secret for every request (Circle rejects reuse).
call GET /config/entity/publicKey | jq -r '.data.publicKey' > "$work/pub.pem"
cipher() {
  printf '%s' "$CIRCLE_ENTITY_SECRET" | xxd -r -p |
    openssl pkeyutl -encrypt -pubin -inkey "$work/pub.pem" -pkeyopt rsa_padding_mode:oaep -pkeyopt rsa_oaep_md:sha256 \
      -pkeyopt rsa_mgf1_md:sha256 | base64 | tr -d '\n'
}
key() { uuidgen | tr 'A-Z' 'a-z'; }

# Register the entity secret if this account has none yet (a 409 means it's registered already).
reg="$work/reg.json"
code=$(curl -sS -o "$reg" -w '%{http_code}' -X POST "${auth[@]}" "$API/config/entity/entitySecret" \
  --data "$(jq -nc --arg c "$(cipher)" '{entitySecretCiphertext:$c}')")
if [ "$code" -lt 300 ]; then
  rec="$(dirname "$0")/circle-recovery-$(date +%Y%m%d).dat"
  (umask 077; jq -r '.data.recoveryFile' "$reg" > "$rec")
  echo "Entity secret registered. Recovery file: $rec (move it to a password manager)."
else
  echo "Entity secret: already registered on this account (HTTP $code), carrying on."
fi

# A wallet set for Circaso's own wallets, then one fee wallet per chain (EOA: it only receives fees).
set_id=$(call POST /developer/walletSets "$(jq -nc --arg k "$(key)" --arg c "$(cipher)" \
  '{idempotencyKey:$k, entitySecretCiphertext:$c, name:"Circaso fees"}')" | jq -r '.data.walletSet.id')
echo "Wallet set: $set_id"
chains=$(printf '%s' "$BLOCKCHAINS" | jq -Rc 'split(",")')
call POST /developer/wallets "$(jq -nc --arg k "$(key)" --arg c "$(cipher)" --arg s "$set_id" --argjson b "$chains" \
  '{idempotencyKey:$k, entitySecretCiphertext:$c, walletSetId:$s, blockchains:$b, count:1, accountType:"EOA",
    metadata:[{name:"Circaso fee wallet", refId:"circaso-fees"}]}')" |
  jq -r '.data.wallets[] | "Fee wallet on \(.blockchain): id \(.id), address \(.address)"'
echo "Next: on the server set WALLET_FEES_CHAIN_<CHAIN>_ADDRESS to each address, and CIRCLE_ENTITY_SECRET, then restart."
