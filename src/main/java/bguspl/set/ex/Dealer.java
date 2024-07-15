package bguspl.set.ex;

import bguspl.set.Env;
import bguspl.set.ThreadLogger;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;
    public Object dealerLock = new Object();
    public ConcurrentLinkedQueue<Player> playerQueue = new ConcurrentLinkedQueue<>();
    private long resetingTime;
    Thread[] playersThread;

    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        playersThread = new Thread[players.length];
        for (int i = 0; i < players.length; i++) {
            Thread playerThread = new Thread(players[i] , "player "+i);
            playersThread[i] = playerThread;
            playerThread.start();
            // synchronized (this){
            //     try{
            //         this.wait();
            //     }
            //     catch (InterruptedException ignored) {}
            // }
        }
        while (!shouldFinish()) {
            shuffleDeck();
            placeCardsOnTable();
            updateTimerDisplay(true);
            if(env.config.turnTimeoutMillis>0){
                timerLoop();
                try {
                    Player.slotLock.acquire();
           
                removeAllCardsFromTable();
                Player.slotLock.release(); 
            } catch (InterruptedException e) {}
            }
            else {
                timerWithoutCountdown();
            }
        }
        announceWinners();
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    private void timerWithoutCountdown() {
        while(true){
            sleepUntilWokenOrTimeout();
            updateTimerDisplay(false);
            if(!isSetOnTable()){
                try {
                    Player.slotLock.acquire();
                    removeAllCardsFromTable();
                    if(shouldFinish()){
                        Player.slotLock.release(); 
                        break;
                    }
                } catch (InterruptedException e) {}
                Player.slotLock.release(); 
                shuffleDeck();
                placeCardsOnTable();
            }
            else{
                removeCardsFromTable();
                placeCardsOnTable();
            }
        }
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        while (!terminate && System.currentTimeMillis()-resetingTime < env.config.turnTimeoutMillis) {
            sleepUntilWokenOrTimeout();
            updateTimerDisplay(false);
            removeCardsFromTable();
            placeCardsOnTable();
        }
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        terminate = true;
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || env.util.findSets(deck, 1).size() == 0;
    }

    /**
     * Checks cards should be removed from the table and removes them.
     */
    private void removeCardsFromTable() {
        if(playerQueue.isEmpty());
        else{
            Player claimer = playerQueue.remove();
            if(playerTokensAreOk(claimer)){
                try {
                    Player.slotLock.acquire();
            } catch (InterruptedException ignored) {}
                int[] claimerTokens = new int[env.config.featureSize];
                for (int i = 0; i < claimerTokens.length; i++) {
                    if(claimer.tokenPlacement[i] != -1 && table.slotToCard[claimer.tokenPlacement[i]] != null)
                        claimerTokens[i] = table.slotToCard[claimer.tokenPlacement[i]];
                }
                Player.slotLock.release();
                if(env.util.testSet(claimerTokens)){
                    claimer.pointOrPenalty = 1;
                    try {
                        Player.slotLock.acquire();
                    removeCardsFromTable(claimer);
                    Player.slotLock.release();
                } catch (InterruptedException ignored) {}
                    updateTimerDisplay(true);
                }
                else claimer.pointOrPenalty = -1;
                claimer.canProceed = true;
                synchronized(dealerLock){
                dealerLock.notifyAll();
                }
            }
        }
    }
    

    private boolean playerTokensAreOk(Player player) {
        for (int i = 0; i < player.tokenPlacement.length; i++) {
            if(player.tokenPlacement[i] == -1) return false;
        }
        return true;
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        boolean isChanged = false;
        for (int i = 0; i < env.config.tableSize; i++) {
            if(table.slotToCard[i] == null && !deck.isEmpty()){
                int toRemove = deck.remove(0);
                table.placeCard(toRemove, i);
                isChanged = true;
            }
        }
        synchronized(dealerLock){
            dealerLock.notifyAll();
            }
        if(isChanged && env.config.hints)
            table.hints();
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        long timeToSleep = 1000;
        if (env.config.turnTimeoutMillis>0 && env.config.turnTimeoutWarningMillis>(env.config.turnTimeoutMillis-(System.currentTimeMillis()-resetingTime)))
            timeToSleep = 10;
        if(env.config.turnTimeoutMillis<0)
            timeToSleep = 0;
        synchronized (dealerLock){
            try {
                if(playerQueue.isEmpty()){
                    dealerLock.wait(timeToSleep);
                }
            } catch (InterruptedException ignored) {}
        }
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        if(reset) reset();
        else{
            long timePassed = System.currentTimeMillis() - resetingTime;
            long timeLeft = env.config.turnTimeoutMillis - timePassed;
            if (env.config.turnTimeoutMillis>0){
                if (env.config.turnTimeoutWarningMillis>timeLeft)
                    if (timeLeft<0)
                        env.ui.setCountdown(0,true);
                    else
                        env.ui.setCountdown(timeLeft,true);
                else{
                    double doubleTimeToDisplay =  (double)((timeLeft))/1000;
                    long timeToDisplay = (Long)(Math.round(doubleTimeToDisplay)*1000);
                    env.ui.setCountdown(timeToDisplay,false);
                }
            }
            else if (env.config.turnTimeoutMillis==0)
                env.ui.setElapsed(timePassed);
        }
    }

   
    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
          
        for (int j = 0; j < players.length; j++) {
            players[j].numOfTokens = 0;
            for(int i = 0; i < players[j].tokenPlacement.length; i++){
            players[j].tokenPlacement[i] = -1;
            }
            players[j].keyPressed.clear();
        }    
        for (int i = 0; i < table.slotToCard.length; i++) {
            if (table.slotToCard[i]!=null){
                deck.add(table.slotToCard[i]);
                table.removeCard(i);
            }
        }
        for (int i = 0; i < players.length; i++) {
             if (!players[i].getHuman())
                synchronized(players[i].aiLock){
                    players[i].aiLock.notifyAll();
                }
        }
       
        playerQueue.clear();
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        int count = 1;
        int bestScore = 0;
        for (int i = 0; i < players.length; i++) {
            if(bestScore < players[i].score()){
                count = 1;
                bestScore = players[i].score();
            }
            else if (bestScore == players[i].score())
                count ++;
        }
        int[] allWinners = new int[count];
        count=0;
        for (int i = 0; i < players.length; i++) {
            if(players[i].score() == bestScore){
                allWinners[count] = players[i].id;
                count++;
            }
        }
        env.ui.announceWinner(allWinners);
        finishProgram();
    }

    private void finishProgram() {
        for (int i =players.length-1 ; i>=0 ;i--) {
            players[i].terminate();
            synchronized (players[i].getPlayerThread()){
                players[i].getPlayerThread().notifyAll();
            }
            try {
                playersThread[i].join();
            } catch (InterruptedException ignore) {}
        }
    }


    private void reset() {
        resetingTime = System.currentTimeMillis();
        if (env.config.turnTimeoutMillis>0){
            boolean toWarn = false;
            if (env.config.turnTimeoutMillis <= env.config.turnTimeoutWarningMillis)
                toWarn = true;
            env.ui.setCountdown(env.config.turnTimeoutMillis,toWarn);
        }
        if (env.config.turnTimeoutMillis == 0)
            env.ui.setElapsed(0);
    }

    private void removeCardsFromTable(Player player) {
        if (player.tokenPlacement == null)
            return;
        if(playerTokensAreOk(player)){
            int[] toRemove = new int[player.tokenPlacement.length];
            for (int i = 0; i < player.tokenPlacement.length; i++) {
                toRemove[i] = player.tokenPlacement[i];
            }
                for (int i = 0; i < toRemove.length; i++) {
                    if(table.slotToCard[toRemove[i]] != null)
                        table.removeCard(toRemove[i]);
                }
                for (int j = 0; j < players.length; j++) {
                    for (int i = 0; i < toRemove.length; i++) {
                        players[j].removeTokenFromSlot(toRemove[i]);
                    }
                }
                reset();
        }
        }

    private void shuffleDeck() {
        Collections.shuffle(deck);
    }

    private boolean isSetOnTable(){
        List<Integer> onTable = new LinkedList<>();
        for (int i = 0; i < env.config.tableSize; i++) {
            if (table.slotToCard[i]!=null)
                onTable.add(table.slotToCard[i]);
        }
        return env.util.findSets(onTable, 1).size() != 0;
    }
}
