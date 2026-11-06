"""Smoke HTTP dos documentos restaurados no backend isolado em loopback."""
import argparse
import getpass
import hashlib
import http.cookiejar
import json
import os
from pathlib import Path
import urllib.error
import urllib.parse
import urllib.request


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def client():
    return urllib.request.build_opener(
        urllib.request.ProxyHandler({}), NoRedirect(),
        urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()),
    )


def request(opener, url, payload=None):
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    req = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"} if data else {})
    try:
        with opener.open(req, timeout=20) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        with error:
            return error.code, error.read()


def authenticated(base, cpf, environment, label):
    opener = client()
    senha = os.environ.get(environment) or getpass.getpass(f"Senha {label}: ")
    status, _ = request(opener, base + "/api/auth/login", {"cpf": cpf, "senha": senha})
    if status != 200:
        raise RuntimeError(f"Login {label} falhou (HTTP {status}).")
    return opener


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--url", required=True)
    p.add_argument("--inventario", required=True, type=Path)
    p.add_argument("--autorizado", required=True, help="CPF de perfil com acesso aos documentos")
    p.add_argument("--negado", required=True, help="CPF de técnico sem vínculo com as ORs selecionadas")
    args = p.parse_args()
    address = urllib.parse.urlparse(args.url)
    if address.scheme not in {"http", "https"} or address.hostname != "127.0.0.1" or address.username or not address.port or address.path not in {"", "/"} or address.query or address.fragment:
        p.error("Use exclusivamente o backend isolado em 127.0.0.1 com porta explícita.")
    base = args.url.rstrip("/")
    rows = json.loads(args.inventario.read_text(encoding="utf-8"))["arquivos"]
    references = {}
    for row in rows:
        ref = row["referencia"]
        if row["estado"] == "REFERENCIADO_INTEGRO":
            references.setdefault(ref["tipo"], ref)
    if not {"EMISSAO", "COMPROVANTE", "DEVOLUCAO"} <= references.keys():
        raise RuntimeError("O inventário precisa ter uma referência íntegra de cada tipo exercitado.")
    positive = authenticated(base, args.autorizado, "R01_SENHA_AUTORIZADO", "autorizado")
    negative = authenticated(base, args.negado, "R01_SENHA_NEGADO", "negado")
    anonymous = client()
    results = []
    for kind in ("EMISSAO", "COMPROVANTE", "DEVOLUCAO"):
        ref = references[kind]
        route = f"/api/ordens-retirada/v2/{int(ref['or_id'])}/arquivos/{int(ref['id'])}"
        ok, data = request(positive, base + route)
        forbidden, _ = request(negative, base + route)
        unauth, _ = request(anonymous, base + route)
        matches = hashlib.sha256(data).hexdigest() == ref["hash"]
        if (ok, forbidden, unauth, matches) != (200, 403, 401, True):
            raise RuntimeError(f"{kind}: HTTP {ok}/{forbidden}/{unauth}; hash confere={matches}")
        results.append({"tipo": kind, "or": ref["or_id"], "arquivo": ref["id"], "versao": ref["versao"],
                        "sha256": ref["hash"], "autorizado": ok, "semVinculo": forbidden, "anonimo": unauth})
    print(json.dumps(results, indent=2, ensure_ascii=True))


if __name__ == "__main__":
    main()
