#!/usr/bin/env bash
# Harness: CONTROLES do single-flight.
#   warm : cache quente  -> esperado 0 SELECT no PG (read-through)
#   down : Redis parado  -> esperado ~N SELECT no PG (fallback, sem coordenação)
set -u
cd "$(dirname "$0")/.."

TOKEN="$(python3 harness/mint-token.py)"
MERCHANT=22222222-2222-2222-2222-222222222222
DATA=2026-10-08
N="${2:-150}"
MODE="$1"

fire() {
  local ip1=$1 ip2=$2 ip3=$3 n=$4 token=$5 out
  out=$(mktemp)
  seq 1 "$n" | xargs -P "$n" -I{} bash -c '
    case $(( {} % 3 )) in 0) ip='"$ip1"';; 1) ip='"$ip2"';; 2) ip='"$ip3"';; esac
    curl -s -o /dev/null -w "%{http_code}\n" \
      -H "Authorization: Bearer '"$token"'" \
      "http://$ip:8081/api/v1/consolidado/'"$MERCHANT"'?data='"$DATA"'"
  ' > "$out"
  sort "$out" | uniq -c | tr '\n' ' '
  rm -f "$out"
}

count_queries() {
  local before=$1 after=$2
  docker logs verety-postgres-1 2>&1 | sed -n "$((before+1)),${after}p" \
    | grep -cE "execute S_[0-9]+: SELECT \* FROM consolidado.saldo_diario"
}

if [ "$MODE" = "warm" ]; then
  echo "### CONTROLE WARM (cache quente, sem flush) ###"
  before=$(docker logs verety-postgres-1 2>&1 | wc -l)
  echo "respostas: $(fire 172.22.0.6 172.22.0.7 172.22.0.8 "$N" "$TOKEN")"
  sleep 1
  after=$(docker logs verety-postgres-1 2>&1 | wc -l)
  echo "SELECT saldo_diario no PG = $(count_queries "$before" "$after")  (esperado 0)"
elif [ "$MODE" = "down" ]; then
  echo "### CONTROLE REDIS-DOWN (sem coordenação; fallback ao PG) ###"
  docker exec verety-redis-1 redis-cli FLUSHALL >/dev/null
  echo "cache limpo: dbsize=$(docker exec verety-redis-1 redis-cli DBSIZE)"
  docker stop verety-redis-1 >/dev/null
  sleep 3
  before=$(docker logs verety-postgres-1 2>&1 | wc -l)
  echo "respostas: $(fire 172.22.0.6 172.22.0.7 172.22.0.8 "$N" "$TOKEN")"
  sleep 2
  after=$(docker logs verety-postgres-1 2>&1 | wc -l)
  echo "SELECT saldo_diario no PG = $(count_queries "$before" "$after")  (esperado ~$N)"
  docker start verety-redis-1 >/dev/null
  echo "redis restaurado: $(docker exec verety-redis-1 redis-cli PING 2>&1)"
fi
