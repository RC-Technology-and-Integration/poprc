"""Java25/PG16, HTTP TCP real e leitura psql independente. Nunca usa banco preexistente.
Requer NF_PGBIN, NF_PGPORT, NF_PGDATA, TEST_DB_USERNAME/PASSWORD e JAVA_HOME.
Uso: python src/test/python/nf_arquivo_http.py --jar ... --out ... [--antes]
"""
import argparse, base64, hashlib, json, os, re, socket, subprocess, time
from concurrent.futures import ThreadPoolExecutor
from http.cookiejar import CookieJar
from pathlib import Path
from urllib.request import Request, build_opener, HTTPCookieProcessor, urlopen
from urllib.error import HTTPError
from uuid import uuid4

PASSWORD='SenhaSinteticaR02abc123'
PASSWORD_HASH='$2a$10$UVTG.fV54s0HcW615QIUZexI13xLJG9DMutnABJyKXk32IDQx0nMO'
NF='/api/estoque/importacoes/notas-fiscais'

def pg(program,*args):
    return subprocess.check_output([str(Path(os.environ['NF_PGBIN'])/(program+'.exe' if os.name=='nt' else program)),
        '-h','127.0.0.1','-p',os.environ['NF_PGPORT'],'-U',os.environ['TEST_DB_USERNAME'],*args],
        env={**os.environ,'PGPASSWORD':os.environ['TEST_DB_PASSWORD'],'PGCLIENTENCODING':'UTF8'},text=True,encoding='utf-8',timeout=30).strip()

class Client:
    def __init__(self,port,cpf=None):
        self.port=port;self.cookies=CookieJar();self.opener=build_opener(HTTPCookieProcessor(self.cookies));self.token=''
        if cpf:
            self.expect('/api/auth/login',{'cpf':cpf,'senha':PASSWORD},200)
            self.token=self.expect('/api/auth/csrf',None,200)['token']
            self.expect('/api/auth/reauth',{'senha':PASSWORD},204)
    def request(self,path,body=None,discard=False):
        req=Request(f'http://127.0.0.1:{self.port}'+path,data=None if body is None else json.dumps(body).encode(),
            headers={'Content-Type':'application/json','X-XSRF-TOKEN':self.token})
        try:
            with self.opener.open(req,timeout=25) as r:return r.status, b'' if discard else r.read()
        except HTTPError as e:return e.code,e.read()
    def expect(self,path,body,status):
        code,data=self.request(path,body);assert code==status,(path,code,data.decode(errors='replace'))
        return json.loads(data) if data else None
    def perder_resposta(self,path,body):
        data=json.dumps(body).encode()
        cookie='; '.join(c.name+'='+c.value for c in self.cookies)
        headers=(f'POST {path} HTTP/1.1\r\nHost: 127.0.0.1:{self.port}\r\nContent-Type: application/json\r\n'
                 f'Content-Length: {len(data)}\r\nCookie: {cookie}\r\nX-XSRF-TOKEN: {self.token}\r\nConnection: close\r\n\r\n').encode()
        with socket.create_connection(('127.0.0.1',self.port),timeout=10) as s:
            s.sendall(headers+data) # fechar sem ler status, headers ou corpo

def run(args):
    out=args.out.resolve();out.mkdir(parents=True,exist_ok=False)
    root=Path(__file__).resolve().parents[3]
    java=Path(os.environ['JAVA_HOME'])/'bin'/('java.exe' if os.name=='nt' else 'java')
    version=subprocess.check_output([str(java),'-version'],stderr=subprocess.STDOUT,text=True)
    assert re.search(r'version "25[."]',version),version
    database='poprc_nf_'+uuid4().hex+'_test'
    def sql(q):return pg('psql','-XqAtw','-v','ON_ERROR_STOP=1','-d',database,'-c',q)
    cluster=pg('psql','-XqAtw','-v','ON_ERROR_STOP=1','-d','postgres','-c',
        "select current_setting('data_directory'),current_setting('server_version_num'),current_user")
    directory,pgversion,user=cluster.split('|')
    assert Path(directory).resolve()==Path(os.environ['NF_PGDATA']).resolve()
    assert pgversion.startswith('16') and user==os.environ['TEST_DB_USERNAME']
    pg('createdb',database)
    with socket.socket() as s:s.bind(('127.0.0.1',0));port=s.getsockname()[1]
    uploads=out/'uploads';uploads.mkdir()
    env={**os.environ,'TEST_DB_URL':f'jdbc:postgresql://127.0.0.1:{os.environ["NF_PGPORT"]}/{database}',
         'APP_UPLOAD_DIR':str(uploads)}
    log=(out/'backend.log').open('wb');process=None;results=[]
    def record(caso,**data):
        results.append({'caso':caso,**data});(out/'resultados.json').write_text(json.dumps(results,indent=2,ensure_ascii=False),encoding='utf-8')
    def state():
        return json.loads(sql("""select json_build_object('materiais',(select json_agg(m order by id) from materiais m),
          'saldos',(select json_agg(s order by id) from saldos_materiais_locais s),
          'movimentos',(select json_agg(m order by id) from movimentacoes_estoque m),
          'nfs',(select json_agg(n order by id) from importacoes_notas_fiscais n),
          'itens',(select json_agg(i order by id) from importacoes_notas_fiscais_itens i))"""))
    def files():return {p.relative_to(uploads).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in uploads.rglob('*') if p.is_file()}
    def nf(invalid=False):
        content=('<TESTE-NF>'+uuid4().hex+'</TESTE-NF>').encode();sha=hashlib.sha256(content).hexdigest()
        item={'importar':True,'materialId':material,'descricao':'TESTE NF','quantidade':.125,'valorUnitario':1.2345}
        return {'nomeArquivo':'sintetica.xml','contentType':'application/xml','arquivoBase64':base64.b64encode(content).decode(),
                'hashSha256':sha,'localEstoqueId':local,'numero':'NF-SINTETICA','itens':[item,{**item,'descricao':' '}] if invalid else [item]}
    def private_checks(paths):
        codes={}
        for path in paths:
            for prefix in [path,path.upper()]:
                code,_=admin.request('/uploads/'+prefix);assert code in (403,404),(prefix,code);codes[prefix]=code
        return codes
    try:
        identity=sql("select current_database(),current_user,current_setting('data_directory'),current_setting('server_version_num'),system_identifier from pg_control_system()")
        parts=identity.split('|');assert parts[0]==database and parts[3].startswith('16')
        assert Path(parts[2]).resolve()==Path(os.environ['NF_PGDATA']).resolve()
        record('runtime',identity=identity,java=version,jar_sha256=hashlib.sha256(args.jar.read_bytes()).hexdigest())
        process=subprocess.Popen([str(java),'-jar',str(args.jar.resolve()),'--spring.config.import=',
            '--spring.profiles.active=test','--server.address=127.0.0.1',f'--server.port={port}',
            '--app.security.enabled=true','--app.security.dev-login-enabled=false','--spring.datasource.hikari.maximum-pool-size=4'],
            cwd=root,env=env,stdout=log,stderr=subprocess.STDOUT)
        for _ in range(160):
            assert process.poll() is None,'Backend encerrou; consultar log'
            try:
                with urlopen(f'http://127.0.0.1:{port}/api/auth/config',timeout=1) as r:
                    if r.status==200:break
            except OSError:time.sleep(.5)
        else:raise AssertionError('Backend não iniciou')
        def seed(cpf,perfil):
            return sql(f"insert into funcionarios(nome,cpf,perfil_acesso,ativo,senha_hash) values('TESTE NF','{cpf}','{perfil}',true,'{PASSWORD_HASH}') returning id")
        seed('11144477735','ADMIN');seed('12345678909','TECNICO');seed('98765432100','ESTOQUE')
        admin=Client(port,'11144477735');tecnico=Client(port,'12345678909');estoque=Client(port,'98765432100');anon=Client(port)
        local=admin.expect('/api/estoque/locais',{'nome':'NF DESCARTAVEL'},201)['id']
        material=admin.expect('/api/estoque/materiais',{'nome':'TESTE NF','partNumber':'TESTE-NF','tipoControle':'FRACIONADO',
            'unidadeMedida':'PACOTE','quantidadeDisponivel':0,'metragemDisponivel':0,'custoMedio':0},201)['id']
        before=state();old=files();bad=nf(True)
        assert admin.request(NF,bad)[0]==400;assert state()==before
        new=files();delta=set(new)-set(old)
        if args.antes:
            assert len(delta)==1
            path=next(iter(delta));code,data=tecnico.request('/uploads/'+path)
            assert code==200 and hashlib.sha256(data).hexdigest()==bad['hashSha256']
            record('rollback-validacao-ANTES',delta=list(delta),hashes=new,acesso_tecnico=code,banco_igual=True)
            return
        assert new==old;record('validacao-antes-escrita',banco_igual=True,arquivos_iguais=True)
        admin.expect('/api/estoque/entrada',{'materialId':material,'localEstoqueId':local,
            'funcionarioId':1,'metragem':1,'custoUnitarioEntrada':2},201)
        sql(f"update materiais set metragem_reservada=.375 where id={material};update saldos_materiais_locais set metragem_reservada=.375 where material_id={material} and local_estoque_id={local}")
        # Documento legado referenciado por duas linhas: o download por ID continua disponível.
        legacy=uploads/'notas-fiscais'/'legado.xml';legacy.parent.mkdir();legacy.write_bytes(b'<NF-ANTERIOR/>')
        lhash=hashlib.sha256(legacy.read_bytes()).hexdigest()
        for i in (1,2):
            sql(f"insert into importacoes_notas_fiscais(nome_arquivo,arquivo_path,hash_sha256,tipo_arquivo,data_importacao,importado_por,funcionario_id,local_estoque_id) values('anterior.xml','{legacy.as_posix()}','{str(i)*64}','XML',current_timestamp,'TESTE',1,{local})")
        old=files();before=state()
        # Erro SQL imediato depois da escrita e do primeiro crédito; rollback do lote todo.
        sql("""create function nf_fail() returns trigger language plpgsql as $$begin raise exception 'NF_SQL_SINTETICO';end$$;
          create trigger nf_fail before insert on importacoes_notas_fiscais_itens for each row execute function nf_fail()""")
        failed=nf();code,_=admin.request(NF,failed);assert code>=400
        sql('drop trigger nf_fail on importacoes_notas_fiscais_itens;drop function nf_fail()')
        assert before==state() and old==files();record('rollback-SQL',status=code,banco_igual=True,hashes=files())
        # Erro adiado: método já retornou ao interceptor, COMMIT ainda falha.
        sql("""create function nf_fail() returns trigger language plpgsql as $$begin raise exception 'NF_COMMIT_SINTETICO';end$$;
          create constraint trigger nf_fail after insert on importacoes_notas_fiscais_itens deferrable initially deferred for each row execute function nf_fail()""")
        code,_=admin.request(NF,nf());assert code>=400
        sql('drop trigger nf_fail on importacoes_notas_fiscais_itens;drop function nf_fail()')
        # STATUS_UNKNOWN pode ser reportado pelo gerenciador; resíduo é retido e continua privado.
        assert before==state();uncertain=set(files())-set(old);private_checks(uncertain)
        record('falha-no-commit',status=code,banco_igual=True,residuos_retidos=list(uncertain),acesso=private_checks(uncertain))
        # Falha real de filesystem antes de abrir arquivo.
        private=uploads/'.notas-fiscais';saved=uploads/'.nf-retida-teste';private.rename(saved);private.write_bytes(b'BLOQUEIO SINTETICO')
        try:
            before=state();code,_=admin.request(NF,nf());assert code==400;assert state()==before
            assert private.read_bytes()==b'BLOQUEIO SINTETICO';record('falha-filesystem',status=code,banco_igual=True)
        finally:private.unlink();saved.rename(private)
        # Cliente fecha a conexão sem ler resposta; confirmar commit por conexão independente.
        body=nf();before=state();admin.perder_resposta(NF,body)
        for _ in range(100):
            if sql(f"select count(*) from importacoes_notas_fiscais where hash_sha256='{body['hashSha256']}'")=='1':break
            time.sleep(.1)
        assert sql(f"select count(*) from importacoes_notas_fiscais where hash_sha256='{body['hashSha256']}'")=='1'
        committed=state();path=Path(committed['nfs'][-1]['arquivo_path']);assert path.is_file()
        assert committed['materiais'][0]['metragem_disponivel']==1.125
        assert committed['materiais'][0]['metragem_reservada']==.375
        assert committed['materiais'][0]['custo_medio']==1.9149
        assert hashlib.sha256(path.read_bytes()).hexdigest()==body['hashSha256']
        committed_files=files()
        code,_=admin.request(NF,body);assert code==400 and state()==committed and files()==committed_files
        mid=committed['nfs'][-1]['id']
        for client,expected in [(admin,200),(estoque,200),(tecnico,403),(anon,401)]:
            code,data=client.request(f'{NF}/{mid}/arquivo');assert code==expected,(expected,code)
            if code==200:assert hashlib.sha256(data).hexdigest()==body['hashSha256']
        record('commit-resposta-descartada-retry',retry=400,nf_id=mid,hash=body['hashSha256'],banco=committed,
               acesso_direto=private_checks([path.relative_to(uploads).as_posix(),'notas-fiscais/legado.xml']))
        # Dois clientes autenticados independentes enviam o mesmo documento ao mesmo tempo.
        body=nf();clients=[Client(port,'11144477735'),Client(port,'98765432100')]
        before=state();before_files=files()
        with ThreadPoolExecutor(2) as pool:statuses=list(pool.map(lambda c:c.request(NF,body)[0],clients))
        assert sorted(statuses)==[201,400],statuses
        after=state();assert len(after['nfs'])==len(before['nfs'])+1
        assert len(after['movimentos'])==len(before['movimentos'])+1
        assert len(files())==len(before_files)+1
        assert after['materiais'][0]['metragem_disponivel']==1.25
        assert after['materiais'][0]['metragem_reservada']==.375
        assert next(s for s in after['saldos'] if s['local_estoque_id']==local)['metragem_reservada']==.375
        assert after['materiais'][0]['unidade_medida']=='PACOTE'
        record('concorrencia',statuses=statuses,banco=after,hashes=files())
        # Queda abrupta real do backend: trigger retém a transação depois da escrita.
        sql("""create function nf_pause() returns trigger language plpgsql as $$begin perform pg_sleep(5);return new;end$$;
          create trigger nf_pause before insert on importacoes_notas_fiscais_itens for each row execute function nf_pause()""")
        before=state();before_files=files();body=nf()
        with ThreadPoolExecutor(1) as pool:
            future=pool.submit(admin.request,NF,body)
            for _ in range(100):
                backend_pid=sql("select pid from pg_stat_activity where datname=current_database() and wait_event='PgSleep'")
                if backend_pid:break
                time.sleep(.1)
            else:raise AssertionError('Ponto SQL de interrupção não alcançado')
            process.kill();process.wait(timeout=15)
            try:future.result(timeout=15)
            except (OSError,TimeoutError):pass
        # Esperar também o encerramento da conexão PostgreSQL do processo morto.
        for _ in range(100):
            if sql(f'select count(*) from pg_stat_activity where pid={int(backend_pid)}')=='0':break
            time.sleep(.1)
        assert sql(f'select count(*) from pg_stat_activity where pid={int(backend_pid)}')=='0'
        assert state()==before
        residual=set(files())-set(before_files);assert len(residual)==1
        record('kill-backend-apos-escrita-antes-commit',banco_igual=True,residuo=list(residual),hashes=files())
        # Reinício no mesmo banco/armazenamento; resíduo privado e retry seguro com novo caminho.
        sql('drop trigger nf_pause on importacoes_notas_fiscais_itens;drop function nf_pause()')
        process=subprocess.Popen([str(java),'-jar',str(args.jar.resolve()),'--spring.config.import=',
            '--spring.profiles.active=test','--server.address=127.0.0.1',f'--server.port={port}',
            '--app.security.enabled=true','--spring.datasource.hikari.maximum-pool-size=4'],cwd=root,env=env,stdout=log,stderr=subprocess.STDOUT)
        for _ in range(160):
            try:
                with urlopen(f'http://127.0.0.1:{port}/api/auth/config',timeout=1) as r:
                    if r.status==200:break
            except OSError:time.sleep(.5)
        admin=Client(port,'11144477735');private_checks(residual)
        result=admin.expect(NF,body,201);after=state();assert len(after['nfs'])==len(before['nfs'])+1
        assert len(after['movimentos'])==len(before['movimentos'])+1
        assert after['materiais'][0]['metragem_disponivel']==1.375
        assert after['materiais'][0]['metragem_reservada']==.375
        assert residual.issubset(files()) # nada é varrido após reinício/retry
        assert files()['notas-fiscais/legado.xml']==lhash
        for i in (1,2):
            code,data=admin.request(f'{NF}/{i}/arquivo');assert code==200 and hashlib.sha256(data).hexdigest()==lhash
        record('recuperacao-pos-kill',nf_id=after['nfs'][-1]['id'],residuo_preservado=list(residual),acesso=private_checks(residual),banco=after,hashes=files())
        record('APROVADO',casos=len(results)-1)
    finally:
        if process and process.poll() is None:
            process.terminate()
            try:process.wait(timeout=15)
            except subprocess.TimeoutExpired:process.kill();process.wait(timeout=10)
        log.close()
        pg('dropdb','--force',database)
        record('encerramento',banco_removido=database)

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--jar',type=Path,required=True)
    parser.add_argument('--out',type=Path,required=True);parser.add_argument('--antes',action='store_true')
    run(parser.parse_args())
