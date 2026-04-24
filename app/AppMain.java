package app;

import dimex.DiMex;
import pl.PerfectLink;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.ServerSocket;


public class AppMain {

    public static void main(String[] args) {

        // 1. Geração Automática de Porta e ID (Zero-Config)
        int portaDinamica = 0;
        try (ServerSocket s = new ServerSocket(0)) {
            portaDinamica = s.getLocalPort();
        } catch (IOException e) {
            System.err.println("Erro ao alocar porta.");
            System.exit(1);
        }
        //cria o id -> P + a porta em que esta
        String myId = "p" + portaDinamica;

        System.out.println("=========================================");
        System.out.println(" START APP (Simulação Aleatória)");
        System.out.println(" ID: " + myId + " | Porta TCP: " + portaDinamica);
        System.out.println("=========================================\n");

        //cria o propriodimex
        DiMex dimex = new DiMex(myId);

        //cria o proprio PL
        PerfectLink pl = new PerfectLink(myId, portaDinamica, dimex);

        //conecta os dois
        dimex.setPerfectLink(pl);

        //starta o pl para que ele comece a ouvir/enviar
        pl.startNetwork();

        System.out.println("\n[App] Rede estabilizada. Iniciando simulação de trabalho...");

        //////////////// SNAPSHOT NO PRIMEIRO PROCESSO  //////////////////////////
        // Com a rede estabilizada, buscamos os Ids de todos os processos
        java.util.List<String> todosIds = new java.util.ArrayList<>(pl.getPeers());
        todosIds.add(myId);
        //coloca os Ids em ordem
        java.util.Collections.sort(todosIds);

        // verifica se o menor id (indice zero) é o deste processo em questão,
        //se for, ele é quem deve fazer snapshots
        if (myId.equals(todosIds.get(0))) {
            System.out.println("[App] Eu sou o Líder do Snapshot. Iniciando sessão...");
            new Thread(() -> {
                int currentSnapshotId = 1;
                while (currentSnapshotId <= 300) {
                    try {
                        Thread.sleep(2000); // Intervalo entre capturas
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    dimex.startSnapshot(currentSnapshotId);
                    currentSnapshotId++;
                }
            }).start();
        } else {
            System.out.println("[App] Atuando apenas como receptor de Snapshots.");
        }


        // 3. O Loop de Trabalho (A Simulação Caótica)
        while (true) {

            // QUER ENTRAR NA REGIÃO CRÍTICA
            System.out.println("[App] Quero acessar o arquivo compartilhado!");

            //pede ao dimex se pode escrever
            dimex.entry(); // O código TRAVA aqui aguardando o Ricart-Agrawala

            // ========================================================
            // DENTRO DA REGIÃO CRÍTICA (Protegido pelo DiMex)
            // ========================================================
            //se chegou ate aquim, quer dizer que o dimex liberou
            System.out.println(">> [App] ENTREI NA RC!");

            // As duas escritas devem acontecer atomicamente dentro da sc
            try (PrintWriter out = new PrintWriter(new FileWriter("rc_log.txt", true))) {
                out.print(".");
                out.flush();
                out.print("|");
                out.flush();
            } catch (IOException e) {
                e.printStackTrace();
            }

            // SAI DA REGIÃO CRÍTICA
            dimex.exit(); // O DiMex envia os ok para quem ficou esperando
            System.out.println("<< [App] RC LIBERADA!");

        }
    }
}