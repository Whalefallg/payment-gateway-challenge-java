#!/usr/bin/env bash
set -euo pipefail

gateway_url="${GATEWAY_URL:-http://localhost:8090}"
expiry_year="$(python3 -c 'from datetime import datetime, timezone; print(datetime.now(timezone.utc).year + 2)')"

post_payment() {
  local card_number="$1"
  curl --fail-with-body --silent --show-error \
    -H 'Content-Type: application/json' \
    -d "{\"card_number\":\"${card_number}\",\"expiry_month\":12,\"expiry_year\":${expiry_year},\"currency\":\"GBP\",\"amount\":1050,\"cvv\":\"007\"}" \
    "${gateway_url}/payments"
}

authorized="$(post_payment 2222405343240001)"
authorized_id="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])' <<<"${authorized}")"
python3 -c 'import json,sys; assert json.load(sys.stdin)["status"] == "Authorized"' <<<"${authorized}"
curl --fail --silent "${gateway_url}/payments/${authorized_id}" | \
  python3 -c 'import json,sys; assert json.load(sys.stdin)["status"] == "Authorized"'

declined="$(post_payment 2222405343240002)"
declined_id="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])' <<<"${declined}")"
python3 -c 'import json,sys; assert json.load(sys.stdin)["status"] == "Declined"' <<<"${declined}"
curl --fail --silent "${gateway_url}/payments/${declined_id}" | \
  python3 -c 'import json,sys; assert json.load(sys.stdin)["status"] == "Declined"'

status_503="$(curl --silent --output /tmp/payment-gateway-bank-503.json --write-out '%{http_code}' \
  -H 'Content-Type: application/json' \
  -d "{\"card_number\":\"2222405343240000\",\"expiry_month\":12,\"expiry_year\":${expiry_year},\"currency\":\"GBP\",\"amount\":1050,\"cvv\":\"007\"}" \
  "${gateway_url}/payments")"
test "${status_503}" = "503"

status_invalid="$(curl --silent --output /tmp/payment-gateway-invalid.json --write-out '%{http_code}' \
  -H 'Content-Type: application/json' -d '{"currency":"GBP"}' "${gateway_url}/payments")"
test "${status_invalid}" = "400"

echo "Smoke tests passed: Authorized, Declined, GET, bank 503, invalid request"
