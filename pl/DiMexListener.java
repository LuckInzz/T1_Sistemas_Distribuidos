package pl;

/**
 * Interface que o módulo DiMex (ou App principal) precisará implementar.
 * O Perfect Link chamará esses métodos para notificar eventos da rede.
 */
public interface DiMexListener {
    // Chamado quando uma nova mensagem lógica chega perfeitamente
    void deliver(Message msg);
    
    // Chamado quando o TCP detecta a quebra de uma conexão (Node Crash)
    void processCrashed(String peerId);
}