package dimex;

import pl.DiMexListener;
import pl.Message;
import pl.PerfectLink;

import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DiMex implements DiMexListener {

    // =========================================================
    // ESTADO DO RICART-AGRAWALA
    // =========================================================

    public enum State { RELEASED, WANTED, HELD }

    private State        state;
    private int          clock;
    private int          requestClock;
    private Queue<String> deferredQueue;
    private Set<String>  waitingAcks;

    // =========================================================
    // MODO DE FALHA (etapa 4 do trabalho)
    //   0 = comportamento correto
    //   1 = FALHA: sempre envia OK (viola exclusão mútua)
    //   2 = FALHA: nunca envia OK (causa deadlock)
    // =========================================================

    private final int faultMode;

    // =========================================================
    // INFRAESTRUTURA
    // =========================================================

    private String      myId;
    private PerfectLink pl;

    // =========================================================
    // CHANDY-LAMPORT SNAPSHOT
    // =========================================================

    // Registro local do estado gravado para cada snapshotId em andamento
    private final Map<Integer, SnapshotRecord>              pendingSnapshots  = new HashMap<>();

    // Mensagens bufferizadas por canal enquanto o snapshot está sendo coletado
    // channelRecording[snapId][peerId] = lista de msgs recebidas após gravar estado
    private final Map<Integer, Map<String, List<Message>>>  channelRecording  = new HashMap<>();

    // Conjunto de pares dos quais ainda aguardamos MARKER para cada snapshot
    private final Map<Integer, Set<String>>                 pendingMarkers    = new HashMap<>();

    // Executor isolado para E/S de arquivo (não bloqueia o lock do DiMex)
    private final ExecutorService snapshotSaver = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "snapshot-saver");
        t.setDaemon(true);
        return t;
    });

    private static final String SNAPSHOTS_DIR = "snapshots";

    // =========================================================
    // CLASSE INTERNA: estado gravado por este processo
    // =========================================================

    private static class SnapshotRecord {
        final int         snapshotId;
        final String      processId;
        final long        timestamp;
        final State       dimexState;
        final int         clock;
        final int         requestClock;
        final List<String> waitingAcks;
        final List<String> deferredQueue;
        final List<String> allPeers;
        // estado de cada canal de entrada: peerId → lista de msgs serializadas
        final Map<String, List<String>> channelStates = new LinkedHashMap<>();

        SnapshotRecord(int snapId, String processId,
                       State dimexState, int clock, int requestClock,
                       Set<String> waitingAcks, Queue<String> deferredQueue,
                       Set<String> allPeers) {
            this.snapshotId   = snapId;
            this.processId    = processId;
            this.timestamp    = System.currentTimeMillis();
            this.dimexState   = dimexState;
            this.clock        = clock;
            this.requestClock = requestClock;
            this.waitingAcks  = new ArrayList<>(waitingAcks);
            this.deferredQueue = new ArrayList<>(deferredQueue);
            this.allPeers     = new ArrayList<>(allPeers);
        }
    }

    // =========================================================
    // CONSTRUTORES
    // =========================================================

    public DiMex(String myId) {
        this(myId, 0);
    }

    public DiMex(String myId, int faultMode) {
        this.myId        = myId;
        this.faultMode   = faultMode;
        this.state       = State.RELEASED;
        this.clock       = 0;
        this.requestClock = 0;
        this.deferredQueue = new LinkedList<>();
        this.waitingAcks  = new HashSet<>();
    }

    public void setPerfectLink(PerfectLink pl) {
        this.pl = pl;
    }

    // =========================================================
    // INTERFACE COM A APLICAÇÃO
    // =========================================================

    public synchronized void entry() {
        System.out.println("[DiMex] App solicitou ENTRY. Iniciando protocolo...");

        this.state = State.WANTED;
        this.clock++;
        this.requestClock = this.clock;

        Set<String> peers = pl.getPeers();
        this.waitingAcks.clear();
        this.waitingAcks.addAll(peers);

        if (this.waitingAcks.isEmpty()) {
            this.state = State.HELD;
            System.out.println("[DiMex] Rede vazia. HELD concedido imediatamente.");
            return;
        }

        Message reqMsg = new Message(myId, "REQUEST", requestClock);
        for (String peer : peers) {
            pl.send(peer, reqMsg);
        }
        System.out.println("[DiMex] REQUEST enviado para " + peers.size() + " pares.");

        while (!this.waitingAcks.isEmpty()) {
            try { wait(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        this.state = State.HELD;
        System.out.println("[DiMex] PERMISSÃO TOTAL RECEBIDA. Estado: HELD");
    }

    public synchronized void exit() {
        System.out.println("[DiMex] App solicitou EXIT.");

        this.state = State.RELEASED;
        this.clock++;

        Message okMsg = new Message(myId, "OK", this.clock);
        for (String deferredPeer : deferredQueue) {
            pl.send(deferredPeer, okMsg);
            System.out.println("[DiMex] OK atrasado enviado para " + deferredPeer);
        }
        deferredQueue.clear();
        System.out.println("[DiMex] Estado: RELEASED");
    }

    // =========================================================
    // CHANDY-LAMPORT: INICIAR SNAPSHOT (chamado pelo processo iniciador)
    // =========================================================

    public synchronized void initiateSnapshot(int snapId) {
        if (pendingSnapshots.containsKey(snapId)) return;

        System.out.println("[Snapshot] Iniciando snapshot #" + snapId);

        Set<String> peers = pl.getPeers();

        // 1. Gravar estado local
        SnapshotRecord record = new SnapshotRecord(snapId, myId,
                state, clock, requestClock, waitingAcks, deferredQueue, peers);
        pendingSnapshots.put(snapId, record);

        // 2. Iniciar gravação de todos os canais de entrada
        Map<String, List<Message>> channels = new HashMap<>();
        for (String peer : peers) channels.put(peer, new ArrayList<>());
        channelRecording.put(snapId, channels);

        // 3. Aguardar MARKER de todos os pares
        pendingMarkers.put(snapId, new HashSet<>(peers));

        // 4. Enviar MARKER para todos os pares
        Message marker = new Message(myId, "MARKER", clock, snapId);
        for (String peer : peers) pl.send(peer, marker);

        // 5. Se não há pares, o snapshot já está completo
        if (peers.isEmpty()) finalizeSnapshot(snapId);
    }

    // =========================================================
    // CHANDY-LAMPORT: TRATAR MARKER RECEBIDO (chamado de deliver)
    // =========================================================

    private void handleMarker(Message msg) {
        int    snapId = msg.getSnapshotId();
        String sender = msg.getSenderId();

        if (!pendingSnapshots.containsKey(snapId)) {
            // === Primeira vez vendo este snapshot: gravar estado local ===

            Set<String> peers = pl.getPeers();
            SnapshotRecord record = new SnapshotRecord(snapId, myId,
                    state, clock, requestClock, waitingAcks, deferredQueue, peers);
            pendingSnapshots.put(snapId, record);

            // Canal do remetente do MARKER está VAZIO (regra Chandy-Lamport)
            record.channelStates.put(sender, new ArrayList<>());

            // Iniciar gravação de todos os OUTROS canais
            Map<String, List<Message>> channels = new HashMap<>();
            for (String peer : peers) {
                if (!peer.equals(sender)) channels.put(peer, new ArrayList<>());
            }
            channelRecording.put(snapId, channels);

            // Aguardar MARKER de todos exceto o remetente atual
            Set<String> pending = new HashSet<>(peers);
            pending.remove(sender);
            pendingMarkers.put(snapId, pending);

            // Propagar MARKER para todos os pares
            Message marker = new Message(myId, "MARKER", clock, snapId);
            for (String peer : peers) pl.send(peer, marker);

            System.out.println("[Snapshot] #" + snapId + " estado gravado (trigger=" + sender + ")");

        } else {
            // === Já gravamos nosso estado: fechar canal do remetente ===

            Map<String, List<Message>> channels = channelRecording.get(snapId);
            if (channels != null && channels.containsKey(sender)) {
                List<Message> buffered = channels.remove(sender);
                List<String> serialized = new ArrayList<>(buffered.size());
                for (Message m : buffered) serialized.add(m.toString());
                pendingSnapshots.get(snapId).channelStates.put(sender, serialized);
            }

            Set<String> pending = pendingMarkers.get(snapId);
            if (pending != null) pending.remove(sender);
        }

        // Verificar se o snapshot está completo para este processo
        Set<String> remaining = pendingMarkers.get(snapId);
        if (remaining != null && remaining.isEmpty()) finalizeSnapshot(snapId);
    }

    private void finalizeSnapshot(int snapId) {
        SnapshotRecord record  = pendingSnapshots.remove(snapId);
        Map<String, List<Message>> leftover = channelRecording.remove(snapId);
        pendingMarkers.remove(snapId);

        if (record == null) return;

        // Canais com mensagens bufferizadas mas sem MARKER recebido ainda
        // (pode ocorrer em finalizações antecipadas por falha de nó)
        if (leftover != null) {
            for (Map.Entry<String, List<Message>> e : leftover.entrySet()) {
                if (!record.channelStates.containsKey(e.getKey())) {
                    List<String> serialized = new ArrayList<>();
                    for (Message m : e.getValue()) serialized.add(m.toString());
                    record.channelStates.put(e.getKey(), serialized);
                }
            }
        }

        // Garantir entrada para todos os pares conhecidos
        for (String peer : record.allPeers) {
            record.channelStates.putIfAbsent(peer, new ArrayList<>());
        }

        System.out.println("[Snapshot] #" + snapId + " completo para " + myId);
        final SnapshotRecord toSave = record;
        snapshotSaver.submit(() -> saveSnapshot(toSave));
    }

    // =========================================================
    // INTERFACE COM O PERFECT LINK
    // =========================================================

    @Override
    public synchronized void deliver(Message msg) {
        String sender   = msg.getSenderId();
        String type     = msg.getType();
        int    msgClock = msg.getClock();

        // Atualização do relógio de Lamport
        this.clock = Math.max(this.clock, msgClock) + 1;

        // MARKER do Chandy-Lamport – não é uma mensagem de aplicação
        if (type.equals("MARKER")) {
            handleMarker(msg);
            return;
        }

        // Bufferizar msg em todos os canais de snapshot que estão gravando este peer
        for (Map<String, List<Message>> channels : channelRecording.values()) {
            List<Message> buf = channels.get(sender);
            if (buf != null) buf.add(msg);
        }

        // === Lógica do Ricart-Agrawala ===
        if (type.equals("REQUEST")) {
            boolean defer;

            if (faultMode == 1) {
                // FALHA 1: sempre responde OK (viola exclusão mútua)
                defer = false;
            } else if (faultMode == 2) {
                // FALHA 2: nunca responde (acumula tudo no deferredQueue → deadlock)
                defer = true;
            } else {
                // Comportamento correto
                defer = false;
                if (this.state == State.HELD) {
                    defer = true;
                } else if (this.state == State.WANTED) {
                    if (this.requestClock < msgClock) {
                        defer = true;
                    } else if (this.requestClock == msgClock && myId.compareTo(sender) < 0) {
                        defer = true;
                    }
                }
            }

            if (defer) {
                deferredQueue.add(sender);
                System.out.println("[DiMex] REQUEST de " + sender + " ADIADO.");
            } else {
                pl.send(sender, new Message(myId, "OK", this.clock));
                System.out.println("[DiMex] OK imediato enviado para " + sender);
            }

        } else if (type.equals("OK")) {
            System.out.println("[DiMex] Recebi OK de " + sender);
            this.waitingAcks.remove(sender);
            if (this.waitingAcks.isEmpty() && this.state == State.WANTED) {
                notifyAll();
            }
        }
    }

    @Override
    public synchronized void processCrashed(String peerId) {
        System.out.println("[DiMex] Falha detectada: " + peerId);

        if (waitingAcks.remove(peerId)) {
            if (waitingAcks.isEmpty() && state == State.WANTED) notifyAll();
        }
        deferredQueue.remove(peerId);

        // Remover peer falho dos snapshots pendentes e verificar conclusão
        List<Integer> toFinalize = new ArrayList<>();
        for (Map.Entry<Integer, Set<String>> e : pendingMarkers.entrySet()) {
            e.getValue().remove(peerId);
            if (e.getValue().isEmpty()) toFinalize.add(e.getKey());
        }
        for (Map<String, List<Message>> ch : channelRecording.values()) ch.remove(peerId);
        for (int snapId : toFinalize) finalizeSnapshot(snapId);
    }

    // =========================================================
    // GRAVAR SNAPSHOT EM ARQUIVO JSON
    // =========================================================

    private void saveSnapshot(SnapshotRecord r) {
        try {
            File dir = new File(SNAPSHOTS_DIR);
            dir.mkdirs();

            // Nome: snap_<snapId zero-padded>_<processId>.json
            String filename = String.format("snap_%05d_%s.json", r.snapshotId, r.processId);
            File file = new File(dir, filename);

            StringBuilder sb = new StringBuilder(512);
            sb.append("{\n");
            sb.append("  \"snapshotId\": ")   .append(r.snapshotId)          .append(",\n");
            sb.append("  \"processId\": \"")  .append(esc(r.processId))      .append("\",\n");
            sb.append("  \"timestamp\": ")    .append(r.timestamp)           .append(",\n");
            sb.append("  \"dimexState\": \"") .append(r.dimexState.name())   .append("\",\n");
            sb.append("  \"clock\": ")        .append(r.clock)               .append(",\n");
            sb.append("  \"requestClock\": ") .append(r.requestClock)        .append(",\n");
            sb.append("  \"waitingAcks\": ")  .append(toJArr(r.waitingAcks)) .append(",\n");
            sb.append("  \"deferredQueue\": ").append(toJArr(r.deferredQueue)).append(",\n");
            sb.append("  \"allPeers\": ")     .append(toJArr(r.allPeers))    .append(",\n");
            sb.append("  \"channelStates\": ").append(channelToJson(r.channelStates)).append("\n");
            sb.append("}\n");

            try (FileWriter fw = new FileWriter(file)) {
                fw.write(sb.toString());
            }
        } catch (IOException e) {
            System.err.println("[Snapshot] Erro ao salvar snapshot: " + e.getMessage());
        }
    }

    // =========================================================
    // HELPERS JSON
    // =========================================================

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String toJArr(List<String> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append("\"").append(esc(list.get(i))).append("\"");
        }
        return sb.append("]").toString();
    }

    private static String channelToJson(Map<String, List<String>> channels) {
        StringBuilder sb = new StringBuilder("{\n");
        boolean first = true;
        for (Map.Entry<String, List<String>> e : channels.entrySet()) {
            if (!first) sb.append(",\n");
            first = false;
            sb.append("    \"").append(esc(e.getKey())).append("\": ")
              .append(toJArr(e.getValue()));
        }
        return sb.append("\n  }").toString();
    }
}
