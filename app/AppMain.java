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



        // 3. O Loop de Trabalho (A Simulação Caótica)
        while (true) {

            // QUER ENTRAR NA REGIÃO CRÍTICA
            System.out.println("[App] Quero acessar o arquivo compartilhado!");
            dimex.entry(); // O código TRAVA aqui aguardando o Ricart-Agrawala

            // ========================================================
            // DENTRO DA REGIÃO CRÍTICA (Protegido pelo DiMex)
            // ========================================================
            System.out.println(">> [App] ENTREI NA RC!");

            // As duas escritas devem acontecer atomicamente dentro da RC
            try (PrintWriter out = new PrintWriter(new FileWriter("rc_log.txt", true))) {
                out.print(".");
                out.flush();
                out.print("|");
                out.flush();
            } catch (IOException e) {
                e.printStackTrace();
            }

            // SAI DA REGIÃO CRÍTICA
            dimex.exit(); // O DiMex envia os OKs para quem ficou esperando
            System.out.println("<< [App] RC LIBERADA!");

        }
    }
}