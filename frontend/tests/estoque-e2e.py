"""Run one stock browser suite on a freshly created loopback PostgreSQL 16 database."""
import argparse
import base64
from contextlib import contextmanager
import hashlib
from http.cookiejar import CookieJar
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import sys
import time
from urllib.error import HTTPError
from urllib.request import Request, build_opener, HTTPCookieProcessor, urlopen
from uuid import UUID, uuid4

ROOT = Path(__file__).resolve().parents[2]
PASSWORD = "SenhaSinteticaR02abc123"
PASSWORD_HASH = "$2a$10$UVTG.fV54s0HcW615QIUZexI13xLJG9DMutnABJyKXk32IDQx0nMO"
STOCK = "/api/estoque"
CREATE = 201
SUITES = {"sessao": "estoqueSessao.integrado.mjs", "retomada": "estoqueReenvio.integrado.mjs",
          "residuais": "estoqueResiduais.integrado.mjs"}


def postgres(program, *args, capture=False):
    bindir = os.environ.get("POP_RC_E2E_PGBIN")
    exe = str(Path(bindir) / (program + (".exe" if os.name == "nt" else ""))) if bindir else program
    return subprocess.run([exe, "-h", "127.0.0.1", "-p", os.environ.get("POP_RC_E2E_PGPORT", "5432"),
                           "-U", os.environ["TEST_DB_USERNAME"], *args], check=True,
                          env={**os.environ, "PGPASSWORD": os.environ["TEST_DB_PASSWORD"], "PGCLIENTENCODING": "UTF8"},
                          encoding="utf-8", stdout=subprocess.PIPE if capture else None, timeout=25).stdout


def sql(query, database=None):
    return postgres("psql", "-XqAtw", "-v", "ON_ERROR_STOP=1", "-d",
                    database or os.environ["POP_RC_E2E_DB"], "-c", query, capture=True).strip()


def destino(database):
    name, directory, version = sql(
        "select current_database(),current_setting('data_directory'),current_setting('server_version_num')", database).split("|")
    assert name == database and version.startswith("16"), (name, version)
    expected = os.environ.get("POP_RC_E2E_PGDATA")
    if expected:
        assert Path(directory).resolve() == Path(expected).resolve(), directory
    elif os.environ.get("GITHUB_ACTIONS") != "true" or os.environ.get("POP_RC_E2E_PGPORT", "5432") != "5432":
        raise RuntimeError("Informe o diretório do cluster descartável em POP_RC_E2E_PGDATA.")
    return {"database": name, "pg16": version, "diretorio": directory}


class Client:
    def __init__(self, port, cpf=None, reauth=True):
        self.port = port
        self.cookies = CookieJar()
        self.opener = build_opener(HTTPCookieProcessor(self.cookies))
        self.token = ""
        if cpf:
            self.expect("/api/auth/login", {"cpf": cpf, "senha": PASSWORD}, 200)
            self.token = self.expect("/api/auth/csrf", None, 200)["token"]
            if reauth:
                self.expect("/api/auth/reauth", {"senha": PASSWORD}, 204)

    def request(self, path, body, key=None, method=None, discard=False):
        headers = {"Content-Type": "application/json"}
        if self.token:
            headers["X-XSRF-TOKEN"] = self.token
        if key:
            headers["Idempotency-Key"] = str(key)
        req = Request(f"http://127.0.0.1:{self.port}{path}",
                      data=None if body is None else json.dumps(body).encode(), headers=headers, method=method)
        try:
            with self.opener.open(req, timeout=20) as response:
                return response.status, None if discard else response.read()
        except HTTPError as error:
            return error.code, error.read()

    def expect(self, path, body, status, key=None, method=None):
        code, data = self.request(path, body, key, method)
        assert code == status, (path, status, code, data)
        return json.loads(data) if data and status < 400 else None


def expire_reauth(session_cookie):
    session_id = UUID(base64.b64decode(session_cookie).decode("ascii"))
    sql(f"""delete from spring_session_attributes where attribute_name='RC_REAUTENTICADO_EM'
         and session_primary_id=(select primary_id from spring_session where session_id='{session_id}')""")


def seed_user(cpf, role):
    return int(sql(f"""insert into funcionarios (nome, cpf, perfil_acesso, senha_hash, ativo)
        values ('R02 {role}', '{cpf}', '{role}', '{PASSWORD_HASH}', true)
        on conflict (cpf) where cpf is not null do update set senha_hash=excluded.senha_hash
        returning id"""))


def state(material):
    return json.loads(sql(f"""select json_build_object(
      'global',(select row_to_json(m) from (select metragem_disponivel saldo, metragem_reservada reserva, custo_medio custo, quantidade_disponivel quantidade
          from materiais where id={material}) m),
      'locais',(select jsonb_object_agg(local_estoque_id, jsonb_build_array(metragem_disponivel, metragem_reservada))
          from saldos_materiais_locais where material_id={material}),
      'historico', (select coalesce(json_agg(row_to_json(m) order by id), '[]') from (
          select id,tipo,quantidade,metragem,saldo_anterior,saldo_posterior,custo_unitario,valor_total_movimentacao,
                 estoque_origem,estoque_destino from movimentacoes_estoque where material_id={material}) m)
    )"""))


def operation(client, funcionario, kind):
    tag = uuid4().hex[:10]
    origin = client.expect(STOCK + "/locais", {"nome": f"Origem {tag}"}, CREATE)["id"]
    dest = client.expect(STOCK + "/locais", {"nome": f"Destino {tag}"}, CREATE)["id"]
    tracked = kind in ("rastreavel", "entrada-rastreavel")
    material = client.expect(STOCK + "/materiais", {
        "nome": f"R02 {kind} {tag}", "partNumber": f"R02-{tag}",
        "tipoControle": "BOBINA" if tracked else "METRAGEM",
        "unidadeMedida": "BOBINA" if tracked else "METRO", "custoMedio": 2,
        "quantidadeDisponivel": 0, "metragemDisponivel": 0}, CREATE)
    mid = material["id"]
    entry = {"materialId": mid, "localEstoqueId": origin, "funcionarioId": funcionario,
             "metragem": 4, "custoUnitarioEntrada": 4}
    audit = {"motivo": "R02 motivo", "lancadoPor": "R02 lancador", "autorizadoPor": "R02 autorizador"}
    if tracked:
        unit_body = {"materialId": mid, "localEstoqueId": origin, "codigo": f"R02-{tag}", "metragemInicial": 40}
        if kind == "entrada-rastreavel":
            return mid, origin, dest, STOCK + "/unidades-rastreaveis", "POST", CREATE, unit_body
        unit = client.expect(STOCK + "/unidades-rastreaveis", unit_body, CREATE)
        return mid, origin, dest, f"{STOCK}/unidades-rastreaveis/{unit['id']}/transferir", "PATCH", 200, {"destinoId": dest, **audit}
    client.expect(STOCK + "/entrada", {**entry, "metragem": 40, "custoUnitarioEntrada": 2}, CREATE)
    sql(f"""update materiais set metragem_reservada=3 where id={mid};
            update saldos_materiais_locais set metragem_reservada=3 where material_id={mid} and local_estoque_id={origin}""")
    if kind == "entrada":
        return mid, origin, dest, STOCK + "/entrada", "POST", CREATE, entry
    if kind.startswith("ajuste"):
        return mid, origin, dest, STOCK + "/ajustes", "POST", CREATE, {
            "materialId": mid, "localEstoqueId": origin, "valor": 4,
            "tipo": "AJUSTE_NEGATIVO" if kind.endswith("-") else "AJUSTE_POSITIVO", **audit}
    return mid, origin, dest, STOCK + "/transferencias", "POST", CREATE, {
        "materialId": mid, "origemId": origin, "destinoId": dest, "valor": 4, **audit}


def free_port():
    with socket.socket() as server:
        server.bind(("127.0.0.1", 0))
        return server.getsockname()[1]


@contextmanager
def processo(args, cwd, env, log):
    with log.open("wb") as output:
        process = subprocess.Popen(args, cwd=cwd, env=env, stdout=output, stderr=subprocess.STDOUT)
        try:
            yield process
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=10)


def esperar(url, process):
    for _ in range(160):
        assert process.poll() is None, f"Processo encerrou antes de responder em {url}"
        try:
            with urlopen(url, timeout=1) as resposta:
                if resposta.status == 200:
                    return
        except OSError:
            time.sleep(.5)
    raise RuntimeError(f"Servidor indisponível em {url}")


def fixture(api_port):
    cpf, outro_cpf = "11144477735", "12345678909"
    funcionario = seed_user(cpf, "ADMIN")
    outro = seed_user(outro_cpf, "ADMIN")
    sql(f"update funcionarios set nome='R02 OUTRO OPERADOR' where id={outro}")
    client = Client(api_port, cpf)
    items = []
    for kind, title in [
        ("entrada", "Entrada de Material"), ("ajuste+", "Ajustar Saldo"),
        ("ajuste-", "Ajustar Saldo"), ("transferencia", "Transferir Localização"),
        ("entrada-rastreavel", "Cadastrar Bobina ou Rolo"), ("rastreavel", "Transferir Bobina ou Rolo"),
    ]:
        mid, origin, dest, path, method, status, body = operation(client, funcionario, kind)
        name = sql(f"select nome from materiais where id={mid}")
        code = sql(f"select codigo from unidades_estoque_rastreaveis where material_id={mid}")
        items.append(dict(kind=kind, title=title, mid=mid, name=name, code=code, origin=origin,
                          dest=dest, path=path, method=method, status=status, body=body))
    return dict(cpf=cpf, outroCpf=outro_cpf, funcionario=funcionario, items=items)


def run(args):
    if not os.environ.get("POP_RC_E2E_PGDATA") and os.environ.get("TEST_DB_URL") != "jdbc:postgresql://localhost:5432/poprc_test":
        raise RuntimeError("Destino padrão do serviço PostgreSQL do CI divergente.")
    destino("postgres")
    database = f"poprc_r02_{args.suite}_{uuid4().hex[:12]}_test"
    os.environ["POP_RC_E2E_DB"] = database
    args.out.mkdir(parents=True, exist_ok=True)
    java = os.environ.get("JAVA_HOME")
    java = str(Path(java) / "bin" / ("java.exe" if os.name == "nt" else "java")) if java else "java"
    version = subprocess.check_output([java, "-version"], stderr=subprocess.STDOUT, text=True)
    assert re.search(r'version "25(?:[."]|$)', version), version
    postgres("createdb", database)
    try:
        identity = destino(database)
        (args.out / "runtime.json").write_text(json.dumps({**identity, "java": version,
            "jar_sha256": hashlib.sha256(args.jar.read_bytes()).hexdigest()}, indent=2), encoding="utf8")
        api_port, web_port = free_port(), free_port()
        if api_port == web_port:
            raise RuntimeError("Portas de teste coincidentes")
        api_url, web_url = f"http://127.0.0.1:{api_port}", f"http://127.0.0.1:{web_port}"
        env = {**os.environ,
               "TEST_DB_URL": f"jdbc:postgresql://127.0.0.1:{os.environ.get('POP_RC_E2E_PGPORT', '5432')}/{database}",
               "APP_UPLOAD_DIR": str(args.out / "uploads"),
               "VITE_PROXY_TARGET": api_url, "POP_RC_E2E_BASE_URL": web_url,
               "POP_RC_E2E_PASSWORD": PASSWORD, "POP_RC_E2E_PYTHON": sys.executable,
               "POP_RC_E2E_HTTP_SCRIPT": str(Path(__file__).resolve()), "PYTHONUTF8": "1"}
        backend = [java, "-jar", str(args.jar), "--spring.config.import=", "--spring.profiles.active=test",
                   "--server.address=127.0.0.1", f"--server.port={api_port}", "--app.security.enabled=true",
                   "--app.security.dev-login-enabled=false", "--app.security.zoho-enabled=false",
                   "--app.ambiente=teste-local", "--app.retiradas-v2.enabled=false", "--alerts.email.enabled=false"]
        frontend = ROOT / "frontend"
        with processo(backend, ROOT, env, args.out / "backend.log") as api:
            esperar(api_url + "/api/auth/config", api)
            fixture_path = args.out / "fixture.json"
            fixture_path.write_text(json.dumps(fixture(api_port), ensure_ascii=False), encoding="utf8")
            env["POP_RC_E2E_FIXTURE"] = str(fixture_path)
            vite = ["node", "node_modules/vite/bin/vite.js", "--host", "127.0.0.1", "--port", str(web_port), "--strictPort"]
            with processo(vite, frontend, env, args.out / "frontend.log") as web:
                esperar(web_url + "/api/auth/config", web)
                with (args.out / "browser.log").open("wb") as log:
                    subprocess.run(["node", "tests/" + SUITES[args.suite]], cwd=frontend, env=env,
                                   stdout=log, stderr=subprocess.STDOUT, check=True, timeout=240)
    finally:
        postgres("dropdb", "--force", database)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--snapshot", type=int)
    parser.add_argument("--expire-reauth")
    parser.add_argument("--intencao", type=UUID)
    parser.add_argument("--suite", choices=SUITES)
    parser.add_argument("--jar", type=lambda p: Path(p).resolve())
    parser.add_argument("--out", type=lambda p: Path(p).resolve())
    args = parser.parse_args()
    if args.suite and args.jar and args.out:
        run(args)
    elif args.snapshot is not None or args.expire_reauth or args.intencao:
        database = os.environ["POP_RC_E2E_DB"]
        assert re.fullmatch(r"poprc_r02_(sessao|retomada|residuais)_[0-9a-f]{12}_test", database)
        destino(database)
        if args.snapshot is not None:
            mid = args.snapshot
            snapshot = state(mid)
            snapshot["unidades"] = json.loads(sql(f"""
                select coalesce(json_agg(row_to_json(u) order by id), '[]')
                from (select id, codigo, local_estoque_id, metragem_atual
                      from unidades_estoque_rastreaveis where material_id={mid}) u
            """))
            print(json.dumps(snapshot))
        elif args.intencao:
            print(sql(f"select count(*) from estoque_intencoes where chave='{args.intencao}'"))
        else:
            expire_reauth(args.expire_reauth)
    else:
        parser.error("Informe a suíte, o JAR e a pasta de evidências.")
