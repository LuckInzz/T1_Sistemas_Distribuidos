package pl;

public class Message {
    private String senderId;
    private String type; // Ex: REQUEST, OK, START
    private int clock;   // Relógio lógico (para o DiMex depois)

    public Message(String senderId, String type, int clock) {
        this.senderId = senderId;
        this.type = type;
        this.clock = clock;
    }

    // Getters
    public String getSenderId() { return senderId; }
    public String getType() { return type; }
    public int getClock() { return clock; }

    /**
     * Serializa a mensagem para o formato texto delimitado por pipe '|'
     * e ADICIONA O \n NO FINAL. Isso resolve o problema de Framing do TCP.
     */
    public String serialize() {
        return senderId + "|" + type + "|" + clock + "\n";
    }

    /**
     * Desserializa uma string recebida do socket de volta para um objeto Message.
     */
    public static Message deserialize(String rawData) {
        String[] parts = rawData.split("\\|");
        return new Message(parts[0], parts[1], Integer.parseInt(parts[2]));
    }
}
