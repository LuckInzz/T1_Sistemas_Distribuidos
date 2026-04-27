package pl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Este é o coração da rede. 
 * Ele sobe o servidor TCP, lida com a descoberta UDP e resolve o conflito de múltiplas conexões.
 */

public class PerfectLink {
    private String myId;
    private int myTcpPort;
    private DiMexListener listener;
    
    // Armazena as conexões ativas. Thread-safe!
    private ConcurrentHashMap<String, PeerConnection> connections = new ConcurrentHashMap<>();
    
    // Configurações do Multicast (Grupo UDP)
    private static final String MULTICAST_IP = "230.0.0.0";
    private static final int MULTICAST_PORT = 4446;
    private volatile long lastDiscoveryTime = System.currentTimeMillis();

    public PerfectLink(String myId, int myTcpPort, DiMexListener listener) {
        this.myId = myId;
        this.myTcpPort = myTcpPort;
        this.listener = listener;
    }

    //Inicia todo o PL e as conexões a partir da descoberta com TCP até ativar um modulo que escuto e outro que fala
    public void startNetwork() {
        startTcpServer();
        startUdpDiscoveryListener();
        startUdpDiscoveryAnnouncer();
        
        System.out.println("[PL] Inicialização iniciada. Aguardando estabilização da rede...");
        
        // Loop inteligente: aguarda 10 segundos de "silêncio" na rede
        while (true) {
            long timeSinceLastDiscovery = System.currentTimeMillis() - lastDiscoveryTime;
            long segundosRestantes = 10 - (timeSinceLastDiscovery / 1000);
            
            // Imprime na mesma linha para criar um efeito de cronômetro
            System.out.print("\r[PL] Travando rede em " + segundosRestantes + "s... ");
            
            if (timeSinceLastDiscovery >= 10000) {
                System.out.println("\n[PL] Tempo esgotado! Fechando as portas de entrada.");
                break; // Sai do loop de aquecimento
            }
            
            try { 
                Thread.sleep(1000); // Dorme 1 segundo e atualiza o cronômetro
            } catch (InterruptedException e) {}
        }
        
        //System.out.println("[PL] Warm-up finalizado. Rede travada.");
        System.out.println("[PL] Tamanho do sistema (N) definido para: " + (connections.size() + 1));
    }

    //Chamado pelo dimex para enviar msg para outro processo
    public void send(String targetId, Message msg) {
        PeerConnection conn = connections.get(targetId);
        if (conn != null) {
            conn.sendMessage(msg);
        } else {
            System.err.println("[PL] Erro: Tentando enviar para nó desconhecido ou morto: " + targetId);
        }
    }

    // =========================================================
    // MÉTODOS INTERNOS DE INFRAESTRUTURA
    // =========================================================

    private void startTcpServer() {
        new Thread(() -> {
            try (ServerSocket serverSocket = new ServerSocket(myTcpPort)) {
                System.out.println("[PL] Servidor TCP rodando na porta " + myTcpPort);
                while (true) {
                    Socket clientSocket = serverSocket.accept();
                    // Quando alguém conecta, inicialmente não sabemos o ID.
                    // Em um cenário real, você exigiria um "handshake" (primeira mensagem contendo o ID).
                    // Para simplificar a lógica de cruzamento, deixamos o cliente se identificar.
                    handleIncomingConnection(clientSocket);
                }
            } catch (IOException e) { e.printStackTrace(); }
        }).start();
    }

    //ANUNCIA POR UDP QUE ESTA VIVO
    private void startUdpDiscoveryAnnouncer() {
        new Thread(() -> {
            try (DatagramSocket socket = new DatagramSocket()) {
                InetAddress group = InetAddress.getByName(MULTICAST_IP);
                // Formato do anúncio: ID:PORTA_TCP
                String message = myId + ":" + myTcpPort;
                byte[] buffer = message.getBytes();
                
                // Fica anunciando a cada 1 segundo durante a fase inicial
                while (true) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length, group, MULTICAST_PORT);
                    socket.send(packet);
                    Thread.sleep(1000);
                }
            } catch (Exception e) { e.printStackTrace(); }
        }).start();
    }

    //STARTA O LISTNER PARA OUVIR AS MENSAGENS QUE CHEGAM
    private void startUdpDiscoveryListener() {
        new Thread(() -> {
            try (MulticastSocket socket = new MulticastSocket(MULTICAST_PORT)) {
                InetAddress group = InetAddress.getByName(MULTICAST_IP);
                socket.joinGroup(new InetSocketAddress(group, MULTICAST_PORT), null);
                
                byte[] buffer = new byte[256];
                while (true) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);
                    
                    String received = new String(packet.getData(), 0, packet.getLength());
                    String[] parts = received.split(":");
                    String peerId = parts[0];
                    int peerPort = Integer.parseInt(parts[1]);
                    
                    // Ignora as próprias mensagens de multicast
                    if (peerId.equals(myId)) continue;
                    
                    // LÓGICA DE DESEMPATE (Crossing Sockets)
                    // Se o meu ID for alfabeticamente "maior" que o do outro cara, EU conecto nele.
                    // Se for menor, eu ignoro o anúncio e espero ELE conectar no meu ServerSocket.
                    // Isso evita duas conexões (ida e volta) entre o mesmo par.
                    if (myId.compareTo(peerId) > 0 && !connections.containsKey(peerId)) {
                        connectToPeer(peerId, packet.getAddress().getHostAddress(), peerPort);
                    }
                }
            } catch (Exception e) { e.printStackTrace(); }
        }).start();
    }

    //FUNCAO QUE CRIA A CONEXAO COM UM OUTRO PROCESSO
    private void connectToPeer(String peerId, String ip, int port) {
        try {
            Socket socket = new Socket(ip, port);
            // Ao conectar ativamente, eu envio uma mensagem "fantasma" de Handshake
            // apenas para o servidor do outro lado saber quem eu sou e criar a PeerConnection.
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            out.println(myId + "|HANDSHAKE|0"); 
            
            registerConnection(peerId, socket);
        } catch (IOException e) {
            System.out.println("[PL] Aguardando nó " + peerId + " subir servidor...");
        }
    }

    //FUNCAO PARA REALIZAR HANDSHAKE
    private void handleIncomingConnection(Socket socket) throws IOException {
        // Lê a primeira linha para o Handshake (descobrir quem conectou na gente)
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        String handshakeLine = in.readLine();
        if (handshakeLine != null) {
            Message msg = Message.deserialize(handshakeLine);
            registerConnection(msg.getSenderId(), socket);
        }
    }

    private synchronized void registerConnection(String peerId, Socket socket) {
        if (!connections.containsKey(peerId)) {
            PeerConnection peerConn = new PeerConnection(peerId, socket, listener);
            connections.put(peerId, peerConn);
            peerConn.start(); // Inicia a thread que fica lendo mensagens
            lastDiscoveryTime = System.currentTimeMillis(); //Reinicia o tempo ao criar uma nova conexão
            System.out.println("[PL] Conexão TCP estabelecida e fixada com " + peerId);
        }
    }

    // Retorna a lista de IDs de todos os processos conectados
    public java.util.Set<String> getPeers() {
        return connections.keySet();
    }
}
