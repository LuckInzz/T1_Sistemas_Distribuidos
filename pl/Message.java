package pl;
//CORPO DE UMA MENSAGEM
public class Message {
    //Quem enviou
    private String senderId;
    //O tipo da mensagem
    private String type; // Ex: REQUEST, OK, START
    //O clock
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

    // Monta a mensagem serializada
    public String serialize() {
        return senderId + "|" + type + "|" + clock + "\n";
    }

    //Desmonta a mensagem serializda
    public static Message deserialize(String rawData) {
        String[] parts = rawData.split("\\|");
        return new Message(parts[0], parts[1], Integer.parseInt(parts[2]));
    }
}
