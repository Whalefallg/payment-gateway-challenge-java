#!/usr/bin/env bash
set -euo pipefail

gateway_url="${GATEWAY_URL:-http://localhost:8090}"
expiry_year="$(python3 -c 'from datetime import datetime, timezone; print(datetime.now(timezone.utc).year + 2)')"
key_prefix="$(python3 -c 'import uuid; print(uuid.uuid4())')"
smoke_dir="$(mktemp -d "${TMPDIR:-/tmp}/payment-gateway-smoke.XXXXXX")"
trap 'rm -rf "$smoke_dir"' EXIT

payload() {
  printf '{"card_number":"%s","expiry_month":12,"expiry_year":%s,"currency":"GBP","amount":%s,"cvv":"007"}' \
    "$1" "$expiry_year" "${2:-1050}"
}

request() {
  local method="$1" path="$2" expected_code="$3" expected_state="$4"
  shift 4
  local actual_code
  actual_code="$(curl --silent --show-error --connect-timeout 5 --max-time 10 \
    -X "$method" -D "$smoke_dir/headers" -o "$smoke_dir/body" -w '%{http_code}' \
    -H 'Content-Type: application/json' "$@" "${gateway_url}${path}")"
  if [[ "$actual_code" != "$expected_code" ]]; then
    printf 'FAIL: %s %s expected HTTP %s, got %s\n' "$method" "$path" "$expected_code" "$actual_code" >&2
    return 1
  fi
  python3 - "$smoke_dir" "$expected_state" "$actual_code" "$method" <<'PY'
import json, pathlib, sys, uuid
root, state, code, method = sys.argv[1:]
body = json.loads(pathlib.Path(root, 'body').read_text())
headers = dict(line.split(':', 1) for line in pathlib.Path(root, 'headers').read_text().splitlines() if ':' in line)
headers = {key.lower(): value.strip() for key, value in headers.items()}
uuid.UUID(headers['x-request-id'])
assert 'card_number' not in body and 'cvv' not in body, body.keys()
if state != '-':
    assert body['status'] == state, (state, body.get('status'))
if int(code) >= 400:
    assert body['request_id'] == headers['x-request-id']
else:
    assert set(body) == {'id', 'status', 'card_number_last_four', 'expiry_month', 'expiry_year', 'currency', 'amount'}
    if method == 'POST':
        assert headers['location'] == '/payments/' + body['id']
PY
}

field() {
  python3 -c 'import json,sys; print(json.load(sys.stdin)[sys.argv[1]])' "$1" < "$smoke_dir/body"
}

request POST /payments 201 Authorized -d "$(payload 2222405343240001)"
authorized_id="$(field id)"
request GET "/payments/$authorized_id" 200 Authorized
test "$(field id)" = "$authorized_id"
request POST /payments 201 Authorized -d "$(payload 2222405343240001)"
test "$(field id)" != "$authorized_id"

request POST /payments 201 Declined -d "$(payload 2222405343240002)"
declined_id="$(field id)"
request GET "/payments/$declined_id" 200 Declined
test "$(field id)" = "$declined_id"

request POST /payments 503 Unknown -d "$(payload 2222405343240000)"
unknown_id="$(field payment_id)"
request GET "/payments/$unknown_id" 200 Unknown
test "$(field id)" = "$unknown_id"
request POST /payments 400 Rejected -d '{"currency":"GBP"}'

request POST /payments 201 Authorized -H "Idempotency-Key: $key_prefix-authorized" -d "$(payload 2222405343240001)"
keyed_id="$(field id)"
request POST /payments 200 Authorized -H "Idempotency-Key: $key_prefix-authorized" -d "$(payload 2222405343240001)"
test "$(field id)" = "$keyed_id"
request POST /payments 409 - -H "Idempotency-Key: $key_prefix-authorized" -d "$(payload 2222405343240001 1051)"
request GET "/payments/$keyed_id" 200 Authorized
test "$(field amount)" = 1050

request POST /payments 503 Unknown -H "Idempotency-Key: $key_prefix-unknown" -d "$(payload 2222405343240000)"
keyed_unknown_id="$(field payment_id)"
request POST /payments 200 Unknown -H "Idempotency-Key: $key_prefix-unknown" -d "$(payload 2222405343240000)"
test "$(field id)" = "$keyed_unknown_id"

printf 'Smoke tests passed: independent unkeyed requests, Authorized/Declined/GET, queryable 503, keyed replay/conflict/Unknown, validation, headers and safe fields\n'
