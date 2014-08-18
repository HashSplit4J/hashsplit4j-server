/*
 */

package org.hashsplit4j.server.event;

import org.hashsplit4j.event.NewBlobEvent;

/**
 * Fired when a blob is added to a network peer
 *
 * @author brad
 */
public class ClusterNewBlobEvent extends NewBlobEvent{
   
    public ClusterNewBlobEvent(String hash, byte[] data) {
        super(hash, data);
    }           
}
