import java.io.*;
import java.net.*;
import java.util.*;

/**
 * ATMNode rappresenta un nodo (ATM) di un sistema bancario distribuito
 * che utilizza l'algoritmo Token Ring per garantire la mutua esclusione.
 */
public class ATMNode {

    // Identificatore logico del nodo (ATM1, ATM2, ...)
    private int id;

    // Porta locale su cui il nodo resta in ascolto
    private int myPort;

    // Porta del nodo successore nell'anello logico
    private int nextPort;

    // Indica se il nodo ha una transazione da eseguire
    private boolean hasPendingTransaction = false;

    // Tipo di transazione (DEPOSIT o WITHDRAW)
    private String transactionType = "";

    // Importo della transazione
    private int amount = 0;

    // Timeout massimo di attesa del token (in millisecondi)
    private static final int TIMEOUT = 8000;

    // Timestamp dell’ultima ricezione del token
    private long lastTokenTime = System.currentTimeMillis();

    // Simula il crash del nodo (fail-stop)
    private boolean crashed = false;

    // Flag che indica la ricezione del token di terminazione
    private boolean stop = false;

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
     * Inizializza una transazione predefinita per alcuni nodi,
     * usata per la dimostrazione del sistema.
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
     * Avvia il server socket del nodo e resta in ascolto
     * dei token provenienti dal predecessore.
     */
    public void start() throws Exception {
        ServerSocket serverSocket = new ServerSocket(myPort);

        // Timeout per evitare blocchi indefiniti su accept()
        serverSocket.setSoTimeout(5000);

        log("In ascolto sulla porta " + myPort);

        // Thread separato che monitora la perdita del token
        new Thread(this::monitorToken).start();

        // Ciclo principale di ricezione del token
        while (true) {
            try {
                Socket socket = serverSocket.accept();
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(socket.getInputStream()));
                String token = in.readLine();
                socket.close();
                handleToken(token);
            } catch (SocketTimeoutException ignored) {
                // Timeout usato solo per permettere controlli periodici
            }
        }
    }

    /**
     * Thread di monitoraggio che rileva la perdita del token.
     * Solo ATM1 può rigenerare il token in caso di timeout.
     */
    private void monitorToken() {
        while (true) {
            long tokenTime = System.currentTimeMillis() - lastTokenTime;

            // Rigenerazione del token in caso di perdita
            if (!crashed && tokenTime > TIMEOUT && id == 1) {
                log("Timeout scaduto! Rigenero il token");
                try {
                    sendToken("TOKEN:1:1000");
                } catch (Exception e) {
                    // Ignorato: simulazione ambiente semplice
                }
                lastTokenTime = System.currentTimeMillis();
            }

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
            }
        }
    }

    /**
     * Gestisce il token ricevuto:
     * - esegue la transazione (se presente)
     * - aggiorna il saldo
     * - inoltra il token
     * - gestisce la terminazione distribuita
     */
    private void handleToken(String token) throws Exception {

        // Se il nodo è crashato, ignora il token
        if (crashed)
            return;

        // Aggiorna il tempo di ricezione del token
        lastTokenTime = System.currentTimeMillis();

        // Parsing del token
        String[] parts = token.split(":");
        int tokenId = Integer.parseInt(parts[1]);
        int balance = Integer.parseInt(parts[2]);

        // Controllo presenza flag STOP
        stop = parts.length == 4 && parts[3].equals("STOP");

        /**
         * Condizione di terminazione:
         * - il token ritorna ad ATM1 dopo un giro completo
         * - oppure il token contiene il flag STOP
         */
        if ((id == 1 && tokenId > 1) || stop) {
            log("Token Ring Completato. Invio STOP.");
            sendToken("TOKEN:" + tokenId + ":" + balance + ":STOP");
            System.exit(0);
        }

        log("Ricevuto TOKEN da id=" + tokenId + " saldo=" + balance);

        // Sezione critica: esecuzione della transazione
        if (hasPendingTransaction) {
            log("Inizio transazione: " + transactionType + " " + amount);

            if (transactionType.equals("WITHDRAW") && balance >= amount)
                balance -= amount;
            else if (transactionType.equals("DEPOSIT"))
                balance += amount;

            log("Fine transazione | Nuovo saldo=" + balance);
            hasPendingTransaction = false;
        }

        // Inoltro del token al nodo successore
        sendToken("TOKEN:" + (tokenId + 1) + ":" + balance);
    }

    /**
     * Invia il token al nodo successore nell'anello logico
     */
    private void sendToken(String token) throws Exception {
        Socket socket = new Socket("localhost", nextPort);
        PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
        out.println(token);
        socket.close();
        log("Inoltrato TOKEN");
    }

    /**
     * Metodo di logging locale del nodo
     */
    private void log(String msg) {
        System.out.println("[ATM" + id + "] " + msg);
    }

    /**
     * Metodo main:
     * - crea il nodo
     * - avvia il server in un thread separato
     * - ATM1 inizializza il token
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

        // ATM1 crea il token iniziale
        if (id == 1 && !crash) {
            Thread.sleep(2000);
            node.log("Creato TOKEN iniziale");
            node.sendToken("TOKEN:1:1000");
        }
    }
}
