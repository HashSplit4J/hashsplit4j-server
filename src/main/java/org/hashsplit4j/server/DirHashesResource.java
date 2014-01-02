package org.hashsplit4j.server;

import io.milton.http.Auth;
import io.milton.http.Range;
import io.milton.http.exceptions.BadRequestException;
import io.milton.http.exceptions.NotAuthorizedException;
import io.milton.http.exceptions.NotFoundException;
import io.milton.resource.GetableResource;
import java.io.*;
import java.util.Map;
import org.hashsplit4j.store.LocalHashManager;

/**
 * Represents a directory of files and folders in a blob file store
 *
 * Allows access to hash information, to enable efficient sync
 *
 * @author brad
 */
class DirHashesResource extends BaseResource implements GetableResource {

    private final LocalHashManager hashManager;
    private final File dir;

    public DirHashesResource(LocalHashManager hashManager, File dir, BlobbyResourceFactory resourceFactory) {
        super(resourceFactory);
        this.hashManager = hashManager;
        this.dir = dir;
    }

    @Override
    public String getName() {
        return dir.getName();
    }

    @Override
    public void sendContent(OutputStream out, Range range, Map<String, String> params, String contentType) throws IOException, NotAuthorizedException, BadRequestException, NotFoundException {
        if (params.containsKey("hashes")) {
            // write hashes for all members
            //hashManager.
            hashManager.writeTripletsToStream(dir, out);
        } else {
            // write just this hash
            String hash = hashManager.getDirHash(dir);
            if( hash != null ) {
                out.write(hash.getBytes(FsHashManager.UTF8));
            }
        }
    }

    @Override
    public Long getMaxAgeSeconds(Auth auth) {
        return null;
    }

    @Override
    public String getContentType(String accepts) {
        return "text";
    }

    @Override
    public Long getContentLength() {
        return null;
    }

}
