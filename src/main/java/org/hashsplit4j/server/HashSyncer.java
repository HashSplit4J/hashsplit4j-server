/*
 */
package org.hashsplit4j.server;

import org.hashsplit4j.event.NewBlobEvent;
import org.hashsplit4j.store.FileSystemBlobStore;
import org.hashsplit4j.store.LocalHashManager;
import com.hazelcast.core.Cluster;
import com.hazelcast.core.ITopic;
import com.hazelcast.core.Member;
import org.hashsplit4j.triplets.HashCalc;
import io.milton.common.Path;
import java.io.File;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

/**
 *
 * @author brad
 */
public class HashSyncer {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(HashSyncer.class);

    private final Cluster cluster;
    private final ITopic topic;
    private final LinkedBlockingQueue<NewBlobEvent> queueOfChanged;
    private final File root;
    private final LocalHashManager localHashManager;
    private final HashCalc hashCalc = HashCalc.getInstance();
    private final Path remoteRoot = Path.path("/dirs/");
    private final FileSystemBlobStore blobStore;

    private int httpPort;
    private String httpUser;
    private String httpPwd;

    public HashSyncer( LocalHashManager localHashManager, Cluster cluster, LinkedBlockingQueue<NewBlobEvent> queueOfChanged, ITopic topic, File root, FileSystemBlobStore blobStore) {
        this.cluster = cluster;
        this.topic = topic;
        this.queueOfChanged = queueOfChanged;
        this.root = root;
        this.localHashManager = localHashManager;
        this.blobStore = blobStore;
    }

    /**
     * Check files against other cluster members
     */
    public void sync() {
        for (Member m : cluster.getMembers()) {
            if (!m.localMember()) {
                sync(m);
            }
        }
    }

    public void removeHashes(File file) {
        if (file == null) {
            return;
        }
        if (file.isDirectory()) {
            File hash = new File(file, ".hash");
            if (hash.exists() && hash.isFile()) {
                if (!hash.delete()) {
                    log.warn("Failed to delete hash file: " + hash.getAbsolutePath());
                }
            }
        }
        if (!file.equals(root)) {
            removeHashes(file.getParentFile());
        }
    }

    public void sync(Member m) {
        InetSocketAddress inet = m.getInetSocketAddress();
        if (inet == null) {
            log.warn("Couldnt sync because no inet address");
            return;
        }
        InetAddress add = inet.getAddress();
        if (add == null) {
            log.warn("Couldnt sync because no IP address: " + m.getUuid());
            return;
        }
        String host = add.getHostAddress();
        try {
            sync(host, httpPort);
        } catch (Exception ex) {
            log.error("Couldnt sync member: " + m, ex);
        }
    }

    public void sync(String host, int port) throws Exception {
//        System.out.println("Sync: " + httpUser + " - " + httpPwd);
//        Host client = new Host(host, port, httpUser, httpPwd, null);
//
//        try {
//            // Do a fast check on the root hash
//            byte[] data = client.get(remoteRoot);
//            String remoteHash = new String(data, FsHashManager.UTF8);
//            remoteHash = remoteHash.trim();
//            if (remoteHash.length() == 0) {
//                log.warn("No root hash for remote blobs, so cant sync: " + host + ":" + port);
//                return;
//            }
//            String localHash = localHashManager.getDirHash(root);
//            if (localHash != null) {
//                if (remoteHash.equals(localHash)) {
//                    log.info("Directories are in sync: " + host + ":" + port);
//                    return;
//                }
//            }
//
//            syncDir(client, remoteRoot, root);
//
//        } catch (HttpException | NotAuthorizedException | BadRequestException | ConflictException | NotFoundException | IOException e) {
//            throw new Exception("Couldnt sync with - " + host + ":" + port, e);
//        }
    }
//
//    private void syncDir(Host client, Path remotePath, File localDir) throws HttpException, NotAuthorizedException, BadRequestException, ConflictException, NotFoundException, IOException {
//        log.info("syncDir: " + remotePath + " ----");
//        byte[] data = client.get(remotePath + "?hashes");
//        ByteArrayInputStream bin = new ByteArrayInputStream(data);
//        List<ITriplet> remoteTriplets = hashCalc.parseTriplets(bin);
//        Map<String, File> local = toMap(localDir.listFiles());
//        for (ITriplet triplet : remoteTriplets) {
//            syncItem(client, remotePath, localDir, triplet, local);
//        }
//    }
//
//    private void syncItem(Host client, Path remotePath, File localDir, ITriplet triplet, Map<String, File> local) throws IOException, HttpException, NotAuthorizedException, BadRequestException, ConflictException, NotFoundException {
//        if( triplet.getName().startsWith(".")) {
//            return ;
//        }
//        if (triplet.getType().equals("f")) {
//            // File
//            File localFile = local.get(triplet.getName());
//            if (localFile == null || !localFile.exists()) {
//                download(client, triplet.getName());
//            } else {
//                log.info("Files in sync: " + localFile.getAbsolutePath());
//            }
//        } else {
//            // Directory
//            File localChildDir = local.get(triplet.getName());
//            if (localChildDir != null) {
//                String localHash = localHashManager.getDirHash(localChildDir);
//                if (localHash != null && localHash.equals(triplet.getHash())) {
//                    log.info("syncItem: Dirs in sync: " + localChildDir.getAbsolutePath());
//                }
//            } else {
//                localChildDir = new File(localDir, triplet.getName());
//                if (!localChildDir.mkdir()) {
//                    throw new IOException("Couldnt create: " + localChildDir.getAbsolutePath());
//                }
//            }
//            syncDir(client, remotePath.child(triplet.getName()), localChildDir);
//        }
//    }
//
//    private void download(Host client, String hash) throws IOException {
//        Path p = Path.path("/blobs/" + hash);
//        byte[] blobData;
//        try {
//            blobData = client.get(p);
//        } catch (Exception e) {
//            throw new IOException("Exception downloading from: " + client.server, e);
//        }
//        blobStore.setBlob(hash, blobData, false);
//    }

    private Map<String, File> toMap(File[] files) {
        Map<String, File> map = new HashMap<>();
        if (files != null) {
            for (File f : files) {
                if (!f.getName().startsWith(".")) {
                    map.put(f.getName(), f);
                }
            }
        }
        return map;
    }

    public String getHttpPwd() {
        return httpPwd;
    }

    public void setHttpPwd(String httpPwd) {
        this.httpPwd = httpPwd;
    }

    public String getHttpUser() {
        return httpUser;
    }

    public void setHttpUser(String httpUser) {
        this.httpUser = httpUser;
    }

    public int getHttpPort() {
        return httpPort;
    }

    public void setHttpPort(int httpPort) {
        this.httpPort = httpPort;
    }
    
    

}
