#!/usr/bin/env python3
"""Harness: cunha JWT HS256 para medir a API com auth REAL (não bypass).

FONTE ÚNICA DO SEGREDO = o mesmo valor do serviço. O app lê
`hmac-secret: ${VERETY_JWT_HMAC_SECRET:}` (application-security.yml:15) e o compose
canônico injeta `VERETY_JWT_HMAC_SECRET` (docker-compose.yml:99,147). Portanto:
  - a variável de ambiente do minter é `VERETY_JWT_HMAC_SECRET`;
  - o default abaixo é o PLACEHOLDER de harness do compose canônico.
Nunca usar em produção (o Alvo provisiona Cognito/JWKS ou Secret gerenciado).

Uso:
  VERETY_JWT_HMAC_SECRET=... python3 harness/mint-token.py <merchant> <scope>
  python3 harness/mint-token.py                # usa o default do compose canônico
"""
import base64, hashlib, hmac, json, os, sys, time

# Default = placeholder do docker-compose.yml (fonte canônica). Sobrescreva via env.
DEFAULT_SECRET = "harness-placeholder-do-not-use-in-prod-0123456789abcdef"
SECRET = os.environ.get("VERETY_JWT_HMAC_SECRET", DEFAULT_SECRET).encode()

def b64u(b: bytes) -> str:
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()

def mint(merchant: str, scope: str, ttl: int = 3600) -> str:
    now = int(time.time())
    header = {"alg": "HS256", "typ": "JWT"}
    payload = {
        "sub": "harness-token",
        "scope": scope,
        "merchant_id": merchant,
        "iat": now,
        "nbf": now - 10,
        "exp": now + ttl,
    }
    h = b64u(json.dumps(header, separators=(",", ":")).encode())
    p = b64u(json.dumps(payload, separators=(",", ":")).encode())
    signing_input = f"{h}.{p}".encode()
    sig = b64u(hmac.new(SECRET, signing_input, hashlib.sha256).digest())
    return f"{h}.{p}.{sig}"

if __name__ == "__main__":
    merchant = sys.argv[1] if len(sys.argv) > 1 else "22222222-2222-2222-2222-222222222222"
    scope = sys.argv[2] if len(sys.argv) > 2 else "consolidado:read lancamentos:write"
    print(mint(merchant, scope))
