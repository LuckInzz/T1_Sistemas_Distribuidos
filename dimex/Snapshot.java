package dimex;

import pl.Message;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Collection;

/**
 * Representa a captura do estado global de um processo e seus canais
 * conforme o algoritmo de Chandy-Lamport.
 */
public class Snapshot {
    private final int snapshotId;
    private final String processId;
    private final String internalState;
    private final int lamportClock;
    private final int requestClock;
    private final List<String> deferredQueue;
    
    // Mapeia o ID do peer para a lista de mensagens que chegaram por aquele canal
    private final Map<String, List<Message>> channelStates;

    public Snapshot(int snapshotId, String processId, String state, int clock, int requestClock, Collection<String> deferredQueue) {
        this.snapshotId = snapshotId;
        this.processId = processId;
        this.internalState = state;
        this.lamportClock = clock;
        this.requestClock = requestClock;
        // Criamos uma cópia da fila para garantir que a foto não mude se o DiMex continuar processando
        this.deferredQueue = new ArrayList<>(deferredQueue);
        this.channelStates = new HashMap<>();
    }

    // Adiciona uma mensagem que chegou em trânsito por um canal específico
    public void addMessageToChannel(String peerId, Message msg) {
        channelStates.computeIfAbsent(peerId, k -> new ArrayList<>()).add(msg);
    }

    /**
     * Gera a representação textual para salvar no arquivo conforme exigido no trabalho.
     */
    /**
     * Gera a representação textual em formato CSV/Log para facilitar o parser de validação.
     * Formato: SNAPSHOT_ID;PROCESSO_ID;ESTADO;CLOCK;REQ_CLOCK;[FILA_ADIADOS];CANAIS
     */
    public String serialize() {
        StringBuilder sb = new StringBuilder();
        
        sb.append(snapshotId).append(";");
        sb.append(processId).append(";");
        sb.append(internalState).append(";");
        sb.append(lamportClock).append(";");
        sb.append(requestClock).append(";");
        
        // Formata a fila de adiados (ex: [p1234,p1235])
        sb.append("[").append(String.join(",", deferredQueue)).append("];");
        
        // Formata as mensagens em trânsito dos canais (ex: p1234:[REQUEST,OK]|p1235:[])
        List<String> canaisStr = new ArrayList<>();
        if (channelStates.isEmpty()) {
            canaisStr.add("VAZIO");
        } else {
            channelStates.forEach((peer, messages) -> {
                StringBuilder msgs = new StringBuilder();
                msgs.append(peer).append(":[");
                
                List<String> msgTypes = new ArrayList<>();
                messages.forEach(m -> msgTypes.add(m.getType()));
                
                msgs.append(String.join(",", msgTypes)).append("]");
                canaisStr.add(msgs.toString());
            });
        }
        sb.append(String.join("|", canaisStr));
        
        // Quebra de linha fundamental para o próximo snapshot ir para a linha de baixo
        sb.append("\n"); 
        
        return sb.toString();
    }

    public int getSnapshotId() {
        return snapshotId;
    }
}