"""Reconciliação e recuperação de Retiradas V2. Python 3.12+ e clientes PostgreSQL."""
import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import uuid

TABLES = (
    "ordens_servico", "comarcas", "comarca_materiais", "ordens_retirada", "ordem_retirada_itens",
    "retiradas_v2", "retirada_v2_arquivos", "retirada_v2_emissoes", "retirada_v2_itens",
    "retirada_v2_reservas", "retirada_v2_operacoes", "retirada_v2_eventos", "retirada_v2_autorizacoes",
    "materiais", "saldos_materiais_locais", "movimentacoes_estoque", "funcionarios",
)
UUID_NAME = re.compile(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")
GUARD = ("locktype='advisory' AND classid=0 AND objid=7316041 AND objsubid=1 "
         "AND database=(SELECT oid FROM pg_database WHERE datname=current_database())")
SNAPSHOT = f"""
BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT jsonb_build_object(
 'instante', clock_timestamp(),
 'ativos', (SELECT coalesce(jsonb_agg(jsonb_build_object('pid',pid,'concedido',granted)), '[]')
            FROM pg_locks WHERE {GUARD}),
 'referencias', (SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY id), '[]') FROM
   (SELECT id,or_id,tipo,versao,caminho,hash,autor,criado_em FROM public.retirada_v2_arquivos) x)
);
COMMIT;
"""


def sha256(path):
    with path.open("rb") as f:
        return hashlib.file_digest(f, "sha256").hexdigest()


class Postgres:
    def __init__(self, args):
        self.bin = args.pg_bin.resolve(strict=True)
        self.connection = ["-h", args.host, "-p", str(args.port), "-U", args.usuario, "-w"]
        self.env = {k: v for k, v in os.environ.items() if not k.startswith("PG") or k in {"PGPASSFILE", "PGPASSWORD", "PGSSLMODE", "PGSSLROOTCERT", "PGSSLCERT", "PGSSLKEY"}}
        self.env["PGCLIENTENCODING"] = "UTF8"

    def command(self, program, *arguments):
        exe = self.bin / (program + (".exe" if os.name == "nt" else ""))
        return [str(exe), *self.connection, *map(str, arguments)]

    def environment(self, readonly):
        options = "-c timezone=UTC -c datestyle=ISO,YMD"
        if readonly:
            options += " -c default_transaction_read_only=on"
        return {**self.env, "PGOPTIONS": options}

    def run(self, program, *arguments, readonly=False):
        return subprocess.check_output(self.command(program, *arguments), env=self.environment(readonly), timeout=3600)

    def query(self, database, sql):
        return self.run("psql", "-X", "-qAt", "-v", "ON_ERROR_STOP=1", "-d", database, "-c", sql,
                        readonly=True).decode("utf-8").strip()

    def table_hashes(self, database):
        result = {}
        for table in TABLES:
            sql = f'COPY (SELECT to_jsonb(t)::text FROM public.{table} t ORDER BY to_jsonb(t)::text COLLATE "C") TO STDOUT'
            cmd = self.command("psql", "-X", "-qAt", "-v", "ON_ERROR_STOP=1", "-d", database, "-c", sql)
            with subprocess.Popen(cmd, stdout=subprocess.PIPE, env=self.environment(True)) as proc:
                result[table] = hashlib.file_digest(proc.stdout, "sha256").hexdigest()
                if proc.wait() != 0:
                    raise RuntimeError(f"Não foi possível conferir {table}.")
        return result


def safe_path(path):
    # Recusa links simbólicos e junctions nas raízes e nas entradas do pacote.
    if path.absolute() != path.resolve(strict=True):
        raise ValueError(f"Caminho redirecionado: {path}")
    return path.absolute()


def inventory(root):
    root = safe_path(root)
    files = {}
    directories = [root]
    while directories:
        for entry in directories.pop().iterdir():
            if entry.is_symlink() or entry.is_junction() or entry.resolve(strict=True) != entry:
                raise ValueError(f"Link não permitido no pacote: {entry}")
            if entry.is_dir():
                directories.append(entry)
            elif entry.is_file():
                files[entry.relative_to(root).as_posix()] = sha256(entry)
            else:
                raise ValueError(f"Entrada não regular: {entry}")
    return files


def reconcile(pg, db, uploads):
    uploads = safe_path(uploads)
    folder = uploads / ".retiradas-v2"
    before = json.loads(pg.query(db, SNAPSHOT))
    files = {}
    if folder.exists() or folder.is_symlink():
        safe_path(folder)
        for f in folder.iterdir():
            try:
                if f.is_symlink() or f.is_junction() or not f.is_file():
                    files[f.name] = {"especial": True}
                    continue
                first = f.stat()
                digest = sha256(f)
                last = f.stat()
                files[f.name] = {"sha256": digest, "bytes": last.st_size,
                                 "mudou": (first.st_size, first.st_mtime_ns) != (last.st_size, last.st_mtime_ns)}
            except OSError as error:
                files[f.name] = {"erro": type(error).__name__}
    after = json.loads(pg.query(db, SNAPSHOT))
    uncertain = bool(before["ativos"] or after["ativos"] or before["referencias"] != after["referencias"])
    refs = {r["caminho"]: r for r in after["referencias"]}
    rows = []
    for name in sorted(refs.keys() | files.keys()):
        ref, f = refs.get(name), files.get(name)
        if ref:
            if not UUID_NAME.fullmatch(name):
                state = "REFERENCIA_INDETERMINADA"
            elif not f:
                state = "REFERENCIA_AUSENTE"
            elif "sha256" not in f or f["mudou"]:
                state = "REFERENCIA_INDETERMINADA"
            else:
                state = "REFERENCIADO_INTEGRO" if ref["hash"] == f["sha256"] else "REFERENCIA_DIVERGENTE"
        elif not UUID_NAME.fullmatch(name):
            state = "TEMPORARIO_OU_NAO_RECONHECIDO"
        elif uncertain or "erro" in f or f.get("mudou") or f.get("especial"):
            state = "POSSIVEL_OPERACAO_EM_ANDAMENTO"
        else:
            state = "SEM_REFERENCIA_OBSERVADA"
        rows.append({"nome": name, "estado": state, "referencia": ref, "arquivo": f})
    return {"inicioSql": before["instante"], "fimSql": after["instante"],
            "advisoryInicio": before["ativos"], "advisoryFim": after["ativos"],
            "janelaInconclusiva": uncertain, "arquivos": rows,
            "contagens": dict(Counter(r["estado"] for r in rows)),
            "bytesSemReferenciaObservada": sum(r["arquivo"].get("bytes", 0) for r in rows if r["estado"] == "SEM_REFERENCIA_OBSERVADA"),
            "observacao": "Inventário sem barreira de manutenção. Ausência de referência não autoriza descarte."}


def assert_drained(pg, db):
    count = pg.query(db, "select count(*) from pg_stat_activity where datname=current_database() and pid<>pg_backend_pid()")
    if count != "0":
        raise RuntimeError(f"Janela recusada. O banco tem {count} outra(s) sessão(ões).")


def new_directory(path):
    dest = safe_path(path.parent) / path.name
    dest.mkdir(exist_ok=False)
    return dest


def write_json(path, data):
    with path.open("x", encoding="utf-8") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)
        f.write("\n")


def backup(pg, args):
    if not args.escritores_drenados:
        raise ValueError("Confirme a drenagem externa de todos os escritores antes do backup.")
    source = safe_path(args.uploads)
    destination = safe_path(args.saida.parent) / args.saida.name
    if destination.is_relative_to(source):
        raise ValueError("O destino não pode ficar sob os uploads.")
    assert_drained(pg, args.banco)
    before = pg.table_hashes(args.banco)
    files = inventory(source)
    diagnosis = reconcile(pg, args.banco, source)
    if diagnosis["janelaInconclusiva"]:
        raise RuntimeError("Atividade detectada durante a reconciliação.")
    package = new_directory(destination)
    dump = package / "banco.dump"
    pg.run("pg_dump", "-Fc", "-f", dump, "-d", args.banco, readonly=True)
    shutil.copytree(source, package / "uploads")
    assert_drained(pg, args.banco)
    if before != pg.table_hashes(args.banco) or files != inventory(source) or files != inventory(package / "uploads"):
        raise RuntimeError("Mudança detectada no corte. O pacote ficou sem manifesto de conclusão.")
    assert_drained(pg, args.banco)
    write_json(package / "reconciliacao.json", diagnosis)
    write_json(package / "manifesto.json", {
        "formato": 1, "utc": datetime.now(timezone.utc).isoformat(), "bancoOrigem": args.banco,
        "postgres": int(pg.query(args.banco, "show server_version_num")),
        "dump": sha256(dump), "uploads": files, "tabelas": before,
        "reconciliacao": sha256(package / "reconciliacao.json"),
    })
    return {"pacote": str(package), "arquivos": len(files), "tabelas": len(before)}


def restore(pg, args):
    if args.host != "127.0.0.1":
        raise ValueError("A restauração exige loopback.")
    expected = safe_path(args.dados_isolados)
    marker = expected.parent / "poprc-r01-isolado.json"
    cfg = json.loads(marker.read_text(encoding="utf-8"))
    if cfg != {"tipo": "poprc-r01-isolado", "data": str(expected), "port": args.port}:
        raise ValueError("Marcador e destino não correspondem ao cluster isolado.")
    data = Path(pg.query("postgres", "show data_directory")).resolve(strict=True)
    if data != expected:
        raise ValueError("O servidor conectado não é o cluster isolado.")
    package = safe_path(args.pacote)
    for name in ("manifesto.json", "banco.dump", "reconciliacao.json", "uploads"):
        safe_path(package / name)
    manifest = json.loads((package / "manifesto.json").read_text(encoding="utf-8"))
    if manifest["formato"] != 1 or manifest["dump"] != sha256(package / "banco.dump"):
        raise ValueError("Dump ou formato divergente.")
    if manifest["reconciliacao"] != sha256(package / "reconciliacao.json") or manifest["uploads"] != inventory(package / "uploads"):
        raise ValueError("Inventário ou uploads divergentes.")
    if int(pg.query("postgres", "show server_version_num")) // 10000 < manifest["postgres"] // 10000:
        raise ValueError("O PostgreSQL de destino é mais antigo que o de origem.")
    dest = new_directory(args.saida)
    db = f"poprc_{uuid.uuid4().hex}_restore_test"
    pg.run("createdb", "-T", "template0", db)
    pg.run("pg_restore", "--exit-on-error", "--single-transaction", "--no-owner", "--no-privileges",
           "-d", db, package / "banco.dump")
    shutil.copytree(package / "uploads", dest / "uploads")
    if pg.table_hashes(db) != manifest["tabelas"] or inventory(dest / "uploads") != manifest["uploads"]:
        raise RuntimeError("Restauração divergente. Banco e arquivos ficaram preservados para diagnóstico.")
    diagnosis = reconcile(pg, db, dest / "uploads")
    original = json.loads((package / "reconciliacao.json").read_text(encoding="utf-8"))
    if diagnosis["janelaInconclusiva"] or diagnosis["arquivos"] != original["arquivos"]:
        raise RuntimeError("Reconciliação após restauração divergiu.")
    write_json(dest / "reconciliacao.json", diagnosis)
    failures = sum(r["referencia"] is not None and r["estado"] != "REFERENCIADO_INTEGRO" for r in diagnosis["arquivos"])
    result = {"banco": db, "uploads": str(dest / "uploads"), "tabelasIguais": len(manifest["tabelas"]),
              "arquivosIguais": len(manifest["uploads"]), "referenciasComProblema": failures,
              "acessoHttpPendente": True}
    write_json(dest / "restauro.json", result)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pg-bin", type=Path, required=True)
    parser.add_argument("--host", required=True)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--usuario", required=True)
    sub = parser.add_subparsers(dest="op", required=True)
    r = sub.add_parser("reconciliar")
    r.add_argument("--banco", required=True)
    r.add_argument("--uploads", required=True, type=Path)
    b = sub.add_parser("backup", help="Corte frio. Requer drenagem externa.")
    b.add_argument("--banco", required=True)
    b.add_argument("--uploads", required=True, type=Path)
    b.add_argument("--saida", required=True, type=Path)
    b.add_argument("--escritores-drenados", action="store_true")
    t = sub.add_parser("restaurar")
    t.add_argument("--pacote", required=True, type=Path)
    t.add_argument("--dados-isolados", required=True, type=Path)
    t.add_argument("--saida", required=True, type=Path)
    args = parser.parse_args()
    if hasattr(args, "banco") and not re.fullmatch(r"[A-Za-z0-9_][A-Za-z0-9_-]{0,62}", args.banco):
        parser.error("Informe somente o nome literal do banco, sem URL ou string de conexão.")
    pg = Postgres(args)
    if args.op == "reconciliar":
        result = reconcile(pg, args.banco, args.uploads)
    elif args.op == "backup":
        result = backup(pg, args)
    else:
        result = restore(pg, args)
    print(json.dumps(result, ensure_ascii=True, indent=2))


if __name__ == "__main__":
    main()
