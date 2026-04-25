package dimex;

import pl.Message;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Collection;


public class Snapshot {
    private final int snapshotId;
    private final String processId;
    private final String internalState;
    private final int lamportClock;
    private final int requestClock;
    private final List<String> deferredQueue;
    private final List<String> waitingAcks;

    private final Map<String, List<Message>> channelStates;

    // 2. Adiciona no construtor
    public Snapshot(int snapshotId, String processId, String state, int clock, int requestClock,
            Collection<String> deferredQueue, Collection<String> waitingAcks) {
        this.snapshotId = snapshotId;
        this.processId = processId;
        this.internalState = state;
        this.lamportClock = clock;
        this.requestClock = requestClock;
        this.deferredQueue = new ArrayList<>(deferredQueue);

        // 3. Cria a cópia isolada da lista de quem estamos esperando
        this.waitingAcks = new ArrayList<>(waitingAcks);
        this.channelStates = new HashMap<>();
    }

    public void addMessageToChannel(String peerId, Message msg) {
        channelStates.computeIfAbsent(peerId, k -> new ArrayList<>()).add(msg);
    }

    public String serialize() {
        StringBuilder sb = new StringBuilder();

        sb.append(snapshotId).append(";");
        sb.append(processId).append(";");
        sb.append(internalState).append(";");
        sb.append(lamportClock).append(";");
        sb.append(requestClock).append(";");
        sb.append("[").append(String.join(",", deferredQueue)).append("];");

        // 4. Adiciona a nova lista no formato do CSV
        sb.append("[").append(String.join(",", waitingAcks)).append("];");

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
        sb.append("\n");
        return sb.toString();
    }

    public int getSnapshotId() {
        return snapshotId;
    }

}