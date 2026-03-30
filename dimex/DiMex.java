package dimex;

import pl.DiMexListener;
import pl.Message;
import pl.PerfectLink;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;

public class DiMex implements DiMexListener {

    // =========================================================
    // VARIÁVEIS DE ESTADO DO RICART-AGRAWALA
    // =========================================================
    
    // Os 3 estados possíveis do processo
    private enum State { RELEASED, WANTED, HELD }
    private State state;
    
    // Relógio Lógico de Lamport (atualizado a cada evento)
    private int clock;
    
    // O timestamp exato de quando ESTE processo pediu para entrar na RC
    private int requestClock;
    
    // Fila de Respostas Adiadas: guarda quem pediu para entrar depois de nós
    private Queue<String> deferredQueue;
    
    // Conjunto de processos de quem ainda estamos esperando o "OK"
    // Usamos um Set (conjunto) em vez de um número (int) para lidar melhor com falhas
    private Set<String> waitingAcks;

    // =========================================================
    // INFRAESTRUTURA
    // =========================================================
    
    private String myId;
    private PerfectLink pl;

    /**
     * CONSTRUTOR
     * Objetivo: Inicializar o DiMex em estado de repouso e preparar as filas.
     */
    public DiMex(String myId) {
        this.myId = myId;
        
        this.state = State.RELEASED;
        this.clock = 0;
        this.requestClock = 0;
        
        this.deferredQueue = new LinkedList<>();
        this.waitingAcks = new HashSet<>();
    }

    public void setPerfectLink(PerfectLink pl) {
        this.pl = pl;
    }

    // =========================================================
    // INTERFACE COM A APLICAÇÃO (De Cima para Baixo)
    // =========================================================

    /**
     * ENTRY (Solicita a Região Crítica)
     * Objetivo: É chamado pela App. Bloqueia a execução até que todos 
     * os outros processos da rede enviem um "OK".
     */
    public synchronized void entry() {
        System.out.println("[DiMex] App solicitou ENTRY. Iniciando protocolo...");
        
        // 1. Muda o estado para WANTED e anota o horário do pedido
        this.state = State.WANTED;
        this.clock++; // Incrementa o relógio local
        this.requestClock = this.clock; // Salva o nosso "ticket"
        
        // 2. Descobre quem está na rede e coloca todos na lista de "Aguardando OK"
        Set<String> peers = pl.getPeers();
        this.waitingAcks.clear();
        this.waitingAcks.addAll(peers);
        
        // Otimização: Se estivermos sozinhos na rede, entramos direto!
        if (this.waitingAcks.isEmpty()) {
            this.state = State.HELD;
            System.out.println("[DiMex] Rede vazia. Permissão HELD concedida imediatamente.");
            return;
        }

        // 3. Dispara a mensagem de REQUEST para todos os conhecidos
        Message reqMsg = new Message(myId, "REQUEST", requestClock);
        for (String peer : peers) {
            pl.send(peer, reqMsg);
            System.out.println("[DiMex] Enviei REQUEST para " + peer);
        }

        // 4. Bloqueia a Thread da App até que a lista de espera esvazie
        // O método wait() libera o 'synchronized' temporariamente para o deliver() poder rodar
        while (!this.waitingAcks.isEmpty()) {
            try {
                wait(); 
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        
        // 5. Se saiu do while, é porque recebemos todos os OKs!
        this.state = State.HELD;
        System.out.println("[DiMex] PERMISSÃO TOTAL RECEBIDA. Estado: HELD");
    }

    /**
     * EXIT (Libera a Região Crítica)
     * Objetivo: É chamado pela App ao terminar o trabalho. Libera quem estava esperando.
     */
    public synchronized void exit() {
        System.out.println("[DiMex] App solicitou EXIT. Liberando Região Crítica...");
        
        // 1. Volta para o estado de repouso
        this.state = State.RELEASED;
        System.out.println("[DiMex] Saída concluída. Estado: RELEASED");
        this.clock++; // Atualiza o relógio lógico por causa do novo evento
        
        // 2. Envia um "OK" para todos que foram colocados na geladeira (Fila Adiada)
        Message okMsg = new Message(myId, "OK", this.clock);
        for (String deferredPeer : deferredQueue) {
            pl.send(deferredPeer, okMsg);
            System.out.println("[DiMex] Enviei OK atrasado para " + deferredPeer);
        }
        
        // 3. Limpa a fila, pois todos já foram atendidos
        deferredQueue.clear();
    }

    // =========================================================
    // INTERFACE COM O PERFECT LINK (De Baixo para Cima)
    // =========================================================

    /**
     * DELIVER (Recebe mensagens da Rede)
     * Objetivo: Processar os REQUESTs e OKs que chegam via TCP.
     */
    @Override
    public synchronized void deliver(Message msg) {
        String sender = msg.getSenderId();
        String type = msg.getType();
        int msgClock = msg.getClock();

        // 1. Atualização do Relógio de Lamport: MAX(local, recebido) + 1
        this.clock = Math.max(this.clock, msgClock) + 1;

        if (type.equals("REQUEST")) {
            // Lógica Central do Ricart-Agrawala: Decidir se enviamos OK ou se Adiamos
            boolean defer = false;

            if (this.state == State.HELD) {
                // Já estamos usando a RC. O outro tem que esperar.
                defer = true;
            } else if (this.state == State.WANTED) {
                // Conflito! Nós também queremos. Quem tem prioridade?
                // Regra: Menor relógio ganha. Em caso de empate, menor ID ganha.
                if (this.requestClock < msgClock) {
                    defer = true; // Nosso pedido é mais antigo. Ele espera.
                } else if (this.requestClock == msgClock && this.myId.compareTo(sender) < 0) {
                    defer = true; // Empatou, mas nosso ID é menor (ex: p1 ganha de p2). Ele espera.
                }
            }

            if (defer) {
                deferredQueue.add(sender);
                System.out.println("[DiMex] REQUEST de " + sender + " ADIADO (Na fila).");
            } else {
                // Não queremos a RC ou o pedido dele é mais prioritário que o nosso.
                Message okMsg = new Message(myId, "OK", this.clock);
                pl.send(sender, okMsg);
                System.out.println("[DiMex] Enviei OK imediato para " + sender);
            }

        } else if (type.equals("OK")) {
            // Recebemos uma permissão!
            System.out.println("[DiMex] Recebi OK de " + sender);
            this.waitingAcks.remove(sender); // Tira da lista de pendências
            
            // Se a lista esvaziou, acordamos a App que estava travada no método entry()
            if (this.waitingAcks.isEmpty() && this.state == State.WANTED) {
                notifyAll(); 
            }
        }
    }

    /**
     * PROCESS CRASHED (Tolerância a Falhas)
     * Objetivo: Evitar Deadlock se um processo morrer antes de mandar o "OK".
     */
    @Override
    public synchronized void processCrashed(String peerId) {
        System.out.println("[DiMex] Notificação de falha processada para: " + peerId);
        
        // Se estávamos esperando o OK de um cara que morreu, 
        // removemos ele da lista de espera para não ficarmos travados para sempre.
        if (this.waitingAcks.contains(peerId)) {
            this.waitingAcks.remove(peerId);
            System.out.println("  -> Removido da lista de espera de OKs.");
            
            // Verifica se com essa "saída", agora nós temos todos os OKs necessários
            if (this.waitingAcks.isEmpty() && this.state == State.WANTED) {
                notifyAll(); // Acorda a thread do entry()
            }
        }
        
        // Se ele estava na fila de adiados esperando um OK nosso no futuro,
        // apenas removemos, afinal não precisamos responder a um morto.
        deferredQueue.remove(peerId);
    }
}