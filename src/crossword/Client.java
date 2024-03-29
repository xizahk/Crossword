/* Copyright (c) 2019 MIT 6.031 course staff, all rights reserved.
 * Redistribution of original or derived work requires permission of course staff.
 */
package crossword;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.awt.BorderLayout;
import java.awt.Font;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.Set;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.WindowConstants;
/**
 * Text-protocol game client.
 */
public class Client {
    
    private static final int CANVAS_WIDTH = 1200;
    private static final int CANVAS_HEIGHT = 900;
    private static final String EXIT =  "EXIT";
    private static final String NEW_MATCH = "NEW MATCH";
    
    private final String host;
    private final int port;
    private String playerID = "";
    private CrosswordCanvas canvas;
    private final List<Thread> listenerThreads; // keeps track of currently running threads
    
    // Abstraction function:
    //  AF(host, port, playerID, canvas, listenerThreads):
    //      client connecting with hostname host to port port, where the client starts with an
    //      empty string for playerID and updates playerID after logging in. The client draws on
    //      the canvas to play the crossword puzzle. The listenerThreads stores the list of 
    //      running threads that check for updates in the match as the client is connected. 
    // Representation invariant:
    //      host.length() > 0,
    //      port >= 0,
    //      playerID.length() >= 0,
    // Safety from rep exposure:
    //  All fields are private
    //  None of the methods return any references to the fields
    // Thread safety argument:
    //  This uses the monitor pattern
    
    /**
     * Creates a new client
     * @param host host value
     * @param port port value
     */
    public Client(String host, int port) {
        this.host = host;
        this.port = port;
        listenerThreads = new ArrayList<>();
        checkRep();
    }
    
    private void checkRep() {
        assert host.length() > 0;
        assert port >= 0;
        assert playerID.length() >= 0;
    }
    
    /**
     * Start a Crossword Extravaganza client.
     * 
     * Command to connect client: java -cp bin crossword.Client localhost 4444
     * 
     * @param args The command line arguments should include only the server address.
     * @throws IOException not connected
     */
    public static void main(String[] args) throws IOException {
        final Queue<String> arguments = new LinkedList<>(List.of(args));
        
        final String host;
        final int port;
        
        try {
            host = arguments.remove();
        } catch (NoSuchElementException nse) {
            throw new IllegalArgumentException("missing HOST", nse);
        }
        try {
            port = Integer.parseInt(arguments.remove());
        } catch (NoSuchElementException | NumberFormatException e) {
            throw new IllegalArgumentException("missing or invalid PORT", e);
        }
        
        try (                
                Socket socket = new Socket(host, port);
                BufferedReader socketIn = new BufferedReader(new InputStreamReader(socket.getInputStream(), UTF_8));
                PrintWriter socketOut = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), UTF_8), true);
                BufferedReader systemIn = new BufferedReader(new InputStreamReader(System.in));
        ) {
            Client client = new Client(host, port);
            client.launchGameWindow(socketIn, socketOut);
            boolean showRaw = false;
            while (!socket.isClosed()) {
                System.out.print("? ");
                final String command = systemIn.readLine();
                switch (command) {
                case "":
                    System.out.println("exiting");
                    return;
                case "!debug":
                    showRaw = ! showRaw;
                    System.out.println("debugging " + (showRaw ? "on" : "off"));
                    continue;
                default:
                    socketOut.println(command);
                }
            }
            System.out.println("connection closed");
        }
    }
    
    /**
     * Returns the response from server given a request
     * @param request the request for the server 
     * @param socketIn the socket for accepting responses
     * @param socketOut the socket for sending requests
     * @return response from the server given the request
     */
    private synchronized String getResponse(String request, BufferedReader socketIn, PrintWriter socketOut) {
        try {
            socketOut.println(request);
            String response = socketIn.readLine();
            canvas.setPreviousResponse(response);
            
            checkRep();
            return response;
        } catch (IOException ioe) {
            throw new RuntimeException("Error occured when processing request: " + request);
        }
    }
    
    /**
     * Starts a new thread for watching changes to available matches
     */
    private synchronized void startNewWatchThread() {
        // Create a new thread to watch for changes for available matches
        Thread watchThread = new Thread(new Runnable() {
            public void run() {
                try (
                    Socket watchSocket = new Socket(host, port);
                    BufferedReader watchSocketIn = new BufferedReader(new InputStreamReader(watchSocket.getInputStream(), UTF_8));
                    PrintWriter watchSocketOut = new PrintWriter(new OutputStreamWriter(watchSocket.getOutputStream(), UTF_8), true);
                    BufferedReader watchSystemIn = new BufferedReader(new InputStreamReader(System.in));
                ) {
                    while (canvas.getState() == State.CHOOSE) {
                        watchSocketOut.println(playerID + " " + "WATCH");
                        String watchResponse = watchSocketIn.readLine();
                        canvas.setMatchList(watchResponse.substring(1));
                        canvas.repaint();
                    }
                } catch (IOException ioe) {
                    System.out.println("Something went wrong in watching for changes in matches");
                } 
            }
         });
        
        listenerThreads.add(watchThread);
        watchThread.start();
    }
    
    /**
     * Sets matches and puzzles for the canvas to draw
     * @param response that contains matches and puzzles information
     */
    private synchronized void setMatchesAndPuzzles(String response) {
        // list of puzzles RESPONSE_DELIM list of matches
        String[] matchesAndPuzzles = response.substring(1).split(CrosswordCanvas.RESPONSE_DELIM);
        canvas.setPuzzleList(matchesAndPuzzles[0]);
        if (matchesAndPuzzles.length == 1) {
            canvas.setMatchList("");
        } else {
            canvas.setMatchList(matchesAndPuzzles[1]);
        }
    }
    
    /**
     * Transition crossword canvas from start state to choose state
     * @param id id of the player/client
     * @param socketIn buffered reader to read input from server
     * @param socketOut print writer to write to the server
     */
    private synchronized void transitionStartState(String id, BufferedReader socketIn, PrintWriter socketOut) {
        String request = id + " " + "LOGIN";
        String response = getResponse(request, socketIn, socketOut);
        
        if (response.charAt(0) == 'I') {
            return;
        }
        
        canvas.setPlayerID(id);
        setMatchesAndPuzzles(response);
        setCanvasState(State.CHOOSE);
        canvas.repaint();
        playerID = id;
        
        startNewWatchThread();
        checkRep();
    }
    
    /**
     * Transition crossword canvas from choose state to play state
     * 
     * Commands:
     *  PLAY Match_ID
     *  NEW Match_ID Puzzle_ID "Description"
     *  EXIT
     * 
     * @param command command that the player can call in the choose state
     * @param socketIn buffered reader to read input from server
     * @param socketOut print writer to write to the server
     */
    private synchronized void transitionChooseState(String command, BufferedReader socketIn, PrintWriter socketOut) {
        String request = playerID + " " + command;
        String response = getResponse(request, socketIn, socketOut);
        
        String[] commandParts = command.split(" ");
        if (response.charAt(0) == 'I') {
            return;
        }
        
        if (commandParts[0].equals("PLAY")) {
            transitionToPlayState(socketIn, socketOut);
        } else {
            setCanvasState(State.WAIT);
        }
        canvas.setPuzzle(response.substring(1));
        canvas.repaint();
        
        // Start a new thread that will be waiting for another player to join the match
        Thread waitThread = new Thread(new Runnable() {
            public void run() {
                try (
                    Socket waitSocket = new Socket(host, port);
                    BufferedReader waitSocketIn = new BufferedReader(new InputStreamReader(waitSocket.getInputStream(), UTF_8));
                    PrintWriter waitSocketOut = new PrintWriter(new OutputStreamWriter(waitSocket.getOutputStream(), UTF_8), true);
                    BufferedReader waitSystemIn = new BufferedReader(new InputStreamReader(System.in));
                ) {
                    waitSocketOut.println(playerID + " " + "WAIT");
                    // Blocked until another player joins the match
                    String waitResponse = waitSocketIn.readLine();
                    canvas.setPuzzle(waitResponse.substring(1));
                    canvas.repaint();
                    // It's time to play!
                    transitionToPlayState(socketIn, socketOut);
                } catch (IOException ioe) {
                    
                } 
            }
         });
        
        listenerThreads.add(waitThread);
        waitThread.start();
        checkRep();
    }
    
    private synchronized void transitionToPlayState(BufferedReader socketIn, PrintWriter socketOut) {
        setCanvasState(State.PLAY);
        
        // Create a new thread to listen for changes in the puzzle being played
        Thread playThread = new Thread(new Runnable() {
            public void run() {
                try (
                    Socket playSocket = new Socket(host, port);
                    BufferedReader playSocketIn = new BufferedReader(new InputStreamReader(playSocket.getInputStream(), UTF_8));
                    PrintWriter playSocketOut = new PrintWriter(new OutputStreamWriter(playSocket.getOutputStream(), UTF_8), true);
                    BufferedReader playSystemIn = new BufferedReader(new InputStreamReader(System.in));
                ) {
                    while (canvas.getState() == State.PLAY) {
                        playSocketOut.println(playerID + " " + "WAIT_PLAY");
                        String playResponse = playSocketIn.readLine();
                        
                        processResponseForPlay(playResponse);
                    }
                } catch (IOException ioe) {
                    
                } 
            }
         });
        
        listenerThreads.add(playThread);
        playThread.start();
        checkRep();
    }
    
    /**
     * Processes the response for PLAY state by changing canvas state to SHOW_SCORE if the game is done and
     *  setting the current puzzle for the canvas
     * @param response the response in PLAY state
     */
    private synchronized void processResponseForPlay(String response) {
        String[] responseParts = response.substring(1).split(CrosswordCanvas.RESPONSE_DELIM);
        
        if (responseParts[0].equals("DONE")) {
            canvas.setOverallScore(responseParts[1]);
            // Only set a winner if a winner exists
            if (responseParts.length > 2) {
                canvas.setWinner(responseParts[2]);
            }
            setCanvasState(State.SHOW_SCORE);
        }
        
        String delim = CrosswordCanvas.RESPONSE_DELIM.replace("\\s", " ");
        
        String currentPuzzle = "";
        for (int i = 1; i < responseParts.length; i++) {
            currentPuzzle += responseParts[i] + delim;
        }
        
        canvas.setCurrentPuzzle(currentPuzzle.substring(0, currentPuzzle.length() - delim.length()));
        canvas.repaint();
        checkRep();
    }
    
    /**
     * Updates the canvas as client plays the crossword puzzle.
     * 
     * Commands:
     *  TRY id word
     *  CHALLENGE id word
     *  EXIT
     * 
     * @param command command that the player can call in the choose state
     * @param socketIn buffered reader to read input from server
     * @param socketOut print writer to write to the server
     */
    private synchronized void playPuzzle(String command, BufferedReader socketIn, PrintWriter socketOut) {
        String request = playerID + " " + command;
        String response = getResponse(request, socketIn, socketOut);
        
        if (response.charAt(0) == 'I') {
            return;
        }
        
        processResponseForPlay(response);
        checkRep();
    }
    
    /**
     * Logs out a player from the server by sending an EXIT request 
     */
    private void logoutFromServer(BufferedReader socketIn, PrintWriter socketOut) {
        String response = getResponse(playerID + " " + "LOGOUT", socketIn, socketOut);
        checkRep();
        if (response.charAt(0) == 'I') {
            throw new RuntimeException("Failed to log out from the server");
        }
    }
    
    /**
     * Exits a player from wait state on the server
     * @param socketIn
     * @param socketOut
     */
    private void exitWaitFromServer(BufferedReader socketIn, PrintWriter socketOut) {
        String response = getResponse(playerID + " " + "EXIT_WAIT", socketIn, socketOut);
        checkRep();
        if (response.charAt(0) == 'I') {
            throw new RuntimeException("Unable to exit from waiting on the server");
        }
    }
    
    /**
     * Exists a player from play state on the server
     * @param socketIn
     * @param socketOut
     */
    private void exitPlayFromServer(BufferedReader socketIn, PrintWriter socketOut) {
        String response = getResponse(playerID + " " + "EXIT_PLAY", socketIn, socketOut);
        if (response.charAt(0) == 'I') {
            throw new RuntimeException("Unable to exit from waiting on the server");
        }
        String[] responseParts = response.substring(1).split(CrosswordCanvas.RESPONSE_DELIM);
        canvas.setOverallScore(responseParts[1]);
        checkRep();
    }
    
    /**
     * Resets the state of the client to start
     */
    private void resetToStart() {
        // Reset player information
        playerID = "";
        canvas.setPlayerID(playerID);
        setCanvasState(State.START);
        checkRep();
    }
    
    /**
     * Stops all currently running listener threads and sets a new canvas state
     * @param state
     */
    private void setCanvasState(State state) {
        canvas.setState(state);
        checkRep();
    }
    
    /**
     * Starts a new match after the player enters SHOW_SCORE state
     */
    private synchronized void startNewMatch(BufferedReader socketIn, PrintWriter socketOut) {
        startNewWatchThread();
        setCanvasState(State.CHOOSE);
        canvas.clearPuzzleInfo();
        String response = getResponse(playerID + " " + "NEW_MATCH", socketIn, socketOut);
        // Get available matches and puzzles
        setMatchesAndPuzzles(response);
    }
    
    /**
     * Checks whether a command is valid or not
     * @param input the input entered by the player, must have 1 or more characters
     */
    private synchronized boolean isValidCommand(String input) {
        String[] tokens = input.split(" ");
        String command = tokens[0];
        Set<String> validCommands;
        switch (canvas.getState()) {
        case START:
            {
                // Only playerID is allowed for logging in
                return (tokens.length == 1);
            }
        case CHOOSE:
            {
                validCommands = Set.of("PLAY", "NEW", "EXIT");
                break;
            }
        case WAIT:
            {
                validCommands = Set.of("EXIT");
                break;
            }
        case PLAY:
            {
                validCommands = Set.of("TRY", "CHALLENGE", "EXIT");
                break;
            }
        case SHOW_SCORE:
            {
                if (tokens.length == 1) {
                    return command.equals("EXIT");
                } else if (tokens.length == 2) {
                    return tokens[0].equals("NEW") && tokens[1].equals("MATCH");
                } else {
                    return false;
                }
            }
        default:
            throw new RuntimeException(canvas.getState() + " is not a valid state!");
        }
        
        return validCommands.contains(command);
    }
    
    /**
     * Starter code to display a window with a CrosswordCanvas,
     * a text box to enter commands and an Enter button.
     */
    private synchronized void launchGameWindow(BufferedReader socketIn, PrintWriter socketOut) {
        canvas = new CrosswordCanvas("");
        canvas.setSize(CANVAS_WIDTH, CANVAS_HEIGHT);
        
        final int textboxSize = 30;
        final int fontSize = 20;
        JTextField textbox = new JTextField(textboxSize);
        textbox.setFont(new Font("Arial", Font.BOLD, fontSize));
                
        JButton enterButton = new JButton("Enter");
        enterButton.addActionListener((event) -> {
            // This code executes every time the user presses the Enter
            // button. Recall from reading 24 that this code runs on the
            // Event Dispatch Thread, which is different from the main
            // thread.
            String text = textbox.getText();
            textbox.setText("");
            canvas.repaint();
            
            if (text.length() == 0) {
                return;
            }
            // Check whether the input is a valid command 
            if (!isValidCommand(text)) {
                canvas.setPreviousResponse("I" + "Invalid command");
                return;
            }
            
            switch (canvas.getState()) {
            case START:
                {
                    transitionStartState(text, socketIn, socketOut);
                    break;
                }
            case CHOOSE:
                {
                    if (text.equals(EXIT)) {
                        logoutFromServer(socketIn, socketOut);
                        resetToStart();
                    } else {
                        transitionChooseState(text, socketIn, socketOut);
                    }
                    break;
                }
            case WAIT:
                {
                    if (text.equals(EXIT)) {
                        exitWaitFromServer(socketIn, socketOut);
                        startNewWatchThread();
                        setCanvasState(State.CHOOSE);
                    }
                    break;
                }
            case PLAY:
                {
                    if (text.equals(EXIT)) {
                        exitPlayFromServer(socketIn, socketOut);
                        setCanvasState(State.SHOW_SCORE);
                    } else {
                        playPuzzle(text, socketIn, socketOut);
                    }
                    break;
                }
            case SHOW_SCORE:
                {
                    if (text.equals(NEW_MATCH)) {
                        startNewMatch(socketIn, socketOut);
                    } else if (text.equals(EXIT)) {
                        logoutFromServer(socketIn, socketOut);
                        canvas.clearPuzzleInfo();
                        canvas.clearPlayerInfo();
                        resetToStart();
                    }
                    break;
                }
            default:
                {
                    throw new AssertionError("should never get here");
                }
            }
        });
        
        final int enterLength = 10;
        enterButton.setSize(enterLength, enterLength);

        JFrame window = new JFrame("Crossword Client");
        window.setLayout(new BorderLayout());
        window.add(canvas, BorderLayout.CENTER);

        JPanel contentPane = new JPanel();
        contentPane.add(textbox);
        contentPane.add(enterButton);

        window.add(contentPane, BorderLayout.SOUTH);

        final int widowDisplacement = 50;
        window.setSize(CANVAS_WIDTH + widowDisplacement, CANVAS_HEIGHT + widowDisplacement);

        window.getContentPane().add(contentPane);

        window.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        window.setVisible(true);
        checkRep();
    }
    
    
}
