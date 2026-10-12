#!/usr/bin/env bash
# Harness: prova de SINGLE-FLIGHT DISTRIBUÍDO multi-réplica.
# Conta EXECUÇÕES REAIS do SELECT de saldo_diario no Postgres via log_min_duration_statement=0.
set -u
cd "$(dirname "$0")/.."

TOKEN="$(python3 harness/mint-token.py)"
I1=172.22.0.6; I2=172.22.0.7; I3=172.22.0.8
MERCHANT=22222222-2222-2222-2222-222222222222
DATA=2026-10-08
N="${1:-150}"

echo "=== réplicas ==="
docker ps --format '{{.Names}}' | grep consolidado

echo "=== FLUSH Redis (cache + lock) ==="
docker exec verety-redis-1 redis-cli FLUSHALL >/dev/null
echo "dbsize=$(docker exec verety-redis-1 redis-cli DBSIZE)"

before=$(docker logs verety-postgres-1 2>&1 | wc -l)
echo "line_mark=$before"

echo "=== $N GETs concorrentes (round-robin 3 réplicas), cache COLD ==="
out=$(mktemp)
seq 1 "$N" | xargs -P "$N" -I{} bash -c '
  case $(( {} % 3 )) in
    0) ip=172.22.0.6 ;;
    1) ip=172.22.0.7 ;;
    2) ip=172.22.0.8 ;;
  esac
  curl -s -o /dev/null -w "%{http_code}\n" \
    -H "Authorization: Bearer '"$TOKEN"'" \
    "http://$ip:8081/api/v1/consolidado/'"$MERCHANT"'?data='"$DATA"'"
' > "$out"
echo "respostas: $(sort "$out" | uniq -c | tr '\n' ' ')"
rm -f "$out"

sleep 2
after=$(docker logs verety-postgres-1 2>&1 | wc -l)
echo "=== EXECUÇÕES REAIS do SELECT saldo_diario no Postgres (janela) ==="
docker logs verety-postgres-1 2>&1 | sed -n "$((before+1)),${after}p" \
  | grep -cE "execute S_[0-9]+: SELECT \* FROM consolidado.saldo_diario"
echo "--- linhas ---"
docker logs verety-postgres-1 2>&1 | sed -n "$((before+1)),${after}p" \
  | grep -E "execute S_[0-9]+: SELECT \* FROM consolidado.saldo_diario"
echo "=== cache após ==="
docker exec verety-redis-1 redis-cli KEYS 'consolidado:*'
