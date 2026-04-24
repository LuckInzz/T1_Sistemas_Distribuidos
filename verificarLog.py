import re

ARQUIVO = "rc_log.txt"

try:
    with open(ARQUIVO, "r") as f:
        conteudo = f.read()
except FileNotFoundError:
    print(f"Arquivo '{ARQUIVO}' não encontrado.")
    exit(1)

# Remove tudo que não for '.' ou '|' para analisar só a sequência
sequencia = re.sub(r'[^.|]', '', conteudo)

print(f"Sequência lida: {sequencia}")
print(f"Total de tokens: {len(sequencia)}")

violacoes = []

for i in range(len(sequencia) - 1):
    if sequencia[i] == sequencia[i + 1]:
        violacoes.append((i, sequencia[i], sequencia[i:i+2]))

if violacoes:
    print(f"\n[FALHA] {len(violacoes)} violação(ões) encontrada(s):")
    for pos, char, trecho in violacoes:
        tipo = "dois pontos seguidos" if char == '.' else "duas barras seguidas"
        print(f"  posição {pos}: '{trecho}' -> {tipo}")
else:
    print("\n[OK] Nenhuma violação. Sequência '.|.|.|...' está correta.")
