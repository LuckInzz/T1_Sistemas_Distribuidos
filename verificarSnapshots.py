import os
import re
from collections import defaultdict

def parse_logs():
    """Lê todos os arquivos snapshots_*.log e agrupa por Snapshot ID."""
    snapshots = defaultdict(list)
    log_files = [f for f in os.listdir('.') if f.startswith('snapshots_p') and f.endswith('.log')]
    
    print("==================================================")
    print(" ARQUIVOS ENCONTRADOS PARA VERIFICAÇÃO")
    print("==================================================")
    
    for filename in log_files:
        print(f" -> Lendo: {filename}")
        with open(filename, 'r') as f:
            for line in f:
                if not line.strip(): continue
                parts = line.strip().split(';')
                if len(parts) < 7: continue
                
                data = {
                    'process_id': parts[1],
                    'state': parts[2],
                    'clock': int(parts[3]),
                    'req_clock': int(parts[4]),
                    'deferred': parts[5].strip('[]').split(',') if parts[5] != '[]' else [],
                    'channels': parse_channels(parts[6])
                }
                snapshots[int(parts[0])].append(data)
    print("==================================================\n")
    return snapshots

def parse_channels(channel_str):
    channels = {}
    if channel_str == "VAZIO": return channels
    pairs = channel_str.split('|')
    for pair in pairs:
        match = re.match(r"(.+):\[(.*)\]", pair)
        if match:
            peer_id = match.group(1)
            messages = match.group(2).split(',') if match.group(2) else []
            channels[peer_id] = messages
    return channels

# =========================================================
# INVARIANTES COM EXPLICAÇÃO DETALHADA
# =========================================================

def inv_1_max_one_sc(processes):
    """Invariante 1: No máximo um processo na Seção Crítica."""
    estados = [f"{p['process_id']}={p['state']}" for p in processes]
    print(f"      [DEBUG] Estados: {', '.join(estados)}")
    
    held_procs = [p['process_id'] for p in processes if p['state'] == 'HELD']
    if len(held_procs) <= 1:
        return True
    return False

def inv_2_quiescent_state(processes):
    """Invariante 2: Se ninguém quer a SC, canais e filas devem estar vazios."""
    if all(p['state'] == 'RELEASED' for p in processes):
        for p in processes:
            msgs_count = sum(len(m) for m in p['channels'].values())
            print(f"      [DEBUG] {p['process_id']} (RELEASED) -> Filas: {len(p['deferred'])}, Msgs Canais: {msgs_count}")
            if p['deferred'] or msgs_count > 0:
                return False
    else:
        print(f"      [DEBUG] Alguém está WANTED ou HELD, ignorando teste de repouso.")
    return True

def inv_3_deferred_logic(processes):
    """Invariante 3: Se Q está na fila de P, então P deve estar HELD ou WANTED."""
    for p in processes:
        if p['deferred']:
            print(f"      [DEBUG] {p['process_id']} ({p['state']}) adiou: {p['deferred']}")
            if p['state'] not in ['HELD', 'WANTED']:
                return False
        else:
            print(f"      [DEBUG] {p['process_id']} ({p['state']}) não adiou ninguém.")
    return True

def inv_4_safety_count(processes):
    """Invariante 4: Soma de permissões deve ser N-1."""
    N = len(processes)
    for p in processes:
        if p['state'] == 'WANTED':
            perms = 0
            detalhes = []
            for q in processes:
                if q['process_id'] == p['process_id']: continue
                
                # Checa se há OK voando ou se P está na fila de Q
                ok_transito = 'OK' in p['channels'].get(q['process_id'], [])
                p_na_fila_q = p['process_id'] in q['deferred']
                
                # Se não está em trânsito nem na fila, assumimos que já recebeu o OK
                # (Lógica simplificada para o log)
                ja_tem = not ok_transito and not p_na_fila_q and not (q['state'] == 'WANTED' and (q['req_clock'], q['process_id']) < (p['req_clock'], p['process_id']))
                
                if ok_transito or p_na_fila_q or ja_tem:
                    perms += 1
                    detalhes.append(f"de {q['process_id']}")

            print(f"      [DEBUG] {p['process_id']} WANTED: {perms}/{N-1} permissões ({', '.join(detalhes)})")
            if perms != N - 1:
                return False
    return True

# =========================================================
# EXECUÇÃO
# =========================================================

if __name__ == "__main__":
    all_snapshots = parse_logs()
    N_sistema = max(len(v) for v in all_snapshots.values()) if all_snapshots else 0
    
    for sn_id in sorted(all_snapshots.keys()):
        procs = all_snapshots[sn_id]
        if len(procs) < N_sistema: continue

        print(f"\n>> ANALISANDO SNAPSHOT GLOBAL ID: {sn_id}")
        testes = [
            ("Inv 1 (Exclusão Mútua)", inv_1_max_one_sc),
            ("Inv 2 (Estado de Repouso)", inv_2_quiescent_state),
            ("Inv 3 (Lógica de Adiamento)", inv_3_deferred_logic),
            ("Inv 4 (Contagem de Permissões)", inv_4_safety_count)
        ]
        
        for nome, func in testes:
            print(f"   * Testando {nome}...")
            if func(procs):
                print(f"     [ OK ]")
            else:
                print(f"     [ERRO] Violada!")