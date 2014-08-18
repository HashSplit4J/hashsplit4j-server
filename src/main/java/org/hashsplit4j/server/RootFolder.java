package org.hashsplit4j.server;

import io.milton.http.Auth;
import io.milton.http.HttpManager;
import io.milton.http.Range;
import io.milton.resource.Resource;
import io.milton.http.exceptions.BadRequestException;
import io.milton.http.exceptions.NotAuthorizedException;
import io.milton.http.exceptions.NotFoundException;
import io.milton.resource.CollectionResource;
import io.milton.resource.GetableResource;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This folder allows blobs to be written directly to the blob store,
 * independently of the file from whence they came
 *
 * @author brad
 */
class RootFolder extends BaseResource implements CollectionResource, GetableResource {

    private final BlobFolder blobFolder;
    
    public RootFolder(BlobbyResourceFactory resourceFactory) {
        super(resourceFactory);
        blobFolder = new BlobFolder(resourceFactory.getBlobStore(), "blobs", resourceFactory);
    }

    @Override
    public String getName() {
        return "";
    }

    @Override
    public Resource child(String name) throws NotAuthorizedException, BadRequestException {
        if( name.equals(blobFolder.getName() )) {
            return blobFolder;
        } else {
            return null;
        }
    }

    @Override
    public List<? extends Resource> getChildren() throws NotAuthorizedException, BadRequestException {
        return Collections.EMPTY_LIST;
    }

    @Override
    public void sendContent(OutputStream out, Range range, Map<String, String> params, String contentType) throws IOException, NotAuthorizedException, BadRequestException, NotFoundException {
        PrintWriter pw = new PrintWriter(out);
        for( Map.Entry<ThreadGroup, List<ThreadData>> entry : getThreadStacks().entrySet()) {
            pw.println("--------------------");
            pw.println();
            pw.println("Thread Group: " + entry.getKey().getName());
            for( ThreadData td : entry.getValue()) {
                pw.println();
                HttpManager.RequestInfo req = td.getRequestData();
                String s;
                if( req != null ) {
                    s = req.getMethod() + " " + req.getUrl() + ", " + req.getDurationMillis() + "ms";
                } else {
                    s = "";
                }
                pw.println("Thread: " + td.getThread().getName() + " - " + s);
                for( StackTraceElement st : td.getTrace()) {
                    pw.println(" - " + st.getClassName() + " (" + st.getLineNumber() + ") :: " + st.getMethodName());
                }
                pw.flush();
            }
        }
        pw.flush();
    }

    @Override
    public Long getMaxAgeSeconds(Auth auth) {
        return null;
    }

    @Override
    public String getContentType(String accepts) {
        return "text/plain";
    }

    @Override
    public Long getContentLength() {
        return null;
    }
    

    public Map<ThreadGroup,List<ThreadData>> getThreadStacks() {
        Map<ThreadGroup,List<ThreadData>> map = new HashMap<>();
        for( Map.Entry<Thread, StackTraceElement[]> st : Thread.getAllStackTraces().entrySet() ) {
            Thread th = st.getKey();
            ThreadGroup tg = th.getThreadGroup();
            if( tg != null ) {
                List<ThreadData> list = map.get(tg);
                if( list == null ) {
                    list = new ArrayList<>();
                    map.put(tg, list);
                }
                ThreadData data = new ThreadData(th, st.getValue(), HttpManager.getRequestDataForThread(th));
                list.add(data);
            }
        }
        return map;
    }

    public class ThreadData {
        private final Thread thread;
        private final StackTraceElement[] trace;
        private final HttpManager.RequestInfo requestData;

        public ThreadData(Thread thread, StackTraceElement[] trace, HttpManager.RequestInfo requestData) {
            this.thread = thread;
            this.trace = trace;
            this.requestData = requestData;
        }

        public HttpManager.RequestInfo getRequestData() {
            return requestData;
        }

        public Thread getThread() {
            return thread;
        }

        public StackTraceElement[] getTrace() {
            return trace;
        }
        
        
    }
    
}
