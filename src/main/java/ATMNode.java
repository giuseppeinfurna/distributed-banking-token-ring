import java.io.*;
import java.net.*;
import java.util.*;

/**
 * ATMNode rappresenta un nodo (ATM) di un sistema bancario distribuito
 * che utilizza l'algoritmo Token Ring per garantire la mutua esclusione.
 *
 * Ogni nodo:
 * - è un processo indipendente
 * - comunica esclusivamente tramite messaggi TCP
 * - accede alla risorsa condivisa (saldo) solo se in possesso del token
 */
public class ATMNode {

    // Identificatore logico del nodo (1..4)
    private int id;

    // Porta TCP su cui il nodo resta in ascolto
    private int myPort;

    // Porta TCP del nodo successore nell’anello logico
    private int nextPort;

    // Indica se il nodo ha una transazione da eseguire
    private boolean hasPendingTransaction = false;

    // Tipo di transazione associata al nodo
    private String transactionType = "";

    // Importo della transazione
    private int amount = 0;

    // Timeout massimo di attesa del token (ms)
    private static final int TIMEOUT = 8000;

    // Istante dell’ultima ricezione valida del token
    private long lastTokenTime = System.currentTimeMillis();

    // Simula il crash del nodo (modello fail-stop)
    private boolean crashed = false;

    // Indica la ricezione di un token di terminazione
    private boolean stop = false;

    // Indica che l’anello è stato inizializzato correttamente
    private boolean ringReady = false;

    // Indica che il token è stato inviato ma non ancora ricevuto
    // Serve per evitare rigenerazioni spurie durante la trasmissione
    private volatile boolean tokenInTransit = false;

    /**
     * Costruttore del nodo ATM
     */
    public ATMNode(int id, int myPort, int nextPort) {
        this.id = id;
        this.myPort = myPort;
        this.nextPort = nextPort;
        initTransaction();
    }

    /**
     * Inizializza una transazione predefinita per alcuni nodi.
     * Questa scelta è puramente dimostrativa e serve a rendere
     * osservabile il comportamento della mutua esclusione.
     */
    private void initTransaction() {
        if (id == 2) {
            hasPendingTransaction = true;
            transactionType = "WITHDRAW";
            amount = 200;
        } else if (id == 3) {
            hasPendingTransaction = true;
            transactionType = "DEPOSIT";
            amount = 100;
        } else if (id == 4) {
            hasPendingTransaction = true;
            transactionType = "WITHDRAW";
            amount = 500;
        }
    }

    /**
     * Avvia il server socket del nodo.
     * Il nodo resta in ascolto dei token provenienti dal predecessore
     * e delega la gestione del token al metodo handleToken().
     */
    public void start() throws Exception {
        ServerSocket serverSocket = new ServerSocket(myPort);

        // Timeout su accept() per evitare blocchi indefiniti
        serverSocket.setSoTimeout(5000);

        log("In ascolto sulla porta " + myPort);

        // Thread separato che monitora l’eventuale perdita del token
        new Thread(this::monitorToken).start();

        // Ciclo principale di ricezione dei token
        while (true) {
            try {
                Socket socket = serverSocket.accept();
                BufferedReader in =
                        new BufferedReader(new InputStreamReader(socket.getInputStream()));
                String token = in.readLine();
                socket.close();
                handleToken(token);
            } catch (SocketTimeoutException ignored) {
                // Usato solo per permettere il controllo periodico del timeout
            }
        }
    }

    /**
     * Thread di monitoraggio del token.
     *
     * SOLO il nodo ATM1 può rigenerare il token e solo se:
     * - l’anello è considerato stabile (ringReady)
     * - il token non è in transito
     * - il timeout è realmente scaduto
     *
     * Questo evita la creazione di token duplicati.
     */
    private void monitorToken() {
        while (true) {
            long tokenTime = System.currentTimeMillis() - lastTokenTime;

            if (!crashed && id == 1 && ringReady &&
                !tokenInTransit && tokenTime > TIMEOUT) {

                log("Timeout reale! Rigenero il token");
                try {
                    sendToken("TOKEN:1:1000");
                } catch (Exception ignored) {}
                lastTokenTime = System.currentTimeMillis();
            }

            try {
                Thread.sleep(2000);
            } catch (InterruptedException ignored) {}
        }
    }

    /**
     * Gestisce il token ricevuto:
     * - aggiorna il tempo di ricezione
     * - esegue la sezione critica (se presente)
     * - rileva la terminazione dell’anello
     * - inoltra il token al successore
     */
    private void handleToken(String token) throws Exception {

        // Nodo crashato: ignora ogni messaggio
        if (crashed)
            return;

        // Se il token ritorna ad ATM1, non è più in transito
        if (id == 1) {
            tokenInTransit = false;
        }

        // Aggiorna il timestamp di ricezione
        lastTokenTime = System.currentTimeMillis();

        // Parsing del token
        String[] parts = token.split(":");
        int origin = Integer.parseInt(parts[1]);
        int balance = Integer.parseInt(parts[2]);

        // Verifica presenza del flag di terminazione
        stop = parts.length == 4 && parts[3].equals("STOP");

        /**
         * Terminazione distribuita:
         * - SOLO ATM1 può rilevare il completamento dell’anello
         * - Il token mantiene invariato il nodo di origine
         */
        if (id == 1 && origin == 1 && !stop) {
            log("Token Ring Completato. Invio STOP.");
            sendToken("TOKEN:" + origin + ":" + balance + ":STOP");
            System.exit(0);
        }

        // I nodi non originatori propagano semplicemente lo STOP
        if (stop) {
            log("Ricevuto STOP. Terminazione.");
            sendToken("TOKEN:" + origin + ":" + balance + ":STOP");
            System.exit(0);
        }

        log("Ricevuto TOKEN da origin=" + origin + " saldo=" + balance);

        // ===== SEZIONE CRITICA =====
        if (hasPendingTransaction) {
            log("Inizio transazione: " + transactionType + " " + amount);

            if (transactionType.equals("WITHDRAW") && balance >= amount)
                balance -= amount;
            else if (transactionType.equals("DEPOSIT"))
                balance += amount;

            log("Fine transazione | Nuovo saldo=" + balance);
            hasPendingTransaction = false;
        }
        // ===========================

        // Inoltro del token al nodo successore
        sendToken("TOKEN:" + origin + ":" + balance);
    }

    /**
     * Invia il token al nodo successore.
     * In caso di indisponibilità del successore, il nodo ritenta
     * periodicamente evitando la perdita del token.
     */
    private void sendToken(String token) throws Exception {
        while (true) {
            try {
                Socket socket = new Socket("localhost", nextPort);
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                out.println(token);
                socket.close();

                log("Inoltrato TOKEN");

                // ATM1 marca il token come "in transito"
                if (id == 1) {
                    tokenInTransit = true;
                }
                break;

            } catch (IOException e) {
                log("Successore non disponibile, ritento...");
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ignored) {}
            }
        }
    }

    /**
     * Metodo di logging locale del nodo
     */
    private void log(String msg) {
        System.out.println("[ATM" + id + "] " + msg);
    }

    /**
     * Metodo main:
     * - inizializza il nodo
     * - avvia il server
     * - ATM1 inizializza il token dopo il bootstrap
     */
    public static void main(String[] args) throws Exception {
        int id = Integer.parseInt(args[0]);
        int myPort = Integer.parseInt(args[1]);
        int nextPort = Integer.parseInt(args[2]);
        boolean crash = args.length > 3 && args[3].equals("CRASH");

        ATMNode node = new ATMNode(id, myPort, nextPort);
        node.crashed = crash;

        // Avvio del server in un thread separato
        new Thread(() -> {
            try {
                node.start();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();

        // ATM1 crea il token iniziale dopo il bootstrap dell’anello
        if (id == 1 && !crash) {
            Thread.sleep(8000);
            node.ringReady = true;
            node.log("Creato TOKEN iniziale");
            node.sendToken("TOKEN:1:1000");
        }
    }
}
