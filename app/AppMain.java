package app;

import dimex.DiMex;
import pl.PerfectLink;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;

public class AppMain {

    // =========================================================
    // LIMITES DE TEMPO (Em milissegundos)
    // =========================================================
    // Tempo que o processo fica rodando código local antes de querer a RC
    private static final int TEMPO_MIN_FORA_RC = 1000; // 1 segundo
    private static final int TEMPO_MAX_FORA_RC = 15000; // 15 segundos
    
    // Tempo que o processo segura a Região Crítica (simulando gravação demorada)
    private static final int TEMPO_MIN_DENTRO_RC = 500;  // 0.5 segundo
    private static final int TEMPO_MAX_DENTRO_RC = 5000; // 5 segundos

    public static void main(String[] args) {
        
        // 1. Geração Automática de Porta e ID (Zero-Config)
        int portaDinamica = 0;
        try (ServerSocket s = new ServerSocket(0)) {
            portaDinamica = s.getLocalPort();
        } catch (IOException e) {
            System.err.println("Erro ao alocar porta.");
            System.exit(1);
        }
        String myId = "p" + portaDinamica;

        System.out.println("=========================================");
        System.out.println(" START APP (Simulação Aleatória)");
        System.out.println(" ID: " + myId + " | Porta TCP: " + portaDinamica);
        System.out.println("=========================================\n");

        DiMex dimex = new DiMex(myId);

        PerfectLink pl = new PerfectLink(myId, portaDinamica, dimex);

        dimex.setPerfectLink(pl);

        pl.startNetwork();
        
        System.out.println("\n[App] Rede estabilizada. Iniciando simulação de trabalho...");
        
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss");
        Random random = new Random();

        // 3. O Loop de Trabalho (A Simulação Caótica)
        while (true) {
            try {
                // ========================================================
                // FORA DA REGIÃO CRÍTICA (Pensando/Processando)
                // ========================================================
                int tempoFora = random.nextInt(TEMPO_MAX_FORA_RC - TEMPO_MIN_FORA_RC + 1) + TEMPO_MIN_FORA_RC;
                System.out.println("\n[App] Trabalhando localmente por " + tempoFora + "ms...");
                Thread.sleep(tempoFora);

                // QUER ENTRAR NA REGIÃO CRÍTICA
                System.out.println("[App] Quero acessar o arquivo compartilhado!");
                dimex.entry(); // O código TRAVA aqui aguardando o Ricart-Agrawala

                // ========================================================
                // DENTRO DA REGIÃO CRÍTICA (Protegido pelo DiMex)
                // ========================================================
                System.out.println(">> [App] ENTREI NA RC!");
                String horaEntrada = LocalTime.now().format(formatter);
                
                // Grava a entrada no arquivo
                try (PrintWriter out = new PrintWriter(new FileWriter("rc_log.txt", true))) {
                    out.println("[" + horaEntrada + "] INICIO - Processo " + myId + " entrou na RC.");
                } catch (IOException e) {
                    e.printStackTrace();
                }

                // Sorteia quanto tempo vai ficar segurando a Região Crítica
                int tempoDentro = random.nextInt(TEMPO_MAX_DENTRO_RC - TEMPO_MIN_DENTRO_RC + 1) + TEMPO_MIN_DENTRO_RC;
                System.out.println("[App] Segurando a RC por " + tempoDentro + "ms...");
                Thread.sleep(tempoDentro); 
                
                String horaSaida = LocalTime.now().format(formatter);
                
                // Grava a saída no arquivo para fechar o bloco de log
                try (PrintWriter out = new PrintWriter(new FileWriter("rc_log.txt", true))) {
                    out.println("[" + horaSaida + "] FIM    - Processo " + myId + " liberou a RC.");
                    out.println("-------------------------------------------------");
                } catch (IOException e) {
                    e.printStackTrace();
                }
                
                // SAI DA REGIÃO CRÍTICA
                dimex.exit(); // O DiMex envia os OKs para quem ficou esperando
                System.out.println("<< [App] RC LIBERADA!");
                
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }
}