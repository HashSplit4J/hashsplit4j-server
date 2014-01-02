/*
 */

package org.hashsplit4j.server.event;

import com.hazelcast.core.Member;
import org.hashsplit4j.event.NewBlobEvent;

/**
 * Fired when a blob is added to a network peer
 *
 * @author brad
 */
public class ClusterNewBlobEvent extends NewBlobEvent{

    private final Member member;
    
    public ClusterNewBlobEvent(String hash, Member member, byte[] data) {
        super(hash, data);
        this.member = member;
    }

    public Member getMember() {
        return member;
    }        


    
    
}
