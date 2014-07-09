package org.hashsplit4j.server;

import io.milton.common.Path;
import io.milton.event.EventManager;
import io.milton.event.EventManagerImpl;
import io.milton.resource.Resource;
import io.milton.http.exceptions.BadRequestException;
import io.milton.http.exceptions.NotAuthorizedException;
import java.util.Date;
import org.hashsplit4j.api.BlobStore;
import io.milton.http.ResourceFactory;
import io.milton.http.SecurityManager;
import io.milton.http.fs.SimpleSecurityManager;
import io.milton.resource.CollectionResource;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.String;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.apache.log4j.Logger;
import org.hashsplit4j.store.CachingBlobStore;
import org.hashsplit4j.store.FileSystemBlobStore;
import org.hashsplit4j.store.LocalHashManager;

/**
 * Implements a URL scheme for handling HTTP interactions with a file sync
 * client
 *
 * Just stores and retrieves blobs, where a blob is a piece of data with a SHA1
 * hash identifying it
 * 
 * Eg
 * /blobs/xyz123 -  a blob with hash xyz123
 * /dirs/xy/xyz1 - a directory which will report files or directories within it and their hashes
 * 
 *
 * @author brad
 */
public class BlobbyResourceFactory implements ResourceFactory {

    private static final Logger log = Logger.getLogger(BlobbyResourceFactory.class);
    public static final Date LONG_LONG_AGO = new Date(0);
    private final io.milton.http.SecurityManager securityManager;
    private final BlobStore blobStore;
    private final RootFolder rootFolder;
    private final FsHashManager hashManager;
    private final FileSystemBlobStore fsBlobStore;
    private final LocalHashManager localHashManager;
    private final File root;

    public BlobbyResourceFactory() throws IOException {
        Properties defaultProps = new Properties();
        defaultProps.setProperty("root", "/blobs");
        defaultProps.setProperty("realm", "blobby");
        defaultProps.setProperty("user", "admin");
        defaultProps.setProperty("password", "password8");
        defaultProps.setProperty("port", "8085");
        Properties props = new Properties(defaultProps);
        InputStream in = BlobbyResourceFactory.class.getResourceAsStream("/blobby.properties");
        if (in == null) {
            log.error("Could not find blobby properties file in classpath: /blobby.properties");
            log.error("Listing default values: ");
            for (String key : defaultProps.stringPropertyNames()) {
                log.error("  " + key + "=" + defaultProps.getProperty(key));
            }
        } else {
            props.load(in);
        }
        root = new File(props.getProperty("root"));
        if (!root.exists()) {
            log.warn("Root blob path does not exist: " + root.getAbsolutePath());
            log.warn("Attempting to create...");
            if (root.mkdir()) {
                log.info("Created root path ok");
            } else {
                throw new RuntimeException("Could not create root blob path: " + root.getAbsolutePath());
            }
        } else {
            log.info("Using blobby root path: " + root.getAbsolutePath());
        }
        String realm = props.getProperty("realm");
        String user = props.getProperty("user");
        String password = props.getProperty("password");
        String sPort = props.getProperty("port");
        int httpPort = 8085;
        if( sPort != null && sPort.length() > 0) {
            sPort = sPort.trim();
            httpPort = Integer.parseInt(sPort);
        }

        EventManager eventManager = new EventManagerImpl();
        localHashManager = new LocalHashManager();        
                
        fsBlobStore = new FileSystemBlobStore(root, eventManager);
        blobStore = new CachingBlobStore(fsBlobStore, 1000);
        String sDisableSync = props.getProperty("disable-sync");
        boolean disableSync = "true".equalsIgnoreCase(sDisableSync);
        if( !disableSync ) {
            log.info("Cluster syncing is enabled. To disable set disable-sync=true in blobby.properties");
            hashManager = new FsHashManager(fsBlobStore, localHashManager, eventManager, root, user, password, httpPort);
        } else {
            log.info("Cluster syncing is disabled. To enable set disable-sync=false in blobby.properties");
            hashManager = null;
        }
        Map<String, String> map = new HashMap<>();
        map.put(user, password);
        this.securityManager = new SimpleSecurityManager(realm, map);
        rootFolder = new RootFolder(this);
        
    }
    

    @Override
    public Resource getResource(String host, final String path) throws NotAuthorizedException, BadRequestException {
        Path p = Path.path(path);
        if( p.getFirst().equals("dirs") ) {
            p = p.getStripFirst();
            return findDir(p);
        }
        return find(p, rootFolder);        
    }

    public static Resource find(Path p, CollectionResource col) throws NotAuthorizedException, BadRequestException {
        Resource r = col;
        for( String s : p.getParts()) {
            if( r instanceof CollectionResource) {
                r = ((CollectionResource)r).child(s);
            } else {
                return null;
            }
        }
        return r;
    }    
    
    public SecurityManager getSecurityManager() {
        return securityManager;
    }

    public BlobStore getBlobStore() {
        return blobStore;
    }

    /**
     * Find a resource representing the hashes of a directory. The given path
     * will identify a physical directory in the blobs dir
     * 
     * @param p
     * @return 
     */
    private Resource findDir(Path p) {
        File dir = root;
        for( String segment : p.getParts() ) {
            dir = locateFile(dir, segment);
            if( dir == null ) {
                return null;
            }
        }
        return new DirHashesResource(localHashManager, dir, this);
    }

    private File locateFile(File dir, String name) {
        File f = new File(dir, name);
        if( f.exists() ) {
            return f;
        } else {
            return null;
        }
    }
    
    
}
