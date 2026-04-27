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
    private enum State {
        RELEASED, WANTED, HELD
    }

    private State state;

    // Relógio Lógico de Lamport (atualizado a cada evento)
    private int clock;

    // O timestamp exato de quando ESTE processo pediu para entrar na RC
    private int requestClock;

    // Fila de Respostas Adiadas: guarda quem pediu para entrar depois de nós
    private Queue<String> deferredQueue;

    // Conjunto de processos de quem ainda estamos esperando o "OK"
    // Usamos um Set (conjunto) em vez de um número (int) para lidar melhor com
    // falhas
    private Set<String> waitingAcks;

    // =========================================================
    // SNAPSHOT
    // =========================================================
    // O snapshot atual
    private Snapshot currentSnapshot;
    // Se ta fazendo a captura
    private boolean isRecording = false;
    // se já recebeu todos os markers e pode terminar a captura.
private Set<String> markersReceived = new HashSet<>(); // <-- Adiciona o = new HashSet<>() aqui
    // =========================================================
    // INFRAESTRUTURA
    // =========================================================

    private String myId;
    private PerfectLink pl;

    // INICIALIZACAO DESTE DIMEX COM O ID DESTE PROCESSO VINDO DO APP
    public DiMex(String myId) {
        this.myId = myId;

        // começa como solto com tudo zerado
        this.state = State.RELEASED;
        this.clock = 0;
        this.requestClock = 0;

        this.deferredQueue = new LinkedList<>();
        this.waitingAcks = new HashSet<>();
    }

    // funcao para setar o pl vinculado a este
    public void setPerfectLink(PerfectLink pl) {
        this.pl = pl;
    }

    // =========================================================
    // INTERFACE COM A APLICAÇÃO (De Cima para Baixo)
    // =========================================================

    // Funcao que o APP chama para tentar acesso a sc
    public synchronized void entry() {
        System.out.println("[DiMex] App solicitou ENTRY. Iniciando protocolo...");

        // 1. Muda o estado para WANTED e anota o horário do pedido
        this.state = State.WANTED;
        this.clock++; // Incrementa o relógio local
        this.requestClock = this.clock; 

        // 2. Busca no PL todos os processos da rede
        // e adiciona na lista de espera
        Set<String> peers = pl.getPeers();
        this.waitingAcks.clear();
        this.waitingAcks.addAll(peers);

        // Otimização: Se estivermos sozinhos na rede, entramos direto!
        if (this.waitingAcks.isEmpty()) {
            this.state = State.HELD;
            System.out.println("[DiMex] Rede vazia. Permissão HELD concedida imediatamente.");
            return;
        }

        // 3. Manda um REQUEST para todos os conhecidos da rede
        // cria a mensagem de request
        Message reqMsg = new Message(myId, "REQUEST", requestClock);
        // envia par cada um da rede
        for (String peer : peers) {
            pl.send(peer, reqMsg);
            System.out.println("[DiMex] Enviei REQUEST para " + peer);
        }

        // 4. Bloqueia o APP até que chegue o OK de todos os outros da Rede (watingAcks
        // tem que estar vazio)
        // O método wait() libera o 'synchronized' temporariamente para o deliver()
        // poder rodar ??????
        while (!this.waitingAcks.isEmpty()) {
            try {
                wait();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        // 5. Se saiu do while, é porque recebemos todos os OKs!
        // muda o estado para HELD, pois esta na SC
        this.state = State.HELD;
        System.out.println("[DiMex] PERMISSÃO TOTAL RECEBIDA. Estado: HELD");
        // termina a função liberando APP para escrever
    }

    // funcao que o APP chama para liberar a SC
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

    // recebe requests e oks que chegam da rede
    @Override
    public synchronized void deliver(Message msg) {
        // pega quem enviou, tipo de msg e o timestamp registrado nela
        String sender = msg.getSenderId();
        String type = msg.getType();
        int msgClock = msg.getClock();

        ///////////////////// ADICAO DO SNAPSHOT E MARKERS ///////////////////////////
        // se a mensagem for um marker
        if (type.equals("MARKER")) {
            // invés de um clock, a variagel vai ser o ID do Snapshot
            int snapshotId = msgClock;

            System.out.println("[DiMex] Recebi MARKER de " + sender + " para o Snapshot " + snapshotId);

            // Se é a primeira vez por este canal, preciso começar a gravar as situacoes das
            // mensagens
            if (!this.isRecording) {
                // começa este snapshot a partir deste
                startSnapshot(snapshotId);
            }

            // Adiciona quem enviou o Marker inicial na lista, pois não precisa capturar o
            // canal dele
            this.markersReceived.add(sender);

            // Verifica se já recebemos marcadores de TODOS os processos da rede
            if (this.markersReceived.size() == pl.getPeers().size()) {
                System.out.println("[DiMex] <<< Todos os MARKERS recebidos! Snapshot " + currentSnapshot.getSnapshotId()
                        + " concluído >>>");
                //salva o snapshot no tct
                saveSnapshotToFile();
                this.isRecording = false; // Desliga a gravação de canais
            }

            // Como está funcao foi chamada apenas para fazer um snapshot, ela acaba aqui
            return;
        }

        // Adicionalmente, se estamos capturando mensagens dos canais (menos do criador
        // do snapshot)
        // vamos adicionando as mensagens na lista
        if (this.isRecording && !this.markersReceived.contains(sender)) {
            System.out.println("[DiMex] Capturando mensagem '" + type + "' em trânsito vinda de " + sender);
            this.currentSnapshot.addMessageToChannel(sender, msg);
        }

        ///////////////////// PARTE NORMAL DO ALGORITMO ///////////////////////////
        // 1. Atualização do Relógio de Lamport
        // ve qual é o maior(seu proprio) ou o da mensagem e atualiza já somando este
        ///////////////////// passo
        this.clock = Math.max(this.clock, msgClock) + 1;

        // se é uma mensagem de request
        if (type.equals("REQUEST")) {
            // Lógica Central do Ricart-Agrawala: Decidir se enviamos OK ou se Adiamos
            boolean defer = false;

            // se eu to usando a RC, o outro precisa esperar
            if (this.state == State.HELD) {
                defer = true;
            }
            // se eu to querendo, causa conflito, pois os dois querem
            else if (this.state == State.WANTED) {
                // Regra: Menor relógio ganha. Em caso de empate, menor ID ganha.
                // se o relógio ta antes, tenho prioridade
                if (this.requestClock < msgClock) {
                    defer = true;

                }
                // se o relogio é igual, o de menor ID ganha
                else if (this.requestClock == msgClock && this.myId.compareTo(sender) < 0) {
                    defer = true;
                }
            }

            // se o outro tem que esperar
            if (defer) {
                // adiciono ele na fila de quem esta esperando
                deferredQueue.add(sender);
                System.out.println("[DiMex] REQUEST de " + sender + " ADIADO (Na fila).");
            }
            // se não, mando um OK pra ele, pois ele é prioridade
            else {
                // Não queremos a RC ou o pedido dele é mais prioritário que o nosso.
                Message okMsg = new Message(myId, "OK", this.clock);
                pl.send(sender, okMsg);
                System.out.println("[DiMex] Enviei OK imediato para " + sender);
            }

            // se a mensagem recebida é OK, tiro o processo que enviou da lista de acks
            // aguardados
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

    /////////// MÉTODOS DO SNAPSHOT /////////////////////////
    
    ///COMECAR UM SNAPSHOT
    public synchronized void startSnapshot(int id) {
        System.out.println("[DiMex] >>> INICIANDO SNAPSHOT GLOBAL ID: " + id + " <<<");

        // Cria um Snapshot com o estado atual do dimex.
        this.currentSnapshot = new Snapshot(
            id, 
            this.myId, 
            this.state.name(), 
            this.clock, 
            this.requestClock, 
            this.deferredQueue,
            this.waitingAcks
        );
        
        // Troca para gravando, assim consegue salvar as mensagens que chegam
        this.isRecording = true;

        // Limpa a lista de markers recebidos
        this.markersReceived.clear();

        // Cria uma mensagem marker => aqui o Clock foi trocado pelo ID deste snapshot
        Message markerMsg = new Message(myId, "MARKER", id);

        // busca todo mundo conectado e envia a msg para todos
        Set<String> peers = pl.getPeers();
        for (String peer : peers) {
            pl.send(peer, markerMsg);
            System.out.println("[DiMex] MARKER enviado para o canal: " + peer);
        }

        // Se estivermos sozinhos na rede, o snapshot termina imediatamente
        if (peers.isEmpty()) {
            System.out.println("[DiMex] Nenhum vizinho detectado. Snapshot local concluído.");
            //salva o snapshot no arquivo
            saveSnapshotToFile();
            this.isRecording = false;
        }
    }

    //SALVAR O SNAPSHOT NO ARQUIVO TEXTO
    private void saveSnapshotToFile() {
        if (currentSnapshot == null) return;
        
        // Nome fixo para este processo (ex: snapshots_p1234.log)
        String filename = "snapshots_" + myId + ".log";
        
        // O 'true' ativa o modo APPEND. Não apaga os dados antigos.
        try (java.io.PrintWriter out = new java.io.PrintWriter(new java.io.FileWriter(filename, true))) {
            out.print(currentSnapshot.serialize());
        } catch (java.io.IOException e) {
            System.err.println("[DiMex] Erro ao salvar o ficheiro do snapshot: " + e.getMessage());
        }
    }

    /**
     * PROCESS CRASHED (Tolerância a Falhas)
     * Objetivo: Evitar Deadlock se um processo morrer antes de mandar o "OK".
     */
    ///////////////////// EXTRA ////////////////////////////////////
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