import glob
import collections

# Estrutura para armazenar o snapshot global: snapshots[snap_id][process_id] = dados
global_snapshots = collections.defaultdict(dict)

def parse_list(s):
    s = s.strip("[]")
    return s.split(",") if s else []

def parse_channels(s):
    if s == "VAZIO": return {}
    channels = {}
    parts = s.split("|")
    for p in parts:
        pid, msgs = p.split(":")
        channels[pid] = msgs.strip("[]").split(",")
    return channels

# 1. Leitura de todos os arquivos de log
arquivos = glob.glob("snapshots_*.log")
if not arquivos:
    print("Nenhum arquivo 'snapshots_*.log' encontrado na pasta atual.")
    exit()

for filename in arquivos:
    with open(filename, 'r') as f:
        for line in f:
            line = line.strip()
            if not line: continue
            
            parts = line.split(";")
            snap_id = int(parts[0])
            pid = parts[1]
            state = parts[2]
            clock = int(parts[3])
            req_clock = int(parts[4])
            deferred = parse_list(parts[5])
            waiting = parse_list(parts[6])
            channels = parse_channels(parts[7])
            
            global_snapshots[snap_id][pid] = {
                'state': state, 'clock': clock, 'req_clock': req_clock,
                'deferred': deferred, 'waiting': waiting, 'channels': channels
            }

# Usando SETS para armazenar quais snapshots deram erro em cada invariante
snaps_erro_inv1 = set()
snaps_erro_inv2 = set()
snaps_erro_inv3 = set()
snaps_erro_inv4 = set()
snaps_erro_inv5 = set()

print("INICIANDO VERIFICACAO DETALHADA DOS SNAPSHOTS")

# 2. Avaliação Snapshot por Snapshot
for snap_id in sorted(global_snapshots.keys()):
    processes = global_snapshots[snap_id]
    
    print(f"\n{'='*70}")
    print(f"SNAPSHOT ID: {snap_id}")
    print(f"{'-'*70}")
    
    # --- PRINT DO ESTADO DOS PROCESSOS ---
    print("ESTADO DOS PROCESSOS LIDOS:")
    for pid, data in processes.items():
        print(f"  [{pid}] Estado: {data['state']:<8} | Relogio: {data['clock']} | ReqClock: {data['req_clock']}")
        print(f"           DeferredQueue: {data['deferred']}")
        print(f"           WaitingAcks  : {data['waiting']}")
        print(f"           Canais Entrada: {data['channels']}")
    print(f"{'-'*70}")
    print("AVALIACAO DAS INVARIANTES:")
    
    # --- INVARIANTE 1: Exclusão Mútua ---
    print("\n[INV 1] EXCLUSAO MUTUA (No maximo 1 processo em HELD):")
    print("  Regra: Garante a regra de ouro de que apenas um processo pode possuir o recurso (estar na Secao Critica) ao mesmo tempo.")
    held_nodes = [p for p, data in processes.items() if data['state'] == 'HELD']
    print(f"  -> Processos em HELD encontrados: {len(held_nodes)} {held_nodes}")
    if len(held_nodes) > 1:
        print("  [X] ERRO INV 1: Violacao! Multiplos processos na Regiao Critica.")
        snaps_erro_inv1.add(snap_id)
    else:
        print("  [V] OK: Regra respeitada.")

    # --- INVARIANTE 2: Consistência de Estado Local ---
    print("\n[INV 2] CONSISTENCIA DE ESTADO LOCAL (Filas coerentes com o estado):")
    print("  Regra: Um processo livre (RELEASED) nao pode reter OKs nem ter pendencias. Um processo usando o recurso (HELD) nao pode estar esperando OKs.")
    for pid, data in processes.items():
        state = data['state']
        deferred = data['deferred']
        waiting = data['waiting']
        
        if state == 'RELEASED':
            print(f"  -> {pid} esta RELEASED. Checando filas vazias... ", end="")
            if len(deferred) > 0 or len(waiting) > 0:
                print(f"[X] ERRO INV 2: Filas sujas! Def={deferred}, Wait={waiting}")
                snaps_erro_inv2.add(snap_id)
            else:
                print("[V] OK.")
        elif state == 'HELD':
            print(f"  -> {pid} esta HELD. Checando se não aguarda ninguem... ", end="")
            if len(waiting) > 0:
                print(f"[X] ERRO INV 2: Ainda aguarda {waiting}!")
                snaps_erro_inv2.add(snap_id)
            else:
                print("[V] OK.")
        else: # WANTED
            print(f"  -> {pid} esta WANTED. Estado transitorio ignorado para esta regra. [V] OK.")

    # --- INVARIANTE 3: Conservação de Mensagens ---
    print("\n[INV 3] CONSERVACAO DE MENSAGENS (Pedidos nao somem na rede):")
    print("  Regra: Se um processoi aguarda OK, a sua solicitacao deve estar no cabo, ou na fila do destino, ou o OK deve estar voltando.")
    inv3_testado = False
    for pid, data in processes.items():
        for peer_id in data['waiting']:
            inv3_testado = True
            print(f"  -> {pid} aguarda OK de {peer_id}:")
            if peer_id not in processes:
                print(f"     [!] Ignorado: O processo {peer_id} nao esta no log deste snapshot.")
                continue
            
            peer_data = processes[peer_id]
            msg_ok_in_transit = 'OK' in data['channels'].get(peer_id, [])
            msg_req_in_transit = 'REQUEST' in peer_data['channels'].get(pid, [])
            in_deferred_queue = pid in peer_data['deferred']
            
            print(f"     - Tem 'OK' voando para {pid}? {msg_ok_in_transit}")
            print(f"     - Tem 'REQUEST' voando para {peer_id}? {msg_req_in_transit}")
            print(f"     - {pid} esta na deferred queue de {peer_id}? {in_deferred_queue}")
            
            if msg_ok_in_transit or msg_req_in_transit or in_deferred_queue:
                print("     [V] OK: A intenção esta devidamente registrada na rede ou no alvo.")
            else:
                print("     [X] ERRO INV 3: Mensagem perdida! Nao ha rastro da requisicao.")
                snaps_erro_inv3.add(snap_id)

    if not inv3_testado:
        print("  -> Nenhum processo estava aguardando (waiting vazio). [V] OK.")

    # --- INVARIANTE 4: Respeito à Prioridade ---
    print("\n[INV 4] RESPEITO A PRIORIDADE (Atrasos justificados):")
    print("  Regra: Ninguem sofre atrasos injustos. Reter um OK so e permitido se voce estiver usando o recurso, ou se voce pediu antes (relogio menor/empate ID).")
    inv4_testado = False
    for pid, data in processes.items():
        state = data['state']
        req_clock = data['req_clock']
        
        for deferred_peer in data['deferred']:
            inv4_testado = True
            print(f"  -> {pid} (Estado: {state}, ReqClock: {req_clock}) colocou {deferred_peer} na geladeira:")
            if deferred_peer not in processes:
                print(f"     [!] Ignorado: O processo {deferred_peer} nao esta no log.")
                continue
            
            peer_data = processes[deferred_peer]
            
            if state == 'HELD':
                print("     [V] OK: Justificado porque já está usando a Seção Crítica (HELD).")
            elif state == 'RELEASED':
                print("     [X] ERRO INV 4: Injustificado! Processo RELEASED nao pode reter OK.")
                snaps_erro_inv4.add(snap_id)
            elif state == 'WANTED':
                peer_req_clock = peer_data['req_clock']
                print(f"     Comparando prioridades: Meu ReqClock ({req_clock}) vs Do Alvo ({peer_req_clock})")
                
                # Regra de desempate
                if req_clock < peer_req_clock:
                    print("     [V] OK: Justificado por relógio menor (pediu antes).")
                elif req_clock == peer_req_clock:
                    if pid < deferred_peer:
                        print(f"     [V] OK: Empate de relogio, mas ID menor vence ({pid} < {deferred_peer}).")
                    else:
                        print(f"     [X] ERRO INV 4: Empate de relogio, mas ID maior segurou ID menor!")
                        snaps_erro_inv4.add(snap_id)
                else:
                    print(f"     [X] ERRO INV 4: Violacao! Relogio MAIOR ({req_clock}) segurou relogio MENOR ({peer_req_clock})!")
                    snaps_erro_inv4.add(snap_id)
    if not inv4_testado:
        print("  -> Ninguem foi colocado na geladeira (deferred vazio). [V] OK.")


    # --- INVARIANTE 5: Validade das Mensagens em Trânsito (Sem Fantasmas) ---
    print("\n[INV 5] VALIDADE DAS MENSAGENS EM TRANSITO (Sem Fantasmas):")
    print("  Regra: Mensagens voando na rede devem fazer sentido com o estado atual de quem as enviou.")
    inv5_testado = False
    
    for pid, data in processes.items():
        # Canais salvos no snapshot de 'pid' são mensagens vindas de 'peer_id' PARA 'pid'
        for peer_id, msgs in data['channels'].items():
            if not msgs or msgs == ['VAZIO']:
                continue
                
            if peer_id not in processes:
                print(f"    [!] Ignorado: Mensagens de {peer_id} para {pid}, mas {peer_id} nao esta no log.")
                continue
            
            inv5_testado = True
            sender_state = processes[peer_id]['state']
            
            # Regra 5.1: Se tem REQUEST no canal, o remetente (peer_id) TEM que estar WANTED
            if 'REQUEST' in msgs:
                print(f"  -> 'REQUEST' em transito de {peer_id} para {pid}. Estado do remetente ({peer_id}): {sender_state}")
                if sender_state != 'WANTED':
                    print(f"    [X] ERRO INV 5: Fantasma! {peer_id} enviou REQUEST mas seu estado é {sender_state}.")
                    snaps_erro_inv5.add(snap_id)
                else:
                    print("    [V] OK: Remetente está WANTED, justificando o REQUEST no canal.")
            
            # Regra 5.2: Se tem OK no canal, o remetente (peer_id) NAO PODE estar HELD
            if 'OK' in msgs:
                print(f"  -> 'OK' em transito de {peer_id} para {pid}. Estado do remetente ({peer_id}): {sender_state}")
                if sender_state == 'HELD':
                    print(f"    [X] ERRO INV 5: Contradição! {peer_id} enviou OK (concedeu acesso) mas está HELD.")
                    snaps_erro_inv5.add(snap_id)
                else:
                    print(f"    [V] OK: Remetente está {sender_state}, justificando o OK no canal.")

    if not inv5_testado:
        print("  -> Nenhuma mensagem em transito nos canais capturados. [V] OK.")

print(f"\n{'='*70}")
print("RESUMO FINAL DA AUDITORIA:")
print(f"Total de Snapshots processados: {len(global_snapshots)}")
print("-" * 70)

# Formatação limpa para o relatório final
def formatar_resultado(nome_inv, conjunto_erros):
    if len(conjunto_erros) == 0:
        return f"{nome_inv:<30} : 0 erros"
    else:
        lista_ordenada = sorted(list(conjunto_erros))
        return f"{nome_inv:<30} : {len(conjunto_erros)} snapshot(s) com erro -> {lista_ordenada}"

print(formatar_resultado("Teste 1 (Apenas um na SC)", snaps_erro_inv1))
print(formatar_resultado("Teste 2 (Released = waiting vazio + canal vazio / Held = waiting vazio)", snaps_erro_inv2))
print(formatar_resultado("Teste 3 (Se tem no waiting: ou REQ ta indo, ou OK ta voltando, ou foi posto na espera)", snaps_erro_inv3))
print(formatar_resultado("Teste 4 (Prioridade para por na lista de espera - Held, Wanted - c/ menor relogio ou menor ID)", snaps_erro_inv4))
print(formatar_resultado("Teste 5 (Mensagens em transito condizem com estado do remetente)", snaps_erro_inv5)) 
print(f"{'='*70}")