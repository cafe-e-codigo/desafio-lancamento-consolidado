#!/usr/bin/env bash
# Demo: VÁRIOS lançamentos -> consolidado (sem frontend; tudo via curl).
# Uso: harness/demo-varios-lancamentos.sh [N] [COMERCIANTE_UUID] [DATA_YYYY-MM-DD]
# Requer a stack no ar:  cd implementacao && docker compose up -d
#
# Por padrão usa um COMERCIANTE NOVO (UUID aleatório) a cada execução, para que o saldo
# lido seja EXATAMENTE a soma dos N lançamentos desta execução (sem acumular de execuções
# anteriores). Para acumular de propósito, passe um comerciante fixo.
set -euo pipefail

N="${1:-5}"
M="${2:-$(python3 -c 'import uuid; print(uuid.uuid4())')}"
D="${3:-$(date +%F)}"
BASE_L=http://localhost:8080   # serviço de lançamentos (escrita)
BASE_C=http://localhost:8081   # serviço do consolidado (leitura)
AQUI="$(cd "$(dirname "$0")" && pwd)"

TOKEN=$(python3 "$AQUI/mint-token.py" "$M" "consolidado:read lancamentos:write")

echo "== $N lançamentos | comerciante=$M | data=$D =="
pares=""   # linhas "sinal valor" p/ conferir a soma no fim
for i in $(seq 1 "$N"); do
  if [ $((i % 2)) -eq 0 ]; then tipo=DEBITO;  sinal=-1; else tipo=CREDITO; sinal=1; fi
  valor=$(printf "%d.%02d" $((i * 10)) $((i * 7 % 100)))
  pares+="$sinal $valor"$'\n'
  key="demo-$M-$i-$$-$(date +%s%N)"   # Idempotency-Key ÚNICA por lançamento
  code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_L/api/v1/lancamentos" \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $key" \
    -d "{\"comerciante_id\":\"$M\",\"tipo\":\"$tipo\",\"valor\":\"$valor\",\"moeda\":\"BRL\",\"data_negocio\":\"$D\"}")
  printf "  [%2d/%2d] %-7s %9s -> HTTP %s\n" "$i" "$N" "$tipo" "$valor" "$code"
done

esperado=$(printf '%s' "$pares" | python3 -c 'import sys,decimal; print(f"{sum((decimal.Decimal(s)*decimal.Decimal(v) for s,v in (l.split() for l in sys.stdin if l.strip())), decimal.Decimal(0)):.2f}")')
echo "soma esperada desta execução = $esperado"

echo "aguardando o rollup assíncrono (outbox -> RabbitMQ -> listener -> saldo_diario)..."
sleep 5
echo "== consolidado (leitura) =="
curl -s "$BASE_C/api/v1/consolidado/$M?data=$D" -H "Authorization: Bearer $TOKEN"; echo
echo "(o saldo acima deve ser igual à 'soma esperada', comerciante novo, sem acúmulo)"
