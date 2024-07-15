package bguspl.set.ex;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;

import bguspl.set.Env;
import bguspl.set.ThreadLogger;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    private Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */
    private int score;
    public ConcurrentLinkedQueue<Integer> keyPressed = new ConcurrentLinkedQueue<>();
    public int[] tokenPlacement;
    public int numOfTokens = 0;
    public static Semaphore slotLock = new Semaphore(1,true);
    private Dealer dealer;
    public int pointOrPenalty = 0;
    public Object aiLock = new Object();
    public boolean canProceed = false;


    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.table = table;
        this.id = id;
        this.human = human;
        this.dealer = dealer;
        tokenPlacement = new int[env.config.featureSize];
        for (int i = 0; i < tokenPlacement.length; i++) {
            tokenPlacement[i] = -1;
        }
    }

    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        if (!human) createArtificialIntelligence();
        synchronized(dealer){
            dealer.notifyAll();
        }
        while (!terminate) {
            synchronized(playerThread){
                while(keyPressed.isEmpty() && !terminate){
                    try {
                        playerThread.wait();
                    } catch (InterruptedException e) {}
                }    
            }
            placeToken();
        }
        if (!human) try {
            synchronized (aiLock){
                aiLock.notifyAll();
            }
            aiThread.join();
        } catch (InterruptedException ignored) {
        }
        //System.out.println("terminate " + id);
        if (!human) try { aiThread.join(); } catch (InterruptedException ignored) {}
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }


    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very, very smart AI (!)
        aiThread = new Thread(() -> {
            env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
            while (!terminate) {
                synchronized(aiLock){
                    while(keyPressed.size() >= env.config.featureSize && !terminate){
                        try {
                            aiLock.wait();
                        } catch (InterruptedException ignored) {}
                    }
                }
                int random = (int)(Math.random()*env.config.tableSize);
                keyPressed(random);
                //System.out.println(random);
            }
            env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        terminate = true;
        if(aiThread != null){
            aiThread.interrupt();
        }
        playerThread.interrupt();
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        synchronized(playerThread){
            if(keyPressed.size() < env.config.featureSize){
                if(table.slotToCard[slot] != null){
                    keyPressed.add(slot);
                    playerThread.notifyAll();
                }
            }
        }
    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        env.ui.setFreeze(id, env.config.pointFreezeMillis);
        env.ui.setScore(id, ++score);
        try{
            long timeToSleep = env.config.pointFreezeMillis;
            while (timeToSleep>=1000) {
                timeToSleep = timeToSleep - 1000;
                Thread.sleep(1000);
                env.ui.setFreeze(this.id, timeToSleep);
            }
        }
            catch(InterruptedException ignored){};
        pointOrPenalty = 0;
        canProceed = false;
        int ignored = table.countCards(); // this part is just for demonstration in the unit tests
    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        env.ui.setFreeze(id, env.config.penaltyFreezeMillis);
        try{
            long timeToSleep = env.config.penaltyFreezeMillis;
            while (timeToSleep>=1000) {
                timeToSleep = timeToSleep - 1000;
                Thread.sleep(1000);
                env.ui.setFreeze(this.id, timeToSleep);
            }
        }
            catch(InterruptedException ignored){};
        pointOrPenalty = 0;
        canProceed = false;
    }

    public int score() {
        return score;
    }

    private void placeToken() {
        while(!keyPressed.isEmpty()){
            int slot = -1;
            if(!human)
                synchronized(aiLock) {
                    if(!keyPressed.isEmpty()){
                        slot = keyPressed.remove();
                        aiLock.notifyAll();
                    }
                }
            else slot = keyPressed.remove();
            if(slot == -1) return;
            boolean checkPoint = false;
            boolean placed = false;
            //if(table.slotToCard[slot] != null){
            try {
                slotLock.acquire();
                if(table.slotToCard[slot] != null){
                    boolean found = false;
                    for (int i = 0; i < tokenPlacement.length && !found; i++) {
                        if(tokenPlacement[i] == slot){
                            tokenPlacement[i] = -1;
                            numOfTokens--;
                            table.removeToken(id, slot);
                            found = true;
                        }
                    }
                    for (int i = 0; i < tokenPlacement.length && !found && !placed; i++) {
                        if(tokenPlacement[i] == -1){
                            tokenPlacement[i] = slot;
                            numOfTokens++;
                            table.placeToken(id, slot);
                            placed = true;
                        }
                    }
                }
                slotLock.release();
            }   catch (InterruptedException e) {}
            if(numOfTokens == env.config.featureSize && placed){
                dealer.playerQueue.add(this);
                checkPoint = true;
            }
            if(checkPoint)
                checkPoint();
        }
    }

    private void checkPoint() {
        synchronized(dealer.dealerLock){
            dealer.dealerLock.notifyAll();
            try {
                while (!canProceed && numOfTokens == 3) 
                    dealer.dealerLock.wait();
            } catch (InterruptedException ignored) {}
        }
            if(pointOrPenalty == 1)
                point();
            else if(pointOrPenalty == -1)
                penalty();
    }

    public boolean getHuman(){
        return human;
    }

    public void removeTokenFromSlot(int slot) {
        for (int i = 0; i < tokenPlacement.length; i++) {
            if (tokenPlacement[i] == slot) 
                tokenPlacement[i] = -1;
        }
        numOfTokens = 0;
        for (int i = 0; i < tokenPlacement.length; i++) {
            if (tokenPlacement[i]!=-1)
            numOfTokens++;
        }
    }
    public Thread getPlayerThread(){
        return playerThread;
    }
}