package core;

import java.io.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.Random;

import core.competition.CompetitionParameters;
import core.game.Game;
import core.game.GameDescription;
import core.game.GameDescription.SpriteData;
import core.game.StateObservation;
import core.game.StateObservationMulti;
import core.generator.AbstractLevelGenerator;
import core.player.AbstractMultiPlayer;
import core.player.AbstractPlayer;
import core.player.Player;
import ontology.Types;
import ontology.avatar.MovingAvatar;
import tools.ElapsedCpuTimer;
import tools.IO;
import tools.StatSummary;

/**
 * Created with IntelliJ IDEA.
 * User: Diego
 * Date: 06/11/13
 * Time: 11:24
 * This is a Java port from Tom Schaul's VGDL - https://github.com/schaul/py-vgdl
 */
public class ArcadeMachine
{
    public static final boolean VERBOSE = false;

    /**
     * Reads and launches a game for a human to be played. Graphics always on.
     * @param game_file game description file.
     * @param level_file file with the level to be played.
     */
    public static double[] playOneGame(String game_file, String level_file, String actionFile, int randomSeed)
    {
        String agentName = "controllers.human.Agent";
        boolean visuals = true;
        return runOneGame(game_file, level_file, visuals, agentName, actionFile, randomSeed, 0);
    }

    /**
     * Reads and launches a game for a human to be played. Graphics always on.
     * @param game_file game description file.
     * @param level_file file with the level to be played.
     * @param actionFile to save the actions of the game.
     * @param randomSeed for the game to be played.
     */
    public static double[] playOneGameMulti(String game_file, String level_file, String actionFile, int randomSeed)
    {
        String agentName = "controllers.multiPlayer.human.Agent";
        boolean visuals = true;
        return runOneGame(game_file, level_file, visuals, agentName, actionFile, randomSeed, 0);
    }
    
    /**
     * Reads game description then generate level using the supplied generator.
     * It also launches the game for a human to be played. Graphics always on. 
     * @param gameFile			the game description file
     * @param actionFile       	the action file name
     * @param levelFile			a file to save the generated level
     */
    public static double playOneGeneratedLevel(String gameFile, String actionFile, String levelFile, int randomSeed){
    	String agentName = "controllers.human.Agent";
        boolean visuals = true;
    	return runOneGeneratedLevel(gameFile, visuals, agentName, actionFile, levelFile, randomSeed, true);
    }
    
    /**
     * Reads and launches a game for a bot to be played. Graphics can be on or off.
     * @param game_file game description file.
     * @param level_file file with the level to be played.
     * @param visuals true to show the graphics, false otherwise.
     * @param agentNames names (inc. package) where the controllers are otherwise. Names separated by space.
     * @param actionFile filename of the files where the actions of these players, for this game, should be recorded.
     * @param randomSeed sampleRandom seed for the sampleRandom generator.
     * @param playerID ID of the human player
     */
    public static double[] runOneGame(String game_file, String level_file, boolean visuals,
                                    String agentNames, String actionFile, int randomSeed, int playerID)
    {
        VGDLFactory.GetInstance().init(); //This always first thing to do.
        VGDLRegistry.GetInstance().init();

        if(VERBOSE)
            System.out.println(" ** Playing game " + game_file + ", level " + level_file + " **");

        // First, we create the game to be played..
        Game toPlay = new VGDLParser().parseGame(game_file);
        toPlay.buildLevel(level_file, randomSeed);

        //Warm the game up.
        //ArcadeMachine.warmUp(toPlay, CompetitionParameters.WARMUP_TIME);

        //Create the players.
        String[] names = agentNames.split(" ");
        int no_players = toPlay.no_players;
        if(no_players > 1 && no_players != names.length)
        {
            //We fill with more human players
            String []newNames = new String[no_players];
            System.arraycopy(names, 0, newNames, 0, names.length);
            for(int i = names.length; i < no_players; ++i)
                newNames[i] = "controllers.multiPlayer.human.Agent";
            names = newNames;
        }


        boolean humans[] = new boolean[no_players];
        boolean anyHuman = false;

        //System.out.println("Number of players: " + no_players);

        Player[] players;
        if (no_players > 1) {
            //multi player games
            players = new AbstractMultiPlayer[no_players];
        } else {
            //single player games
            players = new AbstractPlayer[no_players];
        }

        for (int i = 0; i < no_players; i++) {

            humans[i] = isHuman(names[i]);
            anyHuman |= humans[i];

            if (no_players > 1) {
                //multi player
                players[i] = ArcadeMachine.createMultiPlayer(names[i], actionFile, toPlay.getObservationMulti(), randomSeed, i, humans[i]);
            } else {
                //single player
                players[i] = ArcadeMachine.createPlayer(names[i], actionFile, toPlay.getObservation(), randomSeed, humans[i]);
            }

            if (players[i] == null) {
                //Something went wrong in the constructor, controller disqualified
                if (no_players > 1) {
                    //multi player
                    toPlay.getAvatars()[i].disqualify(true);
                } else {
                    //single player
                    toPlay.disqualify();
                }

                //Get the score for the result.
                return toPlay.handleResult();
            }
        }

        //Then, play the game.
        double[] score;
        if(visuals)
            score = toPlay.playGame(players, randomSeed, anyHuman, playerID);
        else
            score = toPlay.runGame(players, randomSeed);

        //Finally, when the game is over, we need to tear the player down.
        for (Player p : players) {
            if (!ArcadeMachine.tearPlayerDown(toPlay, p))
                return toPlay.handleResult();
        }

        return score;
    }
    
    /**
     * Generate a level for a certain described game and test it against a supplied agent
     * @param gameFile			game description file.
     * @param levelGenerator	level generator class path.
     * @param levelFile			file to save the generated level in it
     */
    public static boolean generateOneLevel(String gameFile, String levelGenerator, String levelFile){
    	VGDLFactory.GetInstance().init(); //This always first thing to do.
        VGDLRegistry.GetInstance().init();

        System.out.println(" ** Generating a level for " + gameFile + ", using level generator " + levelGenerator + " **");

        // First, we create the game to be played..
        Game toPlay = new VGDLParser().parseGame(gameFile);
        GameDescription description = new GameDescription(toPlay);
        AbstractLevelGenerator generator = createLevelGenerator(levelGenerator, description);
        String level = getGeneratedLevel(description, toPlay, generator);
        if(level == "" || level == null){
        	System.out.println("Empty Level Disqualified");
        	toPlay.disqualify();

            //Get the score for the result.
            toPlay.handleResult();
            return false;
        }
        
        HashMap<Character, ArrayList<String>> charMapping = generator.getLevelMapping();
        if(charMapping != null){
        	toPlay.setCharMapping(charMapping);
        }
        
        try{
        	toPlay.buildStringLevel(level.split("\n"), 0);
        }
        catch(Exception e){
        	System.out.println("Undefined symbols or wrong number of avatars Disqualified ");
        	toPlay.disqualify();

            //Get the score for the result.
            toPlay.handleResult();
            return false;
        }
        
        if(levelFile != null){
        	saveLevel(level, levelFile, toPlay.getCharMapping());
        }
        
        return true;
    }

    /**
     * A player (human or bot) plays a generated level, which is passed by parameter, in a determined
     * game.
     * @param gameFile game description file.
     * @param visuals true to show the graphics, false otherwise.
     * @param agentName name (inc. package) where the controller is otherwise.
     * @param actionFile filename of the file where the actions of this player, for this game, should be recorded.
     * @param levelFile level file to play in
     * @param randomSeed random seed for the game to be played
     * @param isHuman indicates if the game is played by a human or a bot
     * @return score of the game plaayed
     */
    public static double runOneGeneratedLevel(String gameFile, boolean visuals,
            String agentName, String actionFile, String levelFile, int randomSeed, boolean isHuman){
    	VGDLFactory.GetInstance().init(); //This always first thing to do.
        VGDLRegistry.GetInstance().init();

        System.out.println(" ** Playing game " + gameFile + ", using generate level file " + levelFile + " **");

        // First, we create the game to be played..
        Game toPlay = new VGDLParser().parseGame(gameFile);
        String level = loadGeneratedFile(toPlay, levelFile);
        String[] levelLines = level.split("\n");
        
        toPlay.reset();
        toPlay.buildStringLevel(levelLines, 0);

        //Warm the game up.
        ArcadeMachine.warmUp(toPlay, CompetitionParameters.WARMUP_TIME);
        
        //Create the player.
        AbstractPlayer player = ArcadeMachine.createPlayer(agentName, actionFile, toPlay.getObservation(), randomSeed, isHuman);

        if(player == null){
            //Something went wrong in the constructor, controller disqualified
            toPlay.disqualify();

            //Get the score for the result.
            return toPlay.handleResult()[0];

        }

        //Then, play the game.
        double score = 0.0;

        /**
         * playGame and runGame methods from the Game class take an array of players as argument,
         * including all players in the game. As this method refers to single player
         * games, an array is created containing only one element: the player created earlier.
         * To get back just 1 score for the player, the first element in the score array is returned.
         */
        AbstractPlayer[] p = new AbstractPlayer[0];
        p[0] = player;
        if(visuals)
            score = toPlay.playGame(p, randomSeed, isHuman, 0)[0];
        else
            score = toPlay.runGame(p, randomSeed)[0];

        //Finally, when the game is over, we need to tear the player down.
        if(! ArcadeMachine.tearPlayerDown(toPlay, player) )
            return toPlay.handleResult()[0];

        return score;
    }
    
    /**
     * Runs a replay given a game, level and file with the actions to execute.
     * @param game_file game description file.
     * @param level_file file with the level to be played.
     * @param visuals true to show the graphics, false otherwise.
     * @param actionFile name of the file where the actions of these players, for this game, must be read from.
     *                   If the game is multi player, this file contains meta game information (winner, scores,
     *                   timesteps, random seed) and names of all the files for player actions.
     *
     */
    public static double[] replayGame(String game_file, String level_file, boolean visuals, String actionFile)
    {
        String agentName = "controllers.replayer.Agent";
        VGDLFactory.GetInstance().init();  //This always first thing to do.
        VGDLRegistry.GetInstance().init();

        // First, we create the game to be played..
        Game toPlay = new VGDLParser().parseGame(game_file);
        toPlay.buildLevel(level_file, 0);

        //Second, create the player. Note: null as action_file and -1 as sampleRandom seed
        // (we don't want to record anything from this execution).
        Player[] players;
        int no_players = toPlay.getNoPlayers();
        if (no_players > 1) {
            //multi player games
            players = new AbstractMultiPlayer[no_players];
        } else {
            //single player games
            players = new AbstractPlayer[no_players];
        }

        for (int i = 0; i < no_players; i++) {
            if (no_players > 1) {
                //multi player
                players[i] = ArcadeMachine.createMultiPlayer(agentName, null, toPlay.getObservationMulti(), -1, i, false);
            } else {
                //single player
                players[i] = ArcadeMachine.createPlayer(agentName, null, toPlay.getObservation(), -1, false);
            }

            if (players[i] == null) {
                //Something went wrong in the constructor, controller disqualified
                if (no_players > 1) {
                    //multi player
                    toPlay.getAvatars()[i].disqualify(true);
                } else {
                    //single player
                    toPlay.disqualify();
                }

                //Get the score for the result.
                return toPlay.handleResult();

            }
        }

        int seed = 0;
        int[] win = new int[no_players];
        double loggedScore = 0.0;
        int timesteps = 0;
        ArrayList<Types.ACTIONS> actions = new ArrayList<Types.ACTIONS> ();

        try
        {
            BufferedReader br = new BufferedReader(new FileReader(actionFile));

            //First line should be the sampleRandom seed, winner, score and timesteps.
            if (no_players < 2) {
                //Single player file
                String[] firstLine = br.readLine().split(" ");
                seed = Integer.parseInt(firstLine[0]);
                win[0] = Integer.parseInt(firstLine[1]);
                loggedScore = Double.parseDouble(firstLine[2]);
                timesteps = Integer.parseInt(firstLine[3]);

                System.out.println("Replaying game in " + game_file + ", " + level_file + " with seed " + seed +
                        " expecting player to win = " + (win[0] == 1) + "; score: " + loggedScore +
                        "; timesteps: " + timesteps);

                //The rest are the actions:
                String line = br.readLine();
                while (line != null) {
                    Types.ACTIONS nextAction = Types.ACTIONS.fromString(line);
                    actions.add(nextAction);

                    //next!
                    line = br.readLine();
                }

                //Assign the actions to the player. playerID used is 0, default for single player games
                ((controllers.replayer.Agent)players[0]).setActions(actions);

            } else {
                //Multi player file

                // first line contains the sampleRandom seed and the timesteps.
                String[] firstLine = br.readLine().split(" ");
                seed = Integer.parseInt(firstLine[0]);
                timesteps = Integer.parseInt(firstLine[3]);

                //next line contain scores for all players, in order.
                String secondLine = br.readLine();

                //next line contains win state for all players, in order.
                String[] thirdLine = br.readLine().split(" ");
                String w = "";
                for (int i = 0; i < no_players; i++) {
                    if (thirdLine.length > i)
                        win[i] = Integer.parseInt(thirdLine[i]);
                    else win[i] = 0;
                    w += (win[i] == 1) + " ";
                }

                //display information
                System.out.println("Replaying game in " + game_file + ", " + level_file + " with seed " + seed +
                        " expecting players' win states = " + w + "; scores: " + secondLine +
                        "; timesteps: " + timesteps);

                //next lines contain players actions, one line per game tick, actions for players in order,
                //separated by spaces.
                ArrayList<ArrayList<Types.ACTIONS>> act = new ArrayList<>();
                for (int i = 0; i < no_players; i++) {
                    act.add(new ArrayList<Types.ACTIONS>());
                }
                String line = br.readLine();
                while (line != null) {
                    String[] acts = line.split(" ");
                    for (int i = 0; i < no_players; i++) {
                        Types.ACTIONS nextAction = acts.length > i ? Types.ACTIONS.fromString(acts[i]) : Types.ACTIONS.ACTION_NIL;
                        act.get(i).add(nextAction);
                    }
                    //next!
                    line = br.readLine();
                }

                //Assign the actions to the players.
                for (int i = 0; i < no_players; i++) {
                    ((controllers.replayer.Agent)players[i]).setActions(act.get(i));
                }
            }
        }catch(Exception e)
        {
            e.printStackTrace();
            System.exit(1);
        }

        //Then, (re-)play the game.
        double[] score;
        if(visuals)
            score = toPlay.playGame(players, seed, false, 0);
        else
            score = toPlay.runGame(players, seed);

        //Finally, when the game is over, we need to tear the player down. Actually in this case this might never do anything.
        for (Player p : players) {
            if (!ArcadeMachine.tearPlayerDown(toPlay, p))
                return toPlay.handleResult();
        }

        for (int i = 0; i < toPlay.getNoPlayers(); i++) {
            int actualWinner = (toPlay.getWinner(i) == Types.WINNER.PLAYER_WINS ? 1 : 0);
            if (actualWinner != win[i] || score[i] != loggedScore || timesteps != toPlay.getGameTick())
                throw new RuntimeException("ERROR: Game Replay Failed.");
        }

        return score;
    }

    /**
     * Reads and launches a game for a bot to be played. It specifies which levels to play and how many times.
     * Filenames for saving actions can be specified. Graphics always off.
     * @param game_file game description file.
     * @param level_files array of level file names to play.
     * @param level_times how many times each level has to be played.
     * @param actionFiles names of the files where the actions of this player, for this game, should be recorded. Accepts
     *                    null if no recording is desired. If not null, this array must contain as much String objects as
     *                    level_files.length*level_times.
     */
    public static StatSummary performance;
    public static void runGames(String game_file, String[] level_files, int level_times,
                                String agentName, String[] actionFiles)
    {
        VGDLFactory.GetInstance().init(); //This always first thing to do.
        VGDLRegistry.GetInstance().init();

        boolean recordActions = false;
        if(actionFiles != null)
        {
            recordActions = true;
            assert actionFiles.length >= level_files.length*level_times :
                    "runGames (actionFiles.length<level_files.length*level_times): " +
                    "you must supply an action file for each game instance to be played, or null.";
        }

        StatSummary victories = new StatSummary();
        StatSummary scores = new StatSummary();
        performance = new StatSummary();

        Game toPlay = new VGDLParser().parseGame(game_file);
        int levelIdx = 0;

        for(String level_file : level_files)
        {
            for(int i = 0; i < level_times; ++i)
            {
                if(VERBOSE)
                    System.out.println(" ** Playing game " + game_file + ", level " + level_file + " ("+(i+1)+"/"+level_times+") **");

                //Determine the random seed, different for each game to be played.
                int randomSeed = new Random().nextInt();
                
                //build the level in the game.
                toPlay.buildLevel(level_file, randomSeed);

                String filename = recordActions ? actionFiles[levelIdx*level_times + i] : null;

                //Warm the game up.
                ArcadeMachine.warmUp(toPlay, CompetitionParameters.WARMUP_TIME);

                //Create the player.
                String[] agentNames = agentName.split(" ");
                int no_players = agentNames.length;

                int disqCount = 0; //count how many players disqualified
                double[] score = new double[no_players]; //store scores for all the players

                Player[] players;
                if (no_players > 1) {
                    //multi player games
                    players = new AbstractMultiPlayer[no_players];
                } else {
                    //single player games
                    players = new AbstractPlayer[no_players];
                }

                for (int j = 0; j < no_players; j++) {
                    if (no_players > 1) {
                        //multi player
                        players[j] = ArcadeMachine.createMultiPlayer(agentNames[j], filename, toPlay.getObservationMulti(), randomSeed, j, false);
                    } else {
                        //single player
                        players[j] = ArcadeMachine.createPlayer(agentNames[j], filename, toPlay.getObservation(), randomSeed, false);
                    }
                    score[j] = -1;
                    if (players[j] == null) {
                        //Something went wrong in the constructor, controller disqualified
                        //toPlay.disqualify(j);
                        toPlay.getAvatars()[j].disqualify(true);

                        disqCount++;
                    }
                }

                //Play the game if at least 2 players in multiplayer games or at least 1 in single player.
                //Get array of scores back.
                if ((no_players - disqCount) >= toPlay.no_players) {
                    score = toPlay.runGame(players, randomSeed);
                }
                else {
                    //Get the score for the result.
                    score = toPlay.handleResult();
                }

                //Finally, when the game is over, we need to tear the players down.
                for (Player player : players)
                    if(player != null)
                        if(!ArcadeMachine.tearPlayerDown(toPlay, player))
                            score = toPlay.handleResult();
                scores.add(score);
                victories.add(toPlay.getWinner()== Types.WINNER.PLAYER_WINS ? 1 : 0);

                for (MovingAvatar a : toPlay.getAvatars()) {
                    if (a.getWinState() == Types.WINNER.PLAYER_WINS)
                        scores.addWin();
                }

                //reset the game.
                toPlay.reset();
            }

            levelIdx++;
        }


        System.out.println("Results in game " + game_file + ", " +
                        victories.mean() + ", " + scores.mean() );
                        //+ "," + performance.mean());
    }

    /**
     * Generate multiple levels for a certain game
     * @param gameFile			The game description file path
     * @param levelGenerator	The current used level generator
     * @param levelFile			array of level files to save the generated levels
     */
    public static void generateLevels(String gameFile, String levelGenerator, String[] levelFile){
    	VGDLFactory.GetInstance().init(); //This always first thing to do.
        VGDLRegistry.GetInstance().init();

        // First, we create the game to be played..
        Game toPlay = new VGDLParser().parseGame(gameFile);
        GameDescription description = new GameDescription(toPlay);
        AbstractLevelGenerator generator = createLevelGenerator(levelGenerator, description);
        HashMap<Character, ArrayList<String>> originalMapping = toPlay.getCharMapping();
        
    	for(int i=0;i<levelFile.length;i++){
    		System.out.println(" ** Generating a level " + (i + 1) +  " for " + gameFile + ", using level generator " + levelGenerator + " **");
    		toPlay.reset();
    		description.reset(toPlay);
    		
    		String level = getGeneratedLevel(description, toPlay, generator);
            if(level == "" || level == null){
            	toPlay.disqualify();

                //Get the score for the result.
                toPlay.handleResult();
            }
            
            HashMap<Character, ArrayList<String>> charMapping = generator.getLevelMapping();
            if(charMapping != null){
            	toPlay.setCharMapping(charMapping);
            }
            try{
            	toPlay.buildStringLevel(level.split("\n"), 0);
            }
            catch(Exception e){
            	System.out.println("Undefined symbols or wrong number of avatars Disqualified ");
            	toPlay.disqualify();

                //Get the score for the result.
                toPlay.handleResult();
            }
            if(levelFile != null){
            	saveLevel(level, levelFile[i], toPlay.getCharMapping());
            }
            toPlay.setCharMapping(originalMapping);
    	}
    }
    
    /**
     * play a couple of generated levels for a certain game
     * @param gameFile The game description file path
     * @param actionFile array of files to save the actions in
     * @param levelFile array of level files to save the generated levels
     * @param isHuman indicates if the level will be played by a human or a bot.
     */
    public static void playGeneratedLevels(String gameFile, String[] actionFile, String[] levelFile, boolean isHuman){
    	String agentName = "controllers.human.Agent";
    	
    	VGDLFactory.GetInstance().init(); //This always first thing to do.
        VGDLRegistry.GetInstance().init();

        boolean recordActions = false;
        if(actionFile != null)
        {
            recordActions = true;
            assert actionFile.length >= levelFile.length :
                    "runGames (actionFiles.length<level_files.length*level_times): " +
                    "you must supply an action file for each game instance to be played, or null.";
        }

        StatSummary scores = new StatSummary();

        Game toPlay = new VGDLParser().parseGame(gameFile);
        int levelIdx = 0;
        for(String file : levelFile){
        	System.out.println(" ** Playing game " + gameFile + ", level " + file +" **");
        	
            //build the level in the game.
        	String level = loadGeneratedFile(toPlay, file);
            String[] levelLines = level.split("\n");
            
            //Determine the random seed, different for each game to be played.
            int randomSeed = new Random().nextInt();
            
            toPlay.buildStringLevel(levelLines, randomSeed);

            String filename = recordActions ? actionFile[levelIdx] : null;

            //Create the player.
            AbstractPlayer player = ArcadeMachine.createPlayer(agentName, filename, toPlay.getObservation(), randomSeed, isHuman);

            double score = -1;
            if(player == null)
            {
            	//Something went wrong in the constructor, controller disqualified
                toPlay.disqualify();
                
                //Get the score for the result. PlayerID used 0, default in single player games.
                score = toPlay.handleResult()[0];

            }else{

            	//Then, play the game.

                /**
                 * playGame method from Game class takes an array of players as argument,
                 * including all players in the game. As this method refers to single player
                 * games, an array is created containing only one element: the player created earlier.
                 * To get back just 1 score for the player, the first element in the score array is returned.
                 */
                AbstractPlayer[] p = new AbstractPlayer[1];
                p[0] = player;
                score = toPlay.playGame(p, randomSeed, isHuman, 0)[0];
            }

            scores.add(score);

            //Finally, when the game is over, we need to tear the player down.
            if(player != null) ArcadeMachine.tearPlayerDown(toPlay, player);


            //reset the game.
            toPlay.reset();
                
            levelIdx += 1;
        }

        System.out.println(" *** Results in game " + gameFile + " *** ");
        System.out.println(scores);
        System.out.println(" *********");
    }
    
    /**
     * Creates a player given its name with package. This class calls the constructor of the agent
     * and initializes the action recording procedure. PlayerID used is 0, default for single player games.
     * @param playerName name of the agent to create. It must be of the type "<agentPackage>.Agent".
     * @param actionFile filename of the file where the actions of this player, for this game, should be recorded.
     * @param so Initial state of the game to be played by the agent.
     * @param randomSeed Seed for the sampleRandom generator of the game to be played.
     * @param isHuman Indicates if the player is human
     * @return the player, created and initialized, ready to start playing the game.
     */
    private static AbstractPlayer createPlayer(String playerName, String actionFile,
                                               StateObservation so, int randomSeed, boolean isHuman)
    {
        AbstractPlayer player = null;

        try{
            //create the controller.
            player = (AbstractPlayer) createController(playerName, 0, so);
            if(player != null)
                player.setup(actionFile, randomSeed, isHuman);
            //else System.out.println("No controller created.");

        }catch (Exception e)
        {
            //This probably happens because controller took too much time to be created.
            e.printStackTrace();
            System.exit(1);
        }

        //System.out.println("Created player.");

        return player;
    }

    /**
     * Creates a player given its name with package for multiplayer. This class calls the constructor of the agent
     * and initializes the action recording procedure. PlayerID used is 0, default for single player games.
     * @param playerName name of the agent to create. It must be of the type "<agentPackage>.Agent".
     * @param actionFile filename of the file where the actions of this player, for this game, should be recorded.
     * @param so Initial state of the game to be played by the agent.
     * @param randomSeed Seed for the sampleRandom generator of the game to be played.
     * @param isHuman Indicates if the player is human
     * @return the player, created and initialized, ready to start playing the game.
     */
    private static AbstractMultiPlayer createMultiPlayer(String playerName, String actionFile, StateObservationMulti so, int randomSeed, int id, boolean isHuman)
    {
        AbstractMultiPlayer player = null;

        try{
            //create the controller.
            player = (AbstractMultiPlayer) createController(playerName, id, so);
            if(player != null) {
                player.setup(actionFile, randomSeed, isHuman);
            }

        }catch (Exception e)
        {
            //This probably happens because controller took too much time to be created.
            e.printStackTrace();
            System.exit(1);
        }

        return player;
    }

    /**
     * Creates and initializes a new controller with the given name. Takes into account the initialization time,
     * calling the appropriate constructor with the state observation and time due parameters.
     * @param playerName Name of the controller to instantiate.
     * @param so Initial state of the game to be played by the agent.
     * @return the player if it could be created, null otherwise.
     */

    protected static Player createController(String playerName, int playerID, StateObservation so) throws RuntimeException
    {
        Player player = null;
        try
        {

            //Determine the time due for the controller creation.
            ElapsedCpuTimer ect = new ElapsedCpuTimer(CompetitionParameters.TIMER_TYPE);
            ect.setMaxTimeMillis(CompetitionParameters.INITIALIZATION_TIME);

            if (so.getNoPlayers() < 2) { //single player
                //Get the class and the constructor with arguments (StateObservation, long).
                Class<? extends AbstractPlayer> controllerClass = Class.forName(playerName).asSubclass(AbstractPlayer.class);
                Class[] gameArgClass = new Class[]{StateObservation.class, ElapsedCpuTimer.class};
                Constructor controllerArgsConstructor = controllerClass.getConstructor(gameArgClass);

                //Call the constructor with the appropriate parameters.
                Object[] constructorArgs = new Object[]{so, ect.copy()};

                player = (AbstractPlayer) controllerArgsConstructor.newInstance(constructorArgs);
                player.setPlayerID(playerID);

            } else { //multi player
                //Get the class and the constructor with arguments (StateObservation, long).
                Class<? extends AbstractMultiPlayer> controllerClass = Class.forName(playerName).asSubclass(AbstractMultiPlayer.class);
                Class[] gameArgClass = new Class[]{StateObservationMulti.class, ElapsedCpuTimer.class};
                Constructor controllerArgsConstructor = controllerClass.getConstructor(gameArgClass);

                //Call the constructor with the appropriate parameters.
                Object[] constructorArgs = new Object[]{(StateObservationMulti)so, ect.copy()};

                player = (AbstractMultiPlayer) controllerArgsConstructor.newInstance(constructorArgs);
                player.setPlayerID(playerID);
            }
            //Check if we returned on time, and act in consequence.
            long timeTaken = ect.elapsedMillis();
            if (ect.exceededMaxTime()) {
                long exceeded = -ect.remainingTimeMillis();
                System.out.println("Controller initialization time out (" + exceeded + ").");

                return null;
            }
            else
            {
                if(VERBOSE)
                    System.out.println("Controller initialization time: " + timeTaken + " ms.");
            }

        //This code can throw many exceptions (no time related):

        }catch(NoSuchMethodException e)
        {
            e.printStackTrace();
            System.err.println("Constructor " + playerName + "(StateObservation,long) not found in controller class:");
            System.exit(1);

        }catch(ClassNotFoundException e)
        {
            System.err.println("Class " + playerName + " not found for the controller:");
            e.printStackTrace();
            System.exit(1);

        }catch(InstantiationException e)
        {
            System.err.println("Exception instantiating " + playerName + ":");
            e.printStackTrace();
            System.exit(1);

        }catch(IllegalAccessException e)
        {
            System.err.println("Illegal access exception when instantiating " + playerName + ":");
            e.printStackTrace();
            System.exit(1);
        }catch(InvocationTargetException e)
        {
            System.err.println("Exception calling the constructor " + playerName + "(StateObservation,long):");
            e.printStackTrace();
            System.exit(1);
        }

        //System.out.println("Controller created. " + player.getPlayerID());

        return player;
    }
    
    /**
     * Generate AbstractLevelGenerator object to generate levels 
     * for the game using the supplied class path.
     * @param levelGenerator	class path for the supplied level generator
     * @param gd				abstract object describes the game
     * @return					AbstractLevelGenerator object.	
     */
    protected static AbstractLevelGenerator createLevelGenerator(String levelGenerator, GameDescription gd) throws RuntimeException
    {
        AbstractLevelGenerator generator = null;
        try
        {
            //Get the class and the constructor with arguments (StateObservation, long).
            Class<? extends AbstractLevelGenerator> controllerClass = Class.forName(levelGenerator).asSubclass(AbstractLevelGenerator.class);
            Class[] gameArgClass = new Class[]{GameDescription.class, ElapsedCpuTimer.class};
            Constructor controllerArgsConstructor = controllerClass.getConstructor(gameArgClass);

            //Determine the time due for the controller creation.
            ElapsedCpuTimer ect = new ElapsedCpuTimer(CompetitionParameters.TIMER_TYPE);
            ect.setMaxTimeMillis(CompetitionParameters.LEVEL_INITIALIZATION_TIME);

            //Call the constructor with the appropriate parameters.
            Object[] constructorArgs = new Object[] {gd, ect.copy()};
            generator = (AbstractLevelGenerator) controllerArgsConstructor.newInstance(constructorArgs);

            //Check if we returned on time, and act in consequence.
            long timeTaken = ect.elapsedMillis();
            if(ect.exceededMaxTime())
            {
                long exceeded =  - ect.remainingTimeMillis();
                System.out.println("Generator initialization time out (" + exceeded + ").");

                return null;
            }
            else
            {
                System.out.println("Generator initialization time: " + timeTaken + " ms.");
            }

        //This code can throw many exceptions (no time related):

        }catch(NoSuchMethodException e)
        {
            e.printStackTrace();
            System.err.println("Constructor " + levelGenerator + "(StateObservation,long) not found in controller class:");
            System.exit(1);

        }catch(ClassNotFoundException e)
        {
            System.err.println("Class " + levelGenerator + " not found for the controller:");
            e.printStackTrace();
            System.exit(1);

        }catch(InstantiationException e)
        {
            System.err.println("Exception instantiating " + levelGenerator + ":");
            e.printStackTrace();
            System.exit(1);

        }catch(IllegalAccessException e)
        {
            System.err.println("Illegal access exception when instantiating " + levelGenerator + ":");
            e.printStackTrace();
            System.exit(1);
        }catch(InvocationTargetException e)
        {
            System.err.println("Exception calling the constructor " + levelGenerator + "(StateObservation,long):");
            e.printStackTrace();
            System.exit(1);
        }

        return generator;
    }


    /**
     * Generate a level for the described game using the supplied level generator.
     * @param gd		Abstract description of game elements
     * @param game		Current game object.
     * @param generator Current level generator.
     * @return			String of symbols contains the generated level. Same as Level Description File string.
     */
    private static String getGeneratedLevel(GameDescription gd, Game game, AbstractLevelGenerator generator){
    	ElapsedCpuTimer ect = new ElapsedCpuTimer(CompetitionParameters.TIMER_TYPE);
        ect.setMaxTimeMillis(CompetitionParameters.LEVEL_ACTION_TIME);

        String level = generator.generateLevel(gd, ect.copy());

        if(ect.exceededMaxTime())
        {
            long exceeded =  - ect.remainingTimeMillis();

            if(ect.elapsedMillis() > CompetitionParameters.LEVEL_ACTION_TIME_DISQ)
            {
                //The agent took too long to replay. The game is over and the agent is disqualified
                System.out.println("Too long: " + "(exceeding "+(exceeded)+"ms): controller disqualified.");
                level = "";
            }else{
                System.out.println("Overspent: " + "(exceeding "+(exceeded)+"ms): applying Empty Level.");
                level = " ";
            }
        }
        
        return level;
    }
    
    /**
     * Saves a level string to a file
     * @param level		current level to save
     * @param levelFile	saved file
     */
    private static void saveLevel(String level, String levelFile, HashMap<Character, ArrayList<String>> charMapping){
    	try{
    		if(levelFile != null){
    			BufferedWriter writer = new BufferedWriter(new FileWriter(levelFile));
    			writer.write("LevelMapping");
    			writer.newLine();
    			for(Entry<Character, ArrayList<String>> e:charMapping.entrySet()){
    				writer.write("    " + e.getKey() + " > ");
    				for(String s:e.getValue()){
    					writer.write(s + " ");
    				}
    				writer.newLine();
    			}
    			writer.newLine();
    			writer.write("LevelDescription");
    			writer.newLine();
    			writer.write(level);
    			writer.close();
    		}
    	}
    	catch(IOException e){
    		e.printStackTrace();
    	}
    }
    
    /**
     * Load a generated level file
     * @param currentGame	Current Game object to se the Level Mapping
     * @param levelFile		The generated level file path
     * @return				Level String to be loaded
     */
    public static String loadGeneratedFile(Game currentGame, String levelFile){
    	HashMap<Character, ArrayList<String>> levelMapping = new HashMap<Character, ArrayList<String>>();
    	String level = "";
    	int mode = 0;
    	String[] lines = new IO().readFile(levelFile);
    	for(String line:lines){
    		if(line.equals("LevelMapping")){
    			mode = 0;
    		}
    		else if(line.equals("LevelDescription")){
    			mode = 1;
    		}
    		else{
    			switch(mode){
    			case 0:
    				if(line.trim().length() == 0){
        				continue;
        			}
    				String[] sides = line.split(">");
    				ArrayList<String> sprites = new ArrayList<String>();
    				for(String sprite:sides[1].trim().split(" ")){
    					if(sprite.trim().length() == 0){
    						continue;
    					}
    					else{
    						sprites.add(sprite.trim());
    					}
    				}
    				levelMapping.put(sides[0].trim().charAt(0), sprites);
    				break;
    			case 1:
    				level += line + "\n";
    				break;
    			}
    		}
    	}
    	currentGame.setCharMapping(levelMapping);
    	return level;
    }
    
    /**
     * This methods takes the game and warms it up. This allows Java to finish the runtime compilation
     * process and optimize the code before the proper game starts.
     * @param toPlay game to be warmed up.
     * @param howLong for how long the warming up process must last (in milliseconds).
     */
    public static void warmUp(Game toPlay, long howLong)
    {
        StateObservation stateObs = toPlay.getObservation();
        ElapsedCpuTimer ect = new ElapsedCpuTimer(CompetitionParameters.TIMER_TYPE);
        ect.setMaxTimeMillis(howLong);

        int playoutLength = 10;
        ArrayList<Types.ACTIONS> actions = stateObs.getAvailableActions();
        int copyStats = 0;
        int advStats = 0;

        StatSummary ss1 = new StatSummary();
        StatSummary ss2 = new StatSummary();


        boolean finish = ect.exceededMaxTime() || (copyStats>CompetitionParameters.WARMUP_CP && advStats>CompetitionParameters.WARMUP_ADV);

        //while(!ect.exceededMaxTime())
        while(!finish)
        {
            for (Types.ACTIONS action : actions)
            {
                StateObservation stCopy = stateObs.copy();
                ElapsedCpuTimer ectAdv = new ElapsedCpuTimer();
                stCopy.advance(action);
                copyStats++;
                advStats++;

                if( ect.remainingTimeMillis() < CompetitionParameters.WARMUP_TIME*0.5)
                {
                    ss1.add(ectAdv.elapsedNanos());
                }

                for (int i = 0; i < playoutLength; i++) {

                    int index = new Random().nextInt(actions.size());
                    Types.ACTIONS actionPO = actions.get(index);

                    ectAdv = new ElapsedCpuTimer();
                    stCopy.advance(actionPO);
                    advStats++;

                    if( ect.remainingTimeMillis() < CompetitionParameters.WARMUP_TIME*0.5)
                    {
                        ss2.add(ectAdv.elapsedNanos());
                    }
                }
            }

            finish = ect.exceededMaxTime() || (copyStats>CompetitionParameters.WARMUP_CP && advStats>CompetitionParameters.WARMUP_ADV);

            //if(VERBOSE)
            //System.out.println("[WARM-UP] Remaining time: " + ect.remainingTimeMillis() +
            //        " ms, copy() calls: " + copyStats + ", advance() calls: " + advStats);
        }

        if(VERBOSE)
        {
            System.out.println("[WARM-UP] Finished, copy() calls: " + copyStats + ", advance() calls: " + advStats + ", time (s): " + ect.elapsedSeconds());
            //System.out.println(ss1);
            //System.out.println(ss2);
        }

        //Reset input to delete warm-up effects.
        MovingAvatar[] avatars = toPlay.getAvatars();
        for (int i = 0; i < toPlay.getNoPlayers(); i++) {
           avatars[i].getKeyHandler().resetAll();
        }
    }


    /**
     * Tears the player down. This initiates the saving of actions to file.
     * It should be called when the game played is over.
     * @param player player to be closed.
     * @return false if there was a timeout from the palyer. true otherwise.
     */
    private static boolean tearPlayerDown(Game toPlay, Player player)
    {
        //This is finished, no more actions, close the writer.
        player.teardown(toPlay);

        //Determine the time due for the controller close up.
        ElapsedCpuTimer ect = new ElapsedCpuTimer(CompetitionParameters.TIMER_TYPE);
        ect.setMaxTimeMillis(CompetitionParameters.TEAR_DOWN_TIME);

        //Inform about the result and the final game state.
        player.result(toPlay.getObservation(), ect);

        //Check if we returned on time, and act in consequence.
        long timeTaken = ect.elapsedMillis();
        if(ect.exceededMaxTime())
        {
            long exceeded =  - ect.remainingTimeMillis();
            System.out.println("Controller tear down time out (" + exceeded + ").");

            toPlay.disqualify();
            return false;
        }

        if(VERBOSE)
            System.out.println("Controller tear down time: " + timeTaken + " ms.");
        return true;
    }

    private static final boolean isHuman(String agentName)
    {
        if( agentName.equalsIgnoreCase("controllers.multiPlayer.human.Agent") ||
            agentName.equalsIgnoreCase("controllers.human.Agent")    )
            return true;
        return false;
    }

}
