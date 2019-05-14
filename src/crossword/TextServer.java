package crossword;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Set;

import crossword.Game.WaitListener;
import crossword.Game.WatchListener;


/**
 * Text-protocol game server.
 */
public class TextServer {
    private static String QUIT = "quit";
    
    // Abstraction function:
    //    AF(serverSocket, game) --> TextServer Object having the ability to connect one player to a Crossword Puzzle game. 
    //                                        One serverSocket (a rep) is able to run multiple connections, game is the game class for
    //                                        the Crossword game
    // Representation invariant:
    //  true
    // Safety from rep exposure:
    //  serverSocket is private and final
    //  game is private and final
    // Thread Safety Argument:
    //   TextServer creates a new thread for each new connecting client
    //   game is an object with threadsafe type
    //   threads for each clients operates on their own and do not interact with each other
    
    private final ServerSocket serverSocket;
    private final Game game;
    /**
     * Make a new text game server using game that listens for connections on port.
     * 
     * @param game shared crossword puzzles
     * @param port server port number
     * @throws IOException if an error occurs opening the server socket
     */
    public TextServer(Game game, int port) throws IOException {
        this.serverSocket = new ServerSocket(port);
        this.game = game;
    }
        
    /**
     * @return the port on which this server is listening for connections
     */
    public int port() {
        return serverSocket.getLocalPort();
    }
    
    /**
     * Run the server, listening for and handling client connections.
     * Never returns normally.
     * 
     * @throws IOException if an error occurs waiting for a connection
     */
    public void serve() throws IOException {
        System.err.println("Server listening on " + serverSocket.getLocalSocketAddress());
        while (true) {  
            Socket socket = serverSocket.accept();
            try {
            new Thread(new Runnable() {
                public void run() {
                    try {
                        handleConnection(socket);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    finally {
                        try {
                            socket.close();
                        }
                        catch(Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
            }).start();
            }
            catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
    
    /**
     * Handle a single client connection.
     * Returns when the client disconnects.
     * 
     * @param socket socket connected to client
     * @throws IOException if the connection encounters an error or closes unexpectedly
     */
    private void handleConnection(Socket socket) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), UTF_8));
        PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), UTF_8), true);
        
        try {
            for (String input = in.readLine(); input != null; input = in.readLine()) {
                String output = handleRequest(input);
                if (output.equals(QUIT)) {
                    break;
                }
                out.println(output);

            }
        } finally {
            out.close();
            in.close();
        }
    }
    
    /**
     * Handle a single client request and return the server response.
     * 
     * @param input message from client
     * @return output message to client
     * @throws IOException 
     */
    private String handleRequest(String input) throws IOException {
        String[] tokens = input.split(" ");
        String playerID = tokens[0];
        String command = tokens[1];  
        
        // Check whether the playerID is valid
        if (!(playerID.matches("[0-9a-zA-Z]*") && playerID.length() > 0)) {
            throw new RuntimeException("Player ID must be alphanumeric and have a length > 0");
        }
        
        if (command.equals("quit")) {
            return QUIT;
        }
        //TODO: implement each command as needed
        if (command.equals("WATCH")) {
            game.addWatchListener(new WatchListener() {
                public String onChange() {
                    return game.getAvailableMatchesForResponse();
                }
            });
        }
        else if (command.equals("WAIT")) {
            game.addWaitListener(playerID, new WaitListener() {
                public String onChange() {
                    System.out.println("id: "+playerID);
                    return game.getMatchPuzzleForResponse(playerID);
                }
            }
            );
        }
        if (command.equals("LOGIN")) { // Logs in a player and returns the names of all puzzle templates
            if (game.login(playerID)) {
                // Build the string in 3 parts: add puzzle names -> add response delim -> add available matches
                StringBuilder builder = new StringBuilder();
                builder.append("V");
                builder.append(game.getPuzzleNamesForResponse());
                builder.append(Game.RESPONSE_DELIM);
                builder.append(game.getAvailableMatchesForResponse());
                return builder.toString();
            } else {
                return "I" + "Player failed to log in";
            }
        }
        else if (command.equals("PLAY")) {
            boolean join = game.joinMatch(playerID, tokens[2]);
            if (join) {
                return "V" + game.getPuzzleFromMatchID(tokens[2]);
            }
            return "I" + "Player was unable to join the match";
        }
        else if (command.equals("NEW")) {
            String matchID = tokens[2];
            String puzzleID = tokens[3];
            String description = tokens[4];
            if (description.matches("\"[0-9a-zA-Z ]*\"")) {
                description = description.substring(1, description.length()-1);
            } else {
                return "I";
            }
            boolean create = game.createMatch(playerID, matchID, puzzleID, description);
            if (create) {
                return "V";
            }
            return "I" + "Match was unable to be created";
        }
        else if (command.equals("LOGOUT")) {
            throw new RuntimeException("Not Implemented");
        }
        else if (command.equals("TRY")) {
            throw new RuntimeException("Not Implemented");
        }
        else if (command.equals("CHALLENGE")) {
            throw new RuntimeException("Not Implemented");
        }
        else if (command.equals("NEW_MATCH")) {
            throw new RuntimeException("Not Implemented");
        }

        /* 
        else if (command.equals("GET")) {
        Set<String> puzzleNames = game.getPuzzleNames();
            StringBuilder allPuzzles = new StringBuilder();
            allPuzzles.append("Puzzles Available: ");
            for (String puzzle: puzzleNames) {
                allPuzzles.append(puzzle + ",");
            }
            allPuzzles.deleteCharAt(allPuzzles.length()-1);
            return allPuzzles.toString();
        }
        else if (puzzleNames.contains(command)) {
            return game.getPuzzleForResponse(command);
        }
        else if (!puzzleNames.contains(command)) {
            return command;
        }
        */
        // if we reach here, the client message did not follow the protocol
        throw new UnsupportedOperationException(input);

    }
}
