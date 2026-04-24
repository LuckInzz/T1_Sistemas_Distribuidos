package pl;

public class Message {
    private String senderId;
    private String type;       // REQUEST, OK, MARKER
    private int clock;
    private int snapshotId;    // usado por mensagens MARKER; 0 nos demais tipos

    public Message(String senderId, String type, int clock) {
        this(senderId, type, clock, 0);
    }

    public Message(String senderId, String type, int clock, int snapshotId) {
        this.senderId   = senderId;
        this.type       = type;
        this.clock      = clock;
        this.snapshotId = snapshotId;
    }

    public String getSenderId()  { return senderId;   }
    public String getType()      { return type;        }
    public int    getClock()     { return clock;       }
    public int    getSnapshotId(){ return snapshotId;  }

    /**
     * Serializa para o transporte TCP.
     * Formato: senderId|type|clock|snapshotId\n
     */
    public String serialize() {
        return senderId + "|" + type + "|" + clock + "|" + snapshotId + "\n";
    }

    /**
     * Reconstrói a partir de uma linha lida do socket.
     * Aceita tanto o formato antigo (3 campos) quanto o novo (4 campos).
     */
    public static Message deserialize(String rawData) {
        String[] parts = rawData.trim().split("\\|");
        String senderId   = parts[0];
        String type       = parts[1];
        int    clock      = Integer.parseInt(parts[2].trim());
        int    snapshotId = (parts.length > 3) ? Integer.parseInt(parts[3].trim()) : 0;
        return new Message(senderId, type, clock, snapshotId);
    }

    /** Representação sem newline – usada para gravar estado de canal no snapshot. */
    @Override
    public String toString() {
        return senderId + "|" + type + "|" + clock + "|" + snapshotId;
    }
}
