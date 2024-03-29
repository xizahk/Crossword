package crossword;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import edu.mit.eecs.parserlib.UnableToParseException;

/**
 * A mutable Game class representing a single Crossword Game
 *
 */
public class Game {
    public static final String ENTRY_DELIM = " as3fb ";
    public static final String WORD_DELIM = " bs1fc ";
    public static final String RESPONSE_DELIM = " cs2fd ";
    private final Set<String> players; // set of all playerIDs currently logged in
    private final Map<String, Puzzle> puzzles; // map of puzzleID : Puzzle
    private final Map<String, String> playerToMatch; // map of playerID : match_id
    private final Map<String, Match> matches; //  map of match_id : match
    private Set<WatchListener> watchListeners;
    private final Map<String, WaitListener> waitListeners;
    private final Map<String, PlayListener> playListeners;
    
    /**
     * Main method. Makes the Game from Puzzle objects from parsing.
     * 
     * @param args command line arguments, not used
     * @throws UnableToParseException if example expression can't be parsed
     * @throws IOException if .puzzle file cannot be opened
     */
    public static void main(final String[] args) throws UnableToParseException, IOException {
        System.out.println(Game.parseGameFromFiles("puzzles/").getPuzzleNames());
    }
    
    /**
     * @param directory the folder to the puzzles
     * @return a new Game with all puzzles parsed from files inside puzzles/
     * @throws UnableToParseException Puzzle cannot be parsed
     * @throws IOException File cannot be read
     */
    public static Game parseGameFromFiles(String directory) throws UnableToParseException, IOException {
        // Do something
        HashMap<String, Puzzle> puzzles = new HashMap<>();
        File folder = new File(directory);
        File[] listofPuzzles = folder.listFiles();
        for (File file : listofPuzzles) {
            if (file.isFile()) {
                Puzzle newPuzzle = Puzzle.parseFromFile(directory + file.getName()); 
                if (newPuzzle.isConsistent()) {
                    String puzzleID = newPuzzle.getName();
                    if (puzzleID.length() == 0) {
                        puzzleID = "JYZ";
                    }
                    
                    // If the puzzle name is already in puzzles, try 
                    //  using puzzleName1, puzzleName2, etc. until one of them hasn't been taken yet
                    if (puzzles.containsKey(puzzleID)) {
                        int i = 1;
                        puzzleID += Integer.toString(i);
                        while (puzzles.containsKey(puzzleID)) {
                            i += 1;
                            // Remove the number at the end and replace it with a new number
                            puzzleID = puzzleID.substring(0, puzzleID.length()-1);
                            puzzleID += Integer.toString(i);
                        }
                    }
                    puzzles.put(puzzleID, newPuzzle);
                }
            }
        }
        return new Game(puzzles);
    }
    
    // Abstraction function:
    //    AF(players, puzzles, playerToMatch, matches, 
    //       watchListeners, waitListeners, playListeners):
    //          a crossword game with multiple crossword puzzles, where players represents the
    //          set of players logged in to the game and where each entry in puzzles 
    //          represents a mapping of puzzle name to the crossword puzzle it represents, 
    //          each entry in playerToMatch represents a mapping of a current player to the match 
    //          it represents, and matches represents a mapping of a match name to an object
    //          representation of a match. watchListeners represents the set of WatchListeners
    //          that notifies all players when a change occurs to the list of available matches.
    //          waitListeners and playListeners represent a mapping of players in matches to 
    //          the respective listeners when a change occurs in their match.
    // Representation invariant:
    //  true
    // Safety from rep exposure:
    //  all fields are final and private
    //  puzzles map is mutable, but defensive copies are made in getPuzzles() to not return the original
    //      puzzles
    //  puzzles's keys and values are immutable types (String and Puzzle respectively)
    //  all getter methods return an immutable String object and does not access the reps directly
    //  only way to modify reps is through mutator methods
    // Thread Safety Argument:
    //  uses monitor pattern
    
    /**
     * Creates a new Game
     * @param puzzles map of name : puzzles that are valid (consistent)
     */
    public Game(Map<String, Puzzle> puzzles) {
        this.puzzles = Collections.unmodifiableMap(new HashMap<>(puzzles));
        this.matches = Collections.synchronizedMap(new HashMap<String, Match>());
        this.playerToMatch = Collections.synchronizedMap(new HashMap<String, String>());
        this.players = Collections.synchronizedSet(new HashSet<String>());
        this.watchListeners = Collections.synchronizedSet(new HashSet<>());
        this.waitListeners = Collections.synchronizedMap(new HashMap<>());
        this.playListeners = Collections.synchronizedMap(new HashMap<>());
    }
    
    /**
     * Returns a puzzle with a specific format where every entry is separate by new lines and no 
     * words are revealed
     * @param name the name of the puzzle
     * @return string with format: length, clue, orientation, row, col\n
     *         e.g: "4, "twinkle twinkle", ACROSS, 0, 1\n"
     */
    public synchronized String getPuzzleForResponse(String name) {
        Puzzle puzzle = puzzles.get(name);
        String puzzleString = "";
        for (Map.Entry<Integer, PuzzleEntry> entry: puzzle.getEntries().entrySet()) {
            Integer id = entry.getKey();
            PuzzleEntry puzzleEntry = entry.getValue();
            puzzleString += id + WORD_DELIM + puzzleEntry.getWord().length() + WORD_DELIM + puzzleEntry.getClue() + WORD_DELIM + 
                    puzzleEntry.getOrientation() + WORD_DELIM + puzzleEntry.getPosition().getRow() 
                            + WORD_DELIM + puzzleEntry.getPosition().getCol() + ENTRY_DELIM;
        }
        return puzzleString.substring(0,puzzleString.length()-ENTRY_DELIM.length());
    }
    
    /**
     * Returns puzzle id from match with match id
     * @param matchID the id of the match
     * @return puzzle of the given match
     */
    public synchronized String getPuzzleFromMatchID(String matchID) {
        return matches.get(matchID).getPuzzleForResponse();
    }

    /**
     * 
     * @param playerID player
     */
    public synchronized void removePlayerAndMatch(String playerID) {
        String matchID = playerToMatch.get(playerID);
        Match match = matches.get(matchID);
        String playerOne = match.getPlayerOne();
        String playerTwo = match.getPlayerTwo();
        if (match.isDone()) {
            playerToMatch.remove(playerOne);
            playerToMatch.remove(playerTwo);
            matches.remove(matchID);
        }
    }
    
    /**
     * Gets names of all puzzles and descriptions that are waiting for another player
     * @return String with format: 
     *      match ::= match_ID WORD_DELIM description;
     *      response ::= match (ENTRY_DELIM match)*;
     */
    public synchronized String getAvailableMatchesForResponse(){
        StringBuilder responseBuilder = new StringBuilder();
        for (Match match : matches.values()) {
            if (responseBuilder.length() > 0) { // Add an entry delim if the entry is not the first one
                responseBuilder.append(ENTRY_DELIM);
            }
            // Only add matches that are waiting for another player
            if (match.isWaiting()) {
                responseBuilder.append(match.getMatchId() + WORD_DELIM + match.getDescription());
            }
        }
        return responseBuilder.toString();
    }
        
    /**
     * Allows a new player into the game
     * @param playerID player name
     * @return true if player managed to join the game, false otherwise
     */
    public synchronized boolean login(String playerID) {
        if (players.contains(playerID)) { // Player already logged in!
            return false;
        }
        // player successfully logged in
        players.add(playerID);
        return true;
    }
    /**
     * Player tries to join a match with match id
     * @param playerID ID of the player who wants to join a game
     * @param matchID ID of the puzzle that the player wants to join
     * @return true if successfully joined, false otherwise
     * @throws IOException 
     */
    public synchronized boolean joinMatch(String playerID, String matchID) throws IOException {
        if (!matches.containsKey(matchID)) {
            // Match ID does not exist
            return false;
        }
        boolean joined = matches.get(matchID).joinMatch(playerID);
        if (joined) {
            playerToMatch.put(playerID, matchID);
            callWatchListeners();
            Match match = matches.get(matchID);
            // Available matches just changed
            callWatchListeners();
            callWaitListener(match.getPlayerOne());
        }
        return joined;
    }
    
    /**
     * Player tries to create a match with matchID as the name
     * @param playerID ID of the player who wants to join a game
     * @param matchID ID of the puzzle that the player wants to join
     * @param puzzleID puzzle name
     * @param description description of the match
     * @return true if successfully joined, false otherwise
     * @throws IOException 
     */
    public synchronized boolean createMatch(String playerID, String matchID, String puzzleID, String description) throws IOException {
        if (playerToMatch.containsKey(playerID) || matches.containsKey(matchID)) {
            return false;
        }
        if (!puzzles.containsKey(puzzleID)) {
            // No such puzzleID exists
            return false;
        }
        playerToMatch.put(playerID, matchID);
        matches.put(matchID, new Match(matchID, description, puzzles.get(puzzleID), playerID));
        callWatchListeners();
        return true;
        
    }
    
    /**
     * Disconnects a player from the game
     * @param playerID player id
     * @return true if player managed to log out of the game, false otherwise
     */
    public synchronized boolean logout(String playerID) {
        if (playerToMatch.containsKey(playerID)) {
            // Player already in a match cannot log out, they must exit the match first!
            return false;
        }
        if (players.contains(playerID)) {
            players.remove(playerID);
            return true;
        } else {
            return false;
        }
    }
    
    /**
     * Exits a player from a match that is waiting
     * @param playerID player id
     * @return true if the player managed to exit the match, false otherwise
     * @throws IOException if something goes wrong
     */
    public synchronized boolean exitWait(String playerID) throws IOException {
        if (!playerToMatch.containsKey(playerID)) {
            // Player is not in a match to exit from
            return false;
        }
        String matchID = playerToMatch.get(playerID);
        Match match = matches.get(matchID);
        if (!match.isWaiting()) {
            // Match is not waiting
            return false;
        }
        matches.remove(matchID);
        playerToMatch.remove(playerID);
        
        callWatchListeners();
        return true;
    }
    
    /**
     * Player tries to guess a word in the puzzle
     * @param playerID player name
     * @param wordID the ID of the word to attempt to solve
     * @param word the word that is guessed
     * @return true if player managed to guess, false otherwise
     * @throws IOException 
     */
    public synchronized boolean tryWord(String playerID, int wordID, String word) throws IOException {
        if (!playerToMatch.containsKey(playerID)) {
            // The player id cannot be found
            return false;
        }
        String matchID = playerToMatch.get(playerID);
        if (!matches.containsKey(matchID)) {
            // The match id cannot be found
            return false;
        }
        Match match = matches.get(matchID);
        if (!match.isOngoing()) {
            return false;
        }
        boolean success = match.tryWord(playerID, wordID, word);
        String playerOne = match.getPlayerOne();
        String playerTwo = match.getPlayerTwo();
        if (success) {
            callPlayListener(playerOne);
            callPlayListener(playerTwo);
        }
        return success;
    }
    
    /**
     * Player tries to challenge another player's word in the puzzle
     * @param playerID player name
     * @param wordID the ID of the word to attempt to challenge
     * @param word the word that the player uses to challenge
     * @return true if player managed to challenge, false otherwise
     * @throws IOException 
     */
    public synchronized boolean challengeWord(String playerID, int wordID, String word) throws IOException {
        if (!playerToMatch.containsKey(playerID)) {
            // The player id cannot be found
            return false;
        }
        String matchID = playerToMatch.get(playerID);
        if (!matches.containsKey(matchID)) {
            // The match id cannot be found
            return false;
        }
        Match match = matches.get(matchID);
        if (!match.isOngoing()) {
            return false;
        }
        String playerOne = match.getPlayerOne();
        String playerTwo = match.getPlayerTwo();
        boolean success = match.challengeWord(playerID, wordID, word);
        if (success) {
            callPlayListener(playerOne);
            callPlayListener(playerTwo);
        }
        return success;
     }
    
    /**
     * Quits the game
     * @param playerID player
     * @return true if managed to quit
     * @throws IOException 
     */
    public synchronized boolean exitPlay(String playerID) throws IOException {
        if (!playerToMatch.containsKey(playerID)) {
            // The player id cannot be found
            return false;
        }
        String matchID = playerToMatch.get(playerID);
        if (!matches.containsKey(matchID)) {
            // The match id cannot be found
            return false;
        }
        Match match = matches.get(matchID);
        String playerOne = match.getPlayerOne();
        String playerTwo = match.getPlayerTwo();
        if (match.forfeit()) {
            callPlayListener(playerOne);
            callPlayListener(playerTwo);
            return true;
        }
        return false;
    }
    
    
    /**
     * Gets a player's score
     * @param playerID player name
     * @return the score of the player
     */
    public synchronized String showScore(String playerID) {
        return matches.get(playerToMatch.get(playerID)).showScore();
    }
    
    /**
     * @return set of the names of all puzzles in the game
     */
    public synchronized Set<String> getPuzzleNames() {
        return puzzles.keySet();
    }
    
    /**
     * Returns a PlayablePuzzle with a specific format where every entry is separate by new lines and no 
     * words are revealed
     * @param playerID name of player
     * @return string with format: length, clue, orientation, row, col\n
     *         e.g: "4, "twinkle twinkle", ACROSS, 0, 1\n"
     */
    public synchronized String getMatchPuzzleForResponse(String playerID) {
        if (!playerToMatch.containsKey(playerID)) {
            throw new RuntimeException("PlayerID is not currently in a match");
        }
        String matchID = playerToMatch.get(playerID);
        Match match = matches.get(matchID);
        return match.getPuzzleForResponse();
    }

    /** A watch listener for the board  */
    public interface WatchListener {
        /** 
         * Called when the available matches in the game changes.
         * A change is defined as when a new match becomes available or an available match becomes full
         */
        public void onChange();
    }
    /**
     * Adds a listener for changes to available matches in the game
     * @param listener Adds a new listener
     */
    public synchronized void addWatchListener(WatchListener listener) {
        watchListeners.add(listener);
    }
    
    private synchronized void callWatchListeners() throws IOException{
        for (WatchListener listener : new ArrayList<>(watchListeners)) {
            listener.onChange();
            watchListeners.remove(listener);
        }
    }
    
    /** A watch listener for the game  */
    public interface WaitListener {
        /** 
         * Called when the available matches in the game changes.
         * A change is defined as when a new match becomes available or an available match becomes full
         */
        public void onChange();
    }
    
    /**
     * Adds a wait listener for a player to wait for another player to join their match
     * @param playerID id of the player
     * @param listener Adds a new listener
     */
    public synchronized void addWaitListener(String playerID, WaitListener listener) {
        waitListeners.put(playerID, listener);
    }
    
    /**
     * Calls the wait listener corresponding to the player ID
     * @param playerID the ID of the player
     * @throws IOException if calling wait listener does not work out
     */
    private synchronized void callWaitListener(String playerID) throws IOException {
        if (!waitListeners.containsKey(playerID)) {
            throw new RuntimeException("PlayerID must be waiting to call their wait listener: " + playerID);
        }
        WaitListener listener = waitListeners.get(playerID);
        listener.onChange();
        waitListeners.remove(playerID);
    }
    
    /** A play listener for the game */
    public interface PlayListener {
        /**
         * Called when a playable puzzle in the game changes.
         * A change is defined as when a change is made in a playable puzzle (such as when a play successfully
         *  TRYs or CHALLENGEs a word
         */
        public void onChange();
    }
    
    /**
     * Adds a play listener for a player to listen for changes in a playable puzzle
     * @param playerID id of the player
     * @param listener the player listener to add
     */
    public synchronized void addPlayListener(String playerID, PlayListener listener) {
        playListeners.put(playerID, listener);
    }
    
    /**
     * Calls the play listener corresponding to the player ID
     * @param playerID the ID of the player
     * @throws IOException if calling play listener does not work out
     */
    private synchronized void callPlayListener(String playerID) throws IOException {
        if (!playListeners.containsKey(playerID)) {
            throw new RuntimeException("PlayerID must be playing to call their play listener: " + playerID);
        }
        PlayListener listener = playListeners.get(playerID);
        listener.onChange();
        playListeners.remove(playerID);
    }
    
    /**
     * @return string of puzzle names with format:
     *  response = puzzleName (ENTRY_DELIM puzzleName)*
     */
    private synchronized String getPuzzleNamesForResponse() {
        StringBuilder builder = new StringBuilder();
        for (String puzzleName : puzzles.keySet()) {
            builder.append(puzzleName + Game.ENTRY_DELIM);
        }
        return builder.toString().substring(0, builder.length()-ENTRY_DELIM.length());
    }
    
    /**
     * @return puzzles and available matches
     */
    public synchronized String getPuzzlesAndAvailableMatchesForResponse() {
        // Build the string in 3 parts: add puzzle names -> add response delim -> add available matches
        StringBuilder builder = new StringBuilder();
        builder.append(getPuzzleNamesForResponse());
        builder.append(RESPONSE_DELIM);
        builder.append(getAvailableMatchesForResponse());
        return builder.toString();
    }
    
    /**
     * Returns response for a play listener
     * @param playerID the ID of the player
     * @return either a response containing a puzzle or the total score of the game based on whether
     *  the the match has ended or not
     */
    public synchronized String getPlayListenerResponse(String playerID) {
        if (!playerToMatch.containsKey(playerID)) {
            throw new RuntimeException("Player is not in a match");
        }
        Match match = matches.get(playerToMatch.get(playerID));
        if (match.isDone()) {
            return match.showScore();
        } else {
            return getGuessesForResponse(playerID);
        }
    }
    
    /**
     * Returns response of the puzzle
     * @param playerID player
     * @return puzzle
     */
    public synchronized String getGuessesForResponse(String playerID) {
        if (!playerToMatch.containsKey(playerID)) {
            throw new RuntimeException("Player is not in a match");
        }
        return matches.get(playerToMatch.get(playerID)).getGuessesForResponse();
    }
    /**
     * Prints number of each listener currently in-game
     */
    public synchronized void printListenerStats() {
        System.out.println("Game listener stats:");
        System.out.println("\tWatch listeners: " + this.watchListeners.size());
        System.out.println("\tWait listeners: " + this.waitListeners.size());
    }
    
    /**
     * @return game with puzzle names and their representation separated by newlines
     */
    @Override
    public synchronized String toString() {
        StringBuilder gameString = new StringBuilder();
        for (String puzzleName : puzzles.keySet()) {
            Puzzle puzzle = puzzles.get(puzzleName);
            gameString.append(puzzleName + "\n" + puzzle.toString() + "\n\n");
        }

        // Remove the string minus the newline at the end
        return gameString.substring(0,  gameString.length()-1);
    }
}
