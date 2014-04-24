/*
 */
package org.hashsplit4j.server;

import com.hazelcast.core.Cluster;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.ITopic;
import com.hazelcast.core.Member;
import com.hazelcast.core.MemberAttributeEvent;
import com.hazelcast.core.MembershipEvent;
import com.hazelcast.core.MembershipListener;
import com.hazelcast.core.Message;
import com.hazelcast.core.MessageListener;
import io.milton.event.Event;
import io.milton.event.EventListener;
import io.milton.event.EventManager;
import java.io.File;
import java.nio.charset.Charset;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.hashsplit4j.event.NewBlobEvent;
import org.hashsplit4j.event.NewBlobMessage;
import org.hashsplit4j.event.NewFileBlobEvent;
import org.hashsplit4j.server.event.ClusterNewBlobEvent;
import org.hashsplit4j.store.FileSystemBlobStore;
import org.hashsplit4j.store.FsHashUtils;
import org.hashsplit4j.store.LocalHashManager;

/**
 * Maintains hash token state for a directory of blobs
 *
 * Each directory contains a file '.hash' which contains a single line with is
 * the string format hash, and a '.hashes' file which contains the HashCalc
 * formated triplets
 *
 *
 * @author brad
 */
public class FsHashManager {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FsHashManager.class);

    public static final Charset UTF8 = Charset.forName("UTF-8");
    public static int DAILY_SYNC_INTERVAL_SECONDS = 60 * 60 * 24;

    private final LinkedBlockingQueue<NewBlobEvent> queueOfChanged;
    private final LinkedBlockingQueue<Member> queueOfSyncTargets;

    private final ITopic topic;
    private final File root;
    private final HazelcastInstance hazel;
    private final ClusterListener clusterListener;
    private final Cluster cluster;
    private final FileSystemBlobStore localBlobStore;
    private final NewBlobEventConsumer newBlobEventConsumer;
    private final SyncQueueConsumer syncQueueConsumer;
    private final HashSyncer hashSyncer;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    private boolean enableFullSync = false;
    private boolean stopped; // not really used yet    

    public FsHashManager(FileSystemBlobStore localBlobStore, LocalHashManager localHashManager, EventManager eventManager, File root, String httpUser, String httpPwd, int httpPort) {
        hazel = Hazelcast.newHazelcastInstance();
        cluster = hazel.getCluster();
        if (hazel != null) {
            topic = hazel.getTopic("new-blobs");
        } else {
            log.warn("Couldnt connect to cluster");
            topic = null;
        }
        queueOfChanged = new LinkedBlockingQueue<>();
        queueOfSyncTargets = new LinkedBlockingQueue<>();
        this.root = root;
        this.localBlobStore = localBlobStore;
        ExecutorService execService = Executors.newFixedThreadPool(2);
        hashSyncer = new HashSyncer(localHashManager, cluster, queueOfChanged, topic, root, localBlobStore);
        hashSyncer.setHttpUser(httpUser);
        hashSyncer.setHttpPwd(httpPwd);
        hashSyncer.setHttpPort(httpPort);
        newBlobEventConsumer = new NewBlobEventConsumer();
        execService.execute(newBlobEventConsumer);
        syncQueueConsumer = new SyncQueueConsumer();
        execService.execute(syncQueueConsumer);
        eventManager.registerEventListener(new NewFileBlobListener(), NewFileBlobEvent.class);
        clusterListener = new ClusterListener();
        topic.addMessageListener(clusterListener);

        cluster.addMembershipListener(new SyncMembershipListener());

        DailyClusterSyncTask dailyClusterSyncTask = new DailyClusterSyncTask();
        scheduler.scheduleWithFixedDelay(dailyClusterSyncTask, 60 * 60 * 1, DAILY_SYNC_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * If true will attempt to perform a full sync on remote members
     *
     * @return
     */
    public boolean isEnableFullSync() {
        return enableFullSync;
    }

    public void setEnableFullSync(boolean enableFullSync) {
        this.enableFullSync = enableFullSync;
    }

    public class DailyClusterSyncTask implements Runnable {

        @Override
        public void run() {
            log.info("Daily cluster sync, running every " + DAILY_SYNC_INTERVAL_SECONDS / 60 + " minutes");
            for (Member m : cluster.getMembers()) {
                if (!m.localMember()) {
                    queueOfSyncTargets.offer(m);
                }
            }
        }

    }

    public class SyncMembershipListener implements MembershipListener {

        public SyncMembershipListener() {
        }

        @Override
        public void memberAdded(MembershipEvent me) {
            if (!me.getMember().localMember()) {
                log.info("Cluster member joined, so add to sync queue");
                queueOfSyncTargets.add(me.getMember());
            }
        }

        @Override
        public void memberRemoved(MembershipEvent me) {
        }

        @Override
        public void memberAttributeChanged(MemberAttributeEvent mae) {

        }
        
        
    }

    public class SyncQueueConsumer implements Runnable {

        @Override
        public void run() {
            try {
                while (!stopped) {
                    Member item = queueOfSyncTargets.take();
                    boolean done = false;
                    int count = 0;
                    while (!done) {
                        try {
                            if (enableFullSync) {
                                log.info("Initiate sync with new cluster member: " + item.getInetSocketAddress());
                                hashSyncer.sync(item);
                            } else {
                                log.warn("Would have done a sync, but is disabled");
                            }
                            done = true;
                        } catch (Throwable e) {
                            count++;
                            log.error("Exception processing queued item: " + item + " Failed count: " + count);
                            if (count > 3) {
                                log.warn("Exceeded retry count, giving up", e);
                                done = true;
                            } else {
                                log.warn("Failed to sync but will wait for a bit and retry");
                                Thread.sleep(3000);
                            }
                        }
                    }
                }
            } catch (InterruptedException e) {
                log.warn("HashRemove thread has stopped do it interrupt");
            }
        }
    }

    public class NewBlobEventConsumer implements Runnable {

        @Override
        public void run() {
            try {
                if (enableFullSync) {
                    log.info("Sync with any existing cluster members before processing new items");
                    hashSyncer.sync();
                } else {
                    log.warn("Would have done an initial sync, but sync is disabled");
                }

                while (!stopped) {
                    NewBlobEvent item = queueOfChanged.take();
                    try {
                        File blob = FsHashUtils.toFile(root, item.getHash());
                        hashSyncer.removeHashes(blob);
                        if (item instanceof NewFileBlobEvent) {
                            // only tell network if generated locally
                            NewFileBlobEvent fileBlobEvent = (NewFileBlobEvent) item;
                            if (topic != null) {
                                log.info("NewFileBlobEvent: Send message to cluster: " + fileBlobEvent.getHash());
                                NewBlobMessage message = new NewBlobMessage(fileBlobEvent.getHash(), fileBlobEvent.getData());
                                topic.publish(message); // let all our friends know
                            }
                        } else if (item instanceof ClusterNewBlobEvent) {
                            ClusterNewBlobEvent clusterNewBlobEvent = (ClusterNewBlobEvent) item;
                            log.info("Saving new blob: " + clusterNewBlobEvent.getHash());
                            localBlobStore.setBlob(clusterNewBlobEvent.getHash(), clusterNewBlobEvent.getData(), false);
                            hashSyncer.removeHashes(blob);
                        }

                        log.info("Queue size is now: " + queueOfChanged.size());
                    } catch (Throwable e) {
                        log.error("Exception processing queued item: " + item, e);
                    }
                }
            } catch (InterruptedException e) {
                log.warn("HashRemove thread has stopped do it interrupt");
            }
        }

    }

    public class NewFileBlobListener implements EventListener {

        @Override
        public void onEvent(Event e) {
            if (e instanceof NewFileBlobEvent) {
                NewFileBlobEvent fe = (NewFileBlobEvent) e;
                System.out.println("Received file system event from blobstore: " + fe.getHash());
                boolean result = queueOfChanged.offer(fe);
                if (!result) {
                    log.error("Couldnt insert changed file onto queue: " + fe);
                }
            }
        }
    }

    public class ClusterListener implements MessageListener<NewBlobMessage> {

        @Override
        public void onMessage(Message<NewBlobMessage> msg) {
            Member sourceMember = msg.getPublishingMember();
            if (!sourceMember.localMember()) {
                NewBlobMessage newBlobMesssage = msg.getMessageObject();
                String hash = newBlobMesssage.getHash();
                File blob = FsHashUtils.toFile(root, hash);
                if (!blob.exists()) {
                    log.info("Received Message from cluster: " + newBlobMesssage.getHash());
                    ClusterNewBlobEvent fe = new ClusterNewBlobEvent(hash, sourceMember, newBlobMesssage.getData());
                    boolean result = queueOfChanged.offer(fe);
                    if (!result) {
                        log.error("Couldnt insert changed file onto queue: " + fe);
                    }
                } else {
                    log.info("Blob file already exists, so ignore");
                }
            }
        }

    }
}
