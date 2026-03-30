package pl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

/**
 * Esta classe representa uma conexão única entre você e um outro processo. 
 * Ela roda em sua própria Thread e fica eternamente lendo o InputStream
 */ 

public class PeerConnection extends Thread {
    private String peerId;
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private DiMexListener listener;

    public PeerConnection(String peerId, Socket socket, DiMexListener listener) {
        this.peerId = peerId;
        this.socket = socket;
        this.listener = listener;
        try {
            // BufferReader nos permite usar o readLine() (lê até o \n)
            this.in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            // PrintWriter com 'true' no segundo argumento faz auto-flush, enviando os dados na hora
            this.out = new PrintWriter(socket.getOutputStream(), true);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Método exposto para o PerfectLink enviar dados por esta conexão
    public void sendMessage(Message msg) {
        out.print(msg.serialize()); // Já contém o \n
        out.flush(); // Garante que saiu do buffer para a rede
    }

    @Override
    public void run() {
        try {
            String line;
            // O readLine() bloqueia a thread até achar um \n. 
            // Se retornar null, a conexão foi fechada amigavelmente pelo outro lado.
            while ((line = in.readLine()) != null) {
                Message msg = Message.deserialize(line);
                // Entrega a mensagem processada para a camada superior (DiMex)
                listener.deliver(msg); 
            }
        } catch (IOException e) {
            // Se cair aqui, o socket quebrou de forma abrupta (crash do outro nó)
            //System.err.println("[PL] Conexão perdida com " + peerId);
        } finally {
            // Avisa o DiMex que o processo morreu (Tolerância a falhas)
            listener.processCrashed(peerId);
            close();
        }
    }

    public void close() {
        try { socket.close(); } catch (IOException e) {}
    }
}